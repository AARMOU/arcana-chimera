package org.arcanachimera.forgehost;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class BughuntTraceSelfTest {
    private static void check(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }

    private static JSONObject message(int version, String phase, int life) {
        JSONObject cards = new JSONObject().put("secret-card-123",
            new JSONObject().put("name", "SECRET_CARD_NEVER_LOG").put("isTapped", version > 1));
        JSONObject p1 = new JSONObject().put("playerId", "offline-you")
            .put("name", "SECRET_PLAYER_NEVER_LOG").put("life", 20);
        JSONObject p2 = new JSONObject().put("playerId", "offline-ai")
            .put("name", "Opponent").put("life", life);
        JSONObject state = new JSONObject()
            .put("cards", cards).put("players", new JSONArray().put(p1).put(p2))
            .put("zones", new JSONArray()).put("turnNumber", 10)
            .put("currentStep", phase).put("activePlayerId", "offline-ai")
            .put("priorityPlayerId", "offline-you");
        return new JSONObject().put("type", "stateUpdate").put("stateVersion", version)
            .put("state", state).put("interactive", true)
            .put("pendingDecision", new JSONObject().put("type", "SelectCardsDecision")
                .put("prompt", "SECRET_PROMPT_NEVER_LOG"))
            .put("legalActions", new JSONArray());
    }

    public static void main(String[] args) {
        BughuntTrace trace = new BughuntTrace("unit-1", false);
        trace.observe(message(46, "DECLARE_BLOCKERS", 20));
        trace.action(new JSONObject().put("type", "submitAction")
            .put("action", new JSONObject().put("type", "DeclareBlockers")
                .put("cardId", "SECRET_ACTION_NEVER_LOG")));
        trace.observe(message(47, "COMBAT_DAMAGE", 18));
        trace.tick();
        trace.observe(new JSONObject().put("type", "error")
            .put("message", "SECRET_ERROR_NEVER_LOG"));

        List<JSONObject> captured = trace.snapshot();
        check(captured.size() == 5, "expected 5 sanitized events");
        check("state".equals(captured.get(0).optString("kind")), "first state");
        check("action".equals(captured.get(1).optString("kind")), "second action");
        check("DeclareBlockers".equals(captured.get(1).optString("name")), "action name");
        check(captured.get(2).optInt("board_revision") == 2, "public board revision");
        check("error".equals(captured.get(4).optString("kind")), "error is generic");
        for (JSONObject event : captured) {
            String encoded = event.toString();
            check(!encoded.contains("SECRET"), "private data in sanitized trace: " + encoded);
            check(!encoded.contains("cardId"), "card identity key leaked");
            check(event.optString("session").equals("unit-1"), "anonymous session");
        }
        BughuntTrace unknown = new BughuntTrace("unit-2", false);
        unknown.action(new JSONObject().put("type", "submitAction")
            .put("action", new JSONObject().put("type", "SECRET_CUSTOM_ACTION")));
        check("other".equals(unknown.snapshot().get(0).optString("name")), "unexpected action leaked");
        check(trace.dropped() == 0, "unexpected dropped trace events");
        System.out.println("BUGHUNT TRACE RECORDER RESULT PASS");
    }
}
