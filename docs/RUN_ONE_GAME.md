# Run one Forge AI Commander game (Experiment 001)

## What is prepared
- [scripts/run_one_commander.sh](../scripts/run_one_commander.sh) is a **manual-only** runner.
- It pins Forge to commit `7c34365c749ca46ab71336c5f267340d6088e600` and builds the real `forge-gui-desktop` jar.
- It runs Forge's documented headless `sim` command: 2 AI players, 1 Commander game, 2 complete precon decks already present in Forge's resource tree (`Buckle Up.dck` and `Counterpunch.dck`), seed 8073, game timeout 300 seconds.
- It saves the Maven build log and AI-game log under ignored `reports/`, and only prints PASS when Forge's own simulator reports a winner with no process error or detected timeout.

## How to execute locally on an owner-controlled Linux computer (no GitHub Actions)

```bash
# In AARMOU/arcana-chimera
java -version
mvn -version
bash scripts/run_one_commander.sh
```

Prerequisites: Git, Java 17 or newer, Maven, Python optional, network access to GitHub/Maven Central, and adequate disk/RAM. Allow 4+ GB Java heap. The shell runner uses `timeout`, a standard Linux command.

Expected successful output:

```
PASS: AI Commander match finished with winner
Game Result: Game 1 ended in ... ms. Ai(...) has won!
```

If it fails, save `reports/commander-*.log`, `reports/commander-build-*.log`, and `reports/commander-*.status.txt` in a private evidence channel or attach sanitized excerpts to Experiment #1. **Do not invent a victory** because Maven built or the simulator printed an inconclusive draw.

## What this does not establish

A headless game with a winner demonstrates a bounded baseline game. It does not prove Forge will support Arcana's 4-player cloud rooms, Android device performance, AI quality, or every card. Those require separate experiments.

## Environment limitations observed

On November 2, 2026 (Mountain time), the assistant's isolated local environment had Java and Git but **no Maven** and **no DNS/network access to github.com**, so it could not clone or compile Forge or run the match locally. This is an execution blocker, not a confirmed Forge source defect.

The first manually triggered GitHub Actions run failed before compilation because Maven cache was configured before Forge was cloned. The owner has since prohibited any workflow that could incur charges. The GitHub Actions workflow was removed from the repository; **do not rerun the historical failed job**. Run only on owner-controlled local hardware with no paid compute services. The repository remains private.
