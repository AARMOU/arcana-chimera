package org.arcanachimera.forgehost;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.management.ObjectName;
import org.json.JSONObject;

/**
 * Owner, Nov 21: offline Forge games sat at 480-511 of the phone's 512 MB from turn 1, and the app ran out of memory.
 * What does Forge keep? This loads Forge the way the phone does (card scripts on demand), measures the memory still in
 * use after a full garbage collection, prints the biggest kinds of objects, then starts a four-player Commander game
 * and measures again. Report only: it fails nothing. Run with -Xmx512m to match the phone's limit.
 *
 * Usage: ForgeMemoryProbe FORGE_GUI_DIR
 */
public final class ForgeMemoryProbe {
    private static long usedMbAfterGc() {
        for (int i = 0; i < 3; i++) { System.gc(); try { Thread.sleep(200); } catch (InterruptedException ignored) { } }
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    }

    private static void histogram(String title, int rows) {
        System.out.println("---- " + title + ": biggest kinds of objects (count, bytes, class) ----");
        try {
            Object out = ManagementFactory.getPlatformMBeanServer().invoke(
                new ObjectName("com.sun.management:type=DiagnosticCommand"), "gcClassHistogram",
                new Object[] { new String[0] }, new String[] { String[].class.getName() });
            String[] lines = String.valueOf(out).split("\n");
            for (int i = 0; i < Math.min(lines.length, rows + 3); i++) System.out.println(lines[i]);
        } catch (Exception e) {
            System.out.println("(histogram unavailable: " + e + ")");
        }
    }

    public static void main(String[] args) throws Exception {
        String assetsDir = args.length > 0 ? args[0] : "../forge-gui/";
        long max = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        System.out.println("MEMORY PROBE · heap limit " + max + " MB · before Forge: " + usedMbAfterGc() + " MB");

        ArcanaForgeHost.lazyCards = true; // as on the phone
        LinkedBlockingQueue<JSONObject> inbox = new LinkedBlockingQueue<>();
        ArcanaForgeHost host = new ArcanaForgeHost(inbox::offer);
        long t0 = System.nanoTime();
        ArcanaForgeHost.initForge(assetsDir, host);
        System.out.println("MEMORY PROBE · Forge loaded in " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
            + " ms · in use after GC: " + usedMbAfterGc() + " MB");
        histogram("after loading Forge", 30);

        List<String> deck = new ArrayList<>();
        for (int i = 0; i < 99; i++) deck.add(i % 3 == 0 ? "Forest" : i % 3 == 1 ? "Llanowar Elves" : "Giant Growth");
        List<ArcanaForgeHost.Seat> seats = new ArrayList<>();
        for (int i = 0; i < 4; i++) seats.add(new ArcanaForgeHost.Seat(i == 0 ? "Human" : "AI " + i, deck, "Ghalta, Primal Hunger"));
        host.prepare(seats, () -> assetsDir);
        host.handle(new JSONObject().put("type", "createQuickGameLobby"));
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
        int states = 0;
        while (System.nanoTime() < deadline && states < 3) {
            JSONObject m = inbox.poll(4, TimeUnit.SECONDS);
            if (m == null) continue;
            String type = m.optString("type");
            if ("mulliganDecision".equals(type)) host.handle(new JSONObject().put("type", "keepHand"));
            if ("stateUpdate".equals(type)) states++;
            if ("error".equals(type)) { System.out.println("MEMORY PROBE · game error: " + m.optString("message")); break; }
        }
        System.out.println("MEMORY PROBE · four-player game started (" + states + " table states) · in use after GC: "
            + usedMbAfterGc() + " MB of " + max + " MB");
        histogram("during a four-player game", 30);
        host.shutdown();
        System.out.println("MEMORY PROBE · after the game ended · in use after GC: " + usedMbAfterGc() + " MB");
        System.exit(0);
    }
}
