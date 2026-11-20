# Chimera: in-app Licenses & Source screen (draft)

A selectable, scrollable full GPLv3 text, Chimera license scope and
release-readiness notices are reachable from:
- XP Start menu
- Other Chimera skin menus
- Classic settings, and the home screen

The runtime also reads legal/SOURCE-ACCESS.txt. It only displays a
version-matched source link if HTTPS URL, full SHA-256 and exact installed
BuildConfig.VERSION_NAME all validate. The default built-in file contains
UNPUBLISHED values, so it visibly holds the distribution gate.

The normal debug builder stages the same license assets as the future
Play release builder, no signing key involved. The existing code's
GooseClippy and Forge paths are unchanged. Build and device UI tests
are still required, including cover/unfolded screen tests and testing
that the dialog remains accessible outside gameplay.

Completing a visible license screen does NOT by itself satisfy notices
or corresponding-source delivery; audits, durable source hosting,
version-specific checksums, and owner/legal approval remain pending.
