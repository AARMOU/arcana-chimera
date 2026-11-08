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
    private final ExecutorService edt = Executors.newSingleThreadExecutor(r -> new Thread(r, "forge-edt"));
    private final ScheduledExecutorService emitter = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "forge-emit"));
    private volatile Thread edtThread;
    private volatile ScheduledFuture<?> pendingEmit;
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

    public ArcanaForgeHost(Consumer<JSONObject> sink) {
        this.sink = sink;
        edt.submit(() -> edtThread = Thread.currentThread());
    }

    // ---------------------------------------------------------------- startup

    /** Loads Forge's card database once. {@code assetsDir} is the folder holding Forge's {@code res/}. */
    private static boolean forgeLoaded;
    /** Set true on Android before {@link #initForge}. */
    public static volatile boolean lazyCards;

    /** One seat: a name, its deck as card names, and an optional commander. */
    public record Seat(String name, List<String> deck, String commander) { }

    private final java.util.IdentityHashMap<RegisteredPlayer, String> seatIds = new java.util.IdentityHashMap<>();
    private List<Seat> pendingSeats;
    private java.util.function.Supplier<String> assetsDir;

    /** Arcana-style setup: seats[0] is you, the rest are Forge AIs. The game starts on "createQuickGameLobby". */
    public void prepare(List<Seat> seats, java.util.function.Supplier<String> forgeAssetsDir) { this.pendingSeats = seats; this.assetsDir = forgeAssetsDir; }

    private static String seatId(int i) { return i == 0 ? HUMAN_ID : i == 1 ? AI_ID : AI_ID + "-" + i; }

    private void startPrepared() {
        try {
            initForge(assetsDir.get(), this);
            boolean commander = pendingSeats.stream().allMatch(x -> x.commander() != null && !x.commander().isEmpty());
            List<RegisteredPlayer> players = new ArrayList<>();
            JSONArray lobby = new JSONArray(), started = new JSONArray();
            for (int i = 0; i < pendingSeats.size(); i++) {
                Seat seat = pendingSeats.get(i);
                Map<String, Integer> counts = new LinkedHashMap<>();
                for (String c : seat.deck()) counts.merge(c, 1, Integer::sum);
                Deck d = deck(seat.name(), counts);
                if (commander) d.getOrCreate(forge.deck.DeckSection.Commander).add(card(seat.commander()), 1);
                RegisteredPlayer rp = commander ? RegisteredPlayer.forCommander(d) : new RegisteredPlayer(d);
                rp.setPlayer(i == 0 ? GamePlayerUtil.getGuiPlayer() : GamePlayerUtil.createAiPlayer(seat.name(), i));
                seatIds.put(rp, seatId(i));
                players.add(rp);
                lobby.put(new JSONObject().put("playerId", seatId(i)).put("name", seat.name()));
                started.put(new JSONObject().put("playerId", seatId(i)).put("isYou", i == 0));
            }
            if (!unknownCards.isEmpty()) error("Forge doesn't have " + unknownCards.size() + " card(s), left out: " + String.join(", ", unknownCards));
            match = GuiBase.getInterface().hostMatch();
            sink.accept(new JSONObject().put("type", "quickGameLobbyState").put("lobbyId", "offline").put("youPlayerId", HUMAN_ID)
                    .put("vsAi", true).put("players", lobby));
            sink.accept(new JSONObject().put("type", "gameStarted").put("players", started));
            match.startMatch(new GameRules(commander ? GameType.Commander : GameType.Constructed), null, players, players.get(0), guiGame());
        } catch (Throwable t) {
            error("Forge could not start the game: " + describe(t));
            t.printStackTrace();
        }
    }

    public static synchronized void initForge(String assetsDir, ArcanaForgeHost owner) {
        if (forgeLoaded) return;
        // Forge keeps prefs/caches under user.home; Android has no usable home folder, so use the app's own storage.
        String home = System.getProperty("user.home");
        if (home == null || home.isEmpty() || !new java.io.File(home).canWrite()) System.setProperty("user.home", assetsDir);
        GuiBase.setInterface(owner.guiBase(assetsDir));
        FModel.initialize(null, prefs -> { // phones: parse card scripts on demand instead of all ~30k at start
            prefs.setPref(forge.localinstance.properties.ForgePreferences.FPref.LOAD_CARD_SCRIPTS_LAZILY, lazyCards);
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
        match.startMatch(new GameRules(GameType.Constructed), null, players, you, guiGame());
    }

    private static PaperCard card(String name) {
        PaperCard pc = FModel.getMagicDb().getCommonCards().getCard(name);
        if (pc == null) throw new IllegalArgumentException("Forge has no card named " + name);
        return pc;
    }

    /** Cards Forge doesn't know, left out of the last game's decks (reported to the player). */
    public final List<String> unknownCards = new ArrayList<>();

    private Deck deck(String name, Map<String, Integer> cards) {
        Deck d = new Deck(name);
        cards.forEach((card, n) -> {
            PaperCard pc = FModel.getMagicDb().getCommonCards().getCard(card);
            if (pc == null && card.contains(" // ")) pc = FModel.getMagicDb().getCommonCards().getCard(card.substring(0, card.indexOf(" // ")));
            if (pc == null) { unknownCards.add(card); return; }
            d.getMain().add(pc, n);
        });
        return d;
    }

    // ---------------------------------------------------------------- messages from the UI

    public void handle(JSONObject message) {
        try { handleUnsafe(message); } catch (Exception e) { error("Forge host problem: " + describe(e)); }
    }

    private void handleUnsafe(JSONObject message) throws Exception {
        String t = message.optString("type");
        if (t.equals("connect")) { sink.accept(new JSONObject().put("type", "connected").put("playerId", HUMAN_ID).put("token", "offline")); return; }
        if (t.equals("ping")) { sink.accept(new JSONObject().put("type", "pong")); return; }
        if (t.equals("createQuickGameLobby") && match == null && pendingSeats != null) { new Thread(this::startPrepared, "forge-start").start(); return; }
        PlayerControllerHuman you = humanController();
        if (you == null) return;
        switch (message.optString("type")) {
            case "submitAction" -> submit(you, message.getJSONObject("action"));
            case "keepHand" -> ok(you);
            case "mulligan" -> { mulligans++; cancel(you); }
            case "chooseBottomCards" -> bottom(you, message.optJSONArray("cardIds"));
            case "concede" -> you.concede();
            case "requestResync" -> scheduleEmit();
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

    private static int intField(Object o, String name) {
        try { java.lang.reflect.Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.getInt(o); }
        catch (ReflectiveOperationException e) { return 1; }
    }

    private void submit(PlayerControllerHuman you, JSONObject action) throws Exception {
        String type = action.optString("type");
        if (type.equals("PassPriority")) { ok(you); return; }
        SpellAbility sa = offered.get(key(type, action.optString("cardId", action.optString("sourceId")), action.optString("abilityId")));
        if (sa == null) { error("You can't do that right now (not enough mana, or it's no longer available)."); scheduleEmit(); return; }
        chosenAbilityId = sa.getId();
        Input input = you.getInputQueue().getActualInput(you);
        if (input == null || !input.selectAbility(sa)) { error("Forge did not accept " + sa); scheduleEmit(); }
    }

    // ---------------------------------------------------------------- state out to the UI

    private void scheduleEmit() {
        ScheduledFuture<?> previous = pendingEmit;
        if (previous != null) previous.cancel(false);
        pendingEmit = emitter.schedule(this::emitSafely, 40, TimeUnit.MILLISECONDS);
    }

    private void emitSafely() {
        try { emit(); } catch (Throwable t) { error("Forge host problem: " + describe(t)); t.printStackTrace(); }
    }

    private void emit() throws Exception {
        Game game = match == null ? null : match.getGame();
        if (game == null) return;
        PlayerControllerHuman you = humanController();
        Input input = you == null ? null : you.getInputQueue().getActualInput(you);
        if (input instanceof InputPayMana) { // Argentum default: auto-pay; if that can't pay, cancel instead of looping
            if (input != lastAutoPaid) { lastAutoPaid = input; ok(you); } else { cancel(you); error("Not enough mana."); }
            return;
        }
        if (input != null && input != lastPrompted) {
            String kind = input.getClass().getSimpleName();
            if (kind.equals("InputConfirm")) { // play or draw: Arcana's offline host doesn't ask; take the play
                lastPrompted = input; defaultedPrompts.add("play or draw: play"); ok(you); return;
            }
            if (kind.equals("InputConfirmMulligan")) {
                lastPrompted = input; mulligans = mulligans < 0 ? 0 : mulligans; inMulligan = true;
                sink.accept(handMessage("mulliganDecision", you.getPlayer()).put("mulliganCount", mulligans)
                        .put("cardsToPutOnBottom", 0).put("isOnThePlay", true));
                return;
            }
            if (kind.equals("InputLondonMulligan")) {
                lastPrompted = input; inMulligan = true;
                sink.accept(handMessage("chooseBottomCards", you.getPlayer()).put("cardsToPutOnBottom", intField(input, "toReturn")));
                return;
            }
        }
        if (inMulligan) { // Argentum's order: mulliganComplete, then the first game state
            inMulligan = false;
            sink.accept(new JSONObject().put("type", "mulliganComplete").put("finalHandSize", you.getPlayer().getCardsIn(ZoneType.Hand).size()));
        }
        boolean priority = input instanceof InputPassPriority;
        if (input != null && !priority && input != lastPrompted) {
            // Milestone 1 stand-in until combat/targets reach Arcana's screens: Forge's default (no attack,
            // no block, its suggested target) so the game never waits on a prompt the UI can't show yet.
            lastPrompted = input;
            defaultedPrompts.add("auto-answered " + input.getClass().getSimpleName());
            autoSelectMinimum(input);
            ok(you);
            return;
        }
        JSONArray legal = new JSONArray();
        offered.clear();
        if (priority) {
            Player me = you.getPlayer();
            legal.put(action("PassPriority", "Pass priority", new JSONObject().put("type", "PassPriority").put("playerId", HUMAN_ID)));
            for (ZoneType z : new ZoneType[]{ZoneType.Hand, ZoneType.Battlefield, ZoneType.Command, ZoneType.Graveyard, ZoneType.Exile}) {
                for (Card c : me.getCardsIn(z)) {
                    for (SpellAbility sa : c.getAllPossibleAbilities(me, true)) {
                        if (sa.isManaAbility()) continue;
                        String type = sa.isLandAbility() ? "PlayLand" : sa.isSpell() ? "CastSpell" : "ActivateAbility";
                        String cardId = cardId(c);
                        String abilityId = type.equals("ActivateAbility") ? "sa" + sa.getId() : "";
                        JSONObject a = new JSONObject().put("type", type).put("playerId", HUMAN_ID);
                        if (type.equals("ActivateAbility")) a.put("sourceId", cardId).put("abilityId", abilityId); else a.put("cardId", cardId);
                        boolean affordable = sa.isLandAbility() || forge.ai.ComputerUtilMana.canPayManaCost(sa, me, 0, false);
                        if (affordable) offered.put(key(type, cardId, abilityId), sa);
                        legal.put(action(type, sa.toString(), a).put("isAffordable", affordable));
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
                .put("interactive", priority)
                .put("interactionEpoch", "forge-" + version)
                .put("forgeInput", input == null ? "none" : input.getClass().getSimpleName());
        sink.accept(fill(update, "top"));
        if (game.isGameOver() && !gameOverSent) {
            gameOverSent = true;
            sink.accept(new JSONObject().put("type", "gameOver").put("winnerId", winnerId(game)).put("reason", "GAME_OVER"));
        }
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

    private JSONObject state(Game game, Player viewer) throws Exception {
        PhaseHandler ph = game.getPhaseHandler();
        JSONObject cards = new JSONObject();
        JSONArray zones = new JSONArray();
        JSONArray players = new JSONArray();
        for (Player p : game.getPlayers()) {
            String pid = playerId(p);
            for (ZoneType z : new ZoneType[]{ZoneType.Library, ZoneType.Hand, ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command}) {
                Collection<Card> in = p.getCardsIn(z);
                boolean visible = z != ZoneType.Library && (z != ZoneType.Hand || p == viewer);
                JSONArray ids = new JSONArray();
                if (visible) for (Card c : in) { ids.put(cardId(c)); cards.put(cardId(c), card(c, z)); }
                zones.put(new JSONObject().put("zoneId", new JSONObject().put("zoneType", zoneName(z)).put("ownerId", pid))
                        .put("cardIds", ids).put("size", in.size()).put("isVisible", visible).put("positions", JSONObject.NULL));
            }
            players.put(fillPlayer(new JSONObject().put("playerId", pid).put("name", p.getName()).put("life", p.getLife())
                    .put("handSize", p.getCardsIn(ZoneType.Hand).size()).put("librarySize", p.getCardsIn(ZoneType.Library).size())
                    .put("graveyardSize", p.getCardsIn(ZoneType.Graveyard).size()).put("exileSize", p.getCardsIn(ZoneType.Exile).size())
                    .put("landsPlayedThisTurn", p.getLandsPlayedThisTurn()).put("hasLost", p.hasLost()).put("poisonCounters", p.getPoisonCounters())
                    .put("maxHandSize", p.getMaxHandSize()).put("commanderDamage", new JSONArray()).put("activeEffects", new JSONArray())
                    .put("manaPool", new JSONObject().put("white", 0).put("blue", 0).put("black", 0).put("red", 0).put("green", 0).put("colorless", 0).put("restrictedMana", new JSONArray()))));
        }
        JSONArray stack = new JSONArray();
        for (Card c : game.getCardsIn(ZoneType.Stack)) { stack.put(cardId(c)); cards.put(cardId(c), card(c, ZoneType.Stack)); }
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
                .put("combat", JSONObject.NULL).put("gameLog", new JSONArray()), "state");
    }

    private static JSONObject fillPlayer(JSONObject p) throws Exception { return fill(p, "player"); }

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
                .put("colors", new JSONArray()).put("oracleText", c.getOracleText())
                .put("power", c.isCreature() ? c.getNetPower() : JSONObject.NULL).put("toughness", c.isCreature() ? c.getNetToughness() : JSONObject.NULL)
                .put("isTapped", c.isTapped()).put("hasSummoningSickness", c.hasSickness())
                .put("controllerId", playerId(c.getController())).put("ownerId", playerId(c.getOwner()))
                .put("zone", new JSONObject().put("zoneType", zoneName(z)).put("ownerId", playerId(c.getOwner())))
                .put("keywords", new JSONArray()).put("counters", new JSONObject()).put("attachments", new JSONArray()).put("targets", new JSONArray())
                .put("isAttacking", false).put("isBlocking", false);
        return fill(card, "card");
    }

    // ---------------------------------------------------------------- naming

    private PlayerControllerHuman humanController() {
        Game game = match == null ? null : match.getGame();
        if (game == null) return null;
        for (Player p : game.getPlayers()) if (p.getController() instanceof PlayerControllerHuman h) return h;
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
                case "isUiSetToSkipPhase": return skipPhase((PhaseType) args[1]);
                case "getAbilityToPlay": { // Forge asks which of the card's abilities: the one the player chose
                    List<?> views = (List<?>) args[1];
                    for (Object v : views) if (v instanceof forge.game.spellability.SpellAbilityView sv && sv.getId() == chosenAbilityId) return v;
                    return views.isEmpty() ? null : views.get(0);
                }
                case "awaitInput": ((CountDownLatch) args[0]).await(); return null; // Forge's "wait for the player
                case "isNetGame": case "isGamePaused": case "isSelecting": case "isLibgdxPort": return false;
                case "equals": return proxy == args[0];
                case "hashCode": return System.identityHashCode(proxy);
                case "toString": return "ArcanaForgeGui";
                default: break;
            }
            if (m.getReturnType() == void.class) return null;
            return defaultAnswer(m, args);
        });
    }

    /** Stop only where Arcana normally stops: main phases and combat declarations. */
    private static boolean skipPhase(PhaseType phase) {
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
    private IGuiBase guiBase(String assetsDir) {
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
                case "getNewGuiGame": return guiGame();
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
        edt.shutdownNow();
        emitter.shutdownNow();
    }
}
