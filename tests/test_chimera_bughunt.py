"""Read-only Chimera bug-hunt regression tests. All traces are invented synthetic data."""
import importlib.util
import json
import os
import subprocess
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "chimera_bughunt.py"
spec = importlib.util.spec_from_file_location("chimera_bughunt", SCRIPT)
bughunt = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bughunt)


def events(name):
    return bughunt.parse_jsonl(ROOT / "bughunt" / "fixtures" / name)


class SourceAuditTests(unittest.TestCase):
    def test_current_known_gaps_do_not_claim_safety(self):
        result = bughunt.audit(ROOT)
        self.assertTrue(result["passed"], result)
        self.assertGreaterEqual(len(result["checks"]), 8)
        self.assertTrue(all(x["status"] in ("KNOWN_GAP", "REDUCED") for x in result["checks"]))

    def test_new_silent_default_is_a_regression(self):
        baseline = json.loads((ROOT / bughunt.BASELINE).read_text())
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "bughunt").mkdir()
            (root / bughunt.HOST).parent.mkdir(parents=True)
            (root / bughunt.BASELINE).write_text(json.dumps(baseline))
            original = (ROOT / bughunt.HOST).read_text()
            (root / bughunt.HOST).write_text(original + "\nreturn defaultAnswer(m, args);\n")
            result = bughunt.audit(root)
        self.assertFalse(result["passed"])
        self.assertIn("gui_silent_default", [r["id"] for r in result["checks"] if r["status"] == "NEW_RISK"])


