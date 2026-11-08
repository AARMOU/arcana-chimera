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
 *  Every scenario fails on a timeout, a "can't show yet" notice, or a missing follow-up state.
 */
public final class PromptScenarios {
    private static final String LIBRARY = String.join(";", java.util.Collections.nCopies(20, "Forest"));

    private static String forgeDir;

    public static void main(String[] args) throws Exception {
        forgeDir = args.length > 0 ? args[0] : "../forge-gui/";
        List<String> failures = new ArrayList<>();
        for (String name : List.of("A-ghalta-order", "B-combat-damage", "C-mulligan", "D-trample-short")) {
            String r;
            try { r = name.startsWith("A") ? ghalta() : name.startsWith("B") ? combatDamage() : name.startsWith("C") ? mulligan() : trampleShort(); }
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
        final ArcanaForgeHost host = new ArcanaForgeHost(inbox::add);
        JSONObject last;
        final List<String> notices = new ArrayList<>();

        final List<Integer> mulliganOffers = new ArrayList<>();
        int bottomAsked = -1;
        boolean awaitingReoffer;
        final List<String> leakedDuringMulligan = new ArrayList<>();

        Session(String board, String marker) throws Exception { this(board, marker, 0); }

        Session(String board, String marker, int mulligansWanted) throws Exception {
            ArcanaForgeHost.initForge(forgeDir, host);
            host.setStartBoardForTest(board);
            Map<String, Integer> forests = new LinkedHashMap<>(); forests.put("Forest", 60);
            new Thread(() -> { try { host.startGame(forests, forests); } catch (Exception e) { e.printStackTrace(); } }, "scenario-start").start();
            while (true) {
                JSONObject m = next(60);
                if (m.optString("type").equals("mulliganDecision")) {
                    int count = m.optInt("mulliganCount");
                    mulliganOffers.add(count);
                    awaitingReoffer = count < mulligansWanted;
                    host.handle(new JSONObject().put("type", count < mulligansWanted ? "mulligan" : "keepHand")); continue;
                }
                // Arcana hides the opening hand once a state arrives: nothing may come between a mulligan and the next offer
                if (awaitingReoffer && !m.optString("type").equals("mulliganDecision")) leakedDuringMulligan.add(m.optString("type"));
                if (m.optString("type").equals("chooseBottomCards")) {
                    bottomAsked = m.optInt("cardsToPutOnBottom");
                    JSONArray hand = m.getJSONArray("hand"), pick = new JSONArray();
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

        void close() { host.shutdown(); }
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
        return out.toString();
    }

    // ------------------------------------------------------------------ C. London mulligan (phone, Nov 8 11:31)

    private static String mulligan() throws Exception {
        Session s = new Session(null, "Forest", 1);
        try {
            JSONObject m = s.next(5);
            int hand = -1;
            for (Object o : m.getJSONObject("state").getJSONArray("players")) {
                JSONObject p = (JSONObject) o;
                if (p.optString("playerId").equals(ArcanaForgeHost.HUMAN_ID)) hand = p.optInt("handSize");
            }
            if (!s.leakedDuringMulligan.isEmpty()) return "FAIL: sent " + s.leakedDuringMulligan + " between the mulligan and the new hand (Arcana would hide the new hand)";
            if (s.mulliganOffers.size() < 2 || s.mulliganOffers.get(1) != 1) return "FAIL: mulligan offers " + s.mulliganOffers + " (expected a second offer with count 1)";
            if (s.bottomAsked != 1) return "FAIL: asked to put " + s.bottomAsked + " card(s) on the bottom (expected 1)";
            if (hand != 6 && hand != 7) return "FAIL: hand " + hand + " after one mulligan";
            return "PASS: mulliganed once (offers " + s.mulliganOffers + "), put 1 on the bottom, playing with " + hand + " cards in hand at v" + m.optInt("stateVersion");
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
}
