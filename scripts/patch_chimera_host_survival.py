#!/usr/bin/env python3
"""Fail-closed host-lifecycle overlay for the TEMPORARY Arcana Android donor.

No writes to AARMOU/arcana_table; no cloud Worker deploy.
"""
from pathlib import Path
import sys


def change(path: Path, before: str, after: str):
    source = path.read_text(encoding="utf-8")
    count = source.count(before)
    if count != 1:
        raise RuntimeError(
            f"host survival anchor mismatch {path.name}: expected 1, got {count}: {before[:90]!r}"
        )
    path.write_text(source.replace(before, after), encoding="utf-8")


def patch_app(app: Path):
    kotlin = app / "src/main/java/com/arcanatable"
    rooms = kotlin / "ArgentumCloudMulti.kt"
    wifi = kotlin / "TableWifi.kt"
    manifest = app / "src/main/AndroidManifest.xml"

    # Reconnection bug: a rejected host join reset firstWelcome and then
    # disconnected() immediately treated an established game as a NEW failure.
    # Never fabricate an empty replacement room (that would invalidate tokens).
    change(rooms, '''                    if (droppedAt != 0L && isHost) {
                        // A room whose last phone left can expire; retry as creator.
                        firstWelcome.set(false)
                        disconnected()
                    } else { close(); onFailed(reason) }''', '''                    if (droppedAt != 0L && rejoinRejections < MAX_REJOIN_REJECTIONS) {
                        // The server can refuse a rejoin for a moment (it has not
                        // yet seen the old socket die). Ask again for the SAME
                        // seat; one refusal must not end a live game.
                        rejoinRejections++
                        disconnected()
                    } else if (droppedAt != 0L && isHost) {
                        // The old game must NOT create a new room with new
                        // private seat tokens: that would strand guests.
                        close()
                        onFailed("The internet room no longer accepts the saved host seat. " +
                            "The server must preserve the room during temporary disconnects: $reason")
                    } else { close(); onFailed(reason) }''')
    change(rooms, '''        droppedAt = 0L
        retryCount = 0''', '''        droppedAt = 0L
        retryCount = 0
        rejoinRejections = 0''')

    # Use the matching server recovery window, including peer-away grace.
    # A temporary background disconnect is not an intentional guest departure.
    change(rooms, '    private val graceMs: Long = 90_000L,',
           '    private val graceMs: Long = 45L * 60_000L,')
    # Space retries after initial attempts to avoid needless battery use.
    change(rooms,
           '        val wait = minOf(8_000L, 1_000L shl retryCount.coerceAtMost(3))',
           '        val wait = minOf(30_000L, 1_000L shl retryCount.coerceAtMost(5))')

    # One network drop can deliver both onClosed and onFailure callbacks.
    # Never schedule two reconnect attempts for one failed socket.
    change(rooms, '''    private var retryCount = 0
    private val timer =''', '''    private var retryCount = 0
    @Volatile private var retryScheduled = false
    @Volatile private var rejoinRejections = 0
    private val MAX_REJOIN_REJECTIONS = 6
    private val timer =''')
    change(rooms, '''    private fun disconnected() {
        if (ended.get()) return
        ready = false''', '''    @Synchronized private fun disconnected() {
        if (ended.get() || retryScheduled) return
        ready = false''')
    change(rooms, '''        retryCount++
        timer.schedule({
            if (!ended.get() && !ready) {
                val previous = socket
                socket = null
                previous?.cancel()
                ArgentumCloudLink.roomUrl(endpoint, code)?.let(::open)
            }
        }, wait, TimeUnit.MILLISECONDS)''', '''        retryCount++
        retryScheduled = true
        timer.schedule({
            synchronized(this) { retryScheduled = false }
            if (!ended.get() && !ready) {
                val previous = socket
                socket = null
                previous?.cancel()
                ArgentumCloudLink.roomUrl(endpoint, code)?.let(::open)
            }
        }, wait, TimeUnit.MILLISECONDS)''')

    # Separate hosting lifecycle from Compose and its DisposableEffect.
    change(wifi, '''            server.start()
            val bound = withContext(Dispatchers.IO)''', '''            if (!ChimeraRoomHostGuardService.start(context)) {
                message = "Android did not permit background hosting. Keep the app open or check notification permissions."
                return@launch
            }
            server.start()
            val bound = withContext(Dispatchers.IO)''')
    change(wifi, '''                server.close()
                return@launch
            }
            wifiServer = server''', '''                server.close()
                ChimeraRoomHostGuardService.stop(context)
                return@launch
            }
            wifiServer = server''')
    change(wifi, '''            wifiServer = host
            wifiMultiLobby = room''', '''            if (!ChimeraRoomHostGuardService.start(context)) {
                message = "Android did not permit background hosting. Keep the app open or check notification permissions."
                return@launch
            }
            wifiServer = host
            wifiMultiLobby = room''')
    change(wifi, '''internal fun TableController.stopWifi() {
    wifiServer?.close()''', '''internal fun TableController.stopWifi() {
    ChimeraRoomHostGuardService.stop(context)
    wifiServer?.close()''')

    service = kotlin / "ChimeraRoomHostGuardService.kt"
    if not service.is_file():
        raise RuntimeError("Missing Chimera host service source before patch")
    raw = manifest.read_text(encoding="utf-8")
    service_tag = '<service android:name=".ChimeraRoomHostGuardService" android:exported="false" android:foregroundServiceType="connectedDevice" />'
    if raw.count(service_tag) != 0 or raw.count("</application>") != 1:
        raise RuntimeError("Host service manifest anchor missing or already patched")
    manifest.write_text(raw.replace("</application>", "    " + service_tag + "\n    </application>"), encoding="utf-8")
    print("Staged Chimera host guard and cloud reconnect fixes; NO Worker deployment")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: patch_chimera_host_survival.py STAGED_ANDROID_APP_DIR")
    patch_app(Path(sys.argv[1]))
