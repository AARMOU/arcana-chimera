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

    public static synchronized void initForge(String assetsDir, ArcanaForgeHost owner) {
        if (forgeLoaded) return;
        GuiBase.setInterface(owner.guiBase(assetsDir));
        FModel.initialize(null, null);
        forgeLoaded = true;
    }

    /** Starts a one-on-one game: you against Forge's AI. Decks are card name → count. */
    public void startGame(Map<String, Integer> yourDeck, Map<String, Integer> aiDeck) {
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

    private static Deck deck(String name, Map<String, Integer> cards) {
        Deck d = new Deck(name);
        cards.forEach((card, n) -> {
            PaperCard pc = FModel.getMagicDb().getCommonCards().getCard(card);
            if (pc == null) throw new IllegalArgumentException("Forge has no card named " + card);
            d.getMain().add(pc, n);
        });
        return d;
    }

    // ---------------------------------------------------------------- messages from the UI

    public void handle(JSONObject message) {
        PlayerControllerHuman you = humanController();
        if (you == null) return;
        switch (message.optString("type")) {
            case "submitAction" -> submit(you, message.getJSONObject("action"));
            case "keepHand" -> you.selectButtonOk();
            case "mulligan" -> you.selectButtonCancel();
            case "concede" -> you.concede();
            case "requestResync" -> scheduleEmit();
            default -> { }
        }
    }

    private void submit(PlayerControllerHuman you, JSONObject action) {
        String type = action.optString("type");
        if (type.equals("PassPriority")) { you.selectButtonOk(); return; }
        SpellAbility sa = offered.get(key(type, action.optString("cardId", action.optString("sourceId")), action.optString("abilityId")));
        if (sa == null) { error("That action is no longer available."); scheduleEmit(); return; }
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
        try { emit(); } catch (Throwable t) { error("Forge host problem: " + t); t.printStackTrace(); }
    }

    private void emit() {
        Game game = match == null ? null : match.getGame();
        if (game == null) return;
        PlayerControllerHuman you = humanController();
        Input input = you == null ? null : you.getInputQueue().getActualInput(you);
        if (input instanceof InputPayMana) { // Argentum default: auto-pay; if that can't pay, cancel instead of looping
            if (input != lastAutoPaid) { lastAutoPaid = input; you.selectButtonOk(); } else { you.selectButtonCancel(); error("Not enough mana."); }
            return;
        }
        boolean priority = input instanceof InputPassPriority;
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
                        offered.put(key(type, cardId, abilityId), sa);
                        legal.put(action(type, sa.toString(), a));
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
        sink.accept(update);
        if (game.isGameOver() && !gameOverSent) {
            gameOverSent = true;
            sink.accept(new JSONObject().put("type", "gameOver").put("winnerId", winnerId(game)).put("reason", "GAME_OVER"));
        }
    }

    private Object winnerId(Game game) {
        for (Player p : game.getPlayers()) if (!p.hasLost() && game.isGameOver()) return playerId(p);
        return JSONObject.NULL;
    }

    private static JSONObject action(String actionType, String description, JSONObject action) {
        return new JSONObject().put("actionType", actionType).put("description", description).put("action", action).put("isAffordable", true);
    }

    private JSONObject state(Game game, Player viewer) {
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
            players.put(new JSONObject().put("playerId", pid).put("name", p.getName()).put("life", p.getLife())
                    .put("handSize", p.getCardsIn(ZoneType.Hand).size()).put("librarySize", p.getCardsIn(ZoneType.Library).size())
                    .put("graveyardSize", p.getCardsIn(ZoneType.Graveyard).size()).put("exileSize", p.getCardsIn(ZoneType.Exile).size())
                    .put("landsPlayedThisTurn", p.getLandsPlayedThisTurn()).put("hasLost", p.hasLost()).put("poisonCounters", p.getPoisonCounters())
                    .put("maxHandSize", p.getMaxHandSize()).put("commanderDamage", new JSONArray()).put("activeEffects", new JSONArray())
                    .put("manaPool", new JSONObject().put("white", 0).put("blue", 0).put("black", 0).put("red", 0).put("green", 0).put("colorless", 0).put("restrictedMana", new JSONArray())));
        }
        JSONArray stack = new JSONArray();
        for (Card c : game.getCardsIn(ZoneType.Stack)) { stack.put(cardId(c)); cards.put(cardId(c), card(c, ZoneType.Stack)); }
        zones.put(new JSONObject().put("zoneId", new JSONObject().put("zoneType", "Stack").put("ownerId", JSONObject.NULL))
                .put("cardIds", stack).put("size", stack.length()).put("isVisible", true).put("positions", JSONObject.NULL));
        PhaseType phase = ph.getPhase();
        return new JSONObject()
                .put("viewingPlayerId", viewer == null ? JSONObject.NULL : playerId(viewer))
                .put("cards", cards).put("zones", zones).put("players", players)
                .put("currentPhase", phaseName(phase)).put("currentStep", stepName(phase))
                .put("activePlayerId", ph.getPlayerTurn() == null ? JSONObject.NULL : playerId(ph.getPlayerTurn()))
                .put("priorityPlayerId", ph.getPriorityPlayer() == null ? JSONObject.NULL : playerId(ph.getPriorityPlayer()))
                .put("turnNumber", ph.getTurn()).put("isGameOver", game.isGameOver()).put("winnerId", game.isGameOver() ? winnerId(game) : JSONObject.NULL)
                .put("combat", JSONObject.NULL).put("gameLog", new JSONArray());
    }

    private JSONObject card(Card c, ZoneType z) {
        JSONArray types = new JSONArray();
        if (c.isLand()) types.put("LAND");
        if (c.isCreature()) types.put("CREATURE");
        if (c.isArtifact()) types.put("ARTIFACT");
        if (c.isEnchantment()) types.put("ENCHANTMENT");
        if (c.isPlaneswalker()) types.put("PLANESWALKER");
        if (c.isInstant()) types.put("INSTANT");
        if (c.isSorcery()) types.put("SORCERY");
        return new JSONObject().put("id", cardId(c)).put("name", c.getName())
                .put("manaCost", c.getManaCost() == null ? "" : c.getManaCost().getShortString()).put("manaValue", c.getCMC())
                .put("typeLine", c.getType().toString()).put("cardTypes", types).put("subtypes", new JSONArray(new ArrayList<>(c.getType().getSubtypes())))
                .put("colors", new JSONArray()).put("oracleText", c.getOracleText())
                .put("power", c.isCreature() ? c.getNetPower() : JSONObject.NULL).put("toughness", c.isCreature() ? c.getNetToughness() : JSONObject.NULL)
                .put("isTapped", c.isTapped()).put("hasSummoningSickness", c.hasSickness())
                .put("controllerId", playerId(c.getController())).put("ownerId", playerId(c.getOwner()))
                .put("zone", new JSONObject().put("zoneType", zoneName(z)).put("ownerId", playerId(c.getOwner())))
                .put("keywords", new JSONArray()).put("counters", new JSONObject()).put("attachments", new JSONArray()).put("targets", new JSONArray())
                .put("isAttacking", false).put("isBlocking", false);
    }

    // ---------------------------------------------------------------- naming

    private PlayerControllerHuman humanController() {
        Game game = match == null ? null : match.getGame();
        if (game == null) return null;
        for (Player p : game.getPlayers()) if (p.getController() instanceof PlayerControllerHuman h) return h;
        return null;
    }

    private String playerId(Player p) {
        return p != null && p.getController() instanceof PlayerControllerHuman ? HUMAN_ID : AI_ID;
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

    private void error(String text) {
        sink.accept(new JSONObject().put("type", "error").put("code", "FORGE_HOST").put("message", text));
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
                case "isNetGame": case "isGamePaused": case "isSelecting": return false;
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
