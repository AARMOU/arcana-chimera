package com.arcanatable

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Owner, Nov 21: "my phone went asleep and I got disconnected from the room and couldn't rejoin".
 *
 * Android closed the guest's app while the phone slept. The private seat key the room gives each player lived only in
 * memory, so after reopening, joining by code counted as a new player and the room (rightly) wouldn't hand out a seat
 * someone holds. A guest in an internet game now keeps its room code, seat and seat key in the app's private storage
 * from the moment the game starts. Reopening the app offers "Rejoin your game", which takes back the same seat with
 * that key; the host then sends the seat, the table and any open question again. Leaving the game, the game ending
 * on this phone, or a refused rejoin forgets it. Room passwords are derived from the code, so nothing else is kept.
 */
internal object ChimeraGuestSeat {
    data class Saved(val code: String, val seat: Int, val token: String, val name: String, val savedAt: Long)

    /** A saved seat older than this is not offered (the host holds an empty seat for 45 minutes). */
    private const val KEEP_MS = 6L * 60L * 60_000L
    private const val PREFS = "chimera_guest_seat"
    private const val KEY = "seat"

    /** The guest link of the internet game this phone is in, and its room code (set when joining). */
    @Volatile var link: ArgentumCloudMultiGuest? = null
    @Volatile var code: String? = null
    @Volatile var name: String = "PLAYER"

    /** Shown on the home screen once per app start, when there is a seat to go back to. */
    var offer by mutableStateOf<Saved?>(null)
    private var checked = false

    /** Called when the game starts (and again after a rejoin): remember the seat on the phone. */
    fun saveCurrent(context: Context) {
        val roomCode = code ?: return
        val (seat, token) = link?.seatCredential() ?: return
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, JSONObject()
                .put("code", roomCode).put("seat", seat).put("token", token).put("name", name)
                .put("savedAt", System.currentTimeMillis()).toString()).apply()
        }
    }

    fun load(context: Context): Saved? = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        val json = JSONObject(raw)
        Saved(json.getString("code"), json.getInt("seat"), json.getString("token"),
            json.optString("name", "PLAYER"), json.getLong("savedAt"))
    }.getOrNull()?.takeIf { it.seat in 1..3 && it.token.isNotBlank() && System.currentTimeMillis() - it.savedAt in 0..KEEP_MS }

    fun clear(context: Context) {
        offer = null
        runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply() }
    }

    /** First home screen after the app starts: offer the saved seat, if any. */
    fun checkOnce(context: Context) {
        if (checked) return
        checked = true
        val saved = load(context)
        if (saved == null) clear(context) else offer = saved
    }
}

/** Guest: take back the saved seat in an internet game after Android closed the app. */
internal fun TableController.chimeraRejoinOnlineGame(saved: ChimeraGuestSeat.Saved) {
    if (wifiConnecting || argentumEnabled) return
    ChimeraGuestSeat.offer = null
    recordDiagnostic("Multiplayer rejoin requested: internet; seat ${saved.seat + 1}", DiagnosticCategory.ACTION)
    wifiConnecting = true
    val guest = ArgentumCloudMultiGuest(lobbyCloudEndpoint, saved.code, saved.name, saved.seat, saved.token)
    ChimeraGuestSeat.link = guest
    ChimeraGuestSeat.code = saved.code
    ChimeraGuestSeat.name = saved.name
    argentumSocket.attachLanGuest(guest)
    guest.connect { failure -> scope.launch {
        wifiConnecting = false
        if (failure != null) {
            guest.close()
            argentumSocket.close()
            ChimeraGuestSeat.clear(context)
            message = "Couldn't rejoin your game: $failure"
            recordDiagnostic("Multiplayer rejoin refused: $failure", DiagnosticCategory.CONNECTION)
            return@launch
        }
        onlineJoinOpen = false
        wifiRole = "online-guest"
        wifiGuestWaiting = true
        wifiGuestLobbyState = null
        argentumOffline = true
        argentumDeckForLobby = null
        argentumCommanderForLobby = null
        argentumVsAiLobby = false
        argentumLobbySubmitted = true
        argentumLocalPlayersBackup = players.toList()
        argentumEnabled = true
        homeOpen = false
        lobbyOpen = false
        argentumSocket.connect(lobbyPlayerName)
        ChimeraRoomHostGuardService.start(context, guest = true)
        if (!guest.send(JSONObject().put("type", "requestResync").put("resume", true))) {
            closeArgentumSession(concede = false)
            homeOpen = true
            message = "Couldn't ask the host for the table. Try rejoining again."
            return@launch
        }
        message = "Back in your seat. Getting the table from the host…"
        recordDiagnostic("Multiplayer rejoined: internet; seat ${saved.seat + 1}; asked the host for the table",
            DiagnosticCategory.CONNECTION)
    } }
}

/** Home screen: "Rejoin your game" after Android closed the app during an internet game. */
@Composable
internal fun TableController.ChimeraGuestRejoinPrompt() {
    LaunchedEffect(Unit) { ChimeraGuestSeat.checkOnce(context) }
    val saved = ChimeraGuestSeat.offer ?: return
    if (argentumEnabled || wifiConnecting) return
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Rejoin your game?") },
        text = { Text("The app was closed during your online game in room ${saved.code}. Rejoin to take back your seat " +
            "(player ${saved.seat + 1}). The host keeps an empty seat for up to 45 minutes.") },
        confirmButton = { TextButton(onClick = { chimeraRejoinOnlineGame(saved) }) { Text("REJOIN") } },
        dismissButton = { TextButton(onClick = {
            ChimeraGuestSeat.clear(context)
            recordDiagnostic("Multiplayer rejoin declined", DiagnosticCategory.ACTION)
        }) { Text("LEAVE GAME") } },
    )
}
