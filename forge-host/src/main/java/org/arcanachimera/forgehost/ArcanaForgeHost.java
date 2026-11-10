package org.arcanachimera.forgehost;

import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.card.Card;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.match.input.Input;
import forge.gamemodes.match.input.InputPassPriority;
import forge.gamemodes.match.input.InputPayMana;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.gui.interfaces.IGuiGame;
import forge.item.PaperCard;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.player.PlayerControllerHuman;
import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Forge as Arcana's rules engine and AI, speaking the same game-message contract as Arcana's
 * {@code LocalArgentumHost}: the UI sends {@code submitAction}/{@code keepHand}/... and receives
 * Argentum-shaped {@code stateUpdate} messages. Forge runs unchanged; this class is the only bridge.
 *
 * <p>Forge's whole GUI is one dynamic proxy: display calls are ignored, and Forge's questions to the
 * player are answered (milestone 1: a logged default; milestone 2: Argentum pending decisions).
 * The human's moves go into Forge through its own {@link PlayerControllerHuman}.
 */
public final class ArcanaForgeHost {
    public static final String HUMAN_ID = "offline-you";
    public static final String AI_ID = "offline-ai";

    private final Consumer<JSONObject> sink;
    /** Forge's "UI thread": one per app process, shared by every game (Forge itself loads once per process). */
    private static final ExecutorService edt = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "forge-edt"); t.setDaemon(true); return t; });
    /** The game Forge's GUI hooks currently talk to. */
    private static volatile ArcanaForgeHost current;
    private final ScheduledExecutorService emitter = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "forge-emit"));
    private static volatile Thread edtThread;
    private final java.util.concurrent.atomic.AtomicLong emitDueAt = new java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE);

    /**
     * How hard Forge's AI searches (its Monte Carlo playouts on copies of the game), from Arcana's AI difficulty.
     * Forge picks this from GuiBase.isMobile(), which this host can't set (it would also turn off lazy card loading),
     * so without this the AI would ask for desktop strength on a phone.
     */
    public enum AiStrength { OFF, LITE, MAX }
    public volatile AiStrength aiStrength = AiStrength.LITE;
    private volatile Game aiTuned;
    private volatile long lastPlayerActionAt;
    private volatile long gcMsAtAction = -1;
    /**
     * Owner, Nov 9 ("is there any way to speed this up?"): the longest any one AI decision may think, in seconds.
     * Forge's default is 5; 3 cuts the slowest waits (attack planning, big boards) and rarely changes the choice.
     */
    public static volatile int aiTimeoutSeconds = 3;

    /** Total garbage-collection time so far, in ms (Android's runtime stat, else the JVM's), or -1 if unknown. */
    private static long gcMs() {
        try {
            Object v = Class.forName("android.os.Debug").getMethod("getRuntimeStat", String.class).invoke(null, "art.gc.gc-time");
            if (v != null && !String.valueOf(v).isEmpty()) return Long.parseLong(String.valueOf(v));
        } catch (Throwable ignored) { }
        try {
            Object beans = Class.forName("java.lang.management.ManagementFactory").getMethod("getGarbageCollectorMXBeans").invoke(null);
            long total = 0;
            for (Object b : (java.util.List<?>) beans) total += (Long) b.getClass().getMethod("getCollectionTime").invoke(b);
            return total;
        } catch (Throwable ignored) { }
        return -1;
    }
    private volatile String lastBuild = "";
    private final Map<String, SpellAbility> offered = new ConcurrentHashMap<>();
    private volatile HostedMatch match;
    private volatile boolean gameOverSent;
    private volatile Input lastAutoPaid;
    private volatile Input lastPrompted;
    private volatile int mulligans = -1;
    private volatile boolean inMulligan;
    private volatile int chosenAbilityId = -1;
    private int version;
    /** Forge prompts answered with a default instead of the player (milestone 1 gap list). */
    public final List<String> defaultedPrompts = new ArrayList<>();

    /** This view's seat: the host is {@link #HUMAN_ID}; a Wi-Fi/internet guest is the seat it took ("offline-ai", ...). */
    private final String me;
    /** The view that owns the game (the host's); itself for the host. */
    private final ArcanaForgeHost primary;
    /** Every person's view of this game, the host's first. Shared by all views. */
    private final List<ArcanaForgeHost> views;

    public ArcanaForgeHost(Consumer<JSONObject> sink) {
        this.sink = sink;
        this.me = HUMAN_ID;
        this.primary = this;
        this.views = new java.util.concurrent.CopyOnWriteArrayList<>(List.of(this));
        this.seatIds = new java.util.IdentityHashMap<>();
        if (edtThread == null) edt.submit(() -> edtThread = Thread.currentThread());
    }

    /** A guest's view of the host's game: its own messages, questions and hidden cards. */
    private ArcanaForgeHost(ArcanaForgeHost primary, String seatId, Consumer<JSONObject> sink) {
        this.sink = sink;
        this.me = seatId;
        this.primary = primary;
        this.views = primary.views;
        this.seatIds = primary.seatIds;
    }

    // ---------------------------------------------------------------- multiplayer (Wi-Fi / internet guests)

    private final Map<String, Seat> guestSeats = new LinkedHashMap<>();

    /**
     * Arcana's setGuest: a person takes an opponent seat before the game starts, with their own deck. Their messages
     * come in through {@link #handleFrom}; everything for them goes to {@code guestSink}.
     */
    public synchronized void addGuest(String seatId, Seat seat, Consumer<JSONObject> guestSink) {
        if (primary != this) throw new IllegalStateException("guests join the host's game");
        if (match != null) throw new IllegalStateException("Remote seats must join before the game starts.");
        if (seatId.equals(HUMAN_ID)) throw new IllegalArgumentException("The host's own seat can't be a guest.");
        views.removeIf(v -> v.me.equals(seatId));
        guestSeats.put(seatId, seat);
        ArcanaForgeHost view = new ArcanaForgeHost(this, seatId, guestSink);
        views.add(view);
        try { guestSink.accept(new JSONObject().put("type", "connected").put("playerId", seatId).put("token", "offline")); }
        catch (Exception ignored) { }
    }

    /** True once a person other than the host has a seat. */
    public boolean hasGuests() { return views.size() > 1 || !guestSeats.isEmpty(); }

    /** A message from the person in {@code seatId} (the host is {@link #HUMAN_ID}). */
    public void handleFrom(String seatId, JSONObject message) {
        if (seatId == null || seatId.equals(me)) { handle(message); return; }
        for (ArcanaForgeHost v : views) if (v.me.equals(seatId)) { v.handle(message); return; }
    }

    private volatile boolean left;

    /**
     * A guest disconnected or quit: the AI plays their seat from here (Forge's own takeover, FServerManager.convertToAI),
     * so nobody is left waiting on a person who isn't there. Arcana's message: "X left. The AI is playing their seat now."
     */
    public void guestLeft(String seatId) {
        ArcanaForgeHost view = null;
        for (ArcanaForgeHost v : views) if (!v.me.equals(HUMAN_ID) && v.me.equals(seatId)) view = v;
        if (view == null) return;
        String name = guestSeats.containsKey(seatId) ? guestSeats.get(seatId).name() : "Your friend";
        Game game = match == null ? null : match.getGame();
        PlayerControllerHuman pch = view.humanController();
        view.left = true;
        views.remove(view);
        if (game != null && !game.isGameOver()) {
            if (pch != null) {
                Player p = pch.getPlayer();
                forge.ai.LobbyPlayerAi ai = new forge.ai.LobbyPlayerAi(p.getName(), null);
                p.dangerouslySetController(new forge.ai.PlayerControllerAi(game, p, ai));
                p.getRegisteredPlayer().setPlayer(ai);
                try { view.answers.offer(new JSONObject().put("left", true)); } // a question they were answering: unblock it
                catch (Exception ignored) { } // Android's org.json declares JSONException
                pch.getInputQueue().clearInputs(); // unblocks Forge's game thread if it was waiting on their input
            }
        } else guestSeats.remove(seatId);
        for (ArcanaForgeHost v : views) v.error(name + " left. The AI is playing their seat now.");
        scheduleEmit();
    }

    // ---------------------------------------------------------------- startup

    /** Loads Forge's card database once. {@code assetsDir} is the folder holding Forge's {@code res/}. */
    private static boolean forgeLoaded;
    /** Set true on Android before {@link #initForge}. */
    public static volatile boolean lazyCards;

    /** One seat: a name, its deck as card names, and an optional commander. */
    public record Seat(String name, List<String> deck, String commander) { }

    private final java.util.IdentityHashMap<RegisteredPlayer, String> seatIds;
    private List<Seat> pendingSeats;
    private java.util.function.Supplier<String> assetsDir;

    /** Arcana-style setup: seats[0] is you, the rest are Forge AIs. The game starts on "createQuickGameLobby". */
    public void prepare(List<Seat> seats, java.util.function.Supplier<String> forgeAssetsDir) { this.pendingSeats = seats; this.assetsDir = forgeAssetsDir; }

    private static String seatId(int i) { return i == 0 ? HUMAN_ID : i == 1 ? AI_ID : AI_ID + "-" + i; }

    /** The loading screen's progress bar (fraction 0..1, what's happening); set by the app. */
    public static volatile java.util.function.BiConsumer<Float, String> progress;

    private static void progress(float f, String what) {
        java.util.function.BiConsumer<Float, String> p = progress;
        if (p != null) try { p.accept(f, what); } catch (RuntimeException ignored) { }
    }

    private void startPrepared() {
        try {
            progress(0.04f, "Starting Forge…");
            String dir = assetsDir.get(); // unpacks Forge's files the first time after an install (reports its own progress)
            if (!forgeLoaded) progress(0.35f, "Loading Forge's card rules…");
            initForge(dir, this);
            boolean commander = true;
            for (int i = 0; i < pendingSeats.size(); i++) {
                Seat x = guestSeats.getOrDefault(seatId(i), pendingSeats.get(i));
                if (x.commander() == null || x.commander().isEmpty()) commander = false;
            }
            List<RegisteredPlayer> players = new ArrayList<>();
            JSONArray lobby = new JSONArray(), started = new JSONArray();
            java.util.Map<RegisteredPlayer, IGuiGame> guis = new java.util.HashMap<>();
            for (int i = 0; i < pendingSeats.size(); i++) {
                Seat seat = guestSeats.getOrDefault(seatId(i), pendingSeats.get(i));
                progress(0.6f + 0.25f * i / pendingSeats.size(), "Building decks (" + (i + 1) + " of " + pendingSeats.size() + ")…");
                ArcanaForgeHost person = null;
                for (ArcanaForgeHost v : views) if (v.me.equals(seatId(i))) person = v;
                Map<String, Integer> counts = new LinkedHashMap<>();
                for (String c : seat.deck()) counts.merge(c, 1, Integer::sum);
                Deck d = deck(seat.name(), counts);
                if (commander) d.getOrCreate(forge.deck.DeckSection.Commander).add(card(seat.commander()), 1);
                RegisteredPlayer rp = commander ? RegisteredPlayer.forCommander(d) : new RegisteredPlayer(d);
                rp.setPlayer(i == 0 ? GamePlayerUtil.getGuiPlayer()
                        : person != null ? new forge.player.LobbyPlayerHuman(seat.name()) : GamePlayerUtil.createAiPlayer(seat.name(), i));
                if (person != null) guis.put(rp, person.guiGame());
                seatIds.put(rp, seatId(i));
                players.add(rp);
                lobby.put(new JSONObject().put("playerId", seatId(i)).put("name", seat.name()));
                started.put(new JSONObject().put("playerId", seatId(i)).put("isYou", i == 0));
            }
            if (!unknownCards.isEmpty()) error("Forge doesn't have " + unknownCards.size() + " card(s), left out: " + String.join(", ", unknownCards));
            if (!aliasedCards.isEmpty()) System.out.println("Forge matched card names: " + String.join("; ", aliasedCards));
            match = GuiBase.getInterface().hostMatch();
            for (ArcanaForgeHost v : views) { // each person: their own seat as "you"
                v.match = match;
                JSONArray mine = new JSONArray();
                for (int i = 0; i < started.length(); i++) {
                    String id = started.getJSONObject(i).getString("playerId");
                    mine.put(new JSONObject().put("playerId", id).put("isYou", id.equals(v.me)));
                }
                v.sink.accept(new JSONObject().put("type", "quickGameLobbyState").put("lobbyId", "offline").put("youPlayerId", v.me)
                        .put("vsAi", views.size() == 1).put("players", lobby));
                v.sink.accept(new JSONObject().put("type", "gameStarted").put("players", mine));
            }
            applyStartBoard(match);
            progress(0.9f, "Shuffling and dealing opening hands…");
            match.startMatch(rules(commander), null, players, guis, null);
            progress(1f, "Ready");
        } catch (Throwable t) {
            error("Forge could not start the game: " + describe(t));
            t.printStackTrace();
        }
    }

    public static synchronized void initForge(String assetsDir, ArcanaForgeHost owner) {
        current = owner;
        if (forgeLoaded) return;
        // Forge keeps prefs/caches under user.home; Android has no usable home folder, so use the app's own storage.
        String home = System.getProperty("user.home");
        if (home == null || home.isEmpty() || !new java.io.File(home).canWrite()) System.setProperty("user.home", assetsDir);
        GuiBase.setInterface(guiBase(assetsDir));
        FModel.initialize(null, prefs -> { // phones: parse card scripts on demand instead of all ~30k at start
            prefs.setPref(forge.localinstance.properties.ForgePreferences.FPref.LOAD_CARD_SCRIPTS_LAZILY, lazyCards);
            prefs.setPref(forge.localinstance.properties.ForgePreferences.FPref.YIELD_SKIP_PHASE_DELAY, true);
            // Scry and similar: Forge's plain questions (top/bottom, which to bottom, order), all bridged, instead of
            // dragging cards around the whole library (manipulateCardList; found by the soak test, Nov 8).
            prefs.setPref(forge.localinstance.properties.ForgePreferences.FPref.UI_SELECT_FROM_CARD_DISPLAYS, false);
            prefs.setPref(forge.localinstance.properties.ForgePreferences.FPref.YIELD_SKIP_RESOLVE_DELAY, true);
            return null;
        });
        forgeLoaded = true;
    }

    /** Starts a one-on-one game: you against Forge's AI. Decks are card name → count. */
    public void startGame(Map<String, Integer> yourDeck, Map<String, Integer> aiDeck) throws Exception {
        RegisteredPlayer you = new RegisteredPlayer(deck("You", yourDeck));
        you.setPlayer(GamePlayerUtil.getGuiPlayer());
        RegisteredPlayer ai = new RegisteredPlayer(deck("AI", aiDeck));
        ai.setPlayer(GamePlayerUtil.createAiPlayer("Forge AI", 0));
        List<RegisteredPlayer> players = List.of(you, ai);
        match = GuiBase.getInterface().hostMatch();
        sink.accept(new JSONObject().put("type", "gameStarted").put("players", new JSONArray()
                .put(new JSONObject().put("playerId", HUMAN_ID).put("isYou", true))
                .put(new JSONObject().put("playerId", AI_ID).put("isYou", false))));
        applyStartBoard(match);
        match.startMatch(rules(commanderRulesForTest), null, players, you, guiGame());
    }

    /**
     * Forge's Commander rules (the command-zone choice when your commander dies, CR 903.9a; losing to 21 commander
     * damage, 704.6c) check the Commander *variant*, which Forge's own lobby adds; the game type alone doesn't
     * (phone, Nov 8 19:07: Ayula died and stayed in the graveyard, no question asked).
     */
    private static GameRules rules(boolean commander) {
        GameRules r = new GameRules(commander ? GameType.Commander : GameType.Constructed);
        if (commander) r.addAppliedVariant(GameType.Commander);
        return r;
    }

    private volatile boolean commanderRulesForTest;
    /** Test-only: play {@link #startGame} under Commander rules (command zone, commander SBA) with a fixed board. */
    public void setCommanderRulesForTest(boolean on) { commanderRulesForTest = on; }
    /** Test-only: the running Forge game, for rules checks in the soak test (read-only). */
    public Game gameForTest() { return match == null ? null : match.getGame(); }

    private static PaperCard card(String name) {
        PaperCard pc = FModel.getMagicDb().getCommonCards().getCard(name);
        if (pc == null) throw new IllegalArgumentException("Forge has no card named " + name);
        return pc;
    }

    /** Cards Forge doesn't know, left out of the last game's decks (reported to the player). */
    public final List<String> unknownCards = new ArrayList<>();

    public final List<String> aliasedCards = new ArrayList<>();

    /**
     * Forge's card for a deck name. Exact name, then the front face of "A // B", then a unique Forge card whose
     * name ends with this one (Arcana's lists say "Middle School" for Forge's "Vault 75: Middle School").
     */
    public static PaperCard resolveCard(String card) {
        forge.card.CardDb db = FModel.getMagicDb().getCommonCards();
        PaperCard pc = db.getCard(card);
        if (pc == null && card.contains(" // ")) pc = db.getCard(card.substring(0, card.indexOf(" // ")));
        if (pc != null) return pc;
        String k = cardKey(card);
        if (k.isEmpty()) return null;
        List<String> hits = new ArrayList<>();
        for (String x : cardKeys()) if (x.endsWith("_" + k)) hits.add(x);
        if (hits.size() > 1) { // e.g. "The Necropolis": prefer a numbered title prefix ("Vault 12: …") over "Unseal the Necropolis"
            List<String> titled = new ArrayList<>();
            for (String x : hits) if (x.substring(0, x.length() - k.length() - 1).matches("[a-z]+_[0-9]+")) titled.add(x);
            hits = titled;
        }
        if (hits.size() != 1) return null;
        String hit = hits.get(0);
        FModel.getMagicDb().attemptToLoadCard(hit); // lazy mode: the key is its own transformed name
        for (PaperCard u : db.getUniqueCards()) if (cardKey(u.getName()).equals(hit)) return u;
        return null;
    }

    private static volatile java.util.Set<String> cardKeys;

    /** Forge's card-file keys (lazy mode keeps an index of all of them), or every loaded card's key. */
    @SuppressWarnings("unchecked")
    private static java.util.Set<String> cardKeys() {
        java.util.Set<String> keys = cardKeys;
        if (keys != null) return keys;
        keys = new java.util.HashSet<>();
        try {
            Object reader = field(FModel.getMagicDb(), "cardReader");
            for (String f : new String[]{"zipEntriesByCardName", "cardFilesByCardName"}) {
                Object m = field(reader, f);
                if (m instanceof Map<?, ?> map) for (Object x : map.keySet()) keys.add(String.valueOf(x));
            }
        } catch (Exception ignored) { }
        if (keys.isEmpty()) for (PaperCard u : FModel.getMagicDb().getCommonCards().getUniqueCards()) keys.add(cardKey(u.getName()));
        return cardKeys = keys;
    }

    /** Forge's card-file name rule (CardStorageReader.transformName). */
    static String cardKey(String name) {
        String n = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            char c = Character.toLowerCase(n.charAt(i));
            if (c == '\'') continue;
            if ((c < 'a' || c > 'z') && (c < '0' || c > '9')) {
                if (b.length() > 0 && b.charAt(b.length() - 1) == '_') continue;
                if (c == ',' && b.length() > 0) continue;
                c = '_';
            }
            b.append(c);
        }
        int end = b.length();
        if (end > 0 && b.charAt(end - 1) == '_') end--;
        return b.substring(0, end);
    }

    private Deck deck(String name, Map<String, Integer> cards) {
        Deck d = new Deck(name);
        cards.forEach((card, n) -> {
            PaperCard pc = resolveCard(card);
            if (pc == null) { unknownCards.add(card); return; }
            if (!pc.getName().equalsIgnoreCase(card)) aliasedCards.add(card + " → " + pc.getName());
            d.getMain().add(pc, n);
        });
        return d;
    }

    // ---------------------------------------------------------------- messages from the UI

    public void handle(JSONObject message) {
        try { handleUnsafe(message); } catch (Exception e) { error("Forge host problem: " + describe(e)); }
    }

    private void handleUnsafe(JSONObject message) throws Exception {
        if (message.optString("type").equals("submitAction")) { lastPlayerActionAt = System.currentTimeMillis(); gcMsAtAction = gcMs(); }
        String t = message.optString("type");
        if (t.equals("connect")) { sink.accept(new JSONObject().put("type", "connected").put("playerId", me).put("token", "offline")); return; }
        if (t.equals("ping")) { sink.accept(new JSONObject().put("type", "pong")); return; }
        if (t.equals("guestLeft") && primary == this) { guestLeft(message.optString("seatId", AI_ID)); return; }
        if (t.equals("createQuickGameLobby") && primary != this) return; // only the host starts the game
        if (t.equals("createQuickGameLobby") && match == null && pendingSeats != null) { new Thread(this::startPrepared, "forge-start").start(); return; }
        PlayerControllerHuman you = humanController();
        if (you == null) return;
        String mt = message.optString("type");
        if (mt.equals("keepHand") || mt.equals("mulligan") || mt.equals("chooseBottomCards")) {
            String open = inputClassName(you.getInputQueue().getActualInput(you));
            // a late or doubled tap from the opening-hand screen must not answer whatever Forge asks next
            if (!open.equals("InputConfirmMulligan") && !open.equals("InputLondonMulligan")) { scheduleSelf(); return; }
        }
        switch (message.optString("type")) {
            case "submitAction" -> submit(you, message.getJSONObject("action"));
            // Forge's London mulligan deals the new 7 and asks for the bottom cards first, then keep/mulligan.
            // Arcana's order is: new hand → keep or mulligan → bottom cards. The host translates (tuckOffered).
            case "keepHand" -> {
                if (tuckOffered) sink.accept(handMessage("chooseBottomCards", you.getPlayer()).put("cardsToPutOnBottom", tuckCount));
                else ok(you);
            }
            case "mulligan" -> {
                mulligans++;
                if (tuckOffered) { tuckOffered = false; autoMulligan = true; bottom(you, firstHandIds(you, tuckCount)); } // shuffled back anyway
                else cancel(you);
            }
            case "chooseBottomCards" -> {
                if (tuckOffered) { tuckOffered = false; autoKeep = true; }
                bottom(you, message.optJSONArray("cardIds"));
            }
            case "concede" -> you.concede();
            case "requestResync" -> { // after a reconnect: send the open question or mulligan offer again
                Input now = you.getInputQueue().getActualInput(you);
                String kind = inputClassName(now);
                if (kind.equals("InputConfirmMulligan") || kind.equals("InputLondonMulligan")) lastPrompted = null;
                if (pending != null) emitDecision(); else scheduleSelf();
            }
            case "arcanaSaveOffline" -> error("Saving a game isn't available in the Forge build yet.");
            case "requestUndo" -> error("Undo isn't available in the Forge build.");
            case "setRespondToOwnStack" -> respondToOwnStack = message.optBoolean("enabled");
            case "setQuietOpponentTurns" -> quietOpponentTurns = message.optBoolean("enabled");
            default -> { }
        }
    }

    /** Presses Forge's OK/Cancel on the live input (the controller's own relay lags behind headless). */
    private static void ok(PlayerControllerHuman you) {
        Input input = you.getInputQueue().getActualInput(you);
        if (input != null) input.selectButtonOK();
    }

    private static void cancel(PlayerControllerHuman you) {
        Input input = you.getInputQueue().getActualInput(you);
        if (input != null) input.selectButtonCancel();
    }

    private volatile boolean tuckOffered, autoKeep, autoMulligan, youChosePlay;
    private volatile int tuckCount;

    private JSONArray firstHandIds(PlayerControllerHuman you, int n) {
        JSONArray ids = new JSONArray();
        for (Card c : you.getPlayer().getCardsIn(ZoneType.Hand)) { if (ids.length() >= n) break; ids.put(cardId(c)); }
        return ids;
    }

    private void bottom(PlayerControllerHuman you, JSONArray ids) throws Exception {
        Input input = you.getInputQueue().getActualInput(you);
        if (input == null || ids == null) return;
        for (int i = 0; i < ids.length(); i++)
            for (Card c : you.getPlayer().getCardsIn(ZoneType.Hand))
                if (cardId(c).equals(ids.getString(i))) input.selectCard(c, null, null);
        ok(you);
    }

    private JSONObject handMessage(String type, Player p) throws Exception {
        JSONArray hand = new JSONArray();
        JSONObject cards = new JSONObject();
        for (Card c : p.getCardsIn(ZoneType.Hand)) { hand.put(cardId(c)); cards.put(cardId(c), card(c, ZoneType.Hand)); }
        return new JSONObject().put("type", type).put("hand", hand).put("cards", cards)
                .put("forgeHandZone", p.getZone(ZoneType.Hand).size()).put("forgeLibrary", p.getCardsIn(ZoneType.Library).size());
    }

    /** For "choose N" prompts (e.g. discard to hand size): pick the first N valid choices so OK is accepted. */
    private static void autoSelectMinimum(Input input) {
        Object min = field(input, "min"), choices = field(input, "validChoices");
        if (!(min instanceof Integer n) || !(choices instanceof Iterable<?> it)) return;
        int picked = 0;
        for (Object o : it) {
            if (picked >= n) break;
            if (o instanceof Card c && input.selectCard(c, null, null)) picked++;
        }
    }

    private static Object field(Object o, String name) {
        for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass()) {
            try { java.lang.reflect.Field f = k.getDeclaredField(name); f.setAccessible(true); return f.get(o); }
            catch (NoSuchFieldException e) { /* keep looking up the hierarchy */ }
            catch (ReflectiveOperationException e) { return null; }
        }
        return null;
    }

    /**
     * Forge creates anonymous subclasses for some human inputs, such as the cleanup discard; getSimpleName() is empty
     * on those, so the prompt was answered automatically. Classify through the nearest named superclass.
     * (Found by Goose's four-seat proof, PR #17.)
     */
    static String inputClassName(Input input) {
        if (input == null) return "";
        for (Class<?> c = input.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            String name = c.getSimpleName();
            if (!name.isEmpty()) return name;
        }
        return "UnknownInput";
    }

    /** JSONObject keys on both desktop org.json and Android (Android's JSONObject has only keys()). */
    private static List<String> keys(JSONObject o) {
        List<String> out = new ArrayList<>();
        for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) out.add(it.next());
        return out;
    }

    private static int intField(Object o, String name) {
        try { java.lang.reflect.Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.getInt(o); }
        catch (ReflectiveOperationException e) { return 1; }
    }

    private void submit(PlayerControllerHuman you, JSONObject action) throws Exception {
        String type = action.optString("type");
        if (type.equals("SubmitDecision")) { answer(action.optJSONObject("response") == null ? new JSONObject() : action.getJSONObject("response")); return; }
        if (type.equals("DeclareAttackers") || type.equals("DeclareBlockers")) { declareCombat(you, action); return; }
        if (type.equals("PassPriority")) {
            // Only when Forge is offering priority (or a combat declaration, where OK means "none"); never while a
            // question is open, so a stray pass can't answer it (from Goose's PR #19).
            Input now = you.getInputQueue().getActualInput(you);
            String kind = inputClassName(now);
            if (pending != null || !(now instanceof InputPassPriority || kind.equals("InputAttack") || kind.equals("InputBlock"))) {
                reject("Forge isn't offering a pass right now; answer the open question first.");
                scheduleEmit();
                return;
            }
            ok(you);
            return;
        }
        SpellAbility sa = offered.get(key(type, action.optString("cardId", action.optString("sourceId")), action.optString("abilityId")));
        if (sa == null) { reject("You can't do that right now (not enough mana, or it's no longer available)."); scheduleEmit(); return; }
        chosenAbilityId = sa.getId();
        castingNow = sa;
        Input input = you.getInputQueue().getActualInput(you);
        if (input == null || !input.selectAbility(sa)) { reject("Forge did not accept " + sa); scheduleEmit(); }
    }

    // ---------------------------------------------------------------- state out to the UI

    /**
     * Coalesces Forge's GUI callbacks into one table update: 40 ms when Forge is waiting on the player, 300 ms while
     * its game thread is busy (AI turns), so the table isn't rebuilt for every callback while the AI is thinking.
     * An update is never dropped: one is always due after the latest callback.
     */
    /** Anything changed: every person's table updates (each from their own seat). */
    private void scheduleEmit() {
        for (ArcanaForgeHost v : views) v.scheduleSelf();
    }

    private void scheduleSelf() {
        if (left) return;
        long delay = waitingOnPlayer() ? 40 : 300;
        long due = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delay);
        while (true) {
            long current = emitDueAt.get();
            if (current <= due) return; // an update is already due sooner; it will read the latest state
            if (emitDueAt.compareAndSet(current, due)) break;
        }
        emitter.schedule(() -> {
            if (System.nanoTime() < emitDueAt.get()) return; // superseded by a later due time... handled by its own task
            emitDueAt.set(Long.MAX_VALUE);
            emitSafely();
        }, delay, TimeUnit.MILLISECONDS);
    }

    private boolean waitingOnPlayer() {
        if (pending != null) return true;
        PlayerControllerHuman you = humanController();
        Input input = you == null ? null : you.getInputQueue().getActualInput(you);
        return input != null && !"InputLockUI".equals(inputClassName(input));
    }

    private volatile int raceRetries;

    /**
     * Forge's cost check asks the paying player which creatures/artifacts to tap for Convoke, Improvise and
     * Waterbend, even when only testing. For you, that question would block this thread forever, so such
     * spells are offered and Forge settles the payment when you cast (scenario F stalled on Stoke the Flames).
     */
    private static boolean asksWhilePricing(SpellAbility sa) {
        Card host = sa.getHostCard();
        return host != null && (host.hasKeyword(forge.game.keyword.Keyword.CONVOKE) || host.hasKeyword(forge.game.keyword.Keyword.IMPROVISE))
                || sa.hasParam("TapCreaturesForMana") || (sa.getMaxWaterbend() != null && sa.getMaxWaterbend() > 0);
    }
    /**
     * Whether you can pay for {@code sa}, without ever letting Forge stop to ask you something while it only checks.
     * Forge's cost check asks the payer during the check for Convoke/Improvise, Emerge, Offering, Delve, Assist,
     * "up to" reductions...; asked on this (non-game) thread, that question would wait forever (scenario F; soak 517:
     * an Eldrazi Emerge spell). Any such question aborts the check; the spell is offered and Forge asks when you cast.
     */
    /** Owner, Nov 9 ("Not enough mana for that"): abilities whose payment failed, with the mana you had then. */
    private final Map<String, String> failedPayments = new java.util.concurrent.ConcurrentHashMap<>();
    private static String payKey(SpellAbility sa) { return (sa.getHostCard() == null ? "" : sa.getHostCard().getId()) + "|" + sa.getDescription(); }
    private static String manaNow(Player me) {
        int untapped = 0;
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) if (!c.isTapped() && !c.getManaAbilities().isEmpty()) untapped++;
        return untapped + "/" + me.getManaPool().totalMana() + "/" + me.getGame().getPhaseHandler().getTurn();
    }

    private boolean affordable(SpellAbility sa, Player me) {
        String failed = failedPayments.get(payKey(sa));
        if (failed != null) { if (failed.equals(manaNow(me))) return false; failedPayments.remove(payKey(sa)); }
        if (asksWhilePricing(sa)) return true;
        PlayerControllerHuman you = humanController();
        Input before = you == null ? null : you.getInputQueue().getInput();
        PRICING.set(true);
        try { return forge.ai.ComputerUtilMana.canPayManaCost(sa, me, 0, false); }
        catch (AskedWhilePricing asked) {
            if (you != null) { Input stale = you.getInputQueue().getInput(); if (stale != null && stale != before) you.getInputQueue().removeInput(stale); }
            return true;
        }
        finally { PRICING.set(false); }
    }

    private static final ThreadLocal<Boolean> PRICING = ThreadLocal.withInitial(() -> false);
    private static final class AskedWhilePricing extends RuntimeException { AskedWhilePricing() { super(null, null, false, false); } }

    private volatile Input lastAutoPassed;
    /** Arcana's "respond to my own spells" (off: your own spells and abilities resolve without a stop, as Argentum did). */
    public volatile boolean respondToOwnStack;
    /** Arcana's "don't stop for AI spells": on AI turns, pass AI items that don't target you or yours. */
    public volatile boolean quietOpponentTurns;

    private boolean autoPassStack(Game game, Player me) {
        if (game.getStack().isEmpty()) return false;
        forge.game.spellability.SpellAbilityStackInstance top = game.getStack().peek();
        if (top == null) return false;
        if (top.getActivatingPlayer() == me) return !(respondToOwnStack || practiceBoard); // lessons show your spell on the stack
        if (!quietOpponentTurns || game.getPhaseHandler().getPlayerTurn() == me) return false;
        forge.game.spellability.TargetChoices tc = top.getTargetChoices();
        if (tc != null) {
            for (Player p : tc.getTargetPlayers()) if (p == me) return false;
            for (Card c : tc.getTargetCards()) if (c.getController() == me) return false;
        }
        return true;
    }

    private void emitSafely() {
        try { emit(); }
        catch (java.util.ConcurrentModificationException | IndexOutOfBoundsException race) { // the game moved while being read
            if (++raceRetries <= 5) { scheduleSelf(); return; }
            error("Forge host problem: " + describe(race));
        }
        catch (Throwable t) { error("Forge host problem: " + describe(t)); t.printStackTrace(); }
        raceRetries = 0;
    }

    private void tuneAi(Game game) {
        if (primary != this) return; // the host's AI setting
        if (aiTuned == game) return;
        aiTuned = game;
        game.AI_TIMEOUT = Math.max(1, Math.min(game.AI_TIMEOUT, aiTimeoutSeconds)); // copies for rollouts inherit it
        game.AI_FLAT_MONTE_CARLO = aiStrength != AiStrength.OFF;
        // Owner, Nov 10: the app now gets more memory (less time cleaning it up), but Brutal keeps the phone-sized search it
        // has always had here; Forge would switch to its slower desktop search once the app has over 768 MB.
        boolean phoneSearch = onAndroid() && aiStrength == AiStrength.MAX;
        game.AI_FLAT_MONTE_CARLO_MAX = aiStrength == AiStrength.MAX && !phoneSearch;
        game.AI_FLAT_MONTE_CARLO_LITE = aiStrength == AiStrength.LITE || phoneSearch;
        System.out.println("Forge AI strength " + aiStrength + ", heap " + (Runtime.getRuntime().maxMemory() >> 20) + " MB");
    }

    private void emit() throws Exception {
        Game game = match == null ? null : match.getGame();
        if (game == null) return;
        tuneAi(game);
        long buildStart = System.nanoTime();
        PlayerControllerHuman you = humanController();
        Input input = you == null ? null : you.getInputQueue().getActualInput(you);
        if (input instanceof InputPayMana) {
            if (pending != null) { emitDecision(); return; }
            SpellAbility paidFor = field(input, "saPaidFor") instanceof SpellAbility sa ? sa : null;
            boolean optionalTrigger = paidFor != null && (paidFor.isTrigger() || paidFor.getRootAbility().isTrigger());
            if (optionalTrigger && input != lastAutoPaid && input != lastPrompted) {
                // "You may pay {1}" on a trigger (phone, Nov 8: Ultron's Robot): Forge's payment prompt is the choice
                // itself, so ask instead of paying automatically; if it can't be paid, say so and skip it.
                final Input payInput = input;
                lastPrompted = input;
                String cost = String.valueOf(field(input, "manaCost")).trim();
                String source = paidFor.getHostCard() == null ? "an ability" : paidFor.getHostCard().getName();
                // check the cost being asked for ("unless you pay {1}"), not the trigger's own cost, which is nothing
                // (soak seeds 118/130, Nov 8: Rhystic Study / Overencumbered looked payable with no mana open)
                boolean payable;
                PRICING.set(true);
                try {
                    payable = field(input, "manaCost") instanceof forge.game.mana.ManaCostBeingPaid mc
                            ? forge.ai.ComputerUtilMana.canPayManaCost(mc, paidFor, you.getPlayer(), Boolean.TRUE.equals(field(input, "effect")))
                            : forge.ai.ComputerUtilMana.canPayManaCost(paidFor, you.getPlayer(), 0, false);
                } catch (AskedWhilePricing asked) { payable = true; } // Forge will ask while paying
                finally { PRICING.set(false); }
                if (!asksWhilePricing(paidFor) && !payable) {
                    error("You can't pay " + cost + " for " + source + " right now, so it was skipped.");
                    cancel(you);
                    return;
                }
                JSONObject d = decisionBase("YesNoDecision", "Pay " + cost + " for " + source + "? " + paidFor.getStackDescription())
                        .put("yesText", "Pay " + cost).put("noText", "Don't pay");
                openForInput(d, Map.of(), r -> {
                    if (r.optBoolean("choice")) { lastAutoPaid = payInput; ok(you); } else cancel(you);
                });
                return;
            }
            // waiting for the answer; once you said "pay" and it still isn't paid, cancel below instead of waiting forever
            if (optionalTrigger && input == lastPrompted && input != lastAutoPaid) return;
            // Spells and abilities you chose: auto-pay (Argentum's default); if that can't pay, cancel instead of looping
            if (input != lastAutoPaid) { lastAutoPaid = input; ok(you); } else {
                if (paidFor != null) failedPayments.put(payKey(paidFor), manaNow(you.getPlayer())); // don't offer it again until your mana changes
                cancel(you); error("Not enough mana for that, so it was cancelled.");
            }
            return;
        }
        if (input != null && input != lastPrompted) {
            String kind = inputClassName(input);
            if (kind.equals("InputConfirm") && game.getPhaseHandler().getTurn() == 0) { // play or draw: Arcana's offline host doesn't ask; take the play
                lastPrompted = input; youChosePlay = true; defaultedPrompts.add("play or draw: play"); ok(you); return;
            }
            // Owner, Nov 9: "Can't it just randomize?" Forge picks the coin-toss winner at random; when that's you, it asked
            // who should start (desktop wording, and the game stalled after the answer). Now the winner simply goes first.
            if (kind.equals("InputSelectEntitiesFromList") && game.getPhaseHandler().getTurn() == 0
                    && field(input, "validChoices") instanceof Iterable<?> choices && allPlayers(choices)) {
                lastPrompted = input; youChosePlay = true; defaultedPrompts.add("coin toss won: you go first");
                input.selectPlayer(you.getPlayer(), null);
                input.selectButtonOK(); // ignored by Forge if picking one already finished it
                return;
            }
            if (kind.equals("InputConfirmMulligan") && practiceBoard) { // a tutorial lesson's board: its hand is the lesson's
                lastPrompted = input; defaultedPrompts.add("practice board: kept"); ok(you); return;
            }
            if (kind.equals("InputConfirmMulligan") && (autoKeep || autoMulligan)) { // already answered on Arcana's screen
                lastPrompted = input;
                boolean keep = autoKeep; autoKeep = false; autoMulligan = false;
                if (keep) ok(you); else cancel(you);
                return;
            }
            if (kind.equals("InputConfirmMulligan")) {
                lastPrompted = input; mulligans = mulligans < 0 ? 0 : mulligans; inMulligan = true;
                sink.accept(handMessage("mulliganDecision", you.getPlayer()).put("mulliganCount", mulligans)
                        .put("cardsToPutOnBottom", 0).put("isOnThePlay", youChosePlay));
                return;
            }
            if (kind.equals("InputLondonMulligan")) { // the new hand first, as Arcana expects; bottom cards after "keep"
                lastPrompted = input; inMulligan = true; tuckOffered = true; tuckCount = intField(input, "toReturn");
                sink.accept(handMessage("mulliganDecision", you.getPlayer()).put("mulliganCount", Math.max(mulligans, 1))
                        .put("cardsToPutOnBottom", tuckCount).put("isOnThePlay", youChosePlay));
                return;
            }
        }
        if (you == null) return; // no human seat (yet): nothing to show
        // You lost (or the game ended): tell Arcana even though Forge no longer lists you, and even if the
        // remaining AIs play on in a multiplayer game (proof, Nov 8: losing left the app waiting forever).
        if (!gameOverSent && (game.isGameOver() || you.getPlayer().hasLost())) {
            gameOverSent = true;
            sink.accept(fill(new JSONObject().put("type", "stateUpdate").put("state", state(game, you.getPlayer()))
                    .put("legalActions", new JSONArray()).put("pendingDecision", JSONObject.NULL).put("stateVersion", ++version)
                    .put("interactive", false).put("interactionEpoch", "forge-" + version).put("priorityMode", "auto"), "top"));
            sink.accept(new JSONObject().put("type", "gameOver").put("winnerId", winnerId(game))
                    .put("reason", you.getPlayer().hasLost() && !game.isGameOver() ? "ELIMINATED" : "GAME_OVER"));
            return;
        }
        if (gameOverSent) return;
        // Forge shuffles and deals between mulligan prompts (and the AI mulligans too): that's still the mulligan.
        // Arcana shows the opening-hand screen only until the first game state, so send nothing until turn 1
        // (phone, Nov 8 11:31: after "Mulligan" the new hand never appeared).
        if (inMulligan && game.getPhaseHandler().getTurn() == 0) return;
        if (inMulligan) { // Argentum's order: mulliganComplete, then the first game state
            inMulligan = false;
            sink.accept(new JSONObject().put("type", "mulliganComplete").put("finalHandSize", you.getPlayer().getCardsIn(ZoneType.Hand).size()));
        }
        if (pending != null) { emitDecision(); return; } // an open question stays on screen until answered
        boolean priority = input instanceof InputPassPriority;
        if (priority && input != lastAutoPassed && autoPassStack(game, you.getPlayer())) { lastAutoPassed = input; ok(you); return; }
        String inputKind = inputClassName(input);
        boolean combatInput = inputKind.equals("InputAttack") || inputKind.equals("InputBlock");
        // InputLockUI is Forge's "waiting for actions" lock while its game thread works: not a decision. Show state only.
        if (inputKind.equals("InputLockUI")) input = null;
        if (input != null && !priority && !combatInput && input != lastPrompted && decisionForInput(input, you, game)) { lastPrompted = input; return; }
        if (input != null && !priority && !combatInput && input != lastPrompted) {
            notice("input-" + inputKind, "Forge asked something Arcana can't show yet (" + inputKind + "); it was answered automatically.");
            // Milestone 1 stand-in until combat/targets reach Arcana's screens: Forge's default (no attack,
            // no block, its suggested target) so the game never waits on a prompt the UI can't show yet.
            lastPrompted = input;
            defaultedPrompts.add("auto-answered " + inputKind);
            autoSelectMinimum(input);
            ok(you);
            return;
        }
        // Before the first turn there's only the opening hand: Arcana leaves its mulligan screen at the first state, so a
        // person still waiting for theirs (another player is choosing) gets nothing yet.
        if (game.getPhaseHandler().getTurn() == 0) return;
        JSONArray legal = new JSONArray();
        offered.clear();
        if (combatInput) legal.put(inputKind.equals("InputAttack") ? attackAction(input, you.getPlayer()) : blockAction(input, you.getPlayer()));
        if (priority) {
            Player me = you.getPlayer();
            legal.put(action("PassPriority", "Pass priority", new JSONObject().put("type", "PassPriority").put("playerId", this.me)));
            for (ZoneType z : new ZoneType[]{ZoneType.Hand, ZoneType.Battlefield, ZoneType.Command, ZoneType.Graveyard, ZoneType.Exile}) {
                for (Card c : me.getCardsIn(z)) {
                    for (SpellAbility sa : c.getAllPossibleAbilities(me, true)) {
                        // Mana abilities are offered too, so you can choose which lands/sources to tap and float mana;
                        // Forge's automatic payment then uses the floating mana first.
                        String type = sa.isLandAbility() ? "PlayLand" : sa.isSpell() ? "CastSpell" : "ActivateAbility";
                        String cardId = cardId(c);
                        String abilityId = type.equals("ActivateAbility") ? "sa" + sa.getId() : "";
                        JSONObject a = new JSONObject().put("type", type).put("playerId", this.me);
                        if (type.equals("ActivateAbility")) a.put("sourceId", cardId).put("abilityId", abilityId); else a.put("cardId", cardId);
                        boolean affordable = sa.isLandAbility() || sa.isManaAbility() || affordable(sa, me);
                        if (affordable) offered.put(key(type, cardId, abilityId), sa);
                        // Owner, Nov 10: "it only says 'play land'": name the land (Forge's own text for it is "Play land").
                        String label = sa.isManaAbility() ? "Tap for mana: " + sa : sa.isLandAbility() ? "Play " + c.getName() : sa.toString();
                        JSONObject offer = action(type, label, a).put("isAffordable", affordable);
                        if (sa.isManaAbility()) offer.put("isManaAbility", true);
                        legal.put(offer);
                    }
                }
            }
        }
        JSONObject update = new JSONObject()
                .put("type", "stateUpdate")
                .put("state", state(game, you == null ? null : you.getPlayer()))
                .put("events", new JSONArray())
                .put("legalActions", legal)
                .put("pendingDecision", JSONObject.NULL)
                .put("nextStopPoint", JSONObject.NULL)
                .put("undoAvailable", false)
                .put("priorityMode", "auto")
                .put("stateVersion", ++version)
                .put("interactive", priority || combatInput)
                .put("interactionEpoch", "forge-" + version)
                .put("forgeInput", input == null ? "none" : inputClassName(input));
        fill(update, "top");
        if (priority || combatInput) { // Arcana's diagnostic log shows these: "engine N ms, stages {...}"
            long now = System.currentTimeMillis();
            if (lastPlayerActionAt > 0) update.put("engineMs", now - lastPlayerActionAt);
            update.put("engineTimingsMs", new JSONObject().put("build", (System.nanoTime() - buildStart) / 1_000_000)
                    .put("kb", update.toString().length() / 1024).put("heapMb", Runtime.getRuntime().maxMemory() >> 20)
                    // Owner, Nov 9: is memory costing time? Memory in use now, and time spent cleaning it since your action.
                    .put("usedMb", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20)
                    .put("gcMs", gcMsAtAction >= 0 && gcMs() >= 0 ? gcMs() - gcMsAtAction : -1)
                    .put("ai", aiStrength.name()));
            lastPlayerActionAt = 0;
        }
        sink.accept(update);
    }

    /** A state with nothing to do yet (Argentum sends one before each mulligan prompt). */
    private JSONObject passiveUpdate(Game game, PlayerControllerHuman you) throws Exception {
        return fill(new JSONObject().put("type", "stateUpdate").put("state", state(game, you.getPlayer()))
                .put("legalActions", new JSONArray()).put("pendingDecision", JSONObject.NULL).put("stateVersion", ++version)
                .put("interactive", false).put("interactionEpoch", "forge-" + version).put("priorityMode", "auto"), "top");
    }

    private Object winnerId(Game game) {
        for (Player p : game.getPlayers()) if (!p.hasLost() && game.isGameOver()) return playerId(p);
        return JSONObject.NULL;
    }

    private static JSONObject action(String actionType, String description, JSONObject action) throws Exception {
        return new JSONObject().put("actionType", actionType).put("description", description).put("action", action).put("isAffordable", true);
    }

    private volatile forge.game.combat.Combat stateCombat;

    private Object idOrNull(forge.game.GameEntity e) {
        if (e instanceof Card c) return cardId(c);
        String id = idOf(e);
        return id == null ? JSONObject.NULL : id;
    }

    private JSONObject state(Game game, Player viewer) throws Exception {
        PhaseHandler ph = game.getPhaseHandler();
        stateCombat = game.getCombat();
        JSONObject cards = new JSONObject();
        JSONArray zones = new JSONArray();
        JSONArray players = new JSONArray();
        for (Player p : game.getRegisteredPlayers()) { // everyone who started: a player who lost stays, with hasLost
            String pid = playerId(p);
            for (ZoneType z : new ZoneType[]{ZoneType.Library, ZoneType.Hand, ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command}) {
                Collection<Card> in = p.getCardsIn(z);
                boolean visible = z != ZoneType.Library && (z != ZoneType.Hand || p == viewer);
                JSONArray ids = new JSONArray();
                int shown = 0;
                if (visible) for (Card c : in) {
                    // Owner, Nov 9: Forge keeps an invisible "Commander Effect" in the command zone; Arcana took it for the
                    // commander once the real one left (blank player chips, "Co…" commander button). Emblems stay.
                    if (z == ZoneType.Command && internalEffect(c)) continue;
                    ids.put(cardId(c)); cards.put(cardId(c), card(c, z)); shown++;
                }
                int size = z == ZoneType.Command && visible ? shown : in.size();
                zones.put(new JSONObject().put("zoneId", new JSONObject().put("zoneType", zoneName(z)).put("ownerId", pid))
                        .put("cardIds", ids).put("size", size).put("isVisible", visible).put("positions", JSONObject.NULL));
            }
            players.put(fillPlayer(new JSONObject().put("playerId", pid).put("name", p.getName()).put("life", p.getLife())
                    .put("handSize", p.getCardsIn(ZoneType.Hand).size()).put("librarySize", p.getCardsIn(ZoneType.Library).size())
                    .put("graveyardSize", p.getCardsIn(ZoneType.Graveyard).size()).put("exileSize", p.getCardsIn(ZoneType.Exile).size())
                    .put("landsPlayedThisTurn", p.getLandsPlayedThisTurn()).put("hasLost", p.hasLost()).put("poisonCounters", p.getPoisonCounters())
                    .put("maxHandSize", p.getMaxHandSize()).put("commanderDamage", commanderDamage(p)).put("activeEffects", new JSONArray())
                    .put("manaPool", manaPool(p)).put("commanderTax", commanderTax(p))));
        }
        JSONArray stack = new JSONArray();
        List<forge.game.spellability.SpellAbilityStackInstance> items = new ArrayList<>();
        for (forge.game.spellability.SpellAbilityStackInstance si : game.getStack()) items.add(si); // top first
        java.util.Collections.reverse(items); // Arcana's stack zone lists the bottom first
        for (forge.game.spellability.SpellAbilityStackInstance si : items) {
            JSONObject item = stackItem(si);
            stack.put(item.getString("id")); cards.put(item.getString("id"), item);
        }
        zones.put(new JSONObject().put("zoneId", new JSONObject().put("zoneType", "Stack").put("ownerId", JSONObject.NULL))
                .put("cardIds", stack).put("size", stack.length()).put("isVisible", true).put("positions", JSONObject.NULL));
        PhaseType phase = ph.getPhase();
        return fill(new JSONObject()
                .put("deck", new JSONArray())
                .put("viewingPlayerId", viewer == null ? JSONObject.NULL : playerId(viewer))
                .put("cards", cards).put("zones", zones).put("players", players)
                .put("currentPhase", phaseName(phase)).put("currentStep", stepName(phase))
                .put("activePlayerId", ph.getPlayerTurn() == null ? JSONObject.NULL : playerId(ph.getPlayerTurn()))
                .put("priorityPlayerId", ph.getPriorityPlayer() == null ? JSONObject.NULL : playerId(ph.getPriorityPlayer()))
                .put("turnNumber", ph.getTurn()).put("isGameOver", game.isGameOver()).put("winnerId", game.isGameOver() ? winnerId(game) : JSONObject.NULL)
                .put("combat", JSONObject.NULL).put("gameLog", gameLog(game, viewer)), "state");
    }

    private static final java.util.Set<forge.game.GameLogEntryType> LOGGED = forge.game.GameLogVerbosity.MEDIUM.getIncludedTypes();

    /**
     * Forge's game log for Arcana's (owner, Nov 9: "the game log doesn't appear to be recording the game"; the bridge
     * sent it empty). Forge's medium detail: turns, spells and abilities, what resolves, combat, damage, life, lands,
     * discards, cards changing zones; no mana or phase lines. Oldest first, the last 200. Your lines are marked yours.
     */
    private JSONArray gameLog(Game game, Player viewer) throws Exception {
        JSONArray out = new JSONArray();
        java.util.List<forge.game.GameLogEntry> all = game.getGameLog().getAllEntries();
        java.util.List<forge.game.GameLogEntry> shown = new ArrayList<>();
        java.util.List<Integer> at = new ArrayList<>(); // each shown entry's place in the full log
        for (int k = 0; k < all.size(); k++) {
            forge.game.GameLogEntry e = all.get(k);
            if (e != null && LOGGED.contains(e.type()) && e.message() != null && !e.message().isBlank()) { shown.add(e); at.add(k); }
        }
        int from = Math.max(0, shown.size() - 200);
        for (int i = from; i < shown.size(); i++) {
            forge.game.GameLogEntry e = shown.get(i);
            JSONObject line = new JSONObject().put("description", plain(e.message()))
                    .put("type", e.type() == forge.game.GameLogEntryType.TURN ? "turnChanged" : e.type().name().toLowerCase(java.util.Locale.ROOT))
                    .put("eventIndex", i);
            if (viewer != null && e.sourceCard() != null && e.sourceCard().getController() != null)
                line.put("isYours", e.sourceCard().getController().getId() == viewer.getId());
            // Owner, Nov 9: medium detail in the list, full detail on tap. Everything Forge logged from this entry up to
            // the next shown one (mana, phases, damage, zone moves...), at most 30 lines.
            int end = i + 1 < shown.size() ? at.get(i + 1) : all.size();
            JSONArray details = new JSONArray();
            for (int k = at.get(i) + 1; k < end && details.length() < 30; k++) {
                forge.game.GameLogEntry d = all.get(k);
                if (d != null && d.message() != null && !d.message().isBlank()) details.put(plain(d.message()));
            }
            if (details.length() > 0) line.put("details", details);
            out.put(line);
        }
        return out;
    }

    private static JSONObject fillPlayer(JSONObject p) throws Exception { return fill(p, "player"); }

    private JSONArray commanderDamage(Player p) throws Exception {
        JSONArray out = new JSONArray();
        for (Map.Entry<Card, Integer> e : p.getCommanderDamage())
            if (e.getValue() > 0) out.put(new JSONObject().put("commanderId", cardId(e.getKey())).put("commanderName", e.getKey().getName()).put("amount", e.getValue()));
        return out;
    }

    /** Chimera (owner, Nov 9: Commander button): each commander's tax, by card id (2 per earlier cast from the command zone). */
    private JSONObject commanderTax(Player p) throws Exception {
        JSONObject out = new JSONObject();
        for (Card c : p.getCommanders()) if (c != null) out.put(String.valueOf(cardId(c)), 2 * p.getCommanderCast(c));
        return out;
    }

    private static JSONObject manaPool(Player p) throws Exception {
        forge.game.mana.ManaPool pool = p.getManaPool();
        return new JSONObject().put("white", pool.getAmountOfColor(forge.card.MagicColor.WHITE)).put("blue", pool.getAmountOfColor(forge.card.MagicColor.BLUE))
                .put("black", pool.getAmountOfColor(forge.card.MagicColor.BLACK)).put("red", pool.getAmountOfColor(forge.card.MagicColor.RED))
                .put("green", pool.getAmountOfColor(forge.card.MagicColor.GREEN)).put("colorless", pool.getAmountOfColor((byte) forge.card.MagicColor.COLORLESS))
                .put("restrictedMana", new JSONArray());
    }

    private JSONObject card(Card c, ZoneType z) throws Exception {
        JSONArray types = new JSONArray();
        if (c.isLand()) types.put("LAND");
        if (c.isCreature()) types.put("CREATURE");
        if (c.isArtifact()) types.put("ARTIFACT");
        if (c.isEnchantment()) types.put("ENCHANTMENT");
        if (c.isPlaneswalker()) types.put("PLANESWALKER");
        if (c.isInstant()) types.put("INSTANT");
        if (c.isSorcery()) types.put("SORCERY");
        JSONObject card = new JSONObject().put("id", cardId(c)).put("name", c.getName())
                .put("manaCost", c.getManaCost() == null ? "" : c.getManaCost().getShortString()).put("manaValue", c.getCMC())
                .put("typeLine", c.getType().toString()).put("cardTypes", types).put("subtypes", new JSONArray(new ArrayList<>(c.getType().getSubtypes())))
                .put("colors", new JSONArray()).put("oracleText", text(c))
                .put("power", c.isCreature() ? c.getNetPower() : JSONObject.NULL).put("toughness", c.isCreature() ? c.getNetToughness() : JSONObject.NULL)
                .put("isTapped", c.isTapped()).put("hasSummoningSickness", c.hasSickness())
                .put("controllerId", playerId(c.getController())).put("ownerId", playerId(c.getOwner()))
                .put("zone", new JSONObject().put("zoneType", zoneName(z)).put("ownerId", playerId(c.getOwner())))
                .put("keywords", new JSONArray()).put("counters", new JSONObject()).put("attachments", new JSONArray()).put("targets", new JSONArray())
                .put("isAttacking", stateCombat != null && stateCombat.isAttacking(c)).put("isBlocking", stateCombat != null && stateCombat.isBlocking(c))
                .put("attackingTarget", stateCombat != null && stateCombat.isAttacking(c) ? idOrNull(stateCombat.getDefenderByAttacker(c)) : JSONObject.NULL);
        cardDetails(card, c);
        // The picture: Arcana's mulligan screen shows only this field, and the table otherwise looks every card up
        // on Scryfall one at a time (owner, Nov 8 23:06: blank cards, no art on the mulligan screen).
        // Forge's internal effect objects ("Commander Effect") have no picture (owner, Nov 9 diagnostics).
        if ((!card.optBoolean("isFaceDown") || c.getController() == youPlayer()) && !internalEffect(c)) {
            String img = picture(c.getName(), c.getOwner() == null ? null : playerId(c.getOwner()));
            if (img != null) card.put("imageUri", img);
        }
        return fill(card, "card");
    }

    /**
     * Card name -> picture link, set by the app (Arcana's bundled card data, which the build fills for Forge's cards
     * too). Null: no pictures from the bridge.
     */
    public static volatile java.util.function.BiFunction<String, String, String> pictures;

    /** {@code ownerSeat}: whose deck the card is from, for the art chosen in that deck (Change art). */
    private static boolean onAndroid() {
        try { Class.forName("android.os.Build"); return true; } catch (Throwable e) { return false; }
    }

    private static boolean allPlayers(Iterable<?> choices) {
        boolean any = false;
        for (Object o : choices) { if (!(o instanceof Player)) return false; any = true; }
        return any;
    }

    /** Forge's rule-keeping objects (commander effect and the like): not cards a player has; emblems are kept. */
    private static boolean internalEffect(Card c) {
        return c.isImmutable() && !c.isEmblem();
    }

    private static String picture(String name, String ownerSeat) {
        java.util.function.BiFunction<String, String, String> f = pictures;
        if (f == null || name == null || name.isBlank()) return null;
        try { String url = f.apply(name, ownerSeat); return url == null || url.isBlank() ? null : url; } catch (RuntimeException e) { return null; }
    }

    private Player youPlayer() { PlayerControllerHuman y = humanController(); return y == null ? null : y.getPlayer(); }

    /** The card's rules text; Forge's printed text when the live card has none (e.g. a card not yet fully loaded). */
    private static String text(Card c) {
        String t = c.getOracleText();
        if ((t == null || t.isBlank()) && c.getRules() != null) t = c.getRules().getOracleText();
        return t == null ? "" : t.replace("\\n", "\n");
    }

    /** Fields Arcana's table draws that the base card leaves at their defaults (audit, Nov 8). */
    private void cardDetails(JSONObject card, Card c) throws Exception {
        JSONArray colors = new JSONArray();
        if (c.isWhite()) colors.put("WHITE"); if (c.isBlue()) colors.put("BLUE"); if (c.isBlack()) colors.put("BLACK");
        if (c.isRed()) colors.put("RED"); if (c.isGreen()) colors.put("GREEN");
        card.put("colors", colors);
        JSONObject counters = new JSONObject();
        for (forge.game.card.CounterType t : c.getCounters().elementSet()) {
            String key = t instanceof forge.game.card.CounterEnumType e
                    ? (e == forge.game.card.CounterEnumType.P1P1 ? "PLUS_ONE_PLUS_ONE" : e == forge.game.card.CounterEnumType.M1M1 ? "MINUS_ONE_MINUS_ONE" : e.name())
                    : t.getName();
            counters.put(key, c.getCounters().count(t));
        }
        card.put("counters", counters);
        if (c.getAttachedTo() != null) card.put("attachedTo", cardId(c.getAttachedTo()));
        if (c.getDamage() > 0) card.put("damage", c.getDamage());
        card.put("isToken", c.isToken()).put("isCommander", c.isCommander());
        if (c.isFaceDown()) { // hidden from opponents: no name, text or cost
            card.put("isFaceDown", true);
            PlayerControllerHuman you = humanController();
            if (you == null || c.getController() != you.getPlayer()) card.put("name", "Face-down card").put("oracleText", "").put("manaCost", "").put("typeLine", "Creature");
        }
    }

    /** A spell or ability on Forge's stack, as an Arcana stack card (abilities aren't cards in Forge's stack zone). */
    private JSONObject stackItem(forge.game.spellability.SpellAbilityStackInstance si) throws Exception {
        Card src = si.getSourceCard();
        JSONObject item = si.isSpell() && src != null ? card(src, ZoneType.Stack)
                : new JSONObject().put("id", "st" + si.getId()).put("name", src == null ? "Ability" : src.getName())
                    .put("typeLine", si.isTrigger() ? "Triggered ability" : "Activated ability").put("cardTypes", new JSONArray())
                    .put("oracleText", (si.isTrigger() ? "Triggered ability: " : "Activated ability: ") + plain(si.getStackDescription()))
                    .put("stackText", plain(si.getStackDescription())).put("manaCost", "").put("manaValue", 0)
                    .put("zone", new JSONObject().put("zoneType", "Stack").put("ownerId", JSONObject.NULL));
        // Owner, Nov 9 (blank cards on the stack): an ability shows the picture of the card it comes from.
        if (!si.isSpell() && src != null && !item.has("imageUri")) {
            String img = picture(src.getName(), src.getOwner() == null ? null : playerId(src.getOwner()));
            if (img != null) item.put("imageUri", img);
        }
        String controller = playerId(si.getActivatingPlayer());
        item.put("controllerId", controller).put("ownerId", controller);
        JSONArray targets = new JSONArray();
        forge.game.spellability.TargetChoices tc = si.getTargetChoices();
        if (tc != null) {
            for (Player p : tc.getTargetPlayers()) targets.put(new JSONObject().put("playerId", playerId(p)).put("name", p.getName()));
            for (Card t : tc.getTargetCards()) targets.put(new JSONObject().put("cardId", cardId(t)).put("name", t.getName()));
        }
        item.put("targets", targets);
        return fill(item, "card");
    }

    // ---------------------------------------------------------------- naming

    private PlayerControllerHuman humanController() {
        Game game = match == null ? null : match.getGame();
        if (game == null) return null;
        if (left) return null;
        if (!seatIds.isEmpty()) { // seated game: this view's own seat (the host's, or a guest's)
            for (Player p : game.getRegisteredPlayers())
                if (me.equals(seatIds.get(p.getRegisteredPlayer()))) return p.getController() instanceof PlayerControllerHuman h ? h : null;
            return null;
        }
        for (Player p : game.getPlayers()) if (p.getController() instanceof PlayerControllerHuman h) return h;
        // A player who lost leaves game.getPlayers(); find them among everyone who started.
        for (Player p : game.getRegisteredPlayers()) if (p.getController() instanceof PlayerControllerHuman h) return h;
        StringBuilder who = new StringBuilder();
        for (Player p : game.getPlayers()) who.append(p.getName()).append('=').append(p.getController() == null ? "null" : p.getController().getClass().getSimpleName()).append(' ');
        System.err.println("no human controller: players [" + who + "] age " + game.getAge() + " over " + game.isGameOver());
        return null;
    }

    private String playerId(Player p) {
        if (p == null) return AI_ID;
        String id = seatIds.get(p.getRegisteredPlayer());
        return id != null ? id : p.getController() instanceof PlayerControllerHuman ? HUMAN_ID : AI_ID;
    }

    private static String cardId(Card c) { return "f" + c.getId(); }
    private static String key(String type, String cardId, String abilityId) { return type + "|" + cardId + "|" + abilityId; }

    private static String zoneName(ZoneType z) {
        return switch (z) {
            case Library -> "Library"; case Hand -> "Hand"; case Battlefield -> "Battlefield"; case Graveyard -> "Graveyard";
            case Exile -> "Exile"; case Command -> "Command"; case Stack -> "Stack"; default -> z.name();
        };
    }

    private static String stepName(PhaseType p) {
        if (p == null) return "UNTAP";
        return switch (p.name()) {
            case "UNTAP" -> "UNTAP"; case "UPKEEP" -> "UPKEEP"; case "DRAW" -> "DRAW"; case "MAIN1" -> "PRECOMBAT_MAIN";
            case "COMBAT_BEGIN" -> "BEGIN_COMBAT"; case "COMBAT_DECLARE_ATTACKERS" -> "DECLARE_ATTACKERS";
            case "COMBAT_DECLARE_BLOCKERS" -> "DECLARE_BLOCKERS"; case "COMBAT_FIRST_STRIKE_DAMAGE" -> "FIRST_STRIKE_COMBAT_DAMAGE";
            case "COMBAT_DAMAGE" -> "COMBAT_DAMAGE"; case "COMBAT_END" -> "END_COMBAT"; case "MAIN2" -> "POSTCOMBAT_MAIN";
            case "END_OF_TURN" -> "END"; default -> "CLEANUP";
        };
    }

    private static String phaseName(PhaseType p) {
        String s = stepName(p);
        return switch (s) {
            case "UNTAP", "UPKEEP", "DRAW" -> "BEGINNING"; case "PRECOMBAT_MAIN" -> "PRECOMBAT_MAIN"; case "POSTCOMBAT_MAIN" -> "POSTCOMBAT_MAIN";
            case "END", "CLEANUP" -> "ENDING"; default -> "COMBAT";
        };
    }

    /** Every field Argentum's stateUpdate carries (neutral values), taken from a real Argentum message, so Arcana's parser finds them all. */
    private static final String ARGENTUM_DEFAULTS = "{\"card\":{\"classMaxLevel\":null,\"isLandscapeFace\":false,\"attackingTarget\":null,\"ownerId\":null,\"subtypes\":[],\"manaPaidCost\":null,\"perModeTargets\":[],\"wasBlightPaid\":false,\"id\":null,\"toughness\":0,\"backFacePower\":null,\"isAttacking\":false,\"counters\":{},\"controllerId\":null,\"isDashed\":false,\"saddleRequirement\":null,\"isBlitzed\":false,\"stackText\":null,\"chosenCardName\":null,\"deliriumInfo\":null,\"isExerted\":false,\"isWarped\":false,\"damage\":null,\"attachments\":[],\"isTransformed\":false,\"optionalCostLabel\":null,\"classLevel\":null,\"targets\":[],\"colors\":[],\"basePower\":0,\"playableFromExile\":false,\"castFaceIndex\":null,\"power\":0,\"faceDownMode\":null,\"chosenCreatureType\":null,\"isBlocking\":false,\"oracleText\":null,\"hexproofFromMonocolored\":false,\"isSaddled\":false,\"sacrificedCreatureTypes\":null,\"backFaceToughness\":null,\"protections\":[],\"copyOf\":null,\"abilityFlags\":[],\"hasSummoningSickness\":false,\"chosenModeDescriptions\":[],\"revealedName\":null,\"isSolved\":false,\"grantedSubtypes\":[],\"chosenCardType\":null,\"cardFaces\":[],\"grantedColors\":[],\"protectorId\":null,\"evoke\":null,\"activeEffects\":[],\"nonLegendaryCopy\":false,\"backFaceKeywords\":[],\"keywords\":[],\"cardTypes\":[],\"rulings\":[],\"damageDistribution\":null,\"isRingBearer\":false,\"isRoom\":false,\"isToken\":false,\"backFaceName\":null,\"zone\":{},\"blockingTarget\":null,\"chosenColor\":null,\"isPreparedSpell\":false,\"hexproofFromColors\":[],\"isFaceDown\":false,\"sourceZone\":null,\"copyOfImageUri\":null,\"grantedCardTypes\":[],\"morphCost\":null,\"impending\":null,\"name\":null,\"manaValue\":0,\"currentFace\":null,\"isMonstrous\":false,\"isPlotted\":false,\"backFaceOracleText\":null,\"castProvenanceLabel\":null,\"isCommander\":false,\"attachedTo\":null,\"bestow\":null,\"revealedImageUri\":null,\"imageUri\":null,\"isDoubleFaced\":false,\"isParadigm\":false,\"backFaceImageUri\":null,\"copyIndex\":null,\"abilityIdentity\":null,\"mutate\":null,\"typeLine\":null,\"isSuspected\":false,\"isSuspended\":false,\"isPhasedOut\":false,\"pairedWithId\":null,\"linkedExile\":[],\"isRenowned\":false,\"costSacrificeLabel\":null,\"legendaryByEffect\":false,\"copyTotal\":null,\"baseToughness\":0,\"thresholdInfo\":null,\"imageRotation\":0,\"backFaceIsLandscape\":false,\"isPrepared\":false,\"chosenX\":null,\"giftPromised\":false,\"sagaTotalChapters\":null,\"planeswalkerAbilities\":null,\"backFaceTypeLine\":null,\"chosenMode\":null,\"manaCost\":null,\"isTapped\":false,\"hexproofFromMulticolored\":false,\"triggeringEntityId\":null},\"player\":{\"experienceCounters\":0,\"poisonCounters\":0,\"landsPlayedThisTurn\":0,\"handSize\":0,\"exileSize\":0,\"manaPool\":{\"white\":0,\"blue\":0,\"black\":0,\"red\":0,\"green\":0,\"colorless\":0,\"restrictedMana\":[]},\"life\":0,\"speed\":0,\"manaPaymentColors\":{},\"graveyardSize\":0,\"energyCounters\":0,\"commanderDamage\":[],\"completedDungeons\":[],\"hasLost\":false,\"companionId\":null,\"playerId\":null,\"maxHandSize\":0,\"teamSharedTurns\":false,\"teamSharedLife\":false,\"dungeon\":null,\"librarySize\":0,\"name\":null,\"radCounters\":0,\"teamIndex\":null,\"activeEffects\":[]},\"state\":{\"gameLog\":[],\"youAreHijacking\":null,\"youAreHijackedBy\":null,\"combat\":null,\"currentStep\":null,\"isGameOver\":false,\"roundNumber\":0,\"priorityPlayerId\":null,\"dayNight\":null,\"currentPhase\":null,\"winnerId\":null,\"attackMode\":null,\"hotseat\":false,\"turnNumber\":0,\"activePlayerId\":null,\"viewingPlayerId\":null,\"activeYields\":[],\"voidActive\":false},\"top\":{\"stopOverrides\":null,\"pendingDecision\":null,\"priorityMode\":null,\"interactionEpoch\":null,\"legalActions\":[],\"opponentDecisionStatus\":null,\"type\":null,\"undoAvailable\":false,\"events\":[],\"nextStopPoint\":null,\"stateVersion\":0}}";
    private static JSONObject defaults;

    private static JSONObject defaults(String part) throws Exception {
        if (defaults == null) defaults = new JSONObject(ARGENTUM_DEFAULTS);
        return defaults.getJSONObject(part);
    }

    private static JSONObject fill(JSONObject target, String part) throws Exception {
        JSONObject d = defaults(part);
        java.util.Iterator<String> keys = d.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            if (!target.has(k)) target.put(k, d.isNull(k) ? JSONObject.NULL : d.get(k));
        }
        return target;
    }

    /** Exception with its root cause and first frames, for Arcana's diagnostic report. */
    static String describe(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        StringBuilder b = new StringBuilder(t.toString());
        if (root != t) b.append(" | root cause: ").append(root);
        StackTraceElement[] st = root.getStackTrace();
        for (int i = 0; i < Math.min(4, st.length); i++) b.append(" | at ").append(st[i]);
        return b.toString();
    }

    private void error(String text) {
        try { sink.accept(new JSONObject().put("type", "error").put("code", "FORGE_HOST").put("message", text)); }
        catch (Exception ignored) { }
    }

    /**
     * Your move was refused (an illegal block, a pass while a question is open...): Argentum's code for that,
     * INVALID_ACTION. Arcana unlocks its block screen only for that code, so with FORGE_HOST a refused block left the
     * declare-blockers step with no way to answer it (owner, Nov 9 11:27: Aang and La, "frozen waiting for the AI").
     */
    private void reject(String text) {
        try { sink.accept(new JSONObject().put("type", "error").put("code", "INVALID_ACTION").put("message", plain(text))); }
        catch (Exception ignored) { }
    }

    /**
     * Forge telling you something (the AI's choice of a creature type or color, a reveal): shown as a note, not an
     * error (owner, Nov 9: "Offline AI picked Bear" arrived as an error). Arcana shows LINK_NOTE messages plainly.
     */
    private void note(String text) {
        try { sink.accept(new JSONObject().put("type", "error").put("code", "LINK_NOTE").put("message", plain(text))); }
        catch (Exception ignored) { }
    }

    /** Forge's combat rule messages, the way a player says them (and without Forge's object numbers). */
    static String plain(String text) {
        String t = humanTypes(text.replaceAll(" \\(\\d+\\)", "")).trim();
        java.util.regex.Matcher m;
        if ((m = java.util.regex.Pattern.compile("^(.+?) cannot be blocked with (\\d+) creatures you've assigned\\.?$").matcher(t)).find())
            return m.group(1) + " can't be blocked by just " + m.group(2) + (m.group(2).equals("1") ? " creature" : " creatures")
                    + ". Choose more blockers, or none.";
        if ((m = java.util.regex.Pattern.compile("^(.+?) must block an attacker, but has not been assigned to block (any|the right ones)\\.?$").matcher(t)).find())
            return m.group(1) + " must block if it can" + (m.group(2).equals("any") ? "" : ", and not just any attacker") + ". Assign it to block.";
        if ((m = java.util.regex.Pattern.compile("^(.+?) must block each combat but was not assigned to block any attacker now\\.?$").matcher(t)).find())
            return m.group(1) + " must block each combat. Assign it to block.";
        if ((m = java.util.regex.Pattern.compile("^(.+?) must still block (.+?)\\.?$").matcher(t)).find())
            return m.group(1) + " must block " + m.group(2) + ".";
        return t;
    }

    /**
     * Owner, Nov 9: "Select 1 Permanent.Other(s) to sacrifice" -> "Select 1 other permanent to sacrifice". Forge's card
     * filters (Type.Property+Property) read as words; "(s)" becomes the right plural for the number in front.
     */
    static String humanTypes(String t) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:(\\d+) )?\\b([A-Z][a-zA-Z]*)((?:[.+][A-Za-z]+)+)(\\(s\\))?").matcher(t);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String n = m.group(1), type = m.group(2), props = m.group(3), plural = m.group(4);
            if (!props.contains(".") && !props.startsWith("+")) { m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(m.group())); continue; }
            java.util.List<String> before = new ArrayList<>(), after = new ArrayList<>();
            for (String p : props.split("[.+]")) {
                if (p.isEmpty()) continue;
                switch (p) {
                    case "Other" -> before.add("other");
                    case "nonToken", "nonToken+YouCtrl" -> before.add("nontoken");
                    case "token" -> before.add("token");
                    case "YouCtrl" -> after.add("you control");
                    case "OppCtrl" -> after.add("an opponent controls");
                    case "YouOwn" -> after.add("you own");
                    case "untapped" -> before.add("untapped");
                    case "tapped" -> before.add("tapped");
                    case "attacking" -> before.add("attacking");
                    case "nonLand" -> before.add("nonland");
                    case "nonCreature" -> before.add("noncreature");
                    default -> before.add(p.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(java.util.Locale.ROOT));
                }
            }
            boolean many = plural != null && (n == null || !n.equals("1"));
            String noun = type.toLowerCase(java.util.Locale.ROOT) + (many ? (type.endsWith("y") ? "" : "s") : "");
            if (many && type.endsWith("y")) noun = noun.substring(0, noun.length() - 1) + "ies";
            String words = (n == null ? "" : n + " ") + String.join(" ", before) + (before.isEmpty() ? "" : " ") + noun
                    + (after.isEmpty() ? "" : " " + String.join(" ", after));
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(words));
        }
        m.appendTail(out);
        return out.toString().replaceAll("\\b1 ([A-Za-z ]+?)\\(s\\)", "1 $1").replace("(s)", "s").replace(" an other ", " another ").replace(" a other ", " another ");
    }

    // ---------------------------------------------------------------- player decisions (Argentum pendingDecision)

    /** An open question to the player. {@code onAnswer} applies it to a Forge input; null = a waiting GUI call takes it. */
    private record Decision(String id, JSONObject json, Map<String, Object> objects, java.util.function.Consumer<JSONObject> onAnswer) { }

    private volatile Decision pending;
    // Buffered: a SynchronousQueue dropped an answer that arrived before Forge's thread reached take().
    private final java.util.concurrent.LinkedBlockingQueue<JSONObject> answers = new java.util.concurrent.LinkedBlockingQueue<>();
    private final java.util.concurrent.atomic.AtomicInteger decisionSeq = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.Set<String> noticed = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private JSONObject decisionBase(String type, String prompt) throws Exception {
        String id = "forge-d" + decisionSeq.incrementAndGet();
        return new JSONObject().put("type", type).put("id", id).put("playerId", me).put("prompt", prompt == null ? "" : plain(prompt))
                .put("context", new JSONObject().put("phase", "RESOLUTION"));
    }

    /** Shows the decision and, for GUI calls, blocks Forge's game thread until the player answers (as desktop Forge does). */
    private JSONObject ask(JSONObject json, Map<String, Object> objects) throws Exception {
        answers.clear();
        pending = new Decision(json.getString("id"), json, objects, null);
        emitDecision();
        try {
            JSONObject r = answers.take();
            return r.optBoolean("left") ? leftAnswer(json) : r;
        } finally { pending = null; }
    }

    /** The answer for a person who left mid-question: the minimum, the default split, "no". */
    private static JSONObject leftAnswer(JSONObject d) throws Exception {
        JSONObject r = new JSONObject();
        switch (d.optString("type")) {
            case "SelectCardsDecision" -> {
                JSONArray picks = new JSONArray(), opts = d.optJSONArray("options");
                for (int i = 0; opts != null && i < Math.min(d.optInt("minSelections"), opts.length()); i++) picks.put(opts.get(i));
                r.put("selectedCards", picks);
            }
            case "ChooseOptionDecision" -> r.put("optionIndex", 0);
            case "ChooseNumberDecision" -> r.put("number", d.optInt("minValue"));
            case "OrderObjectsDecision" -> r.put("orderedObjects", d.optJSONArray("objects"));
            case "AssignDamageDecision" -> r.put("assignments", d.optJSONObject("defaultAssignments"));
            case "DistributeDecision" -> {
                JSONObject split = new JSONObject(); JSONArray t = d.optJSONArray("targets");
                if (t != null && t.length() > 0) { for (int i = 0; i < t.length(); i++) split.put(t.getString(i), 0); split.put(t.getString(0), d.optInt("totalAmount")); }
                r.put("distribution", split);
            }
            default -> r.put("choice", false);
        }
        return r;
    }

    private void openForInput(JSONObject json, Map<String, Object> objects, java.util.function.Consumer<JSONObject> onAnswer) throws Exception {
        pending = new Decision(json.getString("id"), json, objects, onAnswer);
        emitDecision();
    }

    private void emitDecision() throws Exception {
        Decision d = pending;
        Game game = match == null ? null : match.getGame();
        PlayerControllerHuman you = humanController();
        if (d == null || game == null || you == null) return;
        // Forge asks opening-hand questions (Leylines, Chancellors: "Choose cards to activate from opening hand") after
        // everyone has kept, before turn 1: the mulligan is over, so close Arcana's opening-hand screen first (soak 506).
        if (inMulligan) {
            inMulligan = false;
            sink.accept(new JSONObject().put("type", "mulliganComplete").put("finalHandSize", you.getPlayer().getCardsIn(ZoneType.Hand).size()));
        }
        sink.accept(fill(new JSONObject().put("type", "stateUpdate").put("state", state(game, you.getPlayer()))
                .put("legalActions", new JSONArray()).put("pendingDecision", d.json()).put("stateVersion", ++version)
                .put("interactive", true).put("interactionEpoch", "forge-" + version).put("priorityMode", "auto"), "top"));
    }

    private void answer(JSONObject response) {
        Decision d = pending;
        if (d == null || !d.id().equals(response.optString("decisionId"))) {
            // Arcana cleared its question when you answered: show the one Forge is actually asking now, or the table
            reject("That question was already answered.");
            try { if (pending != null) emitDecision(); else scheduleSelf(); } catch (Exception ignored) { scheduleSelf(); }
            return;
        }
        if (d.onAnswer() == null) { answers.offer(response); return; }
        pending = null;
        try { d.onAnswer().accept(response); } catch (Exception e) { error("Forge host problem: " + describe(e)); }
    }

    private void notice(String kind, String text) {
        if (noticed.add(kind)) error(text);
    }

    /** Arcana id for a Forge object (cards and players), or null. */
    private String idOf(Object o) {
        if (o instanceof Card c) return cardId(c);
        if (o instanceof forge.game.card.CardView cv) return "f" + cv.getId();
        if (o instanceof Player p) return playerId(p);
        if (o instanceof forge.game.player.PlayerView pv) {
            Game g = match == null ? null : match.getGame();
            if (g != null) for (Player p : g.getPlayers()) if (p.getId() == pv.getId()) return playerId(p);
        }
        return null;
    }

    private String label(Object o) {
        if (o instanceof forge.game.player.PlayerView pv) return pv.getName();
        if (o instanceof Player p) return p.getName();
        if (o instanceof forge.game.card.CardView cv) return cv.getName();
        return String.valueOf(o);
    }

    private JSONObject cardInfo(Object o) throws Exception {
        // Owner, Nov 9: tap a card or ability name in a choice to see the card, so every entry carries its picture.
        if (o instanceof forge.game.spellability.SpellAbilityView v) {
            String host = v.getHostCard() == null ? "" : v.getHostCard().getName();
            return withPicture(new JSONObject().put("name", host.isEmpty() ? v.toString() : host).put("typeLine", "Ability").put("manaCost", "").put("text", plain(v.toString())), host);
        }
        if (o instanceof forge.game.replacement.ReplacementEffectView r) {
            String host = r.getHostCard() == null ? "" : r.getHostCard().getName();
            return withPicture(new JSONObject().put("name", host.isEmpty() ? r.getDescription() : host).put("typeLine", "Replacement effect").put("manaCost", "").put("text", plain(r.getDescription())), host);
        }
        String name = o instanceof Card c ? c.getName() : o instanceof forge.game.card.CardView cv ? cv.getName() : label(o);
        String type = o instanceof Card c ? c.getType().toString() : "";
        String cost = o instanceof Card c && c.getManaCost() != null ? c.getManaCost().getShortString() : "";
        JSONObject info = new JSONObject().put("name", name).put("typeLine", type).put("manaCost", cost);
        return o instanceof Card || o instanceof forge.game.card.CardView ? withPicture(info, name) : info;
    }

    private JSONObject withPicture(JSONObject info, String cardName) throws Exception {
        if (cardName == null || cardName.isEmpty()) return info;
        String img = picture(cardName, null);
        return img == null ? info : info.put("imageUri", img);
    }

    /** Pick min..max of the choices: cards via SelectCardsDecision, anything else (single pick) via ChooseOptionDecision. */
    private <T> List<T> choose(String prompt, int min, int max, List<T> choices) throws Exception {
        if (choices == null || choices.isEmpty()) return new ArrayList<>();
        max = Math.min(max, choices.size());
        min = Math.min(min, max);
        boolean cards = choices.stream().allMatch(c -> c instanceof Card || c instanceof forge.game.card.CardView);
        Map<String, Object> objects = new LinkedHashMap<>();
        if (cards) {
            JSONObject d = decisionBase("SelectCardsDecision", prompt);
            JSONArray options = new JSONArray(); JSONObject info = new JSONObject();
            for (T c : choices) { String id = idOf(c); objects.put(id, c); options.put(id); info.put(id, cardInfo(c)); }
            d.put("options", options).put("minSelections", min).put("maxSelections", max).put("cardInfo", info);
            JSONArray picked = ask(d, objects).optJSONArray("selectedCards");
            List<T> out = new ArrayList<>();
            if (picked != null) for (int i = 0; i < picked.length(); i++) {
                @SuppressWarnings("unchecked") T t = (T) objects.get(picked.getString(i));
                if (t != null) out.add(t);
            }
            return out;
        }
        if (max <= 1) {
            JSONObject d = decisionBase("ChooseOptionDecision", prompt);
            JSONArray options = new JSONArray();
            for (T c : choices) options.put(label(c));
            d.put("options", options).put("canCancel", min == 0);
            int index = ask(d, objects).optInt("optionIndex", -1);
            return index >= 0 && index < choices.size() ? new ArrayList<>(List.of(choices.get(index))) : new ArrayList<>();
        }
        // Several non-card choices (colours, creature types, modes, players): a card picker with one named tile each.
        JSONObject d = decisionBase("SelectCardsDecision", prompt);
        JSONArray options = new JSONArray(); JSONObject info = new JSONObject();
        int n = 0;
        for (T c : choices) {
            String id = "o" + (n++);
            objects.put(id, c); options.put(id);
            info.put(id, new JSONObject().put("name", label(c)).put("typeLine", "Choice").put("manaCost", ""));
        }
        d.put("options", options).put("minSelections", min).put("maxSelections", max).put("cardInfo", info);
        JSONArray picked = ask(d, objects).optJSONArray("selectedCards");
        List<T> out = new ArrayList<>();
        if (picked != null) for (int i = 0; i < picked.length(); i++) {
            @SuppressWarnings("unchecked") T t = (T) objects.get(picked.optString(i));
            if (t != null && !out.contains(t)) out.add(t);
        }
        for (T c : choices) { if (out.size() >= min) break; if (!out.contains(c)) out.add(c); } // never fewer than Forge requires
        return out;
    }

    /** Forge's dual-list prompt: pure ordering (OrderObjectsDecision) or "move some of these" (SelectCardsDecision). */
    private List<?> order(String prompt, List<?> source, List<?> dest, int remMin, int remMax) throws Exception {
        boolean cardItems = source != null && source.stream().allMatch(o -> o instanceof Card || o instanceof forge.game.card.CardView);
        // Everything ends up in the result (remMin == remMax == 0), or non-card items (replacement effects, abilities)
        // where moving all of them is always allowed (remMin == 0): only the order is the player's.
        if (remMin == 0 && (remMax == 0 || !cardItems)) {
            List<Object> items = new ArrayList<>();
            if (dest != null) items.addAll(dest); // already placed (e.g. Forge's "reorder" form passes them here)
            if (source != null) items.addAll(source);
            if (items.size() <= 1) return items;
            Map<String, Object> objects = new LinkedHashMap<>();
            JSONArray ids = new JSONArray(); JSONObject info = new JSONObject();
            int n = 0;
            for (Object o : items) {
                String id = idOf(o);
                if (id == null || objects.containsKey(id)) id = "o" + (n++) + "-" + (o instanceof forge.game.spellability.SpellAbilityView v ? v.getId() : 0);
                objects.put(id, o); ids.put(id); info.put(id, cardInfo(o));
            }
            JSONObject d = decisionBase("OrderObjectsDecision", prompt).put("objects", ids).put("cardInfo", info).put("orderingTitle", prompt);
            JSONArray ordered = ask(d, objects).optJSONArray("orderedObjects");
            List<Object> out = new ArrayList<>();
            if (ordered != null) for (int i = 0; i < ordered.length(); i++) { Object o = objects.get(ordered.optString(i)); if (o != null && !out.contains(o)) out.add(o); }
            for (Object o : items) if (!out.contains(o)) out.add(o); // never drop anything
            return out;
        }
        if (source == null) return List.of();
        // Choose which cards move: between (size - remMax) and (size - remMin) of them.
        int min = Math.max(0, source.size() - remMax), max = Math.max(min, source.size() - remMin);
        return choose(prompt, min, max, source);
    }

    /**
     * Forge's combat damage division (multi-block, trample) as Argentum's AssignDamageDecision. Forge decides when it's
     * asked and resolves the damage; the bridge only transports. Minimums: lethal to each blocker before the defending
     * player when trampling (CR 702.19c); blockers alone may be divided freely (CR 510.1c-d, Foundations rules).
     * Returns Forge's shape: blocker CardView -> amount, defender under the null key.
     */
    /** Divided damage, counters, shields, mana: Arcana's DistributeDecision. Forge re-checks the split and asks again if it's short. */
    private Map<Object, Integer> assignGenericAmount(forge.game.card.CardView source, Map<?, ?> targets, int amount,
            boolean atLeastOne, String label) throws Exception {
        Map<String, Object> byId = new LinkedHashMap<>();
        JSONArray ids = new JSONArray();
        JSONObject caps = new JSONObject();
        for (Map.Entry<?, ?> e : targets.entrySet()) {
            String id = idOf(e.getKey());
            byId.put(id, e.getKey()); ids.put(id);
            caps.put(id, e.getValue() instanceof Integer cap ? Math.min(cap, amount) : amount);
        }
        String name = source == null ? "this effect" : source.getName();
        while (true) {
            JSONObject d = decisionBase("DistributeDecision", "Divide " + amount + " " + (label == null ? "" : label) + " from " + name)
                    .put("targets", ids).put("totalAmount", amount).put("minPerTarget", atLeastOne ? 1 : 0).put("maxPerTarget", caps);
            JSONObject chosen = ask(d, Map.of()).optJSONObject("distribution");
            Map<Object, Integer> out = new LinkedHashMap<>();
            int total = 0; boolean ok = chosen != null;
            if (chosen != null) for (String id : keys(chosen)) {
                int n = chosen.optInt(id, -1);
                if (n < 0 || !byId.containsKey(id)) { ok = false; break; }
                out.put(byId.get(id), n); total += n;
            }
            if (ok && total == amount) { for (Object t : byId.values()) out.putIfAbsent(t, 0); return out; }
            notice("distribute-invalid", "That split doesn't add up to " + amount + "; choose again.");
        }
    }

    private Map<forge.game.card.CardView, Integer> assignCombatDamage(forge.game.card.CardView attackerView, List<?> blockerViews,
            int damage, Object defenderView, boolean overrideOrder) throws Exception {
        Game game = match.getGame();
        Card attacker = game.findByView(attackerView);
        boolean trample = attacker != null && attacker.hasKeyword(forge.game.keyword.Keyword.TRAMPLE);
        boolean deathtouch = attacker != null && attacker.hasKeyword(forge.game.keyword.Keyword.DEATHTOUCH);
        // overrideOrder is Forge's "divide freely" (current rules, no damage order); it doesn't open the defender.
        // Only trample, or the "divided as you choose among defending player" ability, lets damage reach them.
        boolean freeToPlayer = attacker != null && attacker.hasKeyword("You may assign CARDNAME's combat damage divided as you choose among "
                + "defending player and/or any number of creatures they control.");
        String defenderId = defenderView != null && (trample || freeToPlayer) ? idOf(defenderView) : null;
        Map<String, forge.game.card.CardView> blockers = new LinkedHashMap<>();
        JSONArray ordered = new JSONArray();
        JSONObject minimum = new JSONObject(), defaults = new JSONObject();
        int left = damage;
        Map<String, Integer> lethalOf = new LinkedHashMap<>();
        for (Object o : blockerViews) {
            forge.game.card.CardView v = (forge.game.card.CardView) o;
            String id = "f" + v.getId();
            blockers.put(id, v); ordered.put(id);
            Card b = game.findByView(v);
            int lethal = b == null ? 1 : deathtouch ? 1 : Math.max(0, b.getLethalDamage());
            lethalOf.put(id, lethal);
            minimum.put(id, 0); // a floor only applies if damage goes to the player (CR 702.19c), checked below
            int give = Math.min(lethal, left); defaults.put(id, give); left -= give;
        }
        if (left > 0) {
            if (defenderId != null) defaults.put(defenderId, left);
            else if (!blockers.isEmpty()) { String first = blockers.keySet().iterator().next(); defaults.put(first, defaults.getInt(first) + left); }
        }
        if (defenderId != null) minimum.put(defenderId, 0);
        String name = attacker == null ? attackerView.getName() : attacker.getName();
        while (true) {
            JSONObject d = decisionBase("AssignDamageDecision", "Assign " + damage + " combat damage from " + name)
                    .put("attackerId", "f" + attackerView.getId()).put("availablePower", damage).put("orderedTargets", ordered)
                    .put("defenderId", defenderId == null ? JSONObject.NULL : defenderId).put("minimumAssignments", minimum)
                    .put("defaultAssignments", defaults).put("hasTrample", trample).put("hasDeathtouch", deathtouch);
            d.getJSONObject("context").put("phase", "COMBAT").put("sourceId", "f" + attackerView.getId()).put("sourceName", name);
            JSONObject chosen = ask(d, Map.of()).optJSONObject("assignments");
            Map<forge.game.card.CardView, Integer> out = new java.util.HashMap<>(); // HashMap: Forge keys the defender as null
            int total = 0; boolean ok = chosen != null;
            if (chosen != null) for (String id : keys(chosen)) {
                int n = chosen.optInt(id, -1);
                if (n < 0) { ok = false; break; }
                if (blockers.containsKey(id)) { out.put(blockers.get(id), n); total += n; }
                else if (id.equals(defenderId)) { if (n > 0) out.put(null, n); total += n; }
                else if (n != 0) { ok = false; break; } // an unknown id with 0 (e.g. a stale editor entry) is harmless
            }
            String why = !ok ? "unknown target in " + chosen : total != damage ? "the split adds up to " + total + ", not " + damage : null;
            if (why == null && out.getOrDefault(null, 0) > 0) // trample: the player gets damage only once every blocker has lethal
                for (String id : blockers.keySet())
                    if (out.getOrDefault(blockers.get(id), 0) < lethalOf.get(id)) { why = "damage can reach the player only after each blocker has lethal damage (" + lethalOf.get(id) + " for " + blockers.get(id).getName() + ")"; break; }
            if (why == null) return out;
            error("That damage split can't be used: " + why + ". Try again.");
        }
    }

    // ---------------------------------------------------------------- test hooks (headless proof only)

    private volatile String startBoardForTest;

    /**
     * Test-only: the board to start from, as a Forge GameState script. Applied by Forge's own puzzle-mode start hook
     * (HostedMatch.setStartGameHook), on Forge's game thread, before the first turn.
     */
    public void setStartBoardForTest(String script) { startBoardForTest = script; }

    /**
     * Arcana's tutorial lessons (owner, Nov 10: "update the tutorial to work with the current version"): the lesson's
     * board, applied the same way, and the opening hand is kept for you (the board already chose your hand).
     */
    public void setPracticeBoard(String script) { startBoardForTest = script; practiceBoard = script != null; }
    private volatile boolean practiceBoard;

    private void applyStartBoard(HostedMatch m) {
        String script = startBoardForTest;
        if (script == null) return;
        class Setup extends forge.game.GameState { void run(Game g) { applyGameOnThread(g); } }
        m.setStartGameHook(() -> {
            Setup gs = new Setup();
            gs.parse(java.util.Arrays.asList(script.split("\n")));
            try { gs.run(m.getGame()); }
            catch (Throwable t) { System.err.println("start board failed: " + describe(t)); t.printStackTrace(); throw t; }
        });
    }

    /** Test-only: Forge's in-game GUI for this game, to call prompt shapes directly. */
    public IGuiGame guiForTest() { return guiGame(); }
    public List<Object> playerViewsForTest() { List<Object> l = new ArrayList<>(); for (Player p : match.getGame().getPlayers()) l.add(p.getView()); return l; }

    /** Test-only: Forge view of a battlefield card by Arcana id. */
    public forge.game.card.CardView viewForTest(String cardId) {
        for (Card c : match.getGame().getCardsIn(ZoneType.Battlefield)) if (cardId(c).equals(cardId)) return c.getView();
        return null;
    }

    private boolean askYesNo(String prompt, String yes, String no) throws Exception {
        JSONObject d = decisionBase("YesNoDecision", prompt).put("yesText", yes == null ? "Yes" : yes).put("noText", no == null ? "No" : no);
        return ask(d, Map.of()).optBoolean("choice");
    }

    /** The spell or ability you last chose to cast or activate (for its X). */
    private volatile SpellAbility castingNow;

    /**
     * Owner, Nov 10: "When it asks you to pick for x, I'd like it to limit to how much mana you can produce." For the X of
     * the spell or ability you're casting, the largest X is what your untapped sources and pool can pay after the rest of
     * the cost. Other numbers (and X paid some other way than mana) keep Forge's limit.
     */
    private int capX(String prompt, int min, int max) {
        try {
            SpellAbility sa = castingNow;
            if (sa == null || prompt == null || sa.getHostCard() == null || !prompt.contains(sa.getHostCard().getName())) return max;
            if (sa.getPayCosts() == null || sa.getPayCosts().getCostMana() == null) return max;
            forge.card.mana.ManaCost cost = sa.getPayCosts().getTotalMana();
            int xs = cost.countX();
            if (xs <= 0) return max;
            int available = forge.ai.ComputerUtilMana.getAvailableManaEstimate(sa.getActivatingPlayer() != null ? sa.getActivatingPlayer() : humanController().getPlayer(), true);
            int cap = Math.max(min, (available - cost.getCMC()) / xs);
            return Math.min(max, cap);
        } catch (RuntimeException e) {
            return max;
        }
    }

    private int askNumber(String prompt, int min, int max) throws Exception {
        if (min >= max) return min;
        JSONObject d = decisionBase("ChooseNumberDecision", prompt).put("minValue", min).put("maxValue", max);
        return Math.max(min, Math.min(max, ask(d, Map.of()).optInt("number", min)));
    }

    /** Forge's synchronous questions (called on Forge's game thread). Returns NOT_HANDLED when the name isn't a question. */
    private static final Object NOT_HANDLED = new Object();

    private Object question(String name, Object[] a) throws Exception {
        switch (name) {
            case "confirm": {
                List<?> opts = a.length > 3 && a[3] instanceof List<?> l && l.size() > 1 ? l : null;
                return askYesNo((String) a[1], opts == null ? "Yes" : String.valueOf(opts.get(0)), opts == null ? "No" : String.valueOf(opts.get(1)));
            }
            case "showConfirmDialog":
                return askYesNo((String) a[0], a.length >= 4 ? (String) a[2] : "Yes", a.length >= 4 ? (String) a[3] : "No");
            case "getChoices": return choose((String) a[0], (Integer) a[1], (Integer) a[2], (List<?>) a[3]);
            case "one": { List<?> r = choose((String) a[0], 1, 1, (List<?>) a[1]); return r.isEmpty() ? null : r.get(0); }
            case "oneOrNone": { List<?> r = choose((String) a[0], 0, 1, (List<?>) a[1]); return r.isEmpty() ? null : r.get(0); }
            case "many": {
                boolean range = a.length > 4 && a[2] instanceof Integer && a[3] instanceof Integer;
                int min = (Integer) a[2], max = range ? (Integer) a[3] : min;
                return choose((String) a[0], min, max, (List<?>) a[range ? 4 : 3]);
            }
            case "chooseSingleEntityForEffect": {
                List<?> r = choose((String) a[0], Boolean.TRUE.equals(a[3]) ? 0 : 1, 1, (List<?>) a[1]);
                return r.isEmpty() ? null : r.get(0);
            }
            case "chooseEntitiesForEffect": return choose((String) a[0], (Integer) a[2], (Integer) a[3], (List<?>) a[1]);
            case "getInteger": return askNumber((String) a[0], (Integer) a[1], capX((String) a[0], (Integer) a[1], (Integer) a[2]));
            case "showOptionDialog": { // (message, title, icon, options, default) → chosen index (from Goose's PR #19)
                List<?> opts = a.length > 3 && a[3] instanceof List<?> l ? l : List.of();
                if (opts.size() < 2) return a.length > 4 ? a[4] : 0;
                JSONObject d = decisionBase("ChooseOptionDecision", String.valueOf(a[0]));
                JSONArray options = new JSONArray(); for (Object o : opts) options.put(String.valueOf(o));
                d.put("options", options).put("canCancel", false);
                int i = ask(d, Map.of()).optInt("optionIndex", -1);
                return i >= 0 && i < opts.size() ? i : (a.length > 4 ? a[4] : 0);
            }
            case "showInputDialog": { // (message, title, icon, initial, options...) → with options, a real choice
                List<?> opts = a.length > 4 && a[4] instanceof List<?> l ? l : List.of();
                if (opts.isEmpty()) { notice("gui-free-text", "Forge asked for typed text (" + a[0] + "); Arcana can't show that yet, so its suggested text was used."); return a.length > 3 ? a[3] : null; }
                JSONObject d = decisionBase("ChooseOptionDecision", String.valueOf(a[0]));
                JSONArray options = new JSONArray(); for (Object o : opts) options.put(String.valueOf(o));
                d.put("options", options).put("canCancel", false);
                int i = ask(d, Map.of()).optInt("optionIndex", -1);
                return String.valueOf(opts.get(i >= 0 && i < opts.size() ? i : 0));
            }
            case "assignGenericAmount": return assignGenericAmount((forge.game.card.CardView) a[0], (Map<?, ?>) a[1], (Integer) a[2], (Boolean) a[3], (String) a[4]);
            case "assignCombatDamage": return assignCombatDamage((forge.game.card.CardView) a[0], (List<?>) a[1], (Integer) a[2], a[3], (Boolean) a[4]);
            case "order": {
                // (title, top, source, card) | (title, top, remMin, remMax, source, dest, card, sideboard[, remember])
                boolean full = a.length >= 8;
                List<?> source = (List<?>) (full ? a[4] : a[2]);
                int remMin = full ? (Integer) a[2] : 0, remMax = full ? (Integer) a[3] : 0;
                List<Object> result = new ArrayList<>(order((String) a[0], source, full ? (List<?>) a[5] : null, remMin, remMax));
                return a.length >= 9 ? new IGuiGame.OrderResult<>(result, false) : result;
            }
            default: return NOT_HANDLED;
        }
    }

    // ---------------------------------------------------------------- Forge inputs that are decisions

    /** Turns the live Forge input into an Arcana decision. Returns false if the input isn't a decision type. */
    private boolean decisionForInput(Input input, PlayerControllerHuman you, Game game) throws Exception {
        String kind = inputClassName(input);
        if (kind.equals("InputSelectTargets")) {
            SpellAbility sa = (SpellAbility) field(input, "sa");
            forge.game.spellability.TargetRestrictions tgt = (forge.game.spellability.TargetRestrictions) field(input, "tgt");
            Map<String, Object> objects = new LinkedHashMap<>();
            JSONArray legal = new JSONArray();
            for (Player p : game.getPlayers()) if (sa.canTarget(p)) { objects.put(playerId(p), p); legal.put(playerId(p)); }
            for (Card c : game.getCardsInGame()) if (sa.canTarget(c)) { objects.put(cardId(c), c); legal.put(cardId(c)); }
            int min = tgt == null ? 1 : tgt.getMinTargets(sa.getHostCard(), sa), max = tgt == null ? 1 : tgt.getMaxTargets(sa.getHostCard(), sa);
            JSONObject d = decisionBase("ChooseTargetsDecision", "Choose targets for " + sa.getHostCard().getName())
                    .put("targetRequirements", new JSONArray().put(new JSONObject().put("index", 0)
                            .put("description", tgt == null || field(tgt, "uiPrompt") == null ? "target" : String.valueOf(field(tgt, "uiPrompt"))).put("minTargets", min).put("maxTargets", max)))
                    .put("legalTargets", new JSONObject().put("0", legal)).put("canCancel", true);
            d.getJSONObject("context").put("phase", "CASTING").put("sourceId", cardId(sa.getHostCard())).put("sourceName", sa.getHostCard().getName());
            openForInput(d, objects, r -> {
                JSONObject sel = r.optJSONObject("selectedTargets");
                JSONArray ids = sel == null ? null : sel.optJSONArray("0");
                if (ids == null || ids.length() == 0) { cancel(you); return; }
                for (int i = 0; i < ids.length(); i++) {
                    Object o = objects.get(ids.optString(i));
                    if (o instanceof Card c) input.selectCard(c, null, null);
                    else if (o instanceof Player p) input.selectPlayer(p, null);
                }
                input.selectButtonOK(); // same input; ignored by Forge if picking already finished it
            });
            return true;
        }
        if (kind.equals("InputSelectEntitiesFromList") || kind.equals("InputSelectCardsFromList")) {
            Object choices = field(input, "validChoices");
            int min = field(input, "min") instanceof Integer n ? n : 0, max = field(input, "max") instanceof Integer n ? n : 1;
            Map<String, Object> objects = new LinkedHashMap<>();
            JSONArray options = new JSONArray(); JSONObject info = new JSONObject();
            if (choices instanceof Iterable<?> it) for (Object o : it) { String id = idOf(o); if (id != null) { objects.put(id, o); options.put(id); info.put(id, cardInfo(o)); } }
            String prompt = field(input, "message") instanceof String m ? m : "Choose";
            if (prompt.contains("%d")) prompt = prompt.replace("%d", String.valueOf(min == max ? min : max)); // Forge formats it on display
            JSONObject d = decisionBase("SelectCardsDecision", prompt).put("options", options)
                    .put("minSelections", Math.min(min, options.length())).put("maxSelections", Math.min(max, options.length())).put("cardInfo", info);
            boolean cancellable = Boolean.TRUE.equals(field(input, "allowCancel"));
            if (cancellable) d.put("canCancel", true);
            openForInput(d, objects, r -> {
                if (cancellable && r.optString("type").equals("CancelDecisionResponse")) { cancel(you); return; }
                JSONArray ids = r.optJSONArray("selectedCards");
                if (ids != null) for (int i = 0; i < ids.length(); i++) {
                    Object o = objects.get(ids.optString(i));
                    if (o instanceof Card c) input.selectCard(c, null, null);
                    else if (o instanceof Player p) input.selectPlayer(p, null);
                }
                input.selectButtonOK(); // same input; ignored by Forge if picking already finished it
            });
            return true;
        }
        if (kind.equals("InputSelectCardsForConvokeOrImprovise")) {
            Object choices = field(input, "availableCards");
            Integer cap = field(input, "maxSelectable") instanceof Integer n ? n : null;
            Map<String, Object> objects = new LinkedHashMap<>();
            JSONArray options = new JSONArray(); JSONObject info = new JSONObject();
            if (choices instanceof Iterable<?> it) for (Object o : it) if (o instanceof Card c) { String id = cardId(c); objects.put(id, c); options.put(id); info.put(id, cardInfo(c)); }
            String what = field(input, "description") instanceof String w && !w.isBlank() ? w : "Convoke";
            JSONObject d = decisionBase("SelectCardsDecision", what + ": choose what to tap to help pay (none: pay with mana)").put("options", options)
                    .put("minSelections", 0).put("maxSelections", cap == null ? options.length() : Math.min(cap, options.length())).put("cardInfo", info);
            openForInput(d, objects, r -> {
                JSONArray ids = r.optJSONArray("selectedCards");
                if (ids != null) for (int i = 0; i < ids.length(); i++) if (objects.get(ids.optString(i)) instanceof Card c) input.selectCard(c, null, null);
                input.selectButtonOK();
            });
            return true;
        }
        if (kind.equals("InputConfirm")) {
            String prompt = field(input, "message") instanceof String m ? m : "Confirm";
            Object opts = field(input, "options");
            List<?> labels = opts instanceof List<?> l && l.size() > 1 ? l : null;
            String yes = labels == null ? "Yes" : String.valueOf(labels.get(0)), no = labels == null ? "No" : String.valueOf(labels.get(1));
            // CR 903.9a: Forge's prompt is the rule's full text; say it the way a player would (owner, Nov 8)
            java.util.regex.Matcher cz = java.util.regex.Pattern.compile("^(.+?): If a commander is in a graveyard or in exile").matcher(prompt);
            if (cz.find()) { prompt = "Return " + cz.group(1) + " to the command zone?"; yes = "Command zone"; no = "Leave it"; }
            JSONObject d = decisionBase("YesNoDecision", prompt).put("yesText", yes).put("noText", no);
            openForInput(d, Map.of(), r -> { if (r.optBoolean("choice")) ok(you); else cancel(you); });
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- combat (Argentum DeclareAttackers / DeclareBlockers)

    private JSONObject attackAction(Input input, Player me) throws Exception {
        forge.game.combat.Combat combat = (forge.game.combat.Combat) field(input, "combat");
        JSONArray valid = new JSONArray(), generic = new JSONArray();
        JSONObject byAttacker = new JSONObject();
        for (forge.game.GameEntity d : combat.getDefenders()) generic.put(idOf(d) != null ? idOf(d) : cardId((Card) d));
        for (Card c : me.getCreaturesInPlay()) {
            JSONArray targets = new JSONArray();
            for (forge.game.GameEntity d : combat.getDefenders()) if (forge.game.combat.CombatUtil.canAttack(c, d)) targets.put(d instanceof Card dc ? cardId(dc) : idOf(d));
            if (targets.length() > 0) { valid.put(cardId(c)); byAttacker.put(cardId(c), targets); }
        }
        // Creatures that must attack ("attacks each combat if able", goad): Forge rejects any declaration without them
        // (owner, Nov 9 23:22: "Pass to my turn" declared no attack and Forge said "Attack declaration invalid" forever).
        JSONArray mandatory = new JSONArray();
        try {
            forge.game.combat.AttackConstraints constraints = new forge.game.combat.AttackConstraints(combat);
            Map<Card, forge.game.GameEntity> legal = constraints.getLegalAttackers().getLeft();
            for (Map.Entry<Card, forge.game.combat.AttackRequirement> r : constraints.getRequirements().entrySet()) {
                if (r.getValue() != null && r.getValue().hasRequirement() && legal.containsKey(r.getKey()) && byAttacker.has(cardId(r.getKey())))
                    mandatory.put(cardId(r.getKey()));
            }
        } catch (RuntimeException ignored) { } // the declaration board still works; Forge reports a bad declaration
        return action("DeclareAttackers", "Declare attackers", new JSONObject().put("type", "DeclareAttackers").put("playerId", this.me).put("attackers", new JSONObject()))
                .put("validAttackers", valid).put("mandatoryAttackers", mandatory).put("attackTargetsByAttacker", byAttacker).put("validAttackTargets", generic);
    }

    private JSONObject blockAction(Input input, Player me) throws Exception {
        forge.game.combat.Combat combat = (forge.game.combat.Combat) field(input, "combat");
        JSONArray valid = new JSONArray();
        JSONObject maxBlocks = new JSONObject();
        for (Card c : me.getCreaturesInPlay()) if (forge.game.combat.CombatUtil.canBlock(c, combat)) { valid.put(cardId(c)); maxBlocks.put(cardId(c), 1); }
        return action("DeclareBlockers", "Declare blockers", new JSONObject().put("type", "DeclareBlockers").put("playerId", this.me).put("blockers", new JSONObject()))
                .put("validBlockers", valid).put("blockerMaxBlockCounts", maxBlocks).put("mandatoryBlockerAssignments", new JSONObject());
    }

    private void declareCombat(PlayerControllerHuman you, JSONObject action) throws Exception {
        Input input = you.getInputQueue().getActualInput(you);
        forge.game.combat.Combat combat = input == null ? null : (forge.game.combat.Combat) field(input, "combat");
        Game game = match.getGame();
        if (combat == null) { reject("It's not time to declare that."); scheduleEmit(); return; }
        Map<String, Object> entities = new LinkedHashMap<>();
        for (Player p : game.getPlayers()) entities.put(playerId(p), p);
        for (Card c : game.getCardsIn(ZoneType.Battlefield)) entities.put(cardId(c), c);
        // Each declaration replaces the last: Forge keeps a rejected declaration's creatures in its combat, so a block it
        // refused (Szarekh needs more blockers) stayed and every later try, even "no blocks", failed again (soak 213).
        Player mine = you.getPlayer();
        if (action.optString("type").equals("DeclareAttackers")) {
            for (Card c : new ArrayList<>(combat.getAttackers())) if (c.getController() == mine) combat.removeFromCombat(c);
        } else {
            for (Card c : new ArrayList<>(combat.getAllBlockers())) if (c.getController() == mine) combat.undoBlockingAssignment(c);
        }
        if (action.optString("type").equals("DeclareAttackers")) {
            JSONObject attackers = action.optJSONObject("attackers");
            if (attackers != null) for (String a : keys(attackers))
                if (entities.get(a) instanceof Card c && entities.get(attackers.getString(a)) instanceof forge.game.GameEntity d) combat.addAttacker(c, d);
        } else {
            JSONObject blockers = action.optJSONObject("blockers");
            if (blockers != null) for (String b : keys(blockers)) {
                JSONArray list = blockers.getJSONArray(b);
                for (int i = 0; i < list.length(); i++)
                    if (entities.get(b) instanceof Card blocker && entities.get(list.getString(i)) instanceof Card attacker) combat.addBlocker(attacker, blocker);
            }
        }
        ok(you);
        scheduleEmit(); // if Forge rejected the declaration (rules), the input stays and is offered again
    }

    // ---------------------------------------------------------------- Forge GUI stand-ins

    /** Forge's in-game GUI. Prompts and input changes trigger a state update; questions get a logged default. */
    private IGuiGame guiGame() {
        Object[] gameView = new Object[1];
        return (IGuiGame) Proxy.newProxyInstance(IGuiGame.class.getClassLoader(), new Class<?>[]{IGuiGame.class}, (proxy, m, args) -> {
            String name = m.getName();
            switch (name) {
                case "setGameView": gameView[0] = args[0]; return null;
                case "getGameView": return gameView[0];
                case "showPromptMessage": case "updateButtons": case "finishGame": case "afterGameEnd": scheduleEmit(); return null;
                // Forge's messages to the player (e.g. why a block or attack was rejected): show them, don't drop them.
                case "message": case "showErrorDialog":
                    if (args != null && args.length > 0 && args[0] != null) {
                        PlayerControllerHuman y = humanController();
                        String kind = y == null ? "" : inputClassName(y.getInputQueue().getActualInput(y));
                        // while you're declaring attackers or blockers, Forge's message is why your declaration was refused
                        if (kind.equals("InputBlock") || kind.equals("InputAttack")) reject(String.valueOf(args[0]));
                        else if (name.equals("message")) note(String.valueOf(args[0])); // e.g. "Offline AI picked Bear": news, not a problem
                        else error(String.valueOf(args[0]));
                    }
                    scheduleEmit(); return null;
                case "isUiSetToSkipPhase": return skipPhase(args[0], (PhaseType) args[1]);
                case "canPickStackTargets": return false; // a capability query, not a question: Forge then lists stack targets
                case "getAbilityToPlay": { // Forge asks which of the card's abilities: the one the player chose
                    List<?> views = (List<?>) args[1];
                    for (Object v : views) if (v instanceof forge.game.spellability.SpellAbilityView sv && sv.getId() == chosenAbilityId) return v;
                    return views.isEmpty() ? null : views.get(0);
                }
                case "awaitInput":
                    if (PRICING.get()) throw new AskedWhilePricing(); // a question during a cost check: see affordable()
                    ((CountDownLatch) args[0]).await(); return null; // Forge's "wait for the player
                case "isNetGame": case "isGamePaused": case "isSelecting": case "isLibgdxPort": return false;
                case "equals": return proxy == args[0];
                case "hashCode": return System.identityHashCode(proxy);
                case "toString": return "ArcanaForgeGui";
                default: break;
            }
            if (m.getReturnType() == void.class) return null;
            if (PRICING.get()) throw new AskedWhilePricing();
            Object asked = question(name, args == null ? new Object[0] : args);
            if (asked != NOT_HANDLED) return asked;
            notice("gui-" + name, "Forge asked something Arcana can't show yet (" + name + "); Forge's default was used.");
            return defaultAnswer(m, args);
        });
    }

    /** Stop only where Arcana normally stops: main phases and combat declarations. */
    /** Something you could do right now besides mana: an instant, a flash card or an ability you can afford. */
    private boolean canActNow(Player me) {
        try {
            for (ZoneType z : new ZoneType[]{ZoneType.Hand, ZoneType.Battlefield, ZoneType.Command, ZoneType.Graveyard, ZoneType.Exile})
                for (Card c : me.getCardsIn(z))
                    for (SpellAbility sa : c.getAllPossibleAbilities(me, true))
                        if (!sa.isManaAbility() && !sa.isLandAbility() && affordable(sa, me)) return true;
        } catch (RuntimeException ignored) { } // a rules edge case shouldn't stop the game: just don't add the stop
        return false;
    }

    /** Owner, Nov 9: the auto-pass stops set on the phone's phase bar ("mine:MAIN 1", "opp:END", ...); this phone's seat only. */
    public static volatile java.util.function.Supplier<java.util.Set<String>> phaseStops = java.util.Set::of;

    private static String stopGroup(PhaseType phase) {
        return switch (phase) {
            case UPKEEP -> "BEGIN";
            case MAIN1 -> "MAIN 1";
            case COMBAT_BEGIN -> "COMBAT";
            case MAIN2 -> "MAIN 2";
            case END_OF_TURN -> "END";
            default -> null;
        };
    }

    private boolean skipPhase(Object turnPlayer, PhaseType phase) {
        boolean yourTurn = false;
        PlayerControllerHuman you = humanController();
        if (you != null && turnPlayer instanceof forge.game.player.PlayerView pv) yourTurn = pv.getId() == you.getPlayer().getId();
        String group = stopGroup(phase);
        if (group != null && this == primary) {
            try { if (phaseStops.get().contains((yourTurn ? "mine:" : "opp:") + group)) return false; } catch (RuntimeException ignored) { }
        }
        if (!yourTurn) { // AI turns: stop after blocks (and for anything on the stack), and at their end step if you can act
            if (phase == PhaseType.COMBAT_DECLARE_BLOCKERS) return false;
            if (phase == PhaseType.END_OF_TURN && you != null && canActNow(you.getPlayer())) return false;
            return true;
        }
        return phase != PhaseType.MAIN1 && phase != PhaseType.MAIN2
                && phase != PhaseType.COMBAT_DECLARE_ATTACKERS && phase != PhaseType.COMBAT_DECLARE_BLOCKERS;
    }

    /** Milestone 1 placeholder for questions the player should answer (logged in {@link #defaultedPrompts}). */
    private Object defaultAnswer(Method m, Object[] args) {
        String name = m.getName();
        defaultedPrompts.add(name + (args != null && args.length > 0 && args[0] instanceof String s ? ": " + s : ""));
        Class<?> rt = m.getReturnType();
        if (name.equals("getChoices") || name.equals("many")) {
            List<?> choices = firstList(args);
            int min = firstInt(args);
            return choices == null ? List.of() : new ArrayList<>(choices.subList(0, Math.min(Math.max(min, 0), choices.size())));
        }
        if (name.equals("one") || name.equals("chooseSingleEntityForEffect")) {
            List<?> choices = firstList(args);
            return choices == null || choices.isEmpty() ? null : choices.get(0);
        }
        if (name.equals("getInteger")) return firstInt(args);
        // Keep everything where it is rather than lose cards: an empty list here would drop them.
        if (name.equals("manipulateCardList")) { List<Object> keep = new ArrayList<>(); for (Object c : (Iterable<?>) args[1]) keep.add(c); return keep; }
        if (name.equals("insertInList")) { List<Object> l = new ArrayList<>((List<?>) args[2]); l.add(args[1]); return l; }
        if (name.equals("sideboard")) return null; // Forge keeps the main deck unchanged
        if (name.equals("showInputDialog")) return args[3];
        if (name.equals("showOptionDialog")) return args[4];
        if (rt == boolean.class || rt == Boolean.class) return true;
        if (rt == int.class || rt == Integer.class) return 0;
        if (rt.isEnum()) return rt.getEnumConstants()[0];
        if (List.class.isAssignableFrom(rt)) return new ArrayList<>();
        if (Map.class.isAssignableFrom(rt)) return new LinkedHashMap<>();
        return null;
    }

    private static List<?> firstList(Object[] args) {
        if (args != null) for (Object a : args) if (a instanceof List<?> l) return l;
        return null;
    }

    private static int firstInt(Object[] args) {
        if (args != null) for (Object a : args) if (a instanceof Integer i) return i;
        return 0;
    }

    /** Forge's app-level GUI: a single "UI thread" executor, no images, no sound. */
    private static IGuiBase guiBase(String assetsDir) {
        return (IGuiBase) Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class}, (proxy, m, args) -> {
            switch (m.getName()) {
                case "isRunningOnDesktop": return true;
                case "isLibgdxPort": return false;
                case "getCurrentVersion": return "chimera";
                case "getUserAgent": return "ArcanaChimera";
                case "getAssetsDir": return assetsDir;
                case "isGuiThread": return Thread.currentThread() == edtThread;
                case "invokeInEdtNow": ((Runnable) args[0]).run(); return null;
                case "invokeInEdtLater": edt.submit((Runnable) args[0]); return null;
                case "invokeInEdtAndWait": {
                    Runnable r = (Runnable) args[0];
                    if (Thread.currentThread() == edtThread) { r.run(); return null; }
                    CountDownLatch done = new CountDownLatch(1);
                    edt.submit(() -> { try { r.run(); } finally { done.countDown(); } });
                    done.await();
                    return null;
                }
                case "runBackgroundTask": new Thread((Runnable) args[1], "forge-bg").start(); return null;
                case "hostMatch": return new HostedMatch();
                case "getNewGuiGame": return current.guiGame();
                case "hasNetGame": return false;
                case "getScreenScale": return 1f;
                case "getAvatarCount": case "getSleevesCount": return 1;
                case "encodeSymbols": return args[0];
                case "equals": return proxy == args[0];
                case "hashCode": return System.identityHashCode(proxy);
                case "toString": return "ArcanaForgeGuiBase";
                default: break;
            }
            Class<?> rt = m.getReturnType();
            if (rt == void.class) return null;
            if (rt == boolean.class) return false;
            if (rt == int.class) return 0;
            if (rt == float.class) return 0f;
            return null;
        });
    }

    public void shutdown() {
        for (ArcanaForgeHost v : views) v.emitter.shutdownNow(); // the shared Forge UI thread stays for the next game
        emitter.shutdownNow();
    }
}
