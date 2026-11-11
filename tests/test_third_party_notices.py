import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import third_party_notices as tpn  # noqa: E402
import write_source_access as wsa  # noqa: E402

REVIEWED = tpn.load_reviewed(ROOT / "third_party_licenses/reviewed.json")
TEXTS = ROOT / "third_party_licenses/texts"


def jar(folder, name, notice=None):
    with zipfile.ZipFile(Path(folder) / name, "w") as z:
        z.writestr("a/B.class", "x")
        if notice:
            z.writestr("META-INF/NOTICE.txt", notice)


class NoticesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_reviewed_set_produces_notices_with_texts_and_notice_files(self):
        jar(self.dir, "netty-common-4.1.115.Final.jar", "The Netty Project NOTICE")
        jar(self.dir, "forge-game-2.0.16-SNAPSHOT.jar")
        jar(self.dir, "jgrapht-core-1.5.2.jar")
        text, report = tpn.build([self.dir], REVIEWED, TEXTS)
        self.assertIn("The Netty Project NOTICE", text)
        self.assertIn("Apache License", text)
        self.assertIn("GNU LESSER GENERAL PUBLIC LICENSE", text)
        self.assertEqual(report["jars"]["jgrapht-core-1.5.2.jar"], "LGPL-2.1-or-later")
        self.assertEqual(report["jar_count"], 3)

    def test_cddl_family_stops_the_build(self):
        for name in ("org.jupnp-3.0.5.jar", "jetty-server-9.4.57.v20241219.jar", "javax.servlet-api-3.1.0.jar"):
            with tempfile.TemporaryDirectory() as d:
                jar(d, name)
                with self.assertRaisesRegex(ValueError, "must not ship"):
                    tpn.build([d], REVIEWED, TEXTS)

    def test_unreviewed_jar_stops_the_build(self):
        jar(self.dir, "mystery-lib-1.0.jar")
        with self.assertRaisesRegex(ValueError, "no reviewed licence entry"):
            tpn.build([self.dir], REVIEWED, TEXTS)

    def test_gradle_groups_are_listed_and_unknown_ones_reported(self):
        jar(self.dir, "gson-2.13.2.jar")
        report_file = self.dir / "runtime.txt"
        report_file.write_text(
            "+--- androidx.compose.ui:ui -> 1.9.0\n"
            "|    \\--- org.jetbrains.kotlin:kotlin-stdlib:2.2.0 (*)\n"
            "+--- org.slf4j:slf4j-api:2.0.18\n"
            "\\--- com.example.unknown:thing:1.0\n", encoding="utf-8")
        text, report = tpn.build([self.dir], REVIEWED, TEXTS, report_file)
        self.assertIn("androidx.compose.ui:ui:1.9.0", text)
        self.assertIn("org.slf4j:slf4j-api:2.0.18", text)
        self.assertEqual(report["unreviewed_gradle_coordinates"], ["com.example.unknown:thing"])
        self.assertIn("Licence not yet reviewed", text)

    def test_every_referenced_licence_text_exists(self):
        for entry in REVIEWED["jars"] + REVIEWED["gradle_groups"]:
            for t in entry.get("texts", []):
                self.assertTrue((TEXTS / t).stat().st_size > 0, t)


class SourceAccessTest(unittest.TestCase):
    def test_last_version_name_wins_and_url_rules(self):
        gradle = 'versionName = "0.34.2"\nandroid { defaultConfig {\n        versionName = "0.35.0-chimera-150"\n}}'
        self.assertEqual(wsa.effective_version_name(gradle), "0.35.0-chimera-150")
        self.assertEqual(wsa.source_url({}), "")
        self.assertEqual(wsa.source_url({"GITHUB_RUN_ID": "7", "GITHUB_REPOSITORY": "o/r"}),
                         "https://github.com/o/r/actions/runs/7")
        self.assertEqual(wsa.source_url({"CHIMERA_SOURCE_URL": "https://e.org/x", "GITHUB_RUN_ID": "7",
                                         "GITHUB_REPOSITORY": "o/r"}), "https://e.org/x")
        with self.assertRaises(ValueError):
            wsa.source_url({"CHIMERA_SOURCE_URL": "http://e.org/x"})
        self.assertIn("UNPUBLISHED", wsa.record("v1", ""))
        self.assertEqual(wsa.record("v1", "https://e.org/x"),
                         "SOURCE_VERSION_NAME=v1\nSOURCE_URL=https://e.org/x\n")


if __name__ == "__main__":
    unittest.main()
