"""Fast tests for the wrong-pictures-after-the-first-game fix (owner, Nov 21)."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("stale_pictures", ROOT / "scripts/patch_stale_pictures.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def staged(tmp: Path, skip: str | None = None) -> Path:
    src = tmp / "src/main/java/com/arcanatable"
    src.mkdir(parents=True)
    for name, edits in module.EDITS.items():
        body = "\n".join(old for old, _ in edits if name != skip)
        (src / name).write_text("// before\n" + body + "\n// after\n", encoding="utf-8")
    return tmp


class StalePicturesTests(unittest.TestCase):
    def test_every_anchor_replaced_once(self):
        with tempfile.TemporaryDirectory() as d:
            app = staged(Path(d))
            module.patch(app)
            src = app / "src/main/java/com/arcanatable"
            for name, edits in module.EDITS.items():
                text = (src / name).read_text(encoding="utf-8")
                for old, new in edits:
                    self.assertIn(new, text)
            images = (src / "CardImages.kt").read_text(encoding="utf-8")
            self.assertNotIn("cacheFile(url)", images)
            self.assertNotIn("CardBitmapCache.put(url", images)
            self.assertNotIn("artByName(refName, token = ref?.type", (src / "TableArgentum.kt").read_text(encoding="utf-8"))
            with self.assertRaises(ValueError):  # never applied twice
                module.patch(app)

    def test_missing_anchor_writes_nothing(self):
        with tempfile.TemporaryDirectory() as d:
            app = staged(Path(d), skip="ArgentumCardPick.kt")
            before = (app / "src/main/java/com/arcanatable/CardImages.kt").read_text(encoding="utf-8")
            with self.assertRaises(ValueError):
                module.patch(app)
            self.assertEqual(before, (app / "src/main/java/com/arcanatable/CardImages.kt").read_text(encoding="utf-8"))

    def test_build_runs_it_after_the_fallback_chain_patch(self):
        build = (ROOT / "scripts/build_arcana_forge_apk.sh").read_text(encoding="utf-8")
        self.assertIn("patch_stale_pictures.py", build)
        self.assertLess(build.index("patch_arcana_nov9c.py"), build.index("patch_stale_pictures.py"))


if __name__ == "__main__":
    unittest.main()
