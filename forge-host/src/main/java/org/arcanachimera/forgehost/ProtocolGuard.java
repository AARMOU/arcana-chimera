package org.arcanachimera.forgehost;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Read-only contract guard for actual Forge -> Arcana messages in headless tests.
 * No snapshots or private card identities are stored. Observational only: never
 * supplies an answer to Forge, never changes the rules, and never touches Android.
 *
 * Deliberately does NOT assert that a legal state has advanced by a fixed timeout:
 * Forge may legitimately wait for a user. CI scenario drivers own their deadlines.
 */
public final class ProtocolGuard {
    private int lastVersion;
    private int updates;
    private final List<String> failures = new ArrayList<>();

    public synchronized void observe(JSONObject message) {
        if (!"stateUpdate".equals(message.optString("type"))) return;
        updates++;
        int v = message.optInt("stateVersion", -1);
        if (v <= lastVersion) fail("nonmonotonic stateVersion " + v + " after " + lastVersion);
        if (v <= 0) fail("missing/invalid stateVersion");
        lastVersion = Math.max(lastVersion, v);
        JSONObject state = message.optJSONObject("state");
        if (state == null) { fail("missing state at v" + v); return; }
        JSONArray players = state.optJSONArray("players");
        if (players == null || players.length() < 2 || players.length() > 4) {
            fail("expected 2-4 players at v" + v);
            return;
        }
        Set<String> playerIds = new HashSet<>();
        for (int i = 0; i < players.length(); i++) {
            JSONObject p = players.optJSONObject(i);
            String id = p == null ? "" : p.optString("playerId", "");
            if (id.isBlank() || !playerIds.add(id)) fail("missing/duplicate player id at v" + v);
        }
        for (String field : List.of("activePlayerId", "priorityPlayerId")) {
            if (!state.isNull(field) && !playerIds.contains(state.optString(field)))
                fail(field + " not among seats at v" + v);
        }
        JSONArray legal = message.optJSONArray("legalActions");
        if (legal == null) { fail("missing legalActions at v" + v); return; }
        for (int i = 0; i < legal.length(); i++) {
            JSONObject action = legal.optJSONObject(i);
            if (action == null || action.optString("actionType").isBlank() || action.optJSONObject("action") == null)
                fail("malformed legal action at v" + v + " index " + i);
        }
        JSONObject decision = message.optJSONObject("pendingDecision");
        if (decision != null) {
            if (decision.optString("id").isBlank() || decision.optString("type").isBlank())
                fail("pendingDecision without identity/type at v" + v);
            if (!message.optBoolean("interactive"))
                fail("noninteractive pendingDecision at v" + v);
        }
        if (message.optBoolean("interactive") && decision == null && legal.length() == 0)
            fail("interactive but no legal action or decision at v" + v);
    }

    private void fail(String reason) {
        if (failures.size() < 50) failures.add(reason);
    }

    public synchronized boolean clean() { return failures.isEmpty(); }
    public synchronized int updates() { return updates; }
    public synchronized List<String> failures() { return List.copyOf(failures); }

    public synchronized void assertClean() {
        if (!clean()) throw new IllegalStateException(
                "ProtocolGuard found " + failures.size() + " contract failure(s): " + String.join("; ", failures));
        if (updates == 0) throw new IllegalStateException("ProtocolGuard saw zero state updates");
    }
}
