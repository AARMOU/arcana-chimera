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
 * Decks: 99 Forest + Ghalta, so AI turns are silent (nothing to cast). Headless AI turns take milliseconds, a phone's
 * take seconds: the host's phase-stop lookup (asked on Forge's game thread at each step) waits 400 ms, so AI turns
 * last about as long as on a phone without asking anyone anything. A person must get their first table state within
 * 1.5 s of turn 1 starting, whoever's turn it is. Guest in seat 1 or seat 3; the coin toss varies who starts.
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
        volatile long firstStateAt;
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
                    v.firstStateAt = System.currentTimeMillis();
                    v.firstStateTurn = st.optInt("turnNumber", -1);
                }
            }
        };

        java.util.function.Supplier<java.util.Set<String>> stopsBefore = ArcanaForgeHost.phaseStops;
        ArcanaForgeHost.phaseStops = () -> { // phone-speed AI turns, no prompts (see class comment)
            try { Thread.sleep(400); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return java.util.Set.of();
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
            long turnOneAt = 0;
            String starter = "";
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
                forge.game.Game game = host.gameForTest();
                if (game != null) {
                    int t = game.getPhaseHandler().getTurn();
                    if (t >= 1 && turnOneAt == 0) turnOneAt = System.currentTimeMillis();
                    if (t == 1 && starter.isEmpty() && game.getPhaseHandler().getPlayerTurn() != null)
                        starter = game.getPhaseHandler().getPlayerTurn().getName();
                    gameTurn = Math.max(gameTurn, t);
                }
                if (hostView.firstStateTurn >= 0 && guestView.firstStateTurn >= 0) break;
                if (gameTurn >= 6) break; // well past both people's first turn
                if (turnOneAt > 0 && System.currentTimeMillis() - turnOneAt > 60_000) break;
            }
            String who = "first turn: " + (starter.isEmpty() ? "?" : starter) + "; game reached turn " + gameTurn;
            for (View v : List.of(hostView, guestView)) {
                if (!v.error.isEmpty() && v.error.startsWith("Forge host problem")) return "FAIL " + v.id + " host error: " + v.error;
                if (!v.kept) return "FAIL " + v.id + " was never offered an opening hand (" + who + ")";
                if (v.firstStateTurn < 0)
                    return "FAIL " + v.id + " kept but never got a table state; stuck on the opening-hand/loading screen (" + who + ")";
                long lag = v.firstStateAt - turnOneAt;
                if (lag > 1500)
                    return "FAIL " + v.id + " got its first table state " + lag + " ms after turn 1 began (on turn "
                            + v.firstStateTurn + ", active " + v.firstStateActive + "): on the loading screen meanwhile (" + who + ")";
            }
            return "ok, host " + (hostView.firstStateAt - turnOneAt) + " ms, guest " + (guestView.firstStateAt - turnOneAt)
                    + " ms after turn 1 began (" + who + ")";
        } finally {
            people.shutdownNow();
            host.shutdown();
            ArcanaForgeHost.phaseStops = stopsBefore;
        }
    }
}
