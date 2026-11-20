#!/usr/bin/env python3
"""Move Chimera legal access from play/home shortcuts into Options > Help.

Only edits the temporary assembled donor tree. Never changes Arcana's Git repo.
Fails closed if its exact expected UI anchors drift.
"""
import sys
from pathlib import Path


def replace_once(path: Path, old: str, new: str):
    data = path.read_text(encoding="utf-8")
    if data.count(old) != 1:
        raise RuntimeError(
            f"{path.name}: expected one legal-navigation anchor, found {data.count(old)}"
        )
    path.write_text(data.replace(old, new), encoding="utf-8")


def patch(app: Path):
    src = app / "src/main/java/com/arcanatable"
    features = src / "ChimeraFeatures.kt"
    xp = src / "ChimeraXp.kt"
    skins = src / "ChimeraSkins.kt"
    settings = src / "TableDialogs.kt"
    legal = src / "ChimeraLegal.kt"

    # Only the Options menu retains the navigation item.
    replace_once(features, '''    Text("Licenses & source code", color = SoftWhite, fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().clickable { ChimeraLegal.open = true }
            .padding(vertical = 12.dp).testTag("chimera-license-settings"))
''', "")
    replace_once(features, '''        listOf("DECK STATS" to { ChimeraFeatures.statsOpen = true }, "HISTORY" to { ChimeraFeatures.historyOpen = true },
            "LICENSES" to { ChimeraLegal.open = true }).forEach { (label, open) ->''',
        '''        listOf("DECK STATS" to { ChimeraFeatures.statsOpen = true }, "HISTORY" to { ChimeraFeatures.historyOpen = true }).forEach { (label, open) ->''')
    replace_once(xp,
        '''                    "Licenses & source" to { ChimeraLegal.open = true },
''', "")
    replace_once(skins,
        '''            DropdownMenuItem(text = { Text("Licenses & source") }, onClick = { open = false; ChimeraLegal.open = true })
''', "")
    replace_once(settings,
        '''                LinkRow("Learn & tutorials", "20 chapters and a Magic glossary · works offline", onClick = onTutorials)
''',
        '''                LinkRow("Learn & tutorials", "20 chapters and a Magic glossary · works offline", onClick = onTutorials)
                LinkRow("Licenses & source code", "GPLv3, notices, and corresponding source",
                    modifier = Modifier.testTag("chimera-license-settings"),
                    onClick = { ChimeraLegal.open = true })
''')
    replace_once(legal,
        '''            "The build is not approved for distribution merely because these notices appear."''',
        '''            "Copyright notices for Forge, Chimera and included components: see Notices.\\n\\n" +
            "This software is provided WITHOUT WARRANTY except as expressly stated. " +
            "You may copy, modify and redistribute covered GPLv3 software under the " +
            "license terms shown here.\\n\\n" +
            "The build is not approved for distribution merely because these notices appear."''')
    print("Chimera legal notices accessible in Options > Help & Diagnostics only; content preserved.")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: patch_chimera_legal_navigation.py STAGED_ANDROID_APP")
    patch(Path(sys.argv[1]))
