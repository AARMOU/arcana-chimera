package org.arcanachimera.forgehost;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Owner, Nov 23: "I'd like to be able to take back a play". Plays a land on your first main phase, then asks the host
 * to undo it through Arcana's own "requestUndo" message: the table must offer undo (UNDO (n)) after the play and show
 * the land back in hand afterwards. Exit 0 = PASS.
 * Usage: java ... UndoProof &lt;forge-gui folder holding res/&gt;
 */
public final class UndoProof {
    public static void main(String[] args) throws Exception {
        forge.util.MyRandom.setRandom(new java.util.Random(Long.getLong("proof.seed", 1L)));
        LinkedBlockingQueue<JSONObject> inbox = new LinkedBlockingQueue<>();
        ArcanaForgeHost host = new ArcanaForgeHost(inbox::add);
        ArcanaForgeHost.initForge(args.length > 0 ? args[0] : "../forge-gui/", host);
        Map<String, Integer> you = new LinkedHashMap<>(); you.put("Mountain", 30); you.put("Gray Ogre", 30);
        Map<String, Integer> ai = new LinkedHashMap<>(); ai.put("Mountain", 30); ai.put("Hill Giant", 30);
        new Thread(() -> { try { host.startGame(you, ai); } catch (Exception e) { e.printStackTrace(); } }, "undo-start").start();

        String result = "FAIL: timed out";
        int landsBefore = -1;
        boolean played = false, undoSent = false;
        String whyBefore = null;
        int steps = 0;
        long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5);
        while (System.currentTimeMillis() < deadline) {
            JSONObject m = inbox.poll(5, TimeUnit.SECONDS);
            if (m == null) continue;
            String type = m.optString("type");
            if (type.equals("error")) {
                System.out.println("UNDO host message: " + m.optString("message"));
                if (undoSent) { result = "FAIL: undo refused: " + m.optString("message"); break; }
                continue;
            }
            if (type.equals("gameOver")) { result = "FAIL: game ended early"; break; }
            if (type.equals("mulliganDecision")) { host.handle(new JSONObject().put("type", "keepHand")); continue; }
            if (!type.equals("stateUpdate") || !m.optBoolean("interactive")) continue;
            JSONObject s = m.getJSONObject("state");
            JSONObject decision = m.optJSONObject("pendingDecision");
            if (decision != null) {
                host.handle(new JSONObject().put("type", "submitAction").put("action", new JSONObject().put("type", "SubmitDecision")
                        .put("playerId", ArcanaForgeHost.HUMAN_ID).put("response", OneTurnProof.answer(decision))));
                continue;
            }
            String active = s.optString("activePlayerId"), step = s.optString("currentStep");
            JSONArray legal = m.getJSONArray("legalActions");
            int lands = count(s, "Mountain");
            if (undoSent) {
                System.out.println("UNDO after undo: battlefield " + OneTurnProof.battlefield(s, ArcanaForgeHost.HUMAN_ID) + " (" + lands + " Mountain(s), was " + landsBefore + ")");
                result = lands == landsBefore && step.equals("PRECOMBAT_MAIN")
                        ? "PASS: played a Mountain, undo offered (" + steps + " step(s)), the Mountain is back in hand; before the play undo said: " + whyBefore
                        : "FAIL: after undo the battlefield has " + lands + " Mountain(s), expected " + landsBefore + " (step " + step + ")";
                break;
            }
            if (played) {
                steps = m.optInt("chimeraUndoSteps");
                System.out.println("UNDO after the play: undoAvailable=" + m.optBoolean("undoAvailable") + " steps=" + steps + " why='" + m.optString("chimeraUndoWhy") + "'");
                if (!m.optBoolean("undoAvailable")) { result = "FAIL: no undo offered after playing a land (" + m.optString("chimeraUndoWhy") + ")"; break; }
                host.handle(new JSONObject().put("type", "requestUndo"));
                undoSent = true;
                continue;
            }
            JSONObject attack = OneTurnProof.find(legal, "DeclareAttackers"), block = OneTurnProof.find(legal, "DeclareBlockers");
            if (attack != null || block != null) {
                host.handle(new JSONObject().put("type", "submitAction").put("action", (attack != null ? attack : block).getJSONObject("action")));
                continue;
            }
            JSONObject land = OneTurnProof.find(legal, "PlayLand");
            if (active.equals(ArcanaForgeHost.HUMAN_ID) && step.equals("PRECOMBAT_MAIN") && land != null) {
                whyBefore = m.optBoolean("undoAvailable") ? "available" : "'" + m.optString("chimeraUndoWhy") + "'";
                landsBefore = lands;
                played = true;
                System.out.println("UNDO playing a Mountain on turn " + s.optInt("turnNumber") + " (" + lands + " on the battlefield)");
                host.handle(new JSONObject().put("type", "submitAction").put("action", land.getJSONObject("action")));
                continue;
            }
            JSONObject pass = OneTurnProof.find(legal, "PassPriority");
            if (pass != null) host.handle(new JSONObject().put("type", "submitAction").put("action", pass.getJSONObject("action")));
        }
        host.shutdown();
        System.out.println("UNDO RESULT " + result);
        System.exit(result.startsWith("PASS") ? 0 : 1);
    }

    private static int count(JSONObject s, String name) {
        int n = 0;
        JSONObject cards = s.getJSONObject("cards");
        for (String k : cards.keySet()) {
            JSONObject c = cards.getJSONObject(k);
            if (c.getJSONObject("zone").optString("zoneType").equals("Battlefield") && c.optString("controllerId").equals(ArcanaForgeHost.HUMAN_ID)
                    && c.optString("name").equals(name)) n++;
        }
        return n;
    }
}
