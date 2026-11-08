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
 *  A. Ghalta: cast Ghalta, put two creatures from hand onto the battlefield (the phone's Nov 8 00:19 stall path:
 *     Forge's `order` prompt), then require both creatures on the battlefield and a following playable state.
 *     Also calls the 8- and 9-argument `order` forms directly and checks the returned order and wrapper type.
 *  B. Combat damage: Prized Unicorn (all able creatures must block it) attacks into two Grizzly Bears. The player
 *     puts all 2 damage on the SECOND blocker; the second must die and the first must survive.
 *  Every scenario fails on a timeout, a "can't show yet" notice, or a missing follow-up state.
 */
public final class PromptScenarios {
    private static String forgeDir;

    public static void main(String[] args) throws Exception {
        forgeDir = args.length > 0 ? args[0] : "../forge-gui/";
        List<String> failures = new ArrayList<>();
        for (String name : List.of("A-ghalta-order", "B-combat-damage")) {
            String r;
            try { r = name.startsWith("A") ? ghalta() : combatDamage(); }
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

        Session() throws Exception {
            ArcanaForgeHost.initForge(forgeDir, host);
            Map<String, Integer> forests = new LinkedHashMap<>(); forests.put("Forest", 60);
            new Thread(() -> { try { host.startGame(forests, forests); } catch (Exception e) { e.printStackTrace(); } }, "scenario-start").start();
            while (true) {
                JSONObject m = next(60);
                if (m.optString("type").equals("mulliganDecision")) { host.handle(new JSONObject().put("type", "keepHand")); continue; }
                if (interactive(m) && m.getJSONObject("state").optString("activePlayerId").equals(ArcanaForgeHost.HUMAN_ID)
                        && m.getJSONObject("state").optString("currentStep").equals("PRECOMBAT_MAIN")) return;
                if (interactive(m) && m.optJSONObject("pendingDecision") == null) send(new JSONObject().put("type", "PassPriority").put("playerId", ArcanaForgeHost.HUMAN_ID));
            }
        }

        JSONObject next(int seconds) throws Exception {
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

        void answer(JSONObject decision, JSONObject response) throws Exception {
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
        Session s = new Session();
        try {
            s.host.applyStateForTest(String.join("\n",
                    "humanbattlefield=Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest",
                    "humanhand=Ghalta, Stampede Tyrant;Grizzly Bears;Llanowar Elves",
                    "aibattlefield=", "aihand="), "Ghalta, Stampede Tyrant");
            boolean cast = false, ordered = false, selected = false;
            List<String> orderAnswer = null;
            while (true) {
                JSONObject m = s.next(90);
                if (!interactive(m) || !hasCard(m, "Ghalta, Stampede Tyrant")) continue; // wait for the new board
                JSONObject d = m.optJSONObject("pendingDecision");
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
                List<String> bears = battlefield(state, ArcanaForgeHost.HUMAN_ID, "Grizzly Bears"), elves = battlefield(state, ArcanaForgeHost.HUMAN_ID, "Llanowar Elves");
                if (!cast) {
                    JSONObject a = castAction(m, "Ghalta, Stampede Tyrant");
                    if (a == null) return "FAIL: Ghalta was not castable with 8 Forests";
                    cast = true; s.send(a); continue;
                }
                if (!bears.isEmpty() && !elves.isEmpty()) {
                    if (!ordered) return "FAIL: creatures entered but Forge's order prompt was never shown (selected=" + selected + ")";
                    String shapes = orderShapes(s, bears.get(0), elves.get(0));
                    if (!shapes.startsWith("ok")) return "FAIL: " + shapes;
                    return "PASS: cast Ghalta, chose both creatures, answered the order prompt " + orderAnswer
                            + "; Grizzly Bears + Llanowar Elves on the battlefield, play continued at state v" + m.optInt("stateVersion") + "; " + shapes;
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
                if (d != null && d.optString("type").equals("OrderObjectsDecision")) {
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
        return out.toString();
    }

    // ------------------------------------------------------------------ B. multi-block combat damage

    private static String combatDamage() throws Exception {
        Session s = new Session();
        try {
            s.host.applyStateForTest(String.join("\n",
                    "humanbattlefield=Prized Unicorn",
                    "humanhand=Llanowar Elves",
                    "aibattlefield=Grizzly Bears;Grizzly Bears", "aihand="), "Llanowar Elves");
            boolean attacked = false;
            String first = null, second = null;
            while (true) {
                JSONObject m = s.next(90);
                if (!interactive(m) || !hasCard(m, "Prized Unicorn") && first == null) continue; // wait for the new board
                JSONObject d = m.optJSONObject("pendingDecision");
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
                    if (bears.size() == 1 && bears.get(0).equals(first))
                        return "PASS: both Bears blocked; all damage put on the second blocker " + second + ", which died; the first (" + first
                                + ") survived; play continued at " + state.optString("currentStep") + " v" + m.optInt("stateVersion");
                    if (!state.optString("currentStep").contains("DAMAGE"))
                        return "FAIL: after the assignment the AI has Bears " + bears + " (expected only " + first + ")";
                }
                if (attacked && first == null && state.optString("currentStep").equals("POSTCOMBAT_MAIN"))
                    return "FAIL: combat ended without a damage assignment prompt (did both Bears block?)";
                s.send(new JSONObject().put("type", "PassPriority").put("playerId", ArcanaForgeHost.HUMAN_ID));
            }
        } finally { s.close(); }
    }
}
