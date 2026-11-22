#!/usr/bin/env python3
"""Chimera-only: crash history, memory in the log, and a deck editor that can't crash the app (owner, Nov 21).

Owner's report: an out-of-memory crash in the deck editor during an evening of offline Forge games, where memory sat
at 480-511 of the phone's 512 MB from turn 1. The report kept only that one crash.

* Every crash (this app and the separate Forge game process) goes into ChimeraCrashLog; the report lists the newest.
* "Application opened" shows memory in use, and every minute of a game logs it again.
* The deck editor checks there is room before building its card search list and says so instead of crashing.

Fail-closed: every anchor must match exactly once; nothing is written unless all match.
Apply to an ephemeral staged Arcana donor (after ChimeraCrashLog.kt is copied in), NEVER to arcana_table.
"""
from pathlib import Path
import sys

MARK = "ChimeraCrashLog"

EDITS = {
    "MainActivity.kt": [
        ('''        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
''', '''        ChimeraCrashLog.install(this, "App") // owner, Nov 21: keep every crash, not only the last
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
'''),
    ],
    "TableDiagnostics.kt": [
        ('''        appendLine("Last uncaught app crash:")
        appendLine(DiagnosticRedactor.redact(crash))
    }
''', '''        appendLine("Last uncaught app crash:")
        appendLine(DiagnosticRedactor.redact(crash))
    }
    // Owner, Nov 21: every recent crash, from the app and from the Forge game process, with memory in use.
    ChimeraCrashLog.entries(context).takeIf { it.isNotEmpty() }?.let { crashes ->
        appendLine()
        appendLine("Crash history (newest first; app and Forge game process):")
        crashes.forEach { appendLine(DiagnosticRedactor.redact(it.take(3_000))); appendLine("-----") }
    }
    appendLine("Memory now: app ${ChimeraCrashLog.memoryLine()}")
'''),
    ],
    "TableEffects.kt": [
        ('''        recordDiagnostic("Application opened")
        while (true) {
            delay(1_000)
''', '''        recordDiagnostic("Application opened · app memory ${ChimeraCrashLog.memoryLine()}")
        var memoryTick = 0
        while (true) {
            delay(1_000)
            // Owner, Nov 21: memory once a minute during a game, so a report shows it climbing before a crash.
            if (argentumEnabled && ++memoryTick % 60 == 0) recordDiagnostic("Memory · app ${ChimeraCrashLog.memoryLine()}")
'''),
    ],
    "DeckBuilderScreen.kt": [
        ('''    var loadFailed by remember { mutableStateOf(false) }
''', '''    var loadFailed by remember { mutableStateOf(false) }
    var lowMemory by remember { mutableStateOf(false) }
'''),
        ('''        else { catalog = loaded; library = withContext(Dispatchers.Default) { CardLibrary(loaded) } }
''', '''        else {
            catalog = loaded
            // Owner, Nov 21: building the search list ran the app out of memory once. Too little room: say so.
            library = withContext(Dispatchers.Default) {
                if (ChimeraCrashLog.headroomMb() < 96) System.gc()
                if (ChimeraCrashLog.headroomMb() < 96) null
                else try { CardLibrary(loaded) } catch (oom: OutOfMemoryError) { null }
            }
            if (library == null) lowMemory = true
        }
'''),
        ('''                    if (loadFailed) StudioText("DeckBuilderScreen.Text.49", "Decks", false, "This build's card library couldn't be loaded.", color = CounterGold)
''', '''                    if (loadFailed || lowMemory) StudioText("DeckBuilderScreen.Text.49", "Decks", false,
                        if (lowMemory) "Not enough memory for the card library right now. Finish or close the game, then open the deck editor again."
                        else "This build's card library couldn't be loaded.", color = CounterGold)
'''),
    ],
}


def patch(app: Path) -> None:
    src = app / "src/main/java/com/arcanatable"
    if not (src / "ChimeraCrashLog.kt").is_file():
        raise ValueError("ChimeraCrashLog.kt must be copied in before patch_memory_safety.py")
    if MARK in (src / "MainActivity.kt").read_text(encoding="utf-8"):
        raise ValueError("memory-safety patch already applied")
    staged = {}
    for name, edits in EDITS.items():
        text = (src / name).read_text(encoding="utf-8")
        for old, new in edits:
            count = text.count(old)
            if count != 1:
                raise ValueError(f"{name}: memory-safety anchor found {count} times, expected 1: {old[:70]!r}")
            text = text.replace(old, new)
        staged[name] = text
    for name, text in staged.items():
        (src / name).write_text(text, encoding="utf-8")
    print("Crash history and memory in the diagnostic log; deck editor checks memory first")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_memory_safety.py STAGED_ANDROID_APP")
    patch(Path(sys.argv[1]))
