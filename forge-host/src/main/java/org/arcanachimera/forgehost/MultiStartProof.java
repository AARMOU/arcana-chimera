package org.arcanachimera.forgehost;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.json.JSONObject;

/**
 * Phone report, Nov 11 2026: internet Commander, host + one friend + two AIs. The host kept its hand and then sat on
 * the loading screen for over a minute while the friend's phone reached the table.
 *
 * The opening-hand screen closes only at a person's first table state. This proof checks, for both the host and the
 * guest, that the first table state they get is from turn 1: they see the game from its start instead of waiting
 * (on the loading screen) until a person happens to be asked something, which on AI turns can be minutes later.
 *
 * Decks: 99 Forest + Ghalta, so AI turns are silent (nothing to cast) and the result doesn't depend on AI speed.
 * Several games, so the coin toss gives AI seats the first turn in some of them. Guest in seat 1 or seat 3.
 */
public final class MultiStartProof {
    private static final String COMMANDER = "Ghalta, Primal Hunger";

    public static void main(String[] args) throws Exception {
        String assetsDir = args.length > 0 ? args[0] : "../forge-gui/";
        int games = args.length > 1 ? Integer.parseInt(args[1]) : 8;
        List<String> failures = new ArrayList<>();
        for (int g = 0; g < games; g++) {
            int guestSeat = g % 2 == 0 ? 1 : 3;
            String result = play(assetsDir, guestSeat);
            System.out.println("MULTISTART game " + (g + 1) + " guest seat " + guestSeat + ": " + result);
            if (result.startsWith("FAIL")) failures.add("game " + (g + 1) + ": " + result);
        }
        if (!failures.isEmpty()) {
            System.out.println("MULTISTART FAILED " + failures.size() + " of " + games);
            System.exit(1);
        }
        System.out.println("MULTISTART PASS " + games + " games: host and guest each saw the table from turn 1");
        System.exit(0);
    }

    private static final class View {
        final String id;
        volatile int firstStateTurn = -1;
        volatile String firstStateActive = "";
        volatile boolean kept;
        volatile String error = "";
        View(String id) { this.id = id; }
    }

    private static String play(String assetsDir, int guestSeat) throws Exception {
        List<String> deck = new ArrayList<>();
        for (int i = 0; i < 99; i++) deck.add("Forest");
        List<ArcanaForgeHost.Seat> seats = new ArrayList<>();
        for (int i = 0; i < 4; i++) seats.add(new ArcanaForgeHost.Seat(i == 0 ? "Host" : "AI " + i, deck, COMMANDER));
        String guestId = guestSeat == 1 ? ArcanaForgeHost.AI_ID : ArcanaForgeHost.AI_ID + "-" + guestSeat;

        ScheduledExecutorService people = Executors.newSingleThreadScheduledExecutor();
        View hostView = new View(ArcanaForgeHost.HUMAN_ID), guestView = new View(guestId);
        ArcanaForgeHost[] hostRef = new ArcanaForgeHost[1];

        // A person: keeps their hand after a short think (phones take seconds), records their first table state.
        java.util.function.Function<View, Consumer<JSONObject>> person = v -> m -> {
            String type = m.optString("type");
            if (type.equals("error")) v.error = m.optString("message");
            if (type.equals("mulliganDecision")) people.schedule(() -> {
                v.kept = true;
                hostRef[0].handleFrom(v.id, new JSONObject().put("type", "keepHand"));
            }, 1500, TimeUnit.MILLISECONDS);
            if (type.equals("stateUpdate") && v.firstStateTurn < 0) {
                JSONObject st = m.optJSONObject("state");
                if (st != null) {
                    v.firstStateActive = st.optString("activePlayerId");
                    v.firstStateTurn = st.optInt("turnNumber", -1);
                }
            }
        };

        ArcanaForgeHost host = new ArcanaForgeHost(person.apply(hostView));
        hostRef[0] = host;
        host.aiStrength = ArcanaForgeHost.AiStrength.OFF;
        host.prepare(seats, () -> assetsDir);
        host.addGuest(guestId, new ArcanaForgeHost.Seat("Friend", deck, COMMANDER), person.apply(guestView));
        host.handle(new JSONObject().put("type", "createQuickGameLobby"));
        try {
            long deadline = System.currentTimeMillis() + 180_000;
            int gameTurn = 0;
            String starter = "";
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
                forge.game.Game game = host.gameForTest();
                if (game != null) {
                    int t = game.getPhaseHandler().getTurn();
                    if (t >= 1 && starter.isEmpty() && game.getPhaseHandler().getPlayerTurn() != null)
                        starter = game.getPhaseHandler().getPlayerTurn().getName();
                    gameTurn = Math.max(gameTurn, t);
                }
                if (hostView.firstStateTurn >= 0 && guestView.firstStateTurn >= 0) break;
                if (gameTurn >= 6) break; // well past both people's first turn
            }
            String who = "first turn: " + (starter.isEmpty() ? "?" : starter) + "; game reached turn " + gameTurn;
            for (View v : List.of(hostView, guestView)) {
                if (!v.error.isEmpty() && v.error.startsWith("Forge host problem")) return "FAIL " + v.id + " host error: " + v.error;
                if (!v.kept) return "FAIL " + v.id + " was never offered an opening hand (" + who + ")";
                if (v.firstStateTurn < 0)
                    return "FAIL " + v.id + " kept but never got a table state; stuck on the opening-hand/loading screen (" + who + ")";
                if (v.firstStateTurn > 1) // turn 0 is an opening-hand question (Leylines); still the start
                    return "FAIL " + v.id + " first saw the table on turn " + v.firstStateTurn
                            + " (active " + v.firstStateActive + "): stuck on the loading screen through earlier turns (" + who + ")";
            }
            return "ok, both saw turn 1 (" + who + ")";
        } finally {
            people.shutdownNow();
            host.shutdown();
        }
    }
}
