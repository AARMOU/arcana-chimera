package org.arcanachimera.forgehost;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Opt-in, bounded, privacy-minimized trace of ACTUAL Forge -> Arcana messages.
 *
 * Installed in headless test harnesses only, not in the shipped Android host.
 * It never records card identity, deck, hand, choices, player name/ID,
 * prompt content, error text, network data, or raw gameplay messages.
 * No files or network connections are opened. JVM switch:
 * -Dchimera.bughunt.trace=true to emit BUG-HUNT JSONL to stdout.
 */
public final class BughuntTrace {
    private static final int LIMIT = 2048;
    private static final Set<String> ACTIONS = Set.of(
        "PassPriority", "PlayLand", "CastSpell", "ActivateAbility",
        "DeclareAttackers", "DeclareBlockers", "SubmitDecision",
        "keepHand", "mulligan", "chooseBottomCards", "concede");
    private static final Set<String> PHASES = Set.of(
        "UNTAP", "UPKEEP", "DRAW", "PRECOMBAT_MAIN", "BEGIN_COMBAT",
        "DECLARE_ATTACKERS", "DECLARE_BLOCKERS", "FIRST_STRIKE_COMBAT_DAMAGE",
        "COMBAT_DAMAGE", "END_COMBAT", "POSTCOMBAT_MAIN", "END", "CLEANUP");

    private final long origin = System.nanoTime();
    private final String session;
    private final boolean output;
    private final ArrayList<JSONObject> events = new ArrayList<>();
    private long lastMs;
    private String previousPublicState;
    private int boardRevision;
    private int dropped;

    public BughuntTrace(String session) {
        this(session, Boolean.getBoolean("chimera.bughunt.trace"));
    }

    public BughuntTrace(String session, boolean output) {
        if (!session.matches("[a-z0-9-]{1,32}"))
            throw new IllegalArgumentException("trace session must be an anonymized slug");
        this.session = session;
        this.output = output;
    }

    private long now() {
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - origin);
        lastMs = Math.max(lastMs, ms);
        return lastMs;
    }

    private void record(JSONObject event) {
        event.put("at_ms", now()).put("session", session);
        if (events.size() == LIMIT) { events.remove(0); dropped++; }
        events.add(event);
        if (output) System.out.println("BUGHUNT_EVENT " + event);
    }

    /** Observe the exact message sent by Forge. Do not keep or print the original. */
    public synchronized void observe(JSONObject msg) {
        String kind = msg.optString("type");
        if ("error".equals(kind)) {
            record(new JSONObject().put("kind", "error"));
            return;
        }
        if (!"stateUpdate".equals(kind)) return;
        JSONObject state = msg.optJSONObject("state");
        if (state == null) return; // ProtocolGuard flags malformed states.
        String publicState = publicStateSignature(state);
        if (!publicState.equals(previousPublicState)) {
            boardRevision++;
            previousPublicState = publicState;
        }
        JSONObject d = msg.optJSONObject("pendingDecision");
        String pending = d == null ? "none" : d.optString("type");
        if (!pending.matches("[A-Za-z]{1,40}Decision")) pending = "other";
        JSONArray legal = msg.optJSONArray("legalActions");
        String phase = state.optString("currentStep");
        if (!PHASES.contains(phase)) phase = "other";
        JSONObject e = new JSONObject().put("kind", "state")
            .put("version", msg.optInt("stateVersion", -1))
            .put("turn", state.optInt("turnNumber", -1))
            .put("phase", phase)
            .put("active", role(state.optString("activePlayerId")))
            .put("priority", role(state.optString("priorityPlayerId")))
            .put("pending", pending)
            .put("legal", legal == null ? -1 : legal.length())
            .put("board_revision", boardRevision);
        record(e);
    }

    /** Observe one submitted action BEFORE forwarding it to the live host. */
    public synchronized void action(JSONObject submitted) {
        JSONObject inner = submitted.optJSONObject("action");
        String name = (inner == null ? submitted : inner).optString("type", "");
        record(new JSONObject().put("kind", "action")
            .put("name", ACTIONS.contains(name) ? name : "other"));
    }

    /** An observation at a later time; required to establish a no-progress interval. */
    public synchronized void tick() {
        record(new JSONObject().put("kind", "tick"));
    }

    private static String role(String id) {
        if ("offline-you".equals(id)) return "human";
        if ("offline-ai".equals(id) || id.startsWith("offline-ai-")) return "ai";
        return "other"; // never expose another player's name/identifier.
    }

    /** An internal coarse public-only signature; only revision COUNTS leave this class. */
    private static String publicStateSignature(JSONObject state) {
        StringBuilder b = new StringBuilder();
        JSONArray players = state.optJSONArray("players");
        if (players != null) for (int i = 0; i < players.length(); i++) {
            JSONObject p = players.optJSONObject(i);
            if (p != null) b.append('/').append(p.optInt("life", -1))
                .append(':').append(p.optInt("poisonCounters", -1))
                .append(':').append(p.optBoolean("hasLost", false));
        }
        JSONArray zones = state.optJSONArray("zones");
        if (zones != null) for (int i = 0; i < zones.length(); i++) {
            JSONObject z = zones.optJSONObject(i);
            if (z == null) continue;
            JSONObject zid = z.optJSONObject("zoneId");
            String type = zid == null ? "" : zid.optString("zoneType");
            if (Set.of("Battlefield", "Graveyard", "Exile", "Command", "Stack").contains(type))
                b.append('/').append(type).append(':').append(z.optInt("size", -1));
        }
        JSONObject cards = state.optJSONObject("cards");
        int tapped = 0;
        if (cards != null) {
            Iterator<String> keys = cards.keys();
            while (keys.hasNext()) {
                JSONObject c = cards.optJSONObject(keys.next());
                if (c != null && c.optBoolean("isTapped", false)) tapped++;
            }
        }
        b.append("/tapped:").append(tapped);
        return b.toString();
    }

    /** Already-sanitized, defensive copies; bounded regardless of game duration. */
    public synchronized List<JSONObject> snapshot() {
        ArrayList<JSONObject> out = new ArrayList<>();
        for (JSONObject e : events) out.add(new JSONObject(e.toString()));
        return List.copyOf(out);
    }

    public synchronized int dropped() { return dropped; }
}
