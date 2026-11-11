package org.arcanachimera.forgehost;

import forge.game.card.CardView;
import forge.gui.interfaces.IGuiGame;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Deterministic prompt regressions (Goose, PR #16, Nov 8). Each scenario plays through ArcanaForgeHost using only
 * Argentum-shaped messages, after Forge's own dev-mode GameState setup places an exact board. Both decks are all
 * Forest, so the AI has nothing to do. Exit 0 = every scenario passed.
 *
 *  A. Ghalta: cast Ghalta, put two creatures with enter triggers from hand onto the battlefield (the phone's
 *     Nov 8 00:19 stall path: Forge's `order` prompt for the simultaneous triggers), then require both creatures on the battlefield and a following playable state.
 *     Also calls the 8- and 9-argument `order` forms directly and checks the returned order and wrapper type.
 *  B. Combat damage: Prized Unicorn (all able creatures must block it) attacks into two Grizzly Bears. The player
 *     puts all 2 damage on the SECOND blocker; the second must die and the first must survive.
 *  N. Burgeoning (owner, Nov 11): "Use triggered ability?" answered YES · ALWAYS once; the AI's next land must
 *     trigger it again without asking, and both triggers must put a land from your hand onto the battlefield.
 *  Every scenario fails on a timeout, a "can't show yet" notice, or a missing follow-up state.
 */
public final class PromptScenarios {
    private static final String LIBRARY = String.join(";", java.util.Collections.nCopies(20, "Forest"));

    private static String forgeDir;

    public static void main(String[] args) throws Exception {
        forgeDir = args.length > 0 ? args[0] : "../forge-gui/";
        List<String> failures = new ArrayList<>();
        for (String name : List.of("A-ghalta-order", "B-combat-damage", "C-mulligan", "D-trample-short", "E-stack", "F-mana-convoke", "G-you-lose", "H-end-step", "I-may-pay", "J-ayula-fight-0", "J-ayula-fight-1", "J-ayula-fight-2", "K-commander-dies", "L-illegal-block", "M-forced-block", "N-always-yes")) {
            String r;
            try { r = name.startsWith("A") ? ghalta() : name.startsWith("B") ? combatDamage() : name.startsWith("C") ? mulligan() : name.startsWith("D") ? trampleShort() : name.startsWith("E") ? stack() : name.startsWith("F") ? manaAndConvoke() : name.startsWith("G") ? youLose() : name.startsWith("H") ? endStep() : name.startsWith("I") ? mayPay() : name.startsWith("J") ? ayulaFight(name.charAt(name.length() - 1) - '0') : name.startsWith("K") ? commanderDies()
                    : name.startsWith("L") ? illegalBlock() : name.startsWith("N") ? alwaysYes() : forcedBlock(); }
            catch (Throwable t) { t.printStackTrace(); r = "FAIL: " + t; }
            System.out.println("SCENARIO " + name + " " + r);
            if (!r.startsWith("PASS")) failures.add(name);
        }
        System.out.println("SCENARIOS RESULT " + (failures.isEmpty() ? "PASS" : "FAIL " + failures));
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    /** One game against an all-Forest AI, advanced to your first main phase. */
    private static final class Session {
        final LinkedBlockingQueue<JSONObject> inbox = new LinkedBlockingQueue<>();
        final ProtocolGuard protocol = new ProtocolGuard(); // Goose's contract guard (PR #17): checked on close
        final ArcanaForgeHost host = new ArcanaForgeHost(m -> { protocol.observe(m); inbox.add(m); });
        JSONObject last;
        final List<String> notices = new ArrayList<>();
        final List<String> errorCodes = new ArrayList<>(); // "CODE: message", for refused moves

        final List<Integer> mulliganOffers = new ArrayList<>();
        int bottomAsked = -1;
        boolean awaitingReoffer;
        String lastOfferHand;
        final List<String> leakedDuringMulligan = new ArrayList<>();

        Session(String board, String marker) throws Exception { this(board, marker, 0); }

        Session(String board, String marker, int mulligansWanted) throws Exception { this(board, marker, mulligansWanted, false); }

        Session(String board, String marker, int mulligansWanted, boolean commanderRules) throws Exception {
            ArcanaForgeHost.initForge(forgeDir, host);
            host.setCommanderRulesForTest(commanderRules);
            host.setStartBoardForTest(board);
            Map<String, Integer> forests = new LinkedHashMap<>(); forests.put("Forest", 60);
            new Thread(() -> { try { host.startGame(forests, forests); } catch (Exception e) { e.printStackTrace(); } }, "scenario-start").start();
            while (true) {
                JSONObject m = next(60);
                if (m.optString("type").equals("mulliganDecision")) {
                    int count = m.optInt("mulliganCount");
                    mulliganOffers.add(count);
                    lastOfferHand = m.getJSONArray("hand").toList().toString();
                    awaitingReoffer = count < mulligansWanted;
                    host.handle(new JSONObject().put("type", count < mulligansWanted ? "mulligan" : "keepHand")); continue;
                }
                // Arcana hides the opening hand once a state arrives: nothing may come between a mulligan and the next offer
                if (awaitingReoffer && !m.optString("type").equals("mulliganDecision")) leakedDuringMulligan.add(m.optString("type"));
                if (m.optString("type").equals("chooseBottomCards")) {
                    bottomAsked = m.optInt("cardsToPutOnBottom");
                    JSONArray hand = m.getJSONArray("hand"), pick = new JSONArray();
                    // Arcana draws the bottom-card screen from the last offered hand: it must be the same cards
                    if (!hand.toList().toString().equals(lastOfferHand)) leakedDuringMulligan.add("chooseBottomCards for a hand that wasn't shown");
                    for (int i = 0; i < bottomAsked && i < hand.length(); i++) pick.put(hand.get(i));
                    host.handle(new JSONObject().put("type", "chooseBottomCards").put("cardIds", pick)); continue;
                }
                if (interactive(m) && hasCard(m, marker)) { pushBack = m; return; } // the scenario board is in place
                if (interactive(m) && m.optJSONObject("pendingDecision") == null) send(new JSONObject().put("type", "PassPriority").put("playerId", ArcanaForgeHost.HUMAN_ID));
            }
        }

        JSONObject pushBack;

        JSONObject next(int seconds) throws Exception {
            if (pushBack != null) { JSONObject m = pushBack; pushBack = null; return m; }
            while (true) {
                JSONObject m = inbox.poll(seconds, TimeUnit.SECONDS);
                if (m == null) throw new IllegalStateException("timed out after " + seconds + " s waiting for Forge (last state v" + (last == null ? "-" : last.optInt("stateVersion")) + ")");
                if (m.optString("type").equals("error")) {
                    notices.add(m.optString("message"));
                    errorCodes.add(m.optString("code") + ": " + m.optString("message"));
                    System.out.println("  notice: " + m.optString("message"));
                    if (m.optString("message").contains("can't show yet")) throw new IllegalStateException("unbridged prompt: " + m.optString("message"));
                    continue;
                }
                if (m.optString("type").equals("stateUpdate")) last = m;
                return m;
            }
        }

        void send(JSONObject action) throws Exception { host.handle(new JSONObject().put("type", "submitAction").put("action", action)); }

        /** Decisions already answered: the host re-sends an open decision, so a queued copy can arrive after the answer. */
        final java.util.Set<String> answered = new java.util.HashSet<>();

        boolean fresh(JSONObject d) { return d != null && !answered.contains(d.optString("id")); }

        void answer(JSONObject decision, JSONObject response) throws Exception {
            answered.add(decision.getString("id"));
            System.out.println("  decision " + decision.optString("type") + ": " + decision.optString("prompt") + " -> " + response);
            send(new JSONObject().put("type", "SubmitDecision").put("playerId", ArcanaForgeHost.HUMAN_ID)
                    .put("response", response.put("decisionId", decision.getString("id"))));
        }

        void close() {
            host.shutdown();
            if (!protocol.clean()) throw new IllegalStateException("ProtocolGuard: " + String.join("; ", protocol.failures()));
        }
    }

    private static boolean interactive(JSONObject m) { return m.optString("type").equals("stateUpdate") && m.optBoolean("interactive"); }

    private static List<String> battlefield(JSONObject state, String owner, String name) {
        List<String> ids = new ArrayList<>();
        JSONObject cards = state.getJSONObject("cards");
        for (String k : cards.keySet()) {
            JSONObject c = cards.getJSONObject(k);
            if (c.getJSONObject("zone").optString("zoneType").equals("Battlefield") && c.optString("controllerId").equals(owner)
                    && c.optString("name").equals(name)) ids.add(c.getString("id"));
        }
        return ids;
    }

    /** True if any visible card in the state has this name (hand, stack, battlefield, graveyard...). */
    private static boolean hasCard(JSONObject m, String name) {
        JSONObject cards = m.getJSONObject("state").getJSONObject("cards");
        for (String k : cards.keySet()) if (cards.getJSONObject(k).optString("name").equals(name)) return true;
        return false;
    }

    private static JSONObject castAction(JSONObject m, String name) {
        JSONArray legal = m.getJSONArray("legalActions");
        JSONObject cards = m.getJSONObject("state").getJSONObject("cards");
        for (int i = 0; i < legal.length(); i++) {
            JSONObject a = legal.getJSONObject(i);
            JSONObject c = cards.optJSONObject(a.getJSONObject("action").optString("cardId"));
            if (a.optString("actionType").equals("CastSpell") && a.optBoolean("isAffordable") && c != null && c.optString("name").equals(name)) return a.getJSONObject("action");
        }
        return null;
    }

    // ------------------------------------------------------------------ A. Ghalta / order

    private static String ghalta() throws Exception {
        Session s = new Session(String.join("\n",
                "activeplayer=human", "activephase=MAIN1", "removesummoningsickness=true",
                // Forge sets an unlisted life to -1 (puzzle rule), and clears unlisted zones: list both.
                "humanlife=20", "ailife=20", "humanlibrary=" + LIBRARY, "ailibrary=" + LIBRARY,
                "humanbattlefield=Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest",
                // two creatures with "enters" triggers: entering together, Forge asks the player to order the triggers
                "humanhand=Ghalta, Stampede Tyrant;Elvish Visionary;Wall of Omens",
                "aibattlefield=", "aihand="), "Ghalta, Stampede Tyrant");
        try {
            boolean cast = false, ordered = false, selected = false;
            List<String> orderAnswer = null;
            int afterEntry = 0;
            while (true) {
                JSONObject m = s.next(90);
                if (!interactive(m) || !hasCard(m, "Ghalta, Stampede Tyrant")) continue; // wait for the new board
                JSONObject d = m.optJSONObject("pendingDecision");
                if (d != null && !s.fresh(d)) continue; // a re-sent copy of a decision already answered
                if (d != null) {
                    switch (d.optString("type")) {
                        case "SelectCardsDecision" -> { // which creatures to put onto the battlefield: all of them
                            selected = true;
                            s.answer(d, new JSONObject().put("type", "CardsSelectedResponse").put("selectedCards", d.getJSONArray("options")));
                        }
                        case "OrderObjectsDecision" -> { // put-onto-battlefield order: reversed, so the answer is not Forge's default
                            ordered = true;
                            JSONArray objs = d.getJSONArray("objects"), rev = new JSONArray();
                            orderAnswer = new ArrayList<>();
                            for (int i = objs.length() - 1; i >= 0; i--) { rev.put(objs.get(i)); orderAnswer.add(objs.getString(i)); }
                            s.answer(d, new JSONObject().put("type", "OrderedResponse").put("orderedObjects", rev));
                        }
                        case "YesNoDecision" -> s.answer(d, new JSONObject().put("type", "YesNoResponse").put("choice", true));
                        default -> { return "FAIL: unexpected decision " + d.optString("type") + " (" + d.optString("prompt") + ")"; }
                    }
                    continue;
                }
                JSONObject state = m.getJSONObject("state");
                List<String> bears = battlefield(state, ArcanaForgeHost.HUMAN_ID, "Elvish Visionary"), elves = battlefield(state, ArcanaForgeHost.HUMAN_ID, "Wall of Omens");
                if (!cast) {
                    JSONObject a = castAction(m, "Ghalta, Stampede Tyrant");
                    if (a == null) return "FAIL: Ghalta was not castable with 8 Forests";
                    cast = true; s.send(a); continue;
                }
                if (!bears.isEmpty() && !elves.isEmpty()) {
                    // their enter triggers come next: allow a few states for Forge's order prompt before failing
                    if (!ordered) { if (++afterEntry < 4) continue; return "FAIL: creatures entered but Forge's order prompt was never shown (selected=" + selected + ")"; }
                    String shapes = orderShapes(s, bears.get(0), elves.get(0));
                    if (!shapes.startsWith("ok")) return "FAIL: " + shapes;
                    return "PASS: cast Ghalta, chose both creatures, answered the order prompt " + orderAnswer
                            + "; Elvish Visionary + Wall of Omens on the battlefield, play continued at state v" + m.optInt("stateVersion") + "; " + shapes;
                }
                s.send(new JSONObject().put("type", "PassPriority").put("playerId", ArcanaForgeHost.HUMAN_ID)); // let the stack resolve
                if (state.optInt("turnNumber") > 2) return "FAIL: turn passed without the creatures entering (selected=" + selected + ", ordered=" + ordered + ")";
            }
        } finally { s.close(); }
    }

    /** Calls Forge's 8- and 9-argument `order` forms directly; the answer reverses the list. */
    private static String orderShapes(Session s, String firstId, String secondId) throws Exception {
        IGuiGame gui = s.host.guiForTest();
        CardView a = s.host.viewForTest(firstId), b = s.host.viewForTest(secondId);
        if (a == null || b == null) return "order shape check: views missing";
        StringBuilder out = new StringBuilder("ok");
        for (int argc : new int[]{8, 9}) {
            AtomicReference<Object> result = new AtomicReference<>();
            Thread t = new Thread(() -> {
                try {
                    result.set(argc == 9
                            ? gui.order("Order test", "First", 0, 0, new ArrayList<>(List.of(a, b)), null, null, false, false)
                            : gui.order("Order test", "First", 0, 0, new ArrayList<>(List.of(a, b)), null, null, false));
                } catch (Throwable e) { result.set(e); }
            }, "order-shape-" + argc);
            t.start();
            while (true) {
                JSONObject m = s.next(30);
                JSONObject d = m.optJSONObject("pendingDecision");
                if (s.fresh(d) && d.optString("type").equals("OrderObjectsDecision")) {
                    JSONArray objs = d.getJSONArray("objects");
                    s.answer(d, new JSONObject().put("type", "OrderedResponse").put("orderedObjects", new JSONArray().put(objs.get(1)).put(objs.get(0))));
                    break;
                }
            }
            t.join(30_000);
            Object r = result.get();
            List<?> list = r instanceof IGuiGame.OrderResult<?> or ? or.ordered() : r instanceof List<?> l ? l : null;
            boolean rightType = argc == 9 ? r instanceof IGuiGame.OrderResult<?> : r instanceof List<?>;
            if (!rightType || list == null || list.size() != 2 || list.get(0) != b || list.get(1) != a)
                return argc + "-arg order returned " + r + " (expected the two cards reversed" + (argc == 9 ? " in an OrderResult" : "") + ")";
            out.append(", ").append(argc).append("-arg order ok");
        }
        // Replacement-effect shape (phone, Nov 8 08:46): non-card items, remMin 0, remMax size-1, empty dest.
        List<Object> items = s.host.playerViewsForTest();
        AtomicReference<Object> result = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try { result.set(gui.order("Select order for replacement effects", "Apply first", 0, items.size() - 1, new ArrayList<>(items), new ArrayList<>(), null, false, true)); }
            catch (Throwable e) { result.set(e); }
        }, "order-shape-replacement");
        t.start();
        while (true) {
            JSONObject m = s.next(30);
            JSONObject d = m.optJSONObject("pendingDecision");
            if (s.fresh(d) && d.optString("type").equals("OrderObjectsDecision")) {
                JSONArray objs = d.getJSONArray("objects"), rev = new JSONArray();
                for (int i = objs.length() - 1; i >= 0; i--) rev.put(objs.get(i));
                s.answer(d, new JSONObject().put("type", "OrderedResponse").put("orderedObjects", rev));
                break;
            }
        }
        t.join(30_000);
        Object r = result.get();
        if (!(r instanceof IGuiGame.OrderResult<?> or) || or.ordered().size() != items.size() || or.ordered().get(0) != items.get(items.size() - 1))
            return "replacement-order shape returned " + r + " (expected all items reversed in an OrderResult)";
        out.append(", replacement-order shape ok");
        // Several non-card picks (e.g. "choose two colours") reach the player instead of Forge's first options.
        AtomicReference<Object> picks = new AtomicReference<>();
        Thread pickThread = new Thread(() -> {
            try { picks.set(gui.getChoices("Choose two colors", 2, 2, new ArrayList<>(List.of("White", "Blue", "Black")))); }
            catch (Throwable e) { picks.set(e); }
        }, "multi-pick");
        pickThread.start();
        while (true) {
            JSONObject m = s.next(30);
            JSONObject d = m.optJSONObject("pendingDecision");
            if (s.fresh(d) && d.optString("type").equals("SelectCardsDecision") && d.optInt("maxSelections") == 2) {
                s.answer(d, new JSONObject().put("type", "CardsSelectedResponse").put("selectedCards", new JSONArray().put("o2").put("o0")));
                break;
            }
        }
        pickThread.join(30_000);
        if (!List.of("Black", "White").equals(picks.get())) return "multi-pick returned " + picks.get() + " (expected [Black, White])";
        out.append(", multi-pick ok");
        // Forge's option dialog is a real choice (Goose's PR #19), and a stray pass can't answer it.
        AtomicReference<Object> chosen = new AtomicReference<>();
        Thread dialog = new Thread(() -> {
            try { chosen.set(gui.showOptionDialog("Pick one", "Test", null, List.of("First", "Second", "Third"), 0)); }
            catch (Throwable e) { chosen.set(e); }
        }, "option-dialog");
        dialog.start();
        while (true) {
            JSONObject m = s.next(30);
            JSONObject d = m.optJSONObject("pendingDecision");
            if (s.fresh(d) && d.optString("type").equals("ChooseOptionDecision") && d.optString("prompt").equals("Pick one")) {
                int before = s.notices.size();
                s.send(new JSONObject().put("type", "PassPriority").put("playerId", ArcanaForgeHost.HUMAN_ID));
                // Owner list, Nov 10: Pass brings the open question back (no refusal), and can't answer it.
                JSONObject again = s.next(10);
                JSONObject ad = again.optJSONObject("pendingDecision");
                if (s.notices.size() != before) return "a stray pass during a question was refused instead of bringing it back";
                if (ad == null || !ad.optString("id").equals(d.optString("id"))) return "a stray pass during a question didn't bring the question back";
                s.answer(d, new JSONObject().put("type", "OptionChosenResponse").put("optionIndex", 2));
                break;
            }
        }
        dialog.join(30_000);
        if (!Integer.valueOf(2).equals(chosen.get())) return "option dialog returned " + chosen.get() + " (expected 2)";
        out.append(", option dialog ok, stray pass reopens the question");
        return out.toString();
    }

    // ------------------------------------------------------------------ C. London mulligan (phone, Nov 8 11:31)

    private static String mulligan() throws Exception {
        Session s = new Session(null, "Forest", 2); // mulligan twice: covers "mulligan again" and "keep, then bottom"
        try {
            JSONObject m = s.next(5);
            int hand = -1;
            for (Object o : m.getJSONObject("state").getJSONArray("players")) {
                JSONObject p = (JSONObject) o;
                if (p.optString("playerId").equals(ArcanaForgeHost.HUMAN_ID)) hand = p.optInt("handSize");
            }
            if (!s.leakedDuringMulligan.isEmpty()) return "FAIL: sent " + s.leakedDuringMulligan + " between the mulligan and the new hand (Arcana would hide the new hand)";
            if (!s.mulliganOffers.equals(List.of(0, 1, 2))) return "FAIL: mulligan offers " + s.mulliganOffers + " (expected [0, 1, 2]: each new hand shown before keep)";
            if (s.bottomAsked != 2) return "FAIL: asked to put " + s.bottomAsked + " card(s) on the bottom (expected 2, after keeping)";
            if (hand != 5 && hand != 6) return "FAIL: hand " + hand + " after two mulligans";
            return "PASS: mulliganed twice (offers " + s.mulliganOffers + ", each new hand shown first), kept, put 2 on the bottom, playing with " + hand + " cards at v" + m.optInt("stateVersion");
        } finally { s.close(); }
    }

    // ------------------------------------------------------------------ B. multi-block combat damage

    private static String combatDamage() throws Exception {
        Session s = new Session(String.join("\n",
                "activeplayer=human", "activephase=MAIN1", "removesummoningsickness=true",
                // Forge sets an unlisted life to -1 (puzzle rule), and clears unlisted zones: list both.
                "humanlife=20", "ailife=20", "humanlibrary=" + LIBRARY, "ailibrary=" + LIBRARY,
                "humanbattlefield=Prized Unicorn",
                "humanhand=Llanowar Elves",
                "aibattlefield=Grizzly Bears;Grizzly Bears", "aihand="), "Prized Unicorn");
        try {
            boolean attacked = false;
            String first = null, second = null;
            while (true) {
                JSONObject m = s.next(90);
                if (!interactive(m) || !hasCard(m, "Prized Unicorn") && first == null) continue; // wait for the new board
                JSONObject d = m.optJSONObject("pendingDecision");
                if (d != null && !s.fresh(d)) continue; // a re-sent copy of a decision already answered
                if (d != null) {
                    switch (d.optString("type")) {
                        case "AssignDamageDecision" -> {
                            JSONArray t = d.getJSONArray("orderedTargets");
                            if (t.length() != 2) return "FAIL: expected 2 blockers, got " + t;
                            first = t.getString(0); second = t.getString(1);
                            s.answer(d, new JSONObject().put("type", "DamageAssignmentResponse")
                                    .put("assignments", new JSONObject().put(first, 0).put(second, d.getInt("availablePower"))));
                        }
                        case "OrderObjectsDecision" -> s.answer(d, new JSONObject().put("type", "OrderedResponse").put("orderedObjects", d.getJSONArray("objects")));
                        default -> { return "FAIL: unexpected decision " + d.optString("type") + " (" + d.optString("prompt") + ")"; }
                    }
                    continue;
                }
                JSONObject state = m.getJSONObject("state");
                JSONArray legal = m.getJSONArray("legalActions");
                JSONObject attack = null;
                for (int i = 0; i < legal.length(); i++) if (legal.getJSONObject(i).optString("actionType").equals("DeclareAttackers")) attack = legal.getJSONObject(i);
                if (attack != null && !attacked) {
                    List<String> unicorn = battlefield(state, ArcanaForgeHost.HUMAN_ID, "Prized Unicorn");
                    if (unicorn.isEmpty()) return "FAIL: Prized Unicorn missing";
                    attacked = true;
                    s.send(new JSONObject(attack.getJSONObject("action").toString()).put("attackers", new JSONObject().put(unicorn.get(0), ArcanaForgeHost.AI_ID)));
                    continue;
                }
                if (first != null) {
                    List<String> bears = battlefield(state, ArcanaForgeHost.AI_ID, "Grizzly Bears");
                    if (bears.size() == 1 && bears.get(0).equals(first)) {
                        // Forge's player messages (e.g. "this creature must be blocked") reach Arcana (phone, Nov 8 09:51)
                        String probe = "Forge message probe " + System.nanoTime();
                        s.host.guiForTest().message(probe);
                        try { while (!s.notices.contains(probe)) s.next(5); } catch (IllegalStateException timeout) { return "FAIL: Forge's message() didn't reach Arcana"; }
                        return "PASS: Forge messages shown; both Bears blocked; all damage put on the second blocker " + second + ", which died; the first (" + first
                                + ") survived; play continued at " + state.optString("currentStep") + " v" + m.optInt("stateVersion");
                    }
                    if (!state.optString("currentStep").contains("DAMAGE"))
                        return "FAIL: after the assignment the AI has Bears " + bears + " (expected only " + first + ")";
                }
                if (attacked && first == null && state.optString("currentStep").equals("POSTCOMBAT_MAIN"))
                    return "FAIL: combat ended without a damage assignment prompt (did both Bears block?)";
                s.send(new JSONObject().put("type", "PassPriority").put("playerId", ArcanaForgeHost.HUMAN_ID));
            }
        } finally { s.close(); }
    }

    // ------------------------------------------------------------------ D. trample short of lethal (phone, Nov 8 11:45)

    /**
     * A trampler (Prized Unicorn + Garruk's Uprising) with 2 power is blocked by two Grizzly Bears (lethal 2 each, 4 in
     * all). Damage may go to the player only after every blocker has lethal, so 1 to the player must be refused,
     * and 1 + 1 to the Bears must be accepted (the phone's split was refused forever).
     */
    private static String trampleShort() throws Exception {
        Session s = new Session(String.join("\n",
                "activeplayer=human", "activephase=MAIN1", "removesummoningsickness=true",
                "humanlife=20", "ailife=20", "humanlibrary=" + LIBRARY, "ailibrary=" + LIBRARY,
                "humanbattlefield=Prized Unicorn;Garruk's Uprising",
                "aibattlefield=Grizzly Bears;Grizzly Bears", "aihand="), "Prized Unicorn");
        try {
            boolean attacked = false, refusedPlayerDamage = false;
            int answers = 0;
            while (true) {
                JSONObject m = s.next(90);
                if (!interactive(m) || !hasCard(m, "Prized Unicorn")) continue;
                JSONObject d = m.optJSONObject("pendingDecision");
                if (d != null && !s.fresh(d)) continue;
                if (d != null) {
                    if (!d.optString("type").equals("AssignDamageDecision")) return "FAIL: unexpected decision " + d.optString("type") + " (" + d.optString("prompt") + ")";
                    if (!d.optBoolean("hasTrample") || d.isNull("defenderId")) return "FAIL: expected a trample decision with the defending player, got " + d;
                    JSONArray t = d.getJSONArray("orderedTargets");
                    String a = t.getString(0), b = t.getString(1), player = d.getString("defenderId");
                    int before = s.notices.size();
                    JSONObject split = answers == 0 ? new JSONObject().put(a, 1).put(b, 0).put(player, 1) : new JSONObject().put(a, 1).put(b, 1).put(player, 0);
                    answers++;
                    s.answer(d, new JSONObject().put("type", "DamageAssignmentResponse").put("assignments", split));
                    if (answers == 1) { // must be refused with a reason, then asked again
                        // the refusal comes first, then the question again: keep that question for the loop
                        s.pushBack = s.next(10);
                        if (s.notices.size() == before) return "FAIL: the split with damage to the player was not refused";
                        if (!s.notices.get(s.notices.size() - 1).contains("lethal")) return "FAIL: wrong refusal: " + s.notices.get(s.notices.size() - 1);
                        refusedPlayerDamage = true;
                    }
                    continue;
                }
                JSONObject state = m.getJSONObject("state");
                JSONArray legal = m.getJSONArray("legalActions");
                JSONObject attack = null;
                for (int i = 0; i < legal.length(); i++) if (legal.getJSONObject(i).optString("actionType").equals("DeclareAttackers")) attack = legal.getJSONObject(i);
                if (attack != null && !attacked) {
                    attacked = true;
                    s.send(new JSONObject(attack.getJSONObject("action").toString()).put("attackers", new JSONObject().put(battlefield(state, ArcanaForgeHost.HUMAN_ID, "Prized Unicorn").get(0), ArcanaForgeHost.AI_ID)));
                    continue;
                }
                if (answers >= 2 && state.optString("currentStep").equals("POSTCOMBAT_MAIN")) {
                    int aiLife = -1;
                    for (Object o : state.getJSONArray("players")) { JSONObject p = (JSONObject) o; if (p.optString("playerId").equals(ArcanaForgeHost.AI_ID)) aiLife = p.optInt("life"); }
                    int bears = battlefield(state, ArcanaForgeHost.AI_ID, "Grizzly Bears").size();
                    if (!refusedPlayerDamage || bears != 2 || aiLife != 20) return "FAIL: refused=" + refusedPlayerDamage + ", Bears " + bears + ", AI life " + aiLife;
                    return "PASS: 1 to the player refused (blockers not lethal); 1 + 1 to the Bears accepted; both Bears alive, AI at 20, play continued";
                }
                if (attacked && answers == 0 && state.optString("currentStep").equals("POSTCOMBAT_MAIN")) return "FAIL: no damage assignment prompt";
                s.send(new JSONObject().put("type", "PassPriority").put("playerId", ArcanaForgeHost.HUMAN_ID));
            }
        } finally { s.close(); }
    }

    // ------------------------------------------------------------------ E. the stack (audit, Nov 8)

    private static int life(JSONObject state, String id) {
        for (Object o : state.getJSONArray("players")) { JSONObject p = (JSONObject) o; if (p.optString("playerId").equals(id)) return p.optInt("life"); }
        return -1;
    }

    /**
     * An activated ability on the stack is shown (Forge's stack zone has no card for it) with its source and target;
     * with "respond to my own spells" off, your own Shock resolves without a stop.
     */
    private static String stack() throws Exception {
        Session s = new Session(String.join("\n",
                "activeplayer=human", "activephase=MAIN1", "removesummoningsickness=true",
                "humanlife=20", "ailife=20", "humanlibrary=" + LIBRARY, "ailibrary=" + LIBRARY,
                "humanbattlefield=Prodigal Pyromancer;Mountain", "humanhand=Shock",
                "aibattlefield=", "aihand="), "Prodigal Pyromancer");
        try {
            s.host.respondToOwnStack = true; // part 1: stop on your own ability to see it
            boolean activated = false, seenOnStack = false, shocked = false;
            while (true) {
                JSONObject m = s.next(60);
                if (!interactive(m)) continue;
                JSONObject d = m.optJSONObject("pendingDecision");
                if (d != null && !s.fresh(d)) continue;
                if (d != null) {
                    if (!d.optString("type").equals("ChooseTargetsDecision")) return "FAIL: unexpected decision " + d.optString("type");
                    s.answer(d, new JSONObject().put("type", "TargetsResponse").put("selectedTargets", new JSONObject().put("0", new JSONArray().put(ArcanaForgeHost.AI_ID))));
                    continue;
                }
                JSONObject state = m.getJSONObject("state");
                if (!activated) {
                    JSONArray legal = m.getJSONArray("legalActions");
                    for (int i = 0; i < legal.length(); i++) if (legal.getJSONObject(i).optString("actionType").equals("ActivateAbility")) {
                        activated = true; s.send(legal.getJSONObject(i).getJSONObject("action")); break;
                    }
                    if (!activated) return "FAIL: Prodigal Pyromancer's ability wasn't offered";
                    continue;
                }
                JSONArray stackIds = null;
                for (Object z : state.getJSONArray("zones")) { JSONObject zone = (JSONObject) z; if (zone.getJSONObject("zoneId").optString("zoneType").equals("Stack")) stackIds = zone.getJSONArray("cardIds"); }
                if (!seenOnStack) {
                    if (stackIds == null || stackIds.length() != 1) return "FAIL: expected the ability on the stack, stack " + stackIds;
                    JSONObject item = state.getJSONObject("cards").getJSONObject(stackIds.getString(0));
                    JSONArray t = item.optJSONArray("targets");
                    if (!item.optString("name").equals("Prodigal Pyromancer") || t == null || t.length() != 1 || !t.getJSONObject(0).optString("playerId").equals(ArcanaForgeHost.AI_ID))
                        return "FAIL: stack item " + item.optString("name") + " targets " + t;
                    seenOnStack = true;
                    s.host.respondToOwnStack = false; // part 2: your own spell resolves without a stop
                    s.send(new JSONObject().put("type", "PassPriority").put("playerId", ArcanaForgeHost.HUMAN_ID));
                    continue;
                }
                if (stackIds != null && stackIds.length() > 0) return "FAIL: stopped with your own item on the stack while 'respond to my own spells' is off";
                if (!shocked) {
                    if (life(state, ArcanaForgeHost.AI_ID) != 19) return "FAIL: AI life " + life(state, ArcanaForgeHost.AI_ID) + " after the ability (expected 19)";
                    JSONObject shock = castAction(m, "Shock");
                    if (shock == null) return "FAIL: Shock not castable";
                    shocked = true; s.send(shock); continue;
                }
                if (life(state, ArcanaForgeHost.AI_ID) == 17)
                    return "PASS: ability shown on the stack with its source and target (AI), resolved for 1; own Shock resolved without a stop (AI at 17)";
            }
        } finally { s.close(); }
    }

    // ------------------------------------------------------------------ F. choosing mana sources, Convoke (Nov 8)

    private static List<String> untapped(JSONObject state, String name) {
        List<String> out = new ArrayList<>();
        for (String id : battlefield(state, ArcanaForgeHost.HUMAN_ID, name))
            if (!state.getJSONObject("cards").getJSONObject(id).optBoolean("isTapped")) out.add(id);
        return out;
    }

    /**
     * Part 1: tap the Island for mana yourself, then cast Grizzly Bears ({1}{G}): the floating blue pays the {1},
     * so only one Forest is tapped. Part 2: Stoke the Flames ({2}{R}{R}, Convoke) at the AI, tapping both Bears for
     * the {2}: the Convoke choice reaches you, both Bears and both Mountains end tapped, the AI takes 4.
     */
    private static String manaAndConvoke() throws Exception {
        Session s = new Session(String.join("\n",
                "activeplayer=human", "activephase=MAIN1", "removesummoningsickness=true",
                "humanlife=20", "ailife=20", "humanlibrary=" + LIBRARY, "ailibrary=" + LIBRARY,
                "humanbattlefield=Forest;Forest;Island;Mountain;Mountain;Grizzly Bears", "humanhand=Grizzly Bears;Stoke the Flames",
                "aibattlefield=", "aihand="), "Island");
        try {
            int stage = 0; boolean convokeAsked = false;
            while (true) {
                JSONObject m = s.next(60);
                if (!interactive(m)) continue;
                JSONObject d = m.optJSONObject("pendingDecision");
                if (d != null && !s.fresh(d)) continue;
                if (d != null) {
                    String t = d.optString("type");
                    if (t.equals("ChooseTargetsDecision")) {
                        s.answer(d, new JSONObject().put("type", "TargetsResponse").put("selectedTargets", new JSONObject().put("0", new JSONArray().put(ArcanaForgeHost.AI_ID))));
                    } else if (t.equals("SelectCardsDecision") && d.optString("prompt").contains("tap to help pay")) {
                        convokeAsked = true;
                        JSONArray opts = d.getJSONArray("options"), pick = new JSONArray();
                        for (int i = 0; i < opts.length(); i++) if (d.getJSONObject("cardInfo").getJSONObject(opts.getString(i)).optString("name").equals("Grizzly Bears")) pick.put(opts.getString(i));
                        s.answer(d, new JSONObject().put("type", "CardsSelectedResponse").put("selectedCards", pick));
                    } else return "FAIL: unexpected decision " + t + " (" + d.optString("prompt") + ")";
                    continue;
                }
                JSONObject state = m.getJSONObject("state");
                JSONArray legal = m.getJSONArray("legalActions");
                if (stage == 0) { // tap the Island yourself
                    String island = battlefield(state, ArcanaForgeHost.HUMAN_ID, "Island").get(0);
                    JSONObject tap = null;
                    for (int i = 0; i < legal.length(); i++) { JSONObject a = legal.getJSONObject(i); if (a.optBoolean("isManaAbility") && a.getJSONObject("action").optString("sourceId").equals(island)) tap = a.getJSONObject("action"); }
                    if (tap == null) return "FAIL: the Island's mana ability wasn't offered";
                    stage = 1; s.send(tap); continue;
                }
                if (stage == 1) {
                    JSONObject pool = null;
                    for (Object o : state.getJSONArray("players")) { JSONObject p = (JSONObject) o; if (p.optString("playerId").equals(ArcanaForgeHost.HUMAN_ID)) pool = p.optJSONObject("manaPool"); }
                    if (pool == null || pool.optInt("blue") != 1) return "FAIL: floating mana not shown after tapping the Island: " + pool;
                    JSONObject bears = castAction(m, "Grizzly Bears");
                    if (bears == null) return "FAIL: Grizzly Bears not castable";
                    stage = 2; s.send(bears); continue;
                }
                if (stage == 2) {
                    if (battlefield(state, ArcanaForgeHost.HUMAN_ID, "Grizzly Bears").size() < 2) { s.send(new JSONObject().put("type", "PassPriority").put("playerId", ArcanaForgeHost.HUMAN_ID)); continue; }
                    if (untapped(state, "Forest").size() != 1) return "FAIL: expected one Forest left untapped (floating blue should pay {1}), untapped Forests " + untapped(state, "Forest").size();
                    JSONObject stoke = castAction(m, "Stoke the Flames");
                    if (stoke == null) return "FAIL: Stoke the Flames not castable (Convoke with 2 Mountains + 2 Bears)";
                    stage = 3; s.send(stoke); continue;
                }
                if (life(state, ArcanaForgeHost.AI_ID) == 16) {
                    if (!convokeAsked) return "FAIL: Convoke choice was never shown";
                    if (!untapped(state, "Grizzly Bears").isEmpty() || !untapped(state, "Mountain").isEmpty()) return "FAIL: expected both Bears and both Mountains tapped";
                    return "PASS: tapped the Island yourself (blue floating), Bears paid with it + one Forest; Stoke the Flames convoked with both Bears + 2 Mountains, AI at 16";
                }
                s.send(new JSONObject().put("type", "PassPriority").put("playerId", ArcanaForgeHost.HUMAN_ID));
            }
        } finally { s.close(); }
    }

    // ------------------------------------------------------------------ G. losing ends the game in Arcana (Nov 8)

    /** At 1 life you Shock yourself: Forge drops your seat, and Arcana must still get gameOver (it waited forever). */
    private static String youLose() throws Exception {
        Session s = new Session(String.join("\n",
                "activeplayer=human", "activephase=MAIN1",
                "humanlife=1", "ailife=20", "humanlibrary=" + LIBRARY, "ailibrary=" + LIBRARY,
                "humanbattlefield=Mountain", "humanhand=Shock", "aibattlefield=", "aihand="), "Shock");
        try {
            boolean cast = false;
            long end = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < end) {
                JSONObject m;
                try { m = s.next(5); } catch (IllegalStateException timeout) { continue; }
                if (m.optString("type").equals("gameOver"))
                    return cast ? "PASS: lost at 0 life; Arcana got gameOver (winner " + m.opt("winnerId") + ", " + m.optString("reason") + ")" : "FAIL: game over before casting";
                if (!m.optString("type").equals("stateUpdate") || !interactive(m)) continue;
                JSONObject d = m.optJSONObject("pendingDecision");
                if (d != null && !s.fresh(d)) continue;
                if (d != null && d.optString("type").equals("ChooseTargetsDecision")) {
                    s.answer(d, new JSONObject().put("type", "TargetsResponse").put("selectedTargets", new JSONObject().put("0", new JSONArray().put(ArcanaForgeHost.HUMAN_ID))));
                    continue;
                }
                if (!cast) { JSONObject shock = castAction(m, "Shock"); if (shock == null) return "FAIL: Shock not castable"; cast = true; s.send(shock); continue; }
                s.send(new JSONObject().put("type", "PassPriority").put("playerId", ArcanaForgeHost.HUMAN_ID));
            }
            return "FAIL: no gameOver after losing";
        } finally { s.close(); }
    }

    // ------------------------------------------------------------------ H. the AI's end step (Goose's PR #19 concern)

    /** On the AI's turn, with an instant you can cast, you get a stop at their end step and can cast it there. */
    private static String endStep() throws Exception {
        Session s = new Session(String.join("\n",
                "activeplayer=ai", "activephase=MAIN2",
                "humanlife=20", "ailife=20", "humanlibrary=" + LIBRARY, "ailibrary=" + LIBRARY,
                "humanbattlefield=Mountain", "humanhand=Shock", "aibattlefield=", "aihand="), "Shock");
        try {
            JSONObject m = s.next(5);
            JSONObject st = m.getJSONObject("state");
            if (!st.optString("activePlayerId").equals(ArcanaForgeHost.AI_ID) || !st.optString("currentStep").equals("END"))
                return "FAIL: first stop was " + st.optString("activePlayerId") + " " + st.optString("currentStep") + " (expected the AI's END step)";
            JSONObject shock = castAction(m, "Shock");
            if (shock == null) return "FAIL: Shock not castable at the AI's end step";
            s.send(shock);
            while (true) {
                m = s.next(30);
                if (!interactive(m)) continue;
                JSONObject d = m.optJSONObject("pendingDecision");
                if (d != null && !s.fresh(d)) continue;
                if (d != null && d.optString("type").equals("ChooseTargetsDecision")) {
                    s.answer(d, new JSONObject().put("type", "TargetsResponse").put("selectedTargets", new JSONObject().put("0", new JSONArray().put(ArcanaForgeHost.AI_ID))));
                    continue;
                }
                if (life(m.getJSONObject("state"), ArcanaForgeHost.AI_ID) == 18) return "PASS: stopped at the AI's end step (Shock in hand), cast it there, AI at 18";
                s.send(new JSONObject().put("type", "PassPriority").put("playerId", ArcanaForgeHost.HUMAN_ID));
            }
        } finally { s.close(); }
    }

    // ------------------------------------------------------------------ I. "you may pay" on a trigger (phone, Nov 8 18:28)

    /**
     * Ultron attacks and connives: the discard prompt shows its count (it said "Discard %d card(s)"), then
     * "you may pay {1}" for a Robot is asked (it was auto-paid, or "Not enough mana" when it couldn't be).
     */
    private static String mayPay() throws Exception {
        Session s = new Session(String.join("\n",
                "activeplayer=human", "activephase=MAIN1", "removesummoningsickness=true",
                "humanlife=20", "ailife=20", "humanlibrary=" + LIBRARY, "ailibrary=" + LIBRARY,
                "humanbattlefield=Ultron, Unlimited;Island", "humanhand=Grizzly Bears", "aibattlefield=", "aihand="), "Ultron, Unlimited");
        try {
            boolean attacked = false, discardOk = false, payAsked = false;
            while (true) {
                JSONObject m = s.next(60);
                if (!interactive(m)) continue;
                JSONObject d = m.optJSONObject("pendingDecision");
                if (d != null && !s.fresh(d)) continue;
                if (d != null) {
                    String type = d.optString("type"), prompt = d.optString("prompt");
                    if (type.equals("SelectCardsDecision")) {
                        if (prompt.contains("%d")) return "FAIL: prompt not filled in: " + prompt;
                        discardOk = true;
                        s.answer(d, new JSONObject().put("type", "CardsSelectedResponse").put("selectedCards", new JSONArray().put(d.getJSONArray("options").get(0))));
                    } else if (type.equals("YesNoDecision") && prompt.startsWith("Pay ")) {
                        payAsked = true;
                        s.answer(d, new JSONObject().put("type", "YesNoResponse").put("choice", true));
                    } else if (type.equals("YesNoDecision")) {
                        s.answer(d, new JSONObject().put("type", "YesNoResponse").put("choice", true));
                    } else return "FAIL: unexpected decision " + type + " (" + prompt + ")";
                    continue;
                }
                JSONObject state = m.getJSONObject("state");
                JSONArray legal = m.getJSONArray("legalActions");
                JSONObject attack = null;
                for (int i = 0; i < legal.length(); i++) if (legal.getJSONObject(i).optString("actionType").equals("DeclareAttackers")) attack = legal.getJSONObject(i);
                if (attack != null && !attacked) {
                    attacked = true;
                    s.send(new JSONObject(attack.getJSONObject("action").toString()).put("attackers", new JSONObject().put(battlefield(state, ArcanaForgeHost.HUMAN_ID, "Ultron, Unlimited").get(0), ArcanaForgeHost.AI_ID)));
                    continue;
                }
                List<String> robots = new ArrayList<>();
                for (String id : state.getJSONObject("cards").keySet()) { JSONObject c = state.getJSONObject("cards").getJSONObject(id); if (c.optString("name").contains("Robot") && c.optBoolean("isToken")) robots.add(id); }
                if (!robots.isEmpty()) {
                    if (!discardOk || !payAsked) return "FAIL: Robot made but discardShown=" + discardOk + ", payAsked=" + payAsked;
                    return "PASS: connive discard prompt filled in; \"pay {1}?\" asked and paid; Robot token created";
                }
                if (attacked && state.optString("currentStep").equals("POSTCOMBAT_MAIN")) return "FAIL: no Robot after attacking (discardShown=" + discardOk + ", payAsked=" + payAsked + ") notices " + s.notices;
                s.send(new JSONObject().put("type", "PassPriority").put("playerId", ArcanaForgeHost.HUMAN_ID));
            }
        } finally { s.close(); }
    }

    // ------------------------------------------------------------------ N. "Always yes" for an optional trigger

    private static String alwaysYes() throws Exception {
        Session s = new Session(String.join("\n",
                "activeplayer=ai", "activephase=MAIN1",
                "humanlife=20", "ailife=20", "humanlibrary=" + LIBRARY, "ailibrary=" + LIBRARY,
                "humanbattlefield=Burgeoning", "humanhand=Forest;Forest;Forest", "aibattlefield=", "aihand=Forest;Forest"), "Burgeoning");
        try {
            int asked = 0, aiLands = -1, triggersSeen = 0;
            boolean offered = false;
            long deadline = System.currentTimeMillis() + 240_000;
            while (System.currentTimeMillis() < deadline) {
                JSONObject m = s.next(90);
                if (!m.optString("type").equals("stateUpdate")) continue;
                JSONObject state = m.getJSONObject("state");
                int now = battlefield(state, ArcanaForgeHost.AI_ID, "Forest").size();
                if (aiLands >= 0 && now > aiLands) triggersSeen += now - aiLands;
                aiLands = now;
                int yours = battlefield(state, ArcanaForgeHost.HUMAN_ID, "Forest").size();
                if (triggersSeen >= 2 && yours >= 2) {
                    if (asked != 1) return "FAIL: asked " + asked + " times for " + triggersSeen + " opponent lands (always-yes ignored)";
                    if (!offered) return "FAIL: the trigger question did not offer ALWAYS";
                    return "PASS: asked once, answered YES · ALWAYS; the next opponent land triggered without asking; " + yours + " Forests put onto your battlefield";
                }
                if (!interactive(m)) continue;
                JSONObject d = m.optJSONObject("pendingDecision");
                if (d != null && !s.fresh(d)) continue;
                if (d != null) {
                    String type = d.optString("type"), prompt = d.optString("prompt");
                    if (type.equals("YesNoDecision") && prompt.startsWith("Use triggered ability of Burgeoning")) {
                        asked++;
                        offered |= d.optBoolean("alwaysAvailable");
                        s.answer(d, new JSONObject().put("type", "YesNoResponse").put("choice", true).put("always", true));
                    } else if (type.equals("SelectCardsDecision") || type.equals("ChooseCardsDecision")) {
                        JSONArray opts = d.optJSONArray("options");
                        s.answer(d, new JSONObject().put("type", "CardsSelectedResponse").put("selectedCards",
                                opts == null || opts.isEmpty() ? new JSONArray() : new JSONArray().put(opts.get(0))));
                    } else if (type.equals("YesNoDecision")) {
                        s.answer(d, new JSONObject().put("type", "YesNoResponse").put("choice", false));
                    } else return "FAIL: unexpected decision " + type + " (" + prompt + ")";
                    continue;
                }
                s.send(new JSONObject().put("type", "PassPriority").put("playerId", ArcanaForgeHost.HUMAN_ID));
            }
            return "FAIL: timed out; asked " + asked + ", opponent lands seen " + triggersSeen;
        } finally { s.close(); }
    }

    // ------------------------------------------------------------------ J. Ayula's fight mode (phone, Nov 8 19:00)

    /** Bear Cub enters; choose Ayula's fight mode, Ayula as your Bear: the fight must happen (1 or 2 AI creatures). */
    private static String ayulaFight(int aiCreatures) throws Exception {
        Session s = new Session(String.join("\n",
                "activeplayer=human", "activephase=MAIN1", "removesummoningsickness=true",
                "humanlife=20", "ailife=20", "humanlibrary=" + LIBRARY, "ailibrary=" + LIBRARY,
                "humanbattlefield=Ayula, Queen Among Bears;Forest;Forest", "humanhand=Bear Cub",
                "aibattlefield=" + (aiCreatures == 0 ? "" : aiCreatures == 1 ? "Hill Giant" : "Hill Giant;Gray Ogre"), "aihand="), "Bear Cub");
        try {
            boolean cast = false; List<String> decisions = new ArrayList<>(); List<String> modes = new ArrayList<>();
            long end = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < end) {
                JSONObject m = s.next(30);
                if (!interactive(m)) continue;
                JSONObject d = m.optJSONObject("pendingDecision");
                if (d != null && !s.fresh(d)) continue;
                if (d != null) {
                    String type = d.optString("type");
                    decisions.add(type + ": " + d.optString("prompt"));
                    if (type.equals("ChooseOptionDecision")) {
                        JSONArray o = d.getJSONArray("options"); int pick = 0;
                        for (int i = 0; i < o.length(); i++) { modes.add(o.getString(i)); if (o.getString(i).toLowerCase().contains("fight")) pick = i; }
                        s.answer(d, new JSONObject().put("type", "OptionChosenResponse").put("optionIndex", pick));
                    } else if (type.equals("ChooseTargetsDecision")) {
                        JSONArray legal = d.getJSONObject("legalTargets").getJSONArray("0"); String chosen = null;
                        JSONObject cards = m.getJSONObject("state").getJSONObject("cards");
                        for (int i = 0; i < legal.length(); i++) { JSONObject c = cards.optJSONObject(legal.getString(i)); if (c != null && (c.optString("name").startsWith("Ayula") || c.optString("name").equals("Hill Giant"))) chosen = legal.getString(i); }
                        if (chosen == null) chosen = legal.getString(0);
                        decisions.add("  targets offered " + legal.length() + ", chose " + chosen);
                        s.answer(d, new JSONObject().put("type", "TargetsResponse").put("selectedTargets", new JSONObject().put("0", new JSONArray().put(chosen))));
                    } else s.answer(d, new JSONObject().put("type", "YesNoResponse").put("choice", true));
                    continue;
                }
                JSONObject state = m.getJSONObject("state");
                if (!cast) { JSONObject cub = castAction(m, "Bear Cub"); if (cub == null) return "FAIL: Bear Cub not castable"; cast = true; s.send(cub); continue; }
                if (battlefield(state, ArcanaForgeHost.HUMAN_ID, "Bear Cub").size() == 1 && decisions.stream().anyMatch(x -> x.startsWith("ChooseTargets"))) {
                    boolean ayulaAlive = !battlefield(state, ArcanaForgeHost.HUMAN_ID, "Ayula, Queen Among Bears").isEmpty();
                    int giantDamage = -1;
                    for (String id : battlefield(state, ArcanaForgeHost.AI_ID, "Hill Giant")) giantDamage = state.getJSONObject("cards").getJSONObject(id).optInt("damage", 0);
                    String summary = "modes " + modes + "; " + decisions + "; Ayula alive=" + ayulaAlive + ", Hill Giant damage=" + giantDamage;
                    if (aiCreatures == 0) return "PASS (info, no AI creatures): " + summary; // what Forge offers and does with nothing to fight
                    // Ayula (2/2) fights Hill Giant (3/3): Ayula dies, the Giant has 2 damage
                    return !ayulaAlive && giantDamage == 2 ? "PASS: fight happened. " + summary : "FAIL: no fight. " + summary;
                }
                s.send(new JSONObject().put("type", "PassPriority").put("playerId", ArcanaForgeHost.HUMAN_ID));
            }
            return "FAIL: timed out; decisions " + decisions;
        } finally { s.close(); }
    }

    // ------------------------------------------------------------------ K. your commander dies (phone, Nov 8 19:10)

    /** Your commander (Ayula) is Shocked: you must be asked to move it to the command zone, and be able to recast it. */
    // ------------------------------------------------------------------ L/M. refused blocks (phone, Nov 9 11:27)

    /**
     * Arcana unlocks its block screen after a refused block only for Argentum's INVALID_ACTION code; with any other code
     * the declare-blockers step had no way forward ("frozen waiting for the AI"). L: one blocker for a menace attacker
     * is refused (INVALID_ACTION, plain words), then two blockers are accepted. M: Prized Unicorn must be blocked by
     * every creature able to: no blockers is refused, then blocking is accepted.
     */
    private static String illegalBlock() throws Exception {
        return refusedThenFixed("Boggart Brute", "Grizzly Bears;Grizzly Bears", true, "can't be blocked by just 1 creature");
    }

    private static String forcedBlock() throws Exception {
        return refusedThenFixed("Prized Unicorn", "Grizzly Bears", false, "must block");
    }

    private static String refusedThenFixed(String attackerName, String yourCreatures, boolean oneThenAll, String reason) throws Exception {
        Session s = new Session(String.join("\n",
                "activeplayer=ai", "activephase=COMBAT_DECLARE_BLOCKERS", "removesummoningsickness=true",
                "humanlife=20", "ailife=20", "humanlibrary=" + LIBRARY, "ailibrary=" + LIBRARY,
                "humanbattlefield=" + yourCreatures, "humanhand=",
                "aibattlefield=" + attackerName + "|Attacking", "aihand="), attackerName);
        try {
            int tries = 0;
            boolean refused = false;
            while (true) {
                JSONObject m = s.next(90);
                if (!interactive(m)) continue;
                JSONObject state = m.getJSONObject("state");
                JSONObject block = null;
                JSONArray legal = m.getJSONArray("legalActions");
                for (int i = 0; i < legal.length(); i++) if (legal.getJSONObject(i).optString("actionType").equals("DeclareBlockers")) block = legal.getJSONObject(i);
                if (block == null) {
                    if (!refused) return "FAIL: blockers step ended without the illegal declaration being refused (" + s.errorCodes + ")";
                    return "PASS: refused with " + s.errorCodes.stream().filter(e -> e.startsWith("INVALID_ACTION")).findFirst().orElse("?")
                            + "; the corrected declaration was accepted (" + state.optString("currentStep") + ")";
                }
                List<String> mine = battlefield(state, ArcanaForgeHost.HUMAN_ID, "Grizzly Bears");
                List<String> attacker = battlefield(state, ArcanaForgeHost.AI_ID, attackerName);
                if (attacker.isEmpty() || mine.isEmpty()) return "FAIL: board missing (" + attacker + ", " + mine + ")";
                JSONObject blockers = new JSONObject();
                if (tries == 0) { // the illegal one
                    if (oneThenAll) blockers.put(mine.get(0), new JSONArray().put(attacker.get(0)));
                } else for (String b : mine) blockers.put(b, new JSONArray().put(attacker.get(0))); // the legal one
                if (tries == 1) {
                    String code = s.errorCodes.stream().filter(e -> e.contains(reason)).findFirst().orElse(null);
                    if (code == null) return "FAIL: no refusal mentioning '" + reason + "' (" + s.errorCodes + ")";
                    if (!code.startsWith("INVALID_ACTION")) return "FAIL: refusal sent as " + code + " (Arcana keeps its block screen locked unless INVALID_ACTION)";
                    refused = true;
                }
                if (tries++ > 2) return "FAIL: the corrected declaration was refused too (" + s.errorCodes + ")";
                s.send(new JSONObject(block.getJSONObject("action").toString()).put("blockers", blockers));
            }
        } finally { s.close(); }
    }

    private static String commanderDies() throws Exception {
        Session s = new Session(String.join("\n",
                "activeplayer=human", "activephase=MAIN1", "removesummoningsickness=true",
                "humanlife=20", "ailife=20", "humanlibrary=" + LIBRARY, "ailibrary=" + LIBRARY,
                "humanbattlefield=Ayula, Queen Among Bears|IsCommander;Mountain;Forest;Forest;Forest;Forest;Forest", "humanhand=Shock", "aibattlefield=", "aihand="), "Shock", 0, true);
        try {
            boolean cast = false; List<String> decisions = new ArrayList<>();
            long end = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < end) {
                JSONObject m = s.next(30);
                if (!interactive(m)) continue;
                JSONObject d = m.optJSONObject("pendingDecision");
                if (d != null && !s.fresh(d)) continue;
                JSONObject state = m.getJSONObject("state");
                if (d != null) {
                    decisions.add(d.optString("type") + ": " + d.optString("prompt").replace("\n", " "));
                    if (d.optString("type").equals("ChooseTargetsDecision")) {
                        String ayula = battlefield(state, ArcanaForgeHost.HUMAN_ID, "Ayula, Queen Among Bears").get(0);
                        s.answer(d, new JSONObject().put("type", "TargetsResponse").put("selectedTargets", new JSONObject().put("0", new JSONArray().put(ayula))));
                    } else s.answer(d, new JSONObject().put("type", "YesNoResponse").put("choice", true));
                    continue;
                }
                if (!cast) { JSONObject shock = castAction(m, "Shock"); if (shock == null) return "FAIL: Shock not castable"; cast = true; s.send(shock); continue; }
                if (battlefield(state, ArcanaForgeHost.HUMAN_ID, "Ayula, Queen Among Bears").isEmpty()) {
                    String where = "nowhere";
                    for (Object z : state.getJSONArray("zones")) {
                        JSONObject zone = (JSONObject) z;
                        if (!zone.getJSONObject("zoneId").optString("ownerId").equals(ArcanaForgeHost.HUMAN_ID)) continue;
                        for (Object id : zone.getJSONArray("cardIds")) if (state.getJSONObject("cards").getJSONObject(String.valueOf(id)).optString("name").startsWith("Ayula")) where = zone.getJSONObject("zoneId").optString("zoneType");
                    }
                    JSONObject recast = castAction(m, "Ayula, Queen Among Bears");
                    String summary = "Ayula now in " + where + "; recast offered=" + (recast != null) + "; decisions " + decisions;
                    return where.equalsIgnoreCase("COMMAND") && recast != null ? "PASS: " + summary : "FAIL: " + summary;
                }
                s.send(new JSONObject().put("type", "PassPriority").put("playerId", ArcanaForgeHost.HUMAN_ID));
            }
            return "FAIL: timed out; decisions " + decisions;
        } finally { s.close(); }
    }
}
