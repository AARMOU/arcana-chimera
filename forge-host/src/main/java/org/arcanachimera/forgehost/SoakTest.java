package org.arcanachimera.forgehost;

import forge.util.MyRandom;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * SOAK TEST — random play through the real Arcana ↔ Forge bridge. See docs/TESTING.md.
 *
 * Plays whole games (2–4 seats, Arcana's bundled precons and AI Commander decks) with a bot that picks random
 * legal actions and gives random valid answers to every question, and fails on anything a player would hit:
 *   STALL       nothing from the bridge for 45 s while the game isn't over (thread stacks are printed)
 *   UNBRIDGED   a question the bridge can't show ("can't show yet")
 *   DEFAULTED   the bridge answered a question for the player (other than the play/draw choice)
 *   HOST_ERROR  "Forge host problem" (an exception inside the bridge)
 *   PROTOCOL    ProtocolGuard findings (malformed messages, a state mid-mulligan, 3 refusals in a row)
 *   NO_GAMEOVER the player lost but Arcana wasn't told
 *   RULES       Forge's rules aren't what the game needs: a Commander game without Forge's Commander variant; a
 *               player at 0 life, 10 poison or 21 commander damage who hasn't lost; your commander put in the
 *               graveyard or exile without the command-zone question (the Nov 8 Ayula bug: the bridge was fine,
 *               the rules were silently off, so only a rules check could see it)
 *   RAW_TEXT    a prompt or choice with Forge's unfilled placeholders (%d, %s, %1$s) or untranslated keys (lblXxx)
 * A game that reaches the turn limit without ending is fine (counted, not failed).
 *
 * Every game has its own seed; a failure prints it, and `seed=<n> games=1` replays that game.
 * Usage: SoakTest <forge-gui dir> <arcana assets dir> [games=8] [seed=1] [turns=12] [players=2-4] [ai=OFF|LITE|MAX]
 */
public final class SoakTest {
    record Deck(String name, String commander, List<String> cards) { }

    /**
     * Rules-level checks: things the bridge can't report as an error because Forge never asked anything. Each one
     * is a player-visible rules mistake (a missing question, a player who should have lost).
     */
    static final class RulesWatch {
        private static final java.util.regex.Pattern RAW = java.util.regex.Pattern.compile("%[ds]|%\\d\\$|\\blbl[A-Z]");
        boolean variantChecked;
        boolean askedCommandZone;          // a command-zone question since your commander last left play
        final Set<String> declinedCommander = new HashSet<>(); // you said "leave it": staying put is right
        String lastPrompt = "";
        int lethalStates, unaskedStates;

        String check(ArcanaForgeHost host, JSONObject m, JSONObject st, List<ArcanaForgeHost.Seat> seats) {
            if (!variantChecked && host.gameForTest() != null) {
                variantChecked = true;
                boolean commanderGame = seats.stream().allMatch(x -> x.commander() != null && !x.commander().isEmpty());
                if (commanderGame && !host.gameForTest().getRules().hasCommander())
                    return "FAIL RULES Commander game started without Forge's Commander variant (no command-zone return, no 21-damage loss)";
            }
            JSONObject d = m.optJSONObject("pendingDecision");
            if (d != null) {
                StringBuilder text = new StringBuilder(d.optString("prompt"));
                for (String k : new String[]{"options", "yesText", "noText"}) if (d.has(k)) text.append(' ').append(d.opt(k));
                java.util.regex.Matcher raw = RAW.matcher(text);
                if (raw.find()) return "FAIL RAW_TEXT \"" + raw.group() + "\" in " + d.optString("type") + ": " + text;
                if (d.optString("prompt").contains("command zone")) askedCommandZone = true;
                lastPrompt = d.optString("prompt");
            }
            JSONObject cards = st.getJSONObject("cards");
            boolean unasked = false;
            for (Object z : st.getJSONArray("zones")) {
                JSONObject zone = (JSONObject) z;
                JSONObject id = zone.getJSONObject("zoneId");
                if (!ArcanaForgeHost.HUMAN_ID.equals(id.optString("ownerId"))) continue;
                String type = id.optString("zoneType");
                for (Object cid : zone.getJSONArray("cardIds")) {
                    JSONObject c = cards.optJSONObject(String.valueOf(cid));
                    if (c == null || !c.optBoolean("isCommander")) continue;
                    if (type.equals("Battlefield") || type.equals("Command")) { askedCommandZone = false; declinedCommander.remove(String.valueOf(cid)); continue; }
                    if (!type.equals("Graveyard") && !type.equals("Exile") || declinedCommander.contains(String.valueOf(cid))) continue;
                    if (askedCommandZone) { declinedCommander.add(String.valueOf(cid)); continue; }
                    // a state can slip out between the move and Forge's question: fail only if it never comes
                    if (d == null && ++unaskedStates >= 3)
                        return "FAIL RULES your commander " + c.optString("name") + " went to the " + type + " without the command-zone question";
                    unasked = true;
                }
            }
            if (!unasked) unaskedStates = 0;
            // lethal but still playing: allow a moment (Forge checks state-based actions when a player would get priority)
            boolean lethal = false; String who = "";
            for (Object o : st.getJSONArray("players")) {
                JSONObject p = (JSONObject) o;
                if (p.optBoolean("hasLost")) continue;
                boolean dead = p.optInt("life", 1) <= 0 || p.optInt("poisonCounters") >= 10;
                JSONArray cd = p.optJSONArray("commanderDamage");
                if (cd != null) for (Object e : cd) if (((JSONObject) e).optInt("amount") >= 21) dead = true;
                if (dead) { lethal = true; who = p.optString("name") + " (life " + p.optInt("life") + ", poison " + p.optInt("poisonCounters") + ", commander damage " + cd + ")"; }
            }
            lethalStates = lethal && m.optBoolean("interactive") ? lethalStates + 1 : 0;
            if (lethalStates >= 3) return "FAIL RULES " + who + " should have lost but is still playing";
            return null;
        }
    }

    public static void main(String[] args) throws Exception {
        String forgeDir = args[0];
        Path assets = Path.of(args[1]);
        Map<String, String> opt = new LinkedHashMap<>(Map.of("games", "8", "seed", "1", "turns", "12", "players", "2-4", "ai", "OFF"));
        for (int i = 2; i < args.length; i++) { String[] kv = args[i].split("=", 2); if (kv.length == 2) opt.put(kv[0], kv[1]); }
        int games = Integer.parseInt(opt.get("games")), turns = Integer.parseInt(opt.get("turns"));
        long seed0 = Long.parseLong(opt.get("seed"));
        String[] pr = opt.get("players").split("-");
        int minP = Integer.parseInt(pr[0]), maxP = Integer.parseInt(pr[pr.length - 1]);

        List<Deck> decks = loadDecks(assets);
        System.out.println("SOAK decks loaded: " + decks.size() + "; games " + games + ", seeds " + seed0 + "–" + (seed0 + games - 1)
                + ", turn limit " + turns + ", players " + minP + "–" + maxP + ", AI " + opt.get("ai"));
        ArcanaForgeHost.lazyCards = false;

        List<String> failures = new ArrayList<>();
        int finished = 0, limited = 0;
        long totalDecisions = 0, totalActions = 0;
        for (int g = 0; g < games; g++) {
            long seed = seed0 + g;
            Random rng = new Random(seed);
            MyRandom.setRandom(new Random(seed)); // Forge's shuffles and AI choices
            int players = minP + (int) (seed % (maxP - minP + 1));
            List<Deck> pick = new ArrayList<>();
            for (int i = 0; i < players; i++) pick.add(decks.get(rng.nextInt(decks.size())));
            Game game = new Game(seed, rng, pick, turns, ArcanaForgeHost.AiStrength.valueOf(opt.get("ai")), forgeDir);
            String outcome;
            try { outcome = game.play(); }
            catch (Throwable t) { outcome = "FAIL HOST_ERROR " + t; t.printStackTrace(); }
            totalDecisions += game.decisions; totalActions += game.actions;
            String decksLine = String.join(" vs ", pick.stream().map(Deck::name).toList());
            System.out.println("SOAK game seed=" + seed + " " + players + "p [" + decksLine + "] -> " + outcome
                    + " (turn " + game.turn + ", " + game.actions + " actions, " + game.decisions + " answers, decisions " + game.decisionTypes + ")");
            if (outcome.startsWith("FAIL")) failures.add("seed=" + seed + " " + outcome);
            else if (outcome.startsWith("GAME OVER")) finished++;
            else limited++;
        }
        System.out.println("SOAK summary: " + games + " games, " + finished + " finished, " + limited + " stopped at the turn limit, "
                + failures.size() + " failed; " + totalActions + " actions, " + totalDecisions + " answers");
        failures.forEach(f -> System.out.println("SOAK FAILED " + f));
        System.out.println("SOAK RESULT " + (failures.isEmpty() ? "PASS" : "FAIL"));
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    /** One game: the bot plays seat 0; Forge AIs play the rest. */
    static final class Game {
        final long seed; final Random rng; final List<Deck> decks; final int turnLimit; final String forgeDir;
        final ArcanaForgeHost.AiStrength ai;
        final LinkedBlockingQueue<JSONObject> inbox = new LinkedBlockingQueue<>();
        final ProtocolGuard guard = new ProtocolGuard();
        final Set<String> answered = new HashSet<>();
        final Map<String, Integer> decisionTypes = new LinkedHashMap<>();
        int turn, actions, decisions, actionsThisStep, unboundedNumbers;
        String stepKey = "";
        boolean youLost;

        Game(long seed, Random rng, List<Deck> decks, int turnLimit, ArcanaForgeHost.AiStrength ai, String forgeDir) {
            this.seed = seed; this.rng = rng; this.decks = decks; this.turnLimit = turnLimit; this.ai = ai; this.forgeDir = forgeDir;
        }

        String play() throws Exception {
            ArcanaForgeHost host = new ArcanaForgeHost(m -> { guard.observe(m); inbox.add(m); });
            host.aiStrength = ai;
            List<ArcanaForgeHost.Seat> seats = new ArrayList<>();
            for (int i = 0; i < decks.size(); i++) seats.add(new ArcanaForgeHost.Seat(i == 0 ? "Bot" : "AI " + i, decks.get(i).cards(), decks.get(i).commander()));
            host.prepare(seats, () -> forgeDir);
            host.handle(new JSONObject().put("type", "createQuickGameLobby"));
            try {
                long deadline = System.currentTimeMillis() + 15 * 60_000;
                while (System.currentTimeMillis() < deadline) {
                    JSONObject m = inbox.poll(45, TimeUnit.SECONDS);
                    if (m == null) return youLost ? "FAIL NO_GAMEOVER you lost at turn " + turn + " but Arcana was never told"
                            : "FAIL STALL no message for 45 s at turn " + turn + " (step " + stepKey + ")\n" + stacks();
                    String type = m.optString("type");
                    switch (type) {
                        case "error" -> {
                            String text = m.optString("message");
                            if (text.contains("can't show yet")) return "FAIL UNBRIDGED " + text;
                            if (text.startsWith("Forge host problem")) return "FAIL HOST_ERROR " + text;
                        }
                        case "gameOver" -> {
                            String extra = verdict(host);
                            return extra != null ? extra : "GAME OVER winner " + m.opt("winnerId") + " (" + m.optString("reason") + ")";
                        }
                        case "mulliganDecision" -> host.handle(new JSONObject().put("type",
                                m.optInt("mulliganCount") < 2 && rng.nextInt(100) < 15 ? "mulligan" : "keepHand"));
                        case "chooseBottomCards" -> {
                            JSONArray hand = m.getJSONArray("hand");
                            host.handle(new JSONObject().put("type", "chooseBottomCards").put("cardIds", sample(hand, m.optInt("cardsToPutOnBottom"))));
                        }
                        case "stateUpdate" -> {
                            JSONObject st = m.getJSONObject("state");
                            turn = st.optInt("turnNumber");
                            String broken = rules.check(host, m, st, seats);
                            if (broken != null) return broken;
                            for (Object o : st.getJSONArray("players")) {
                                JSONObject p = (JSONObject) o;
                                if (p.optString("playerId").equals(ArcanaForgeHost.HUMAN_ID) && p.optBoolean("hasLost")) youLost = true;
                            }
                            if (turn > turnLimit) {
                                String extra = verdict(host);
                                return extra != null ? extra : "TURN LIMIT";
                            }
                            if (m.optBoolean("interactive")) {
                                try { act(host, m, st); }
                                catch (RuntimeException bot) { bot.printStackTrace(); return "FAIL BOT_ERROR (the soak bot, not the bridge) " + bot; }
                            }
                        }
                        default -> { }
                    }
                }
                return "FAIL STALL game took over 15 minutes";
            } finally { host.shutdown(); }
        }

        final RulesWatch rules = new RulesWatch();

        /** Checks that apply at the end of any game. Null if clean. */
        String verdict(ArcanaForgeHost host) {
            List<String> defaulted = new ArrayList<>();
            for (String d : host.defaultedPrompts) if (!d.startsWith("play or draw")) defaulted.add(d);
            if (!defaulted.isEmpty()) return "FAIL DEFAULTED " + defaulted;
            if (!guard.clean()) return "FAIL PROTOCOL " + guard.failures();
            return null;
        }

        void act(ArcanaForgeHost host, JSONObject m, JSONObject st) throws Exception {
            JSONObject d = m.optJSONObject("pendingDecision");
            if (d != null) {
                if (!answered.add(d.optString("id"))) return; // a re-sent copy
                decisions++;
                decisionTypes.merge(d.optString("type"), 1, Integer::sum);
                JSONObject response = answer(d).put("decisionId", d.getString("id"));
                host.handle(new JSONObject().put("type", "submitAction").put("action", new JSONObject().put("type", "SubmitDecision")
                        .put("playerId", ArcanaForgeHost.HUMAN_ID).put("response", response)));
                return;
            }
            JSONArray legal = m.optJSONArray("legalActions");
            if (legal == null || legal.length() == 0) return;
            String key = st.optInt("turnNumber") + "/" + st.optString("currentStep");
            if (!key.equals(stepKey)) { stepKey = key; actionsThisStep = 0; }
            JSONObject chosen = null;
            for (int i = 0; i < legal.length(); i++) {
                JSONObject a = legal.getJSONObject(i);
                if (a.optString("actionType").equals("DeclareAttackers")) chosen = attack(a, st);
                if (a.optString("actionType").equals("DeclareBlockers")) chosen = block(a, st);
            }
            if (chosen == null) {
                List<JSONObject> options = new ArrayList<>();
                JSONObject pass = null;
                for (int i = 0; i < legal.length(); i++) {
                    JSONObject a = legal.getJSONObject(i);
                    if (a.optString("actionType").equals("PassPriority")) { pass = a.getJSONObject("action"); continue; }
                    if (!a.optBoolean("isAffordable", true)) continue;
                    if (a.optBoolean("isManaAbility") && rng.nextInt(100) >= 8) continue; // tap for mana only now and then
                    options.add(a.getJSONObject("action"));
                }
                // bounded per step, so a repeatable ability can't loop forever
                if (!options.isEmpty() && actionsThisStep < 6 && rng.nextInt(100) < 70) chosen = options.get(rng.nextInt(options.size()));
                else chosen = pass;
            }
            if (chosen == null) return;
            actionsThisStep++;
            actions++;
            host.handle(new JSONObject().put("type", "submitAction").put("action", chosen));
        }

        JSONObject attack(JSONObject a, JSONObject st) {
            JSONObject act = new JSONObject(a.getJSONObject("action").toString());
            JSONObject attackers = new JSONObject();
            JSONArray valid = a.optJSONArray("validAttackers");
            JSONObject byAttacker = a.optJSONObject("attackTargetsByAttacker");
            JSONArray anyTarget = a.optJSONArray("validAttackTargets");
            if (valid != null) for (int i = 0; i < valid.length(); i++) {
                if (rng.nextInt(100) >= 50) continue;
                String id = valid.getString(i);
                JSONArray targets = byAttacker != null && byAttacker.optJSONArray(id) != null ? byAttacker.getJSONArray(id) : anyTarget;
                if (targets != null && targets.length() > 0) attackers.put(id, targets.getString(rng.nextInt(targets.length())));
            }
            return act.put("attackers", attackers);
        }

        JSONObject block(JSONObject a, JSONObject st) {
            JSONObject act = new JSONObject(a.getJSONObject("action").toString());
            List<String> attacking = new ArrayList<>();
            JSONObject cards = st.getJSONObject("cards");
            for (String id : cards.keySet()) if (cards.getJSONObject(id).optBoolean("isAttacking")) attacking.add(id);
            JSONObject blockers = new JSONObject();
            JSONArray valid = a.optJSONArray("validBlockers");
            if (valid != null && !attacking.isEmpty()) for (int i = 0; i < valid.length(); i++)
                if (rng.nextInt(100) < 40) blockers.put(valid.getString(i), new JSONArray().put(attacking.get(rng.nextInt(attacking.size()))));
            return act.put("blockers", blockers);
        }

        /** A random but valid answer for every decision type the bridge sends. */
        JSONObject answer(JSONObject d) {
            String type = d.optString("type");
            switch (type) {
                case "YesNoDecision": return new JSONObject().put("type", "YesNoResponse").put("choice", rng.nextBoolean());
                case "ChooseOptionDecision": {
                    int n = d.getJSONArray("options").length();
                    return new JSONObject().put("type", "OptionChosenResponse").put("optionIndex", rng.nextInt(Math.max(1, n)));
                }
                case "ChooseNumberDecision": {
                    int min = d.optInt("minValue"), max = Math.max(min, d.optInt("maxValue", min));
                    long span = Math.min((long) max - min, 20); // Forge's "choose X" can be unbounded (Integer.MAX_VALUE)
                    if (max == Integer.MAX_VALUE) unboundedNumbers++;
                    return new JSONObject().put("type", "NumberChosenResponse").put("number", min + rng.nextInt((int) span + 1));
                }
                case "SelectCardsDecision": {
                    JSONArray opts = d.optJSONArray("options");
                    int n = opts == null ? 0 : opts.length();
                    int min = Math.min(d.optInt("minSelections"), n), max = Math.min(Math.max(min, d.optInt("maxSelections", min)), n);
                    return new JSONObject().put("type", "CardsSelectedResponse").put("selectedCards", sample(opts, min + rng.nextInt(max - min + 1)));
                }
                case "ChooseTargetsDecision": {
                    JSONObject sel = new JSONObject();
                    JSONArray reqs = d.optJSONArray("targetRequirements");
                    JSONObject legal = d.optJSONObject("legalTargets");
                    if (reqs != null) for (int i = 0; i < reqs.length(); i++) {
                        JSONObject r = reqs.getJSONObject(i);
                        String idx = String.valueOf(r.optInt("index"));
                        JSONArray opts = legal == null ? new JSONArray() : legal.optJSONArray(idx);
                        int n = opts == null ? 0 : opts.length();
                        int min = Math.min(r.optInt("minTargets", 1), n), max = Math.min(Math.max(min, r.optInt("maxTargets", 1)), n);
                        sel.put(idx, sample(opts, min + rng.nextInt(max - min + 1)));
                    }
                    return new JSONObject().put("type", "TargetsResponse").put("selectedTargets", sel);
                }
                case "OrderObjectsDecision": {
                    List<Object> objs = new ArrayList<>(d.getJSONArray("objects").toList());
                    Collections.shuffle(objs, rng);
                    return new JSONObject().put("type", "OrderedResponse").put("orderedObjects", new JSONArray(objs));
                }
                case "AssignDamageDecision": // Forge's own default split (lethal in order, rest to the player if trample)
                    return new JSONObject().put("type", "DamageAssignmentResponse").put("assignments", d.getJSONObject("defaultAssignments"));
                case "DistributeDecision": {
                    JSONArray targets = d.getJSONArray("targets");
                    int total = d.optInt("totalAmount"), minEach = d.optInt("minPerTarget");
                    JSONObject dist = new JSONObject();
                    for (int i = 0; i < targets.length(); i++) dist.put(targets.getString(i), minEach);
                    int left = total - minEach * targets.length();
                    while (left-- > 0) { String t = targets.getString(rng.nextInt(targets.length())); dist.put(t, dist.getInt(t) + 1); }
                    return new JSONObject().put("type", "DistributionResponse").put("distribution", dist);
                }
                default:
                    throw new IllegalStateException("soak bot has no answer for " + type + " — add one here");
            }
        }

        JSONArray sample(JSONArray from, int n) {
            List<Object> all = from == null ? new ArrayList<>() : new ArrayList<>(from.toList());
            Collections.shuffle(all, rng);
            return new JSONArray(all.subList(0, Math.max(0, Math.min(n, all.size()))));
        }
    }

    /** Stacks of the threads that matter, for a stall report. */
    static String stacks() {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            String name = e.getKey().getName();
            if (name.startsWith("Reference") || name.startsWith("Finalizer") || name.startsWith("Signal") || name.startsWith("Common-Cleaner")) continue;
            b.append("  thread ").append(name).append(" (").append(e.getKey().getState()).append(")\n");
            StackTraceElement[] st = e.getValue();
            for (int i = 0; i < Math.min(12, st.length); i++) b.append("      at ").append(st[i]).append('\n');
        }
        return b.toString();
    }

    /** Arcana's bundled decks: precons/*.txt ([COMMANDER] section) and argentum/commander-decks.json. */
    static List<Deck> loadDecks(Path assets) throws Exception {
        List<Deck> out = new ArrayList<>();
        try (var files = Files.list(assets.resolve("precons"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".txt")).sorted().toList()) {
                String commander = null, section = "";
                List<String> cards = new ArrayList<>();
                for (String line : Files.readAllLines(f)) {
                    line = line.trim();
                    if (line.startsWith("[")) { section = line; continue; }
                    var m = java.util.regex.Pattern.compile("(\\d+)x?\\s+(.+)").matcher(line);
                    if (!m.matches()) continue;
                    if (section.equals("[COMMANDER]") && commander == null) { commander = m.group(2); continue; }
                    for (int i = 0; i < Integer.parseInt(m.group(1)); i++) cards.add(m.group(2));
                }
                if (commander != null) out.add(new Deck(f.getFileName().toString().replace(".txt", ""), commander, cards));
            }
        }
        JSONArray ai = new JSONArray(Files.readString(assets.resolve("argentum/commander-decks.json")));
        for (int i = 0; i < ai.length(); i++) {
            JSONObject d = ai.getJSONObject(i);
            List<String> cards = new ArrayList<>();
            Object list = d.get("cards"); // {name: count} (or a plain list)
            if (list instanceof JSONObject counts) { for (String n : counts.keySet()) for (int k = 0; k < counts.optInt(n, 1); k++) cards.add(n); }
            else for (Object c : (JSONArray) list) cards.add(String.valueOf(c));
            out.add(new Deck(d.optString("name", d.optString("id")), d.getString("commander"), cards));
        }
        return out;
    }
}
