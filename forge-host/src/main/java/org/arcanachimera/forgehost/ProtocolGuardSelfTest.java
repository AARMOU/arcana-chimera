package org.arcanachimera.forgehost;

import org.json.JSONArray;
import org.json.JSONObject;

/** Synthetic checks: the guard must fail closed on malformed/missing protocol data. */
public final class ProtocolGuardSelfTest {
    private static JSONObject snapshot(int version) {
        JSONArray players = new JSONArray()
            .put(new JSONObject().put("playerId", "offline-you"))
            .put(new JSONObject().put("playerId", "offline-ai"));
        JSONObject state = new JSONObject().put("players", players)
            .put("activePlayerId", "offline-you")
            .put("priorityPlayerId", "offline-you");
        JSONArray legal = new JSONArray().put(new JSONObject()
            .put("actionType", "PassPriority")
            .put("action", new JSONObject().put("type", "PassPriority")));
        return new JSONObject().put("type", "stateUpdate").put("stateVersion", version)
            .put("state", state).put("legalActions", legal).put("interactive", true)
            .put("pendingDecision", JSONObject.NULL);
    }

    private static void require(boolean ok, String why) {
        if (!ok) throw new IllegalStateException(why);
    }

    public static void main(String[] args) {
        ProtocolGuard fine = new ProtocolGuard();
        fine.observe(snapshot(1));
        fine.observe(snapshot(2));
        fine.assertClean();
        require(fine.updates() == 2, "expected two updates");

        ProtocolGuard duplicate = new ProtocolGuard();
        duplicate.observe(snapshot(3));
        duplicate.observe(snapshot(3));
        require(!duplicate.clean(), "duplicate versions must fail");

        ProtocolGuard alien = new ProtocolGuard();
        alien.observe(invalidActor());
        require(!alien.clean(), "alien active player must fail");

        ProtocolGuard noDecision = new ProtocolGuard();
        noDecision.observe(snapshot(1).put("legalActions", new JSONArray()));
        require(!noDecision.clean(), "interactive state with no action must fail");

        ProtocolGuard passive = new ProtocolGuard();
        passive.observe(snapshot(1).put("interactive", false).put("legalActions", new JSONArray()));
        passive.assertClean();

        System.out.println("PROTOCOL GUARD RESULT PASS");
    }

    private static JSONObject invalidActor() {
        JSONObject s = snapshot(1);
        s.getJSONObject("state").put("activePlayerId", "not-a-seat");
        return s;
    }
}
