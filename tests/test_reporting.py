import importlib.util
import tempfile
import unittest
from pathlib import Path

REPORTER = Path(__file__).resolve().parents[1] / "scripts" / "collect_maven_results.py"
spec = importlib.util.spec_from_file_location("report", REPORTER)
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)


class ReportingTests(unittest.TestCase):
    def test_surefire_counts(self):
        with tempfile.TemporaryDirectory() as tmp:
            location = Path(tmp) / "forge-game" / "target" / "surefire-reports"
            location.mkdir(parents=True)
            (location / "TEST-example.xml").write_text('<testsuite tests="4" failures="1" errors="0" skipped="1"/>')
            result = report.summarize(Path(tmp))
            self.assertEqual(result["tests"], 4)
            self.assertEqual(result["failures"], 1)
            self.assertEqual(result["skipped"], 1)

    def test_empty_is_not_a_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = report.summarize(Path(tmp))
            self.assertEqual(result["tests"], 0)
            self.assertEqual(result["reports"], 0)

    def test_corrupt_xml_is_recorded(self):
        with tempfile.TemporaryDirectory() as tmp:
            location = Path(tmp) / "forge-ai" / "target" / "surefire-reports"
            location.mkdir(parents=True)
            (location / "TEST-broken.xml").write_text("<testsuite")
            result = report.summarize(Path(tmp))
            self.assertEqual(len(result["invalid_reports"]), 1)


if __name__ == "__main__":
    unittest.main()
