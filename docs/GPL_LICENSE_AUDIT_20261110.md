# Chimera GPL licence audit (Nov 10, 2026)

Engineering audit by Claude of what the Forge-powered Arcana build actually ships. It is
not legal advice. Every row below was read from the artifact itself: its own POM and
parent POM chain, its embedded LICENSE/NOTICE files, or the upstream LICENSE at the
release tag. The JAR set was reproduced by running the build script's exact Maven command
on the pinned Forge commit `7c34365c` (72 JARs, matching run 42954804869).

## Result

| | |
| --- | --- |
| Licences that cannot ship with GPLv3 Forge | **1 family found, now removed** (jUPnP, CDDL-1.0) |
| Open licence question | **1** (MXParser, low risk, see below) |
| Everything else | Compatible with GPLv3 |
| Notices reaching the installed app | **Fixed**: generated and packaged as `legal/THIRD-PARTY-NOTICES.txt` |
| Corresponding source | Uploaded in the same artifact as every kept APK/AAB |

## Forge-side JARs (72 before, 61 after)

| Library | JARs | Licence | GPLv3 | Action |
| --- | ---: | --- | --- | --- |
| Forge core/game/ai/gui | 4 | GPL-3.0 | yes | Source ships with every binary |
| Netty 4.1.115 | 35 | Apache-2.0 | yes | NOTICE reproduced in the app |
| Apache Commons lang3/math3/text | 3 | Apache-2.0 | yes | NOTICE reproduced |
| Guava, failureaccess, Gson, jsr305, error-prone and j2objc annotations | 6 | Apache-2.0 | yes | Listed |
| tinylog (3), JHeaps, lz4-java | 5 | Apache-2.0 | yes | Listed |
| checker-qual, Apfloat, RSS Reader, Sentry | 4 | MIT | yes | Licence texts packaged |
| XStream | 1 | BSD-3-Clause | yes | Licence text packaged |
| xmlpull | 1 | Public domain | yes | Listed |
| JGraphT 1.5.2 | 1 | LGPL-2.1-or-later **or** EPL-2.0 | yes, under the LGPL | LGPL elected and packaged |
| MXParser 1.2.2 | 1 | Indiana University Extreme! Lab 1.2 | **disputed** | Open item 1 |
| **jUPnP 3.0.5** (+ support) | 2 | **CDDL-1.0** | **no** | **Removed** |
| Jetty 9.4.57 | 8 | Apache-2.0 or EPL-1.0 | yes under Apache | Removed with jUPnP (its only user) |
| javax.servlet-api 3.1.0 | 1 | CDDL-1.0 or GPL-2.0 with Classpath exception | only via the exception | Removed with jUPnP |

### The one real conflict: jUPnP

The FSF lists CDDL-1.0 as incompatible with the GPL, so a GPLv3 program cannot be conveyed
with CDDL code linked into it. Forge upstream ships jUPnP for its own UPnP network hosting.
Chimera never starts that: its host (`ArcanaForgeHost`) does not use Forge's network server classes. Forge's
`IGuiBase` and `IDeviceAdapter` only *name* one jUPnP type in a method signature, so Chimera
now supplies an empty type of its own (`forge-host/src/stubs`) and drops the 11 JARs.

Checked on the JVM with exactly the 61 remaining JARs: `OneTurnProof` PASS, `FourSeatProof`
PASS, and a 3-game multiplayer `SoakTest` (2 to 3 people, one leaving mid-game, AI takeover)
PASS with no missing-class errors. Not yet checked on a phone.

## Android-side libraries

From the pinned Arcana `build.gradle.kts`: AndroidX/Compose, Kotlin and kotlinx, OkHttp/Okio
(all Apache-2.0) and slf4j-api (MIT). There is **no** Google Play Services, Firebase, ads or
billing SDK, so nothing closed-source is linked. The exact resolved list is written into the
notices at build time; any group not in `third_party_licenses/reviewed.json` is listed in the
app and in `third-party-notices-report.json` as unreviewed.

The four `argentum-*.jar` files from the Arcana donor are still packaged because nothing
removes them. They are MIT, which is compatible; the notice is included.

## Notices in the installed app (was broken)

The build excludes `META-INF/*`, so no library's own NOTICE or LICENSE file reached the APK,
and the in-app Notices tab only showed an internal readiness document. Now
`scripts/third_party_notices.py` generates one notices file from the staged JARs (component
list, every NOTICE file, each licence text) and the Notices tab shows it. The build stops if
a JAR is unreviewed or forbidden. CI then inspects the real APK for the legal files.

## Corresponding source (GPLv3 section 6)

- Every kept binary is uploaded in the same artifact as the source archive made from the
  same staged trees (Chimera at the built commit, Arcana `a5c428ad`, Forge `7c34365c`, the
  staged JARs and generated assets), with a manifest tying the two hashes together.
- The app's Source tab names where that source is offered for its exact version: the build's
  own run page for test APKs, the release tag `chimera-<versionName>` for a Play AAB.
- The earlier design wanted the archive's SHA-256 inside the app. That is circular (the
  archive records the app's hash), so the checksum is now published beside the archive.
- **Before a Play upload** the source archive and `SHA256SUMS` must be published at the
  release tag the AAB names, and kept there while that version is offered.
- Section 6's "Installation Information" duty applies to devices sold with the software
  built in, not to an app on a store. Play App Signing re-signing the app does not change
  the source duty.

## Open items for the owner

1. **MXParser** (XStream's XML parser). Its licence is Apache-1.1 style: it requires an
   acknowledgment in the documentation (now in the notices) and restricts use of the
   university's names. The FSF treats that licence family as GPL-incompatible; distributions
   such as Debian and Fedora ship it as free software. Forge upstream ships it too. Practical
   risk is low. Removing it means making Forge's XStream use a different parser, which is a
   Forge patch and needs device testing.
2. **Arcana's own code has no licence file.** `AARMOU/arcana_table` is public with no
   LICENSE, so nothing yet grants GPLv3 rights in the Arcana UI code inside this app. The
   owner needs to state that the Arcana source used here is offered under GPL-3.0 (a line in
   Chimera's `LICENSE_SCOPE.md` or a LICENSE in Arcana).
3. **XP wallpaper photos** (`chimera_xp/wall_*.jpg`) have no recorded origin. They are not
   the Microsoft "Bliss" photograph, but a photo needs a known licence to be redistributed.
4. **Not licence questions, but Play policy risks:** card names, rules text and card images
   (fetched from Scryfall at run time) are Wizards of the Coast's; the app relies on the Fan
   Content Policy, which requires it to stay free and carry the unofficial-content notice.
   The Windows XP look and a Clippy-style assistant imitate Microsoft's designs.
5. **Sentry** is linked by Forge but never initialised in this build, so no crash data
   leaves the device. Re-check if Forge's mobile startup code is ever adopted.

## What was not done

- No Android build or phone test was possible in the audit environment; CI builds the APK.
- Forge's card scripts and other `res` data were treated as covered by Forge's GPLv3 as
  upstream publishes them; individual files were not reviewed.
- Desktop-only native libraries inside Netty and lz4-java JARs are unused on Android and
  were left in place.