class TraceTests(unittest.TestCase):
    def test_progressing_combat_is_not_stall(self):
        report = bughunt.analyze(events("progress-combat.jsonl"), 3000)
        self.assertTrue(report["passed"], report)
        self.assertFalse(report["findings"], report)

    def test_zero_blockers_deadlock_signature(self):
        report = bughunt.analyze(events("stalled-blockers.jsonl"), 3000)
        self.assertFalse(report["passed"])
        self.assertIn("NO_OBSERVABLE_PROGRESS", [f["code"] for f in report["findings"]])
        self.assertEqual(report["last_version"], 47)

    def test_repeated_rejection_fails_even_when_versions_increase(self):
        trace = [{"at_ms": 0, "kind": "state", "version": 1,
                  "turn": 6, "phase": "COMBAT_DAMAGE", "legal": 0}]
        for i in range(4):
            trace += [{"at_ms": 3 * i + 1, "kind": "action", "name": "SubmitDecision"},
                      {"at_ms": 3 * i + 2, "kind": "error"},
                      {"at_ms": 3 * i + 3, "kind": "state", "version": i + 2,
                       "turn": 6, "phase": "COMBAT_DAMAGE", "legal": 0}]
        report = bughunt.analyze(trace)
        self.assertFalse(report["passed"])
        self.assertEqual(sum(f["code"] == "REPEATED_REJECTED_ACTION"
                             for f in report["findings"]), 1)

    def test_rejection_then_corrected_answer_is_not_p0(self):
        trace = [
            {"at_ms": 0, "kind": "state", "version": 1,
             "turn": 6, "phase": "COMBAT_DAMAGE", "legal": 0},
            {"at_ms": 1, "kind": "action", "name": "SubmitDecision"},
            {"at_ms": 2, "kind": "error"},
            {"at_ms": 3, "kind": "state", "version": 2,
             "turn": 6, "phase": "COMBAT_DAMAGE", "legal": 0},
            {"at_ms": 4, "kind": "action", "name": "SubmitDecision"},
            {"at_ms": 5, "kind": "state", "version": 3,
             "turn": 6, "phase": "POSTCOMBAT_MAIN", "legal": 2},
        ]
        self.assertTrue(bughunt.analyze(trace)["passed"])

    def test_rejection_streak_resets_on_progress(self):
        trace = [{"at_ms": 0, "kind": "state", "version": 1,
                  "turn": 5, "phase": "MAIN", "legal": 0}]
        for i in range(4):
            trace.extend([
                {"at_ms": 3 * i + 1, "kind": "action", "name": "SubmitDecision"},
                {"at_ms": 3 * i + 2, "kind": "error"},
                {"at_ms": 3 * i + 3, "kind": "state", "version": i + 2,
                 "turn": 5, "phase": "MAIN" if i % 2 == 0 else "COMBAT",
                 "legal": 0},
            ])
        self.assertTrue(bughunt.analyze(trace)["passed"])

    def test_version_regression_even_without_a_gameplay_stall(self):
        trace = [{"at_ms": 0, "kind": "state", "version": 7, "turn": 1},
                 {"at_ms": 1, "kind": "state", "version": 6, "turn": 2}]
        report = bughunt.analyze(trace)
        self.assertFalse(report["passed"])
        self.assertEqual(report["findings"][0]["code"], "NON_MONOTONIC_VERSION")

    def test_explicit_error_is_not_silently_classified_as_an_ai_stall(self):
        trace = [{"at_ms": 0, "kind": "state", "version": 1, "turn": 1},
                 {"at_ms": 1, "kind": "action", "name": "CastSpell"},
                 {"at_ms": 2, "kind": "error"},
                 {"at_ms": 100_000, "kind": "tick"}]
        report = bughunt.analyze(trace, 3000)
        self.assertTrue(report["passed"], report)
        self.assertEqual([x["code"] for x in report["findings"]], ["ENGINE_REJECTION_OR_ERROR"])

    def test_one_update_does_not_prove_progress(self):
        trace = [{"at_ms": 0, "kind": "state", "version": 10, "turn": 3, "phase": "DECLARE_BLOCKERS"},
                 {"at_ms": 1, "kind": "action", "name": "DeclareBlockers"},
                 {"at_ms": 2, "kind": "state", "version": 11, "turn": 3, "phase": "DECLARE_BLOCKERS"},
                 {"at_ms": 9000, "kind": "tick"}]
        report = bughunt.analyze(trace, 1000)
        self.assertFalse(report["passed"])

    def test_no_future_observation_does_not_invent_a_stall(self):
        trace = [{"at_ms": 0, "kind": "state", "version": 10, "turn": 3},
                 {"at_ms": 1, "kind": "action", "name": "PassPriority"}]
        self.assertTrue(bughunt.analyze(trace)["passed"])

    def test_invalid_trace_fails_closed(self):
        with self.assertRaises(ValueError):
            bughunt.analyze([{"at_ms": 2, "kind": "tick"}, {"at_ms": 1, "kind": "tick"}])
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "bad.jsonl"
            path.write_text("this isn't json\n")
            with self.assertRaises(ValueError):
                bughunt.parse_jsonl(path)

    def test_local_diagnostic_parser_discards_card_names(self):
        text = """#00001  2026-11-08 09:51:35.000  [Engine]  Argentum state v46 applied: 2 players, turn 10 DECLARE BLOCKERS, 1 legal actions
#00002  2026-11-08 09:51:40.000  [Player action]  Declared 0 blocker(s)
#00003  2026-11-08 09:51:40.050  [Engine]  Argentum state v47 applied: 2 players, turn 10 DECLARE BLOCKERS, 1 legal actions
#00004  2026-11-08 09:53:43.000  [App]  Diagnostic export requested
"""
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "private.txt"
            path.write_text(text)
            parsed = bughunt.parse_diagnostic(path)
        self.assertEqual([x["kind"] for x in parsed], ["state", "action", "state", "tick"])
        self.assertFalse(bughunt.analyze(parsed, 10000)["passed"])
        self.assertNotIn("cardId", json.dumps(parsed))




    def test_optin_live_log_parsing_and_session_isolation(self):
        records = [
            {"kind": "state", "at_ms": 0, "session": "one-turn", "version": 3,
             "turn": 2, "phase": "DECLARE_BLOCKERS", "active": "ai",
             "priority": "human", "pending": "none", "legal": 1, "board_revision": 4},
            {"kind": "action", "at_ms": 100, "session": "one-turn", "name": "DeclareBlockers"},
            {"kind": "state", "at_ms": 200, "session": "one-turn", "version": 4,
             "turn": 2, "phase": "COMBAT_DAMAGE", "active": "ai",
             "priority": "ai", "pending": "none", "legal": 0, "board_revision": 5},
            {"kind": "tick", "at_ms": 9000, "session": "scenario-1"},
        ]
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "safe.log"
            p.write_text("other log text\n" + "\n".join(
                "BUGHUNT_EVENT " + json.dumps(r) for r in records
            ) + "\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "multiple independent sessions"):
                bughunt.parse_live_log(p)
            parsed = bughunt.parse_live_log(p, "one-turn")
        self.assertEqual(len(parsed), 3)
        self.assertTrue(bughunt.analyze(parsed, 3000)["passed"])

    def test_live_log_rejects_unexpected_private_fields(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "leak.log"
            p.write_text('BUGHUNT_EVENT {"kind":"tick","at_ms":1,"session":"test",'
                         '"cardName":"NEVER_ALLOWED"}\n')
            with self.assertRaisesRegex(ValueError, "unexpected trace fields"):
                bughunt.parse_live_log(p)

class CommanderSeedTests(unittest.TestCase):
    """Check replay seed validation without fetching/building/running Forge."""

    def run_seed(self, seed=None):
        env = os.environ.copy()
        env.pop("CHIMERA_SEED", None)
        if seed is not None:
            env["CHIMERA_SEED"] = seed
        return subprocess.run(
            ["bash", "scripts/run_four_commander.sh", "--check-seed"],
            cwd=ROOT, env=env, text=True, capture_output=True, check=False
        )

    def test_default_seed_unchanged(self):
        result = self.run_seed()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "8074")

    def test_valid_manual_replay_seeds(self):
        for seed in ("0", "42", "8074", "2147483647"):
            with self.subTest(seed=seed):
                result = self.run_seed(seed)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout.strip(), seed)

    def test_invalid_seeds_fail_before_running_forge(self):
        for seed in ("-1", "01", "abc", "2147483648", "999999999999999999999"):
            with self.subTest(seed=seed):
                result = self.run_seed(seed)
                self.assertEqual(result.returncode, 2)
                self.assertIn("INVALID_SEED", result.stderr)


if __name__ == "__main__":
    unittest.main()
