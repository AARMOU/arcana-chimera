package org.arcanachimera.forgehost;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Bounded FOUR-SEAT Commander start/handoff smoke through ArcanaForgeHost (by Goose, PR #17).
 *
 * Unlike Forge's standalone AI-vs-AI simulator, this uses the *real Chimera
 * adapter contract*: createQuickGameLobby, mulliganDecision, stateUpdate and
 * submitAction. Deliberately tests just one turn cycle, NOT full-game rules,
 * card diversity, network play or on-phone speed.
 *
 * Decks: synthetic, legal-size mono-green Commander (99 Forest + Ghalta).
 * Never sends human card identities or real user deck information to a log.
 */
public final class FourSeatProof {
    private static final String COMMANDER = "Ghalta, Primal Hunger";
    private static final String HUMAN = ArcanaForgeHost.HUMAN_ID;
    private static final List<String> IDS = List.of(
        ArcanaForgeHost.HUMAN_ID, ArcanaForgeHost.AI_ID,
        ArcanaForgeHost.AI_ID + "-2", ArcanaForgeHost.AI_ID + "-3");

    private static JSONObject action(JSONArray legal, String type) {
        for (int i = 0; i < legal.length(); i++) {
            JSONObject a = legal.optJSONObject(i);
            if (a != null && type.equals(a.optString("actionType")))
                return a.optJSONObject("action");
        }
        return null;
    }

    private static void send(ArcanaForgeHost host, JSONObject act) {
        host.handle(new JSONObject().put("type", "submitAction").put("action", act));
    }

    private static void require(boolean ok, String reason) {
        if (!ok) throw new IllegalStateException(reason);
    }

    public static void main(String[] args) throws Exception {
        final String assetsDir = args.length > 0 ? args[0] : "../forge-gui/";
        final LinkedBlockingQueue<JSONObject> inbox = new LinkedBlockingQueue<>();
        final ProtocolGuard guard = new ProtocolGuard();
        final ArcanaForgeHost host = new ArcanaForgeHost(m -> {
            guard.observe(m);
            inbox.offer(m);
        });

        List<String> mainDeck = new ArrayList<>();
        for (int i = 0; i < 99; i++) mainDeck.add("Forest");
        List<ArcanaForgeHost.Seat> seats = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            seats.add(new ArcanaForgeHost.Seat(i == 0 ? "Human" : "AI " + i, mainDeck, COMMANDER));

        host.prepare(seats, () -> assetsDir);
        host.handle(new JSONObject().put("type", "createQuickGameLobby"));

        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(4);
        int updates = 0, submitted = 0, maxTurn = 0, mulligans = 0, cleanupChoices = 0;
        Set<String> answeredDecisions = new LinkedHashSet<>();
        boolean lobby = false, started = false, sawHuman = false, gotBackToHuman = false;
        Set<String> observedActors = new LinkedHashSet<>();
        try {
            while (System.nanoTime() < deadline) {
                JSONObject m = inbox.poll(4, TimeUnit.SECONDS);
                if (m == null) continue;
                String type = m.optString("type");
                if ("error".equals(type))
                    throw new IllegalStateException("Forge host error during 4-seat proof: " + m.optString("message"));
                if ("quickGameLobbyState".equals(type)) {
                    lobby = true;
                    require(m.getJSONArray("players").length() == 4, "lobby did not expose 4 seats");
                    continue;
                }
                if ("gameStarted".equals(type)) {
                    started = true;
                    require(m.getJSONArray("players").length() == 4, "game did not expose 4 seats");
                    continue;
                }
                if ("mulliganDecision".equals(type)) {
                    mulligans++;
                    JSONObject keep = new JSONObject().put("type", "keepHand");
                    host.handle(keep);
                    continue;
                }
                if ("gameOver".equals(type)) throw new IllegalStateException("game ended before 4-seat handoff");
                if (!"stateUpdate".equals(type)) continue;
                updates++;
                JSONObject state = m.getJSONObject("state");
                require(state.getJSONArray("players").length() == 4, "state lost a Commander seat");
                Set<String> ids = new LinkedHashSet<>();
                JSONArray players = state.getJSONArray("players");
                for (int i = 0; i < players.length(); i++) {
                    JSONObject p = players.getJSONObject(i);
                    ids.add(p.optString("playerId"));
                }
                require(ids.containsAll(IDS), "wrong seat identities: " + ids);
                String active = state.optString("activePlayerId");
                if (IDS.contains(active)) observedActors.add(active);
                int turn = state.optInt("turnNumber", -1);
                maxTurn = Math.max(maxTurn, turn);
                boolean interactive = m.optBoolean("interactive");
                if (!interactive) continue;
                JSONObject decision = m.optJSONObject("pendingDecision");
                if (decision != null) {
                    if (!answeredDecisions.add(decision.optString("id"))) continue;
                    require("SelectCardsDecision".equals(decision.optString("type")),
                        "unexpected human decision: " + decision.optString("type"));
                    JSONArray choices = decision.optJSONArray("options");
                    int min = decision.optInt("minSelections", -1);
                    require(choices != null && min > 0 && choices.length() >= min,
                        "invalid cleanup-discard selection from Forge");
                    JSONArray picked = new JSONArray();
                    for (int i = 0; i < min; i++) picked.put(choices.get(i));
                    JSONObject response = new JSONObject().put("type", "CardsSelectedResponse")
                        .put("decisionId", decision.getString("id"))
                        .put("selectedCards", picked);
                    send(host, new JSONObject().put("type", "SubmitDecision")
                        .put("playerId", HUMAN).put("response", response));
                    cleanupChoices++;
                    submitted++;
                    continue;
                }
                JSONArray legal = m.optJSONArray("legalActions");
                require(legal != null && legal.length() > 0, "interactive state has no actions");
                if (HUMAN.equals(active)) {
                    if (sawHuman && turn >= 5) {
                        gotBackToHuman = true;
                        break;
                    }
                    sawHuman = true;
                }
                JSONObject selected = action(legal, "DeclareAttackers");
                if (selected != null) {
                    selected = new JSONObject(selected.toString()).put("attackers", new JSONObject());
                } else {
                    selected = action(legal, "DeclareBlockers");
                    if (selected != null) selected = new JSONObject(selected.toString()).put("blockers", new JSONObject());
                    else selected = action(legal, "PassPriority");
                }
                require(selected != null, "no supported pass/combat declaration at turn " + turn);
                send(host, selected);
                submitted++;
            }

            guard.assertClean();
            require(lobby && started, "lobby/start event missing");
            require(mulligans >= 1, "no human opening-hand decision");
            require(sawHuman && gotBackToHuman, "four-seat turn cycle did not return to human"
                + " (last turn=" + maxTurn + ", actors=" + observedActors + ")");
            require(submitted >= 2, "no meaningful human handoff actions");
            require(cleanupChoices >= 1, "anonymous cleanup discard was not exposed as an Arcana decision");
            System.out.println("FOUR-SEAT ADAPTER RESULT PASS: four Commander seats, "
                + "one mulligan handoff, " + cleanupChoices + " cleanup decision(s), "
                + submitted + " human action(s), "
                + updates + " snapshots; return at turn " + maxTurn
                + "; actors observed " + observedActors);
        } finally {
            host.shutdown();
        }
    }
}
