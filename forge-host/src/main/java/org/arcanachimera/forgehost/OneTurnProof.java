package org.arcanachimera.forgehost;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Milestone 1 proof, no Android: a scripted "Arcana UI" plays against Forge through {@link ArcanaForgeHost}
 * using only Argentum-shaped messages. It plays a land, casts a creature when it can, passes, and must see
 * Forge's AI take a turn and hand priority back on its next turn. Exit 0 = PASS.
 * Usage: java ... OneTurnProof &lt;forge-gui folder holding res/&gt;
 */
public final class OneTurnProof {
    public static void main(String[] args) throws Exception {
        LinkedBlockingQueue<JSONObject> inbox = new LinkedBlockingQueue<>();
        ArcanaForgeHost host = new ArcanaForgeHost(inbox::add);
        long t0 = System.currentTimeMillis();
        ArcanaForgeHost.initForge(args.length > 0 ? args[0] : "../forge-gui/", host);
        System.out.println("PROOF forge loaded in " + (System.currentTimeMillis() - t0) + " ms");

        Map<String, Integer> you = new LinkedHashMap<>(); you.put("Forest", 30); you.put("Grizzly Bears", 30);
        Map<String, Integer> ai = new LinkedHashMap<>(); ai.put("Mountain", 30); ai.put("Hill Giant", 30);
        new Thread(() -> { try { host.startGame(you, ai); } catch (Exception e) { e.printStackTrace(); } }, "proof-start").start();

        boolean landPlayed = false, creatureCast = false, aiTurnSeen = false;
        int myFirstTurn = -1, landTurn = -1, castTurn = -1, updates = 0;
        long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5);
        String result = "FAIL: timed out";
        while (System.currentTimeMillis() < deadline) {
            JSONObject m = inbox.poll(5, TimeUnit.SECONDS);
            if (m == null) continue;
            String type = m.optString("type");
            if (type.equals("error")) { System.out.println("PROOF host error: " + m.optString("message")); continue; }
            if (type.equals("gameOver")) { result = "FAIL: game ended early " + m; break; }
            if (type.equals("mulliganDecision")) {
                System.out.println("PROOF mulligan offer: " + m.getJSONArray("hand").length() + " cards (zone " + m.optInt("forgeHandZone") + ", library " + m.optInt("forgeLibrary") + ") -> keep");
                host.handle(new JSONObject().put("type", "keepHand")); continue;
            }
            if (!type.equals("stateUpdate")) { System.out.println("PROOF message " + type); continue; }
            updates++;
            JSONObject s = m.getJSONObject("state");
            String active = s.optString("activePlayerId");
            int turn = s.optInt("turnNumber");
            String step = s.optString("currentStep");
            if (active.equals(ArcanaForgeHost.AI_ID)) {
                if (!aiTurnSeen) System.out.println("PROOF AI turn " + turn + " observed, AI battlefield " + battlefield(s, ArcanaForgeHost.AI_ID));
                aiTurnSeen = true;
            }
            if (!m.optBoolean("interactive")) {
                System.out.println("PROOF   (update v" + m.optInt("stateVersion") + " turn " + turn + " " + step + " active=" + active
                        + " input=" + m.optString("forgeInput") + " you=" + battlefield(s, ArcanaForgeHost.HUMAN_ID) + ")");
                continue;
            }
            if (active.equals(ArcanaForgeHost.HUMAN_ID) && myFirstTurn < 0) myFirstTurn = turn;
            boolean bearsOut = battlefield(s, ArcanaForgeHost.HUMAN_ID).contains("Grizzly Bears");
            if (creatureCast && !bearsOut && turn > castTurn) { result = "FAIL: cast Grizzly Bears on turn " + castTurn + " but it never reached the battlefield"; break; }
            if (aiTurnSeen && active.equals(ArcanaForgeHost.HUMAN_ID) && bearsOut && turn > castTurn) {
                result = "PASS: played a land on turn " + landTurn + (creatureCast ? ", cast Grizzly Bears on turn " + castTurn : "")
                        + ", Forge AI took a turn, priority back on turn " + turn + " (" + step + "); your battlefield "
                        + battlefield(s, ArcanaForgeHost.HUMAN_ID) + ", AI battlefield " + battlefield(s, ArcanaForgeHost.AI_ID);
                break;
            }
            JSONArray legal = m.getJSONArray("legalActions");
            JSONObject choice = find(legal, "PassPriority");
            if (active.equals(ArcanaForgeHost.HUMAN_ID) && step.equals("PRECOMBAT_MAIN")) {
                JSONObject land = find(legal, "PlayLand");
                JSONObject spell = findAffordable(legal, "CastSpell");
                if (land != null && player(s, ArcanaForgeHost.HUMAN_ID).optInt("landsPlayedThisTurn") == 0) {
                    choice = land; landPlayed = true; landTurn = turn;
                } else if (spell != null && !creatureCast && true) {
                    choice = spell; creatureCast = true; castTurn = turn;
                }
            }
            System.out.println("PROOF turn " + turn + " " + step + " active=" + active + " you=" + battlefield(s, ArcanaForgeHost.HUMAN_ID) + " -> " + choice.getString("actionType")
                    + " (" + legal.length() + " legal, input " + m.optString("forgeInput") + ")");
            host.handle(new JSONObject().put("type", "submitAction").put("action", choice.getJSONObject("action")));
        }
        if (result.startsWith("PASS")) { // a second game in the same app session (phone crash, Nov 7 23:05)
            host.shutdown();
            LinkedBlockingQueue<JSONObject> inbox2 = new LinkedBlockingQueue<>();
            ArcanaForgeHost host2 = new ArcanaForgeHost(inbox2::add);
            ArcanaForgeHost.initForge(args.length > 0 ? args[0] : "../forge-gui/", host2);
            new Thread(() -> { try { host2.startGame(you, ai); } catch (Exception e) { e.printStackTrace(); } }, "proof-start-2").start();
            boolean offered = false;
            long until = System.currentTimeMillis() + 90_000;
            while (!offered && System.currentTimeMillis() < until) {
                JSONObject m2 = inbox2.poll(5, TimeUnit.SECONDS);
                if (m2 != null && m2.optString("type").equals("mulliganDecision")) offered = true;
            }
            System.out.println("PROOF second game in the same session: " + (offered ? "started (opening hand offered)" : "DID NOT START"));
            if (!offered) result = "FAIL: second game did not start";
            host2.shutdown();
        }
        System.out.println("PROOF updates received: " + updates);
        System.out.println("PROOF prompts answered by default (to become player decisions): " + new java.util.LinkedHashSet<>(host.defaultedPrompts));
        System.out.println("PROOF RESULT " + result);
        System.exit(result.startsWith("PASS") ? 0 : 1);
    }

    private static JSONObject find(JSONArray legal, String actionType) {
        for (int i = 0; i < legal.length(); i++) if (legal.getJSONObject(i).optString("actionType").equals(actionType)) return legal.getJSONObject(i);
        return null;
    }

    private static JSONObject findAffordable(JSONArray legal, String actionType) {
        for (int i = 0; i < legal.length(); i++) {
            JSONObject a = legal.getJSONObject(i);
            if (a.optString("actionType").equals(actionType) && a.optBoolean("isAffordable", true)) return a;
        }
        return null;
    }

    private static JSONObject player(JSONObject s, String id) {
        JSONArray ps = s.getJSONArray("players");
        for (int i = 0; i < ps.length(); i++) if (ps.getJSONObject(i).optString("playerId").equals(id)) return ps.getJSONObject(i);
        return new JSONObject();
    }

    private static String battlefield(JSONObject s, String owner) {
        StringBuilder b = new StringBuilder("[");
        JSONObject cards = s.getJSONObject("cards");
        for (String k : cards.keySet()) {
            JSONObject c = cards.getJSONObject(k);
            if (c.getJSONObject("zone").optString("zoneType").equals("Battlefield") && c.optString("controllerId").equals(owner))
                b.append(b.length() > 1 ? ", " : "").append(c.optString("name")).append(c.optBoolean("isTapped") ? " (tapped)" : "");
        }
        return b.append("]").toString();
    }

    private static int countLands(JSONObject s, String owner) {
        int n = 0;
        JSONObject cards = s.getJSONObject("cards");
        for (String k : cards.keySet()) {
            JSONObject c = cards.getJSONObject(k);
            if (c.getJSONObject("zone").optString("zoneType").equals("Battlefield") && c.optString("controllerId").equals(owner)
                    && c.getJSONArray("cardTypes").toList().contains("LAND")) n++;
        }
        return n;
    }
}
