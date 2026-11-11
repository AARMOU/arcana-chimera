package com.arcanatable

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * "XP Desktop" screen layout (owner, Nov 10): the game as a retro blue-and-beige desktop. Every game window can be
 * dragged by its title bar, resized from any edge or corner, brought to the front, minimized to the taskbar and
 * maximized; positions are kept per screen shape, and Start › Reset layout puts them back. Card pictures are drawn by
 * Arcana's own CardFace, unchanged. Landscape follows the owner's wide mock-up (player windows on top, two green
 * battlefields, Game Info / Phases / Stack, an action bar); portrait follows the tall one (a Commander window with the
 * board as a list, plus Game Info, Board Overview and Zones). Popups appear only for real events.
 */

// ------------------------------------------------------------------ look

private object Xp {
    val beige = Color(0xFFECE9D8)
    val frame = Color(0xFF0B55E4)
    val titleTop = Color(0xFF3D95FF)
    val titleMid = Color(0xFF0A5DEB)
    val titleLow = Color(0xFF0646C9)
    val inactiveTop = Color(0xFF9DB9EB)
    val inactiveLow = Color(0xFF7A96DF)
    val ink = Color(0xFF111111)
    val muted = Color(0xFF55524A)
    val select = Color(0xFF316AC5)
    val felt = Color(0xFF2E7D32)
    val feltDark = Color(0xFF1F5E24)
    val taskTop = Color(0xFF3A81F3)
    val taskLow = Color(0xFF1F55D0)
    val startTop = Color(0xFF4CB04C)
    val startLow = Color(0xFF2E8B2E)
    val red = Color(0xFFE0553B)
    val font = FontFamily.SansSerif
    val mono = FontFamily.Monospace
    val title = Brush.verticalGradient(0f to titleTop, 0.12f to titleMid, 0.8f to titleLow, 1f to Color(0xFF0A3FB0))
    val inactive = Brush.verticalGradient(listOf(inactiveTop, inactiveLow))
    val task = Brush.verticalGradient(0f to taskTop, 0.15f to Color(0xFF2D6BE6), 1f to taskLow)
    val start = Brush.verticalGradient(0f to startTop, 0.5f to Color(0xFF3A9E3A), 1f to startLow)
    val button = Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFF2F1EA), Color(0xFFD9D5C6)))
    val buttonHot = Brush.verticalGradient(listOf(Color(0xFFF4FFF0), Color(0xFFC9F0B9), Color(0xFF8FD27A)))
    val close = Brush.verticalGradient(listOf(Color(0xFFF08A6E), Color(0xFFD9452A)))
    val felts = Brush.verticalGradient(listOf(Color(0xFF358A3A), felt, feltDark))
}

@Composable
private fun XText(text: String, size: TextUnit = 12.sp, color: Color = Xp.ink, bold: Boolean = false, modifier: Modifier = Modifier,
    mono: Boolean = false, maxLines: Int = 1, align: TextAlign? = null) =
    Text(text, color = color, fontSize = size, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        fontFamily = if (mono) Xp.mono else Xp.font, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = modifier,
        textAlign = align, lineHeight = size * 1.2f)

/** A raised beige push button. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun XButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, hot: Boolean = false,
    icon: String? = null, size: TextUnit = 12.sp, onHold: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(3.dp)
    Row(modifier.heightIn(min = 34.dp).clip(shape).background(if (hot && enabled) Xp.buttonHot else Xp.button)
        .border(1.dp, if (hot && enabled) Color(0xFF3C8A2E) else Color(0xFF003C74), shape)
        .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onHold)
        .padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (icon != null) Text(icon, fontSize = size * 1.15f, modifier = Modifier.padding(end = 4.dp))
        XText(label, size, if (enabled) Xp.ink else Color(0xFF9A978C), maxLines = 2, align = TextAlign.Center)
    }
}

/** One of the three title-bar buttons. */
@Composable
private fun TitleButton(glyph: String, onClick: () -> Unit, red: Boolean = false) {
    val shape = RoundedCornerShape(3.dp)
    Box(Modifier.padding(start = 2.dp).size(22.dp).clip(shape)
        .background(if (red) Xp.close else Brush.verticalGradient(listOf(Color(0xFF6FA4FF), Color(0xFF2763E0))))
        .border(1.dp, Color.White, shape).combinedClickableNoHold(onClick), contentAlignment = Alignment.Center) {
        Text(glyph, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black)
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableNoHold(onClick: () -> Unit) = this.combinedClickable(onClick = onClick)

// ------------------------------------------------------------------ windows

internal data class XpGeom(val x: Float, val y: Float, val w: Float, val h: Float)

/** Window positions, sizes, stacking, minimized and maximized state; saved per screen shape. */
internal object XpDesk {
    private val prefs: SharedPreferences? by lazy {
        runCatching {
            (Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Context)
                .getSharedPreferences("chimera_skin", Context.MODE_PRIVATE)
        }.getOrNull()
    }
    val geom = mutableStateMapOf<String, XpGeom>()
    val order = mutableStateListOf<String>()
    val minimized = mutableStateListOf<String>()
    private val restore = HashMap<String, XpGeom>()
    var key by mutableStateOf("")
        private set
    var deskW = 0f; var deskH = 0f

    fun load(newKey: String, defaults: Map<String, XpGeom>, startMinimized: Set<String>) {
        if (newKey == key && geom.keys.containsAll(defaults.keys)) return
        key = newKey
        geom.clear(); order.clear(); minimized.clear(); restore.clear()
        val saved = runCatching { JSONObject(prefs?.getString("xp_$newKey", null) ?: "{}") }.getOrDefault(JSONObject())
        defaults.forEach { (id, d) ->
            val s = saved.optJSONObject("g")?.optJSONObject(id)
            geom[id] = if (s != null) XpGeom(s.optDouble("x").toFloat(), s.optDouble("y").toFloat(), s.optDouble("w").toFloat(), s.optDouble("h").toFloat()) else d
        }
        val savedOrder = saved.optJSONArray("o")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty().filter { it in defaults }
        order.addAll(savedOrder + defaults.keys.filter { it !in savedOrder })
        val savedMin = saved.optJSONArray("m")?.let { a -> (0 until a.length()).map { a.optString(it) } }
        minimized.addAll((savedMin ?: startMinimized.toList()).filter { it in defaults })
    }

    fun reset(defaults: Map<String, XpGeom>, startMinimized: Set<String>) {
        prefs?.edit()?.remove("xp_$key")?.apply()
        val k = key; key = ""; load(k, defaults, startMinimized)
    }

    private fun save() {
        val g = JSONObject(); geom.forEach { (id, r) -> g.put(id, JSONObject().put("x", r.x).put("y", r.y).put("w", r.w).put("h", r.h)) }
        prefs?.edit()?.putString("xp_$key", JSONObject().put("g", g).put("o", org.json.JSONArray(order.toList()))
            .put("m", org.json.JSONArray(minimized.toList())).toString())?.apply()
    }

    fun front(id: String) { if (order.lastOrNull() != id) { order.remove(id); order.add(id); save() } }
    fun top(): String? = order.lastOrNull { it !in minimized }
    fun minimize(id: String) { if (id !in minimized) minimized.add(id); save() }
    fun show(id: String) { minimized.remove(id); front(id); save() }
    fun toggleTask(id: String) { if (id in minimized || top() != id) show(id) else minimize(id) }
    fun maximized(id: String) = id in restore

    fun maximize(id: String) {
        val r = geom[id] ?: return
        if (id in restore) { geom[id] = restore.remove(id)!! } else { restore[id] = r; geom[id] = XpGeom(0f, 0f, deskW, deskH) }
        front(id); save()
    }

    fun move(id: String, dx: Float, dy: Float) {
        val r = geom[id] ?: return
        restore.remove(id)
        geom[id] = r.copy(x = (r.x + dx).coerceIn(-r.w + 80f, deskW - 80f), y = (r.y + dy).coerceIn(0f, deskH - 28f))
    }

    /** [sx]/[sy]: -1 = left/top edge, 1 = right/bottom edge, 0 = that axis doesn't change. */
    fun resize(id: String, sx: Int, sy: Int, dx: Float, dy: Float) {
        val r = geom[id] ?: return
        restore.remove(id)
        var (x, y, w, h) = r
        if (sx > 0) w = (w + dx).coerceIn(140f, deskW - x + 40f)
        if (sx < 0) { val nw = (w - dx).coerceIn(140f, x + w); x += w - nw; w = nw }
        if (sy > 0) h = (h + dy).coerceIn(80f, deskH - y + 40f)
        if (sy < 0) { val nh = (h - dy).coerceIn(80f, y + h); y += h - nh; h = nh }
        geom[id] = XpGeom(x, y, w, h)
    }

    fun done() = save()
}

/** A movable, resizable window with an XP-style title bar. Its close button minimizes it to the taskbar. */
@Composable
private fun XpWindow(id: String, title: String, icon: String, content: @Composable () -> Unit) {
    val r = XpDesk.geom[id] ?: return
    if (id in XpDesk.minimized) return
    val active = XpDesk.top() == id
    val shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 2.dp, bottomEnd = 2.dp)
    Box(Modifier.offset(r.x.dp, r.y.dp).size(r.w.dp, r.h.dp).testTag("xp-window-$id")
        .pointerInput(id) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); XpDesk.front(id) } }) {
        Column(Modifier.fillMaxSize().shadow(if (active) 10.dp else 4.dp, shape).clip(shape)
            .background(if (active) Xp.frame else Color(0xFF7A96DF)).padding(start = 3.dp, end = 3.dp, bottom = 3.dp)) {
            Row(Modifier.fillMaxWidth().height(28.dp).background(if (active) Xp.title else Xp.inactive)
                .pointerInput(id) {
                    detectDragGestures(onDragEnd = { XpDesk.done() }) { change, d -> change.consume(); XpDesk.move(id, d.x.toDp().value, d.y.toDp().value) }
                }.padding(start = 6.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(icon, fontSize = 13.sp, modifier = Modifier.padding(end = 5.dp))
                XText(title, 13.sp, Color.White, bold = true, modifier = Modifier.weight(1f))
                TitleButton("_", { XpDesk.minimize(id) })
                TitleButton(if (XpDesk.maximized(id)) "❐" else "□", { XpDesk.maximize(id) })
                TitleButton("✕", { XpDesk.minimize(id) }, red = true)
            }
            Box(Modifier.fillMaxSize().background(Xp.beige)) { content() }
        }
        // Resize handles: four edges and four corners, wide enough for a finger.
        val e = 12.dp; val c = 24.dp
        listOf(
            Triple(Alignment.CenterStart, -1 to 0, Modifier.width(e).fillMaxHeight().padding(vertical = c)),
            Triple(Alignment.CenterEnd, 1 to 0, Modifier.width(e).fillMaxHeight().padding(vertical = c)),
            Triple(Alignment.BottomCenter, 0 to 1, Modifier.height(e).fillMaxWidth().padding(horizontal = c)),
            Triple(Alignment.TopCenter, 0 to -1, Modifier.height(5.dp).fillMaxWidth().padding(horizontal = c)),
            Triple(Alignment.BottomStart, -1 to 1, Modifier.size(c)),
            Triple(Alignment.BottomEnd, 1 to 1, Modifier.size(c)),
            Triple(Alignment.TopStart, -1 to -1, Modifier.size(10.dp)),
            Triple(Alignment.TopEnd, 1 to -1, Modifier.size(10.dp)),
        ).forEach { (align, s, m) ->
            Box(m.align(align).pointerInput(id, s) {
                detectDragGestures(onDragEnd = { XpDesk.done() }) { change, d -> change.consume(); XpDesk.resize(id, s.first, s.second, d.x.toDp().value, d.y.toDp().value) }
            })
        }
        if (active) Box(Modifier.align(Alignment.BottomEnd).padding(5.dp).size(10.dp)) { Text("◢", color = Color(0xFF8A877C), fontSize = 9.sp) }
    }
}

// ------------------------------------------------------------------ popups (real events only)

internal data class XpAlert(val id: Long, val title: String, val icon: String, val text: String, val detail: String? = null,
    val primary: String = "OK", val secondary: String? = null, val onPrimary: () -> Unit = {}, val onSecondary: () -> Unit = {})

internal object XpAlerts {
    val list = mutableStateListOf<XpAlert>()
    var mutedPriority = false
    private var seq = 0L
    fun add(a: XpAlert) { list.removeAll { it.title == a.title }; list.add(a.copy(id = ++seq)); while (list.size > 4) list.removeAt(0) }
    fun close(a: XpAlert) { list.removeAll { it.id == a.id } }
}

@Composable
private fun XpDialog(a: XpAlert, index: Int, onClose: () -> Unit) {
    val shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
    Column(Modifier.offset((index * 18).dp, (index * 22).dp).widthIn(min = 250.dp, max = 340.dp).shadow(12.dp, shape).clip(shape)
        .background(Xp.frame).padding(start = 3.dp, end = 3.dp, bottom = 3.dp).testTag("xp-alert")) {
        Row(Modifier.fillMaxWidth().height(28.dp).background(Xp.title).padding(start = 8.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            XText(a.title, 13.sp, Color.White, bold = true, modifier = Modifier.weight(1f))
            TitleButton("✕", { onClose(); a.onPrimary() }, red = true)
        }
        Column(Modifier.fillMaxWidth().background(Xp.beige).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (glyph, fill) = when (a.icon) { "error" -> "✕" to Color(0xFFD9342B); "warn" -> "!" to Color(0xFFF2C12E); else -> "i" to Color(0xFF2B63D9) }
                Box(Modifier.size(38.dp).clip(CircleShape).background(fill).border(2.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                    Text(glyph, color = if (a.icon == "warn") Xp.ink else Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
                }
                Column(Modifier.padding(start = 12.dp)) {
                    XText(a.text, 14.sp, bold = true, maxLines = 4)
                    a.detail?.let { XText(it, 12.sp, Xp.muted, maxLines = 4) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
                XButton(a.primary, { onClose(); a.onPrimary() }, Modifier.widthIn(min = 86.dp))
                a.secondary?.let { s -> XButton(s, { onClose(); a.onSecondary() }, Modifier.widthIn(min = 86.dp)) }
            }
        }
    }
}

/** Watches the real game for the moments that get an XP popup. */
@Composable
private fun XpAlertWatch(g: SkinGame) {
    val t = g.t
    LaunchedEffect(g.snap.turnNumber, g.myTurn) {
        if (g.myTurn && g.snap.turnNumber > 0) {
            val a = XpAlert(0, "Arcana Table", "info", "Your Turn!", "It's your priority.")
            XpAlerts.add(a); kotlinx.coroutines.delay(4000); XpAlerts.list.removeAll { it.title == a.title && it.text == a.text }
        }
    }
    // Your spell or ability countered (Forge's log says so).
    val countered = g.log.lastOrNull()?.takeIf { it.description.contains("countered", true) && it.isYours != true }
    LaunchedEffect(countered?.description, g.log.size) {
        val c = countered ?: return@LaunchedEffect
        val mine = g.me?.let { me -> (me.battlefield + me.graveyard + me.hand).any { c.description.contains(it.name) } || (me.commanderName?.let { c.description.contains(it) } == true) } == true
        if (!mine) return@LaunchedEffect
        val commander = g.me?.commanderName?.let { c.description.contains(it) } == true
        XpAlerts.add(XpAlert(0, "Counterspell.exe", "error",
            if (commander) "Counterspell.exe has stopped your commander from working." else "Your spell has been countered.",
            c.description, secondary = "Details…", onSecondary = { g.log() }))
    }
    // Someone else's spell on the stack and you can respond.
    val respond = t.argentumCanRespond && g.snap.stack.isNotEmpty() && g.snap.stack.firstOrNull()?.isYours == false
    LaunchedEffect(respond, g.snap.stack.firstOrNull()?.id) {
        if (respond && !XpAlerts.mutedPriority) XpAlerts.add(XpAlert(0, "Priority Alert", "info", "Another player has responded at instant speed.",
            g.snap.stack.firstOrNull()?.let { "${it.controllerName}: ${it.name}" }, secondary = "Ignore", onSecondary = { XpAlerts.mutedPriority = true }))
    }
    // Arcana's own messages (refused actions, notes) in XP dress; OK clears them as Arcana's popup would.
    val msg = t.message
    LaunchedEffect(msg) {
        if (msg == null) return@LaunchedEffect
        val title = when {
            msg.contains("mana", true) || msg.contains("can't pay", true) -> "NoMana.dll"
            msg.contains("couldn't be done", true) || msg.contains("can't", true) || msg.contains("not allowed", true) -> "IllegalAction.exe"
            msg.contains("attack", true) || msg.contains("block", true) -> "Combat Error"
            else -> "Arcana Table"
        }
        XpAlerts.add(XpAlert(0, title, if (title == "Arcana Table") "info" else if (title == "NoMana.dll") "warn" else "error", msg,
            onPrimary = { t.message = null }))
    }
}

// ------------------------------------------------------------------ wallpaper

@Composable
private fun XpWallpaper(wide: Boolean, ratio: Float) {
    val ctx = LocalContext.current
    val name = when {
        wide && ratio >= 1.6f -> "wall_cover_landscape"
        wide -> "wall_open_landscape"
        ratio >= 1.6f -> "wall_cover_portrait"
        else -> "wall_open_portrait"
    }
    val bmp = remember(name) {
        runCatching { ctx.assets.open("chimera_xp/$name.jpg").use { BitmapFactory.decodeStream(it)?.asImageBitmap() } }.getOrNull()
    }
    if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    else Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0xFF2F6FE0), 0.62f to Color(0xFF8DBDF0), 0.63f to Color(0xFF5EAE2E), 1f to Color(0xFF2F7A1C))))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DesktopIcon(glyph: String, label: String, onClick: () -> Unit) {
    Column(Modifier.width(76.dp).combinedClickable(onClick = onClick).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(glyph, fontSize = 28.sp)
        Text(label, color = Color.White, fontSize = 11.sp, fontFamily = Xp.font, textAlign = TextAlign.Center, maxLines = 2,
            style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black, androidx.compose.ui.geometry.Offset(1f, 1f), 2f)))
    }
}

// ------------------------------------------------------------------ the screen

private data class XpWinInfo(val id: String, val title: String, val icon: String)

@Composable
internal fun ChimeraXpDesktop(ui: SkinUi) {
    val g = ui.g
    var oppIndex by remember { mutableStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF3A6EA5))) {
        val wide = maxWidth > maxHeight
        val ratio = maxOf(maxWidth, maxHeight) / minOf(maxWidth, maxHeight)
        val narrowScreen = maxWidth < 500.dp
        val wins = windows(g, wide)
        XpWallpaper(wide, ratio)
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (wide) AppBar(g, "Arcana Table – Commander (XP Edition)")
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val iconsW = if (maxWidth > 600.dp) 80f else 0f
                val dw = maxWidth.value - iconsW; val dh = maxHeight.value - (if (wide) 50f else 0f)
                val narrow = maxWidth < 500.dp
                val defaults = if (wide) landscapeDefaults(g, dw, dh) else portraitDefaults(dw, dh, narrow)
                val startMin = if (wide) setOf("hand") else if (narrow) setOf("info", "overview", "zones", "hand", "stack") else setOf("hand", "stack")
                val key = (if (wide) "w" else "p") + "${(dw / 40).toInt()}x${(dh / 40).toInt()}"
                LaunchedEffect(key, defaults.keys) { XpDesk.load(key, defaults, startMin) }
                XpDesk.deskW = dw; XpDesk.deskH = dh
                if (iconsW > 0) Column(Modifier.padding(top = 4.dp)) { desktopIcons(g, wide).forEach { (gl, l, a) -> DesktopIcon(gl, l, a) } }
                Box(Modifier.padding(start = iconsW.dp).fillMaxSize()) {
                    Box(Modifier.width(dw.dp).height(dh.dp)) {
                        XpDesk.order.toList().forEach { id ->
                            val w = wins.firstOrNull { it.id == id } ?: return@forEach
                            XpWindow(w.id, w.title, w.icon) { WindowBody(id, ui, wide) { oppIndex } }
                        }
                    }
                    if (wide) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(50.dp).padding(horizontal = 6.dp, vertical = 4.dp)) {
                        ActionBar(ui)
                    }
                }
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box { XpAlerts.list.toList().forEachIndexed { i, a -> XpDialog(a, i) { XpAlerts.close(a) } } }
                }
                // Opponent picker for the landscape opponent battlefield
                OppPicker.index = oppIndex; OppPicker.set = { oppIndex = it }
            }
            Taskbar(g, wins, onReset = { XpDesk.reset(if (wide) landscapeDefaults(g, XpDesk.deskW, XpDesk.deskH) else portraitDefaults(XpDesk.deskW, XpDesk.deskH, narrowScreen),
                if (wide) setOf("hand") else setOf("hand", "stack")) })
        }
    }
    XpAlertWatch(g)
}

/** Which opponent the landscape opponent-battlefield window shows. */
private object OppPicker { var index = 0; var set: (Int) -> Unit = {} }

private fun windows(g: SkinGame, wide: Boolean): List<XpWinInfo> = if (wide) buildList {
    g.me?.let { add(XpWinInfo("p:${it.id}", "${it.name} (You)", "🟢")) }
    g.opponents.forEach { add(XpWinInfo("p:${it.id}", (if (it.isComputer) "AI – " else "") + it.name, "🖥")) }
    add(XpWinInfo("bf:you", "Battlefield – You", "🌳"))
    add(XpWinInfo("bf:opp", "Battlefield – " + (g.opponents.getOrNull(OppPicker.index)?.name ?: "Opponents"), "🌲"))
    add(XpWinInfo("info", "Game Info", "ℹ️"))
    add(XpWinInfo("phases", "Phases", "⏱"))
    add(XpWinInfo("stack", "The Stack (${g.stackCount})", "📚"))
    add(XpWinInfo("hand", "Hand (${g.hand.size})", "🂠"))
} else listOf(
    XpWinInfo("main", "Arcana – Commander (Live Game)", "🅰"),
    XpWinInfo("info", "Game Info", "🖥"),
    XpWinInfo("overview", "Board Overview", "📁"),
    XpWinInfo("zones", "Zones", "📂"),
    XpWinInfo("hand", "Hand (${g.hand.size})", "🂠"),
    XpWinInfo("stack", "The Stack (${g.stackCount})", "📚"),
)

private fun landscapeDefaults(g: SkinGame, w: Float, h: Float): Map<String, XpGeom> {
    val players = listOfNotNull(g.me) + g.opponents
    val pw = (w - 8f) / players.size.coerceAtLeast(1)
    val top = 96f
    val side = (w * 0.19f).coerceIn(150f, 230f)
    val mid = w - side * 2 - 12f
    val bfH = (h - top - 12f) / 2
    return buildMap {
        players.forEachIndexed { i, p -> put("p:${p.id}", XpGeom(4f + i * pw, 2f, pw - 6f, top - 6f)) }
        put("info", XpGeom(2f, top + 6f, side, bfH * 0.62f))
        put("phases", XpGeom(2f, top + 12f + bfH * 0.62f, side, bfH * 0.95f))
        put("bf:you", XpGeom(side + 6f, top, mid, bfH))
        put("bf:opp", XpGeom(side + 6f, top + bfH + 4f, mid, bfH))
        put("stack", XpGeom(w - side - 2f, top + 6f, side, bfH * 1.1f))
        put("hand", XpGeom(w - side - 2f, top + bfH * 1.1f + 12f, side, bfH * 0.85f))
    }
}

private fun portraitDefaults(w: Float, h: Float, narrow: Boolean): Map<String, XpGeom> {
    val mainW = if (narrow) w - 4f else w * 0.66f
    val sideX = if (narrow) w * 0.25f else mainW + 6f
    val sideW = if (narrow) w * 0.72f else w - mainW - 8f
    return mapOf(
        "main" to XpGeom(2f, 2f, mainW, h - 4f),
        "info" to XpGeom(sideX, 40f, sideW, 150f),
        "overview" to XpGeom(sideX, 200f, sideW, 200f),
        "zones" to XpGeom(sideX, 410f, sideW, 250f),
        "hand" to XpGeom(sideX, h * 0.45f, sideW, h * 0.3f),
        "stack" to XpGeom(sideX, h * 0.2f, sideW, 200f),
    )
}

private fun desktopIcons(g: SkinGame, wide: Boolean): List<Triple<String, String, () -> Unit>> = listOf(
    Triple("📜", "Game Log") { g.log() },
    Triple("📊", "Board Summary") { ChimeraFeatures.boardOpen = true },
    Triple("🕘", "Game History") { ChimeraFeatures.historyOpen = true },
    Triple("⚙️", "Settings") { g.t.optionsOpen = true },
    Triple("🪦", "Graveyard") { g.graveyard() },
    Triple("🗑️", "Recycle Bin (Exile)") { g.exile() },
)

@Composable
private fun AppBar(g: SkinGame, title: String) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(28.dp).background(Xp.title).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🃏", fontSize = 14.sp, modifier = Modifier.padding(end = 6.dp))
            XText(title, 14.sp, Color.White, bold = true, modifier = Modifier.weight(1f))
        }
        MenuBar(g)
    }
}

@Composable
private fun MenuBar(g: SkinGame) {
    Row(Modifier.fillMaxWidth().height(24.dp).background(Xp.beige).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        MenuTitle("Game", listOf("Concede" to { ChimeraConcede.asking = true }, "Leave game…" to { g.t.argentumLeaveConfirm = true },
            "Classic screen" to { ChimeraSkins.set("") }))
        MenuTitle("View", listOf("Board summary" to { ChimeraFeatures.boardOpen = true }, "Game log" to { g.log() },
            "Stack" to { g.stack() }, "Game history" to { ChimeraFeatures.historyOpen = true }))
        MenuTitle("Actions", listOf(g.passLabel to { g.pass() }, "Pass to my turn" to { g.holdPass() }, "Undo" to { g.undo() },
            "Actions (${g.actions.size})" to { g.openActions() }) +
            (if (ChimeraFeatureSettings.coach) listOf("Coach: what would the AI do?" to { ChimeraCoach.ask(g.t) }) else emptyList()))
        MenuTitle("Help", listOf("Options" to { g.t.optionsOpen = true }, "MTG rules" to { g.t.optionsOpen = true }))
    }
}

@Composable
private fun MenuTitle(name: String, items: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        XText(name, 13.sp, modifier = Modifier.clip(RoundedCornerShape(2.dp)).combinedClickableNoHold { open = true }.padding(horizontal = 9.dp, vertical = 3.dp))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { (label, act) -> DropdownMenuItem(text = { Text(label, fontFamily = Xp.font) }, onClick = { open = false; act() }) }
        }
    }
}

@Composable
private fun Taskbar(g: SkinGame, wins: List<XpWinInfo>, onReset: () -> Unit) {
    var startOpen by remember { mutableStateOf(false) }
    var clock by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { clock = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()); kotlinx.coroutines.delay(20_000) } }
    Row(Modifier.fillMaxWidth().height(40.dp).background(Xp.task), verticalAlignment = Alignment.CenterVertically) {
        Box {
            Row(Modifier.fillMaxHeight().clip(RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp)).background(Xp.start)
                .combinedClickableNoHold { startOpen = true }.padding(horizontal = 12.dp).testTag("xp-start"), verticalAlignment = Alignment.CenterVertically) {
                Text("🃏", fontSize = 16.sp, modifier = Modifier.padding(end = 6.dp))
                Text("start", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, fontFamily = Xp.font)
            }
            DropdownMenu(expanded = startOpen, onDismissRequest = { startOpen = false }) {
                listOf("Reset layout" to onReset, "Board summary" to { ChimeraFeatures.boardOpen = true },
                    "Game history" to { ChimeraFeatures.historyOpen = true }, "Game log" to { g.log() }, "Options" to { g.t.optionsOpen = true },
                    "Classic screen" to { ChimeraSkins.set("") }, "Concede" to { ChimeraConcede.asking = true }, "Leave game…" to { g.t.argentumLeaveConfirm = true })
                    .forEach { (label, act) -> DropdownMenuItem(text = { Text(label, fontFamily = Xp.font) }, onClick = { startOpen = false; act() }) }
            }
        }
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            wins.forEach { w ->
                val on = XpDesk.top() == w.id && w.id !in XpDesk.minimized
                Row(Modifier.height(32.dp).widthIn(min = 70.dp, max = 170.dp).clip(RoundedCornerShape(3.dp))
                    .background(if (on) Color(0xFF1E48A8) else Color(0xFF3C81F3)).border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(3.dp))
                    .combinedClickableNoHold { XpDesk.toggleTask(w.id) }.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(w.icon, fontSize = 12.sp, modifier = Modifier.padding(end = 4.dp))
                    XText(w.title, 11.sp, Color.White)
                }
            }
        }
        Row(Modifier.fillMaxHeight().background(Color(0xFF0F8EE6)).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (g.t.argentumCanRespond) Text("⚡", fontSize = 13.sp, modifier = Modifier.padding(end = 6.dp))
            XText(clock, 12.sp, Color.White)
        }
    }
}

// ------------------------------------------------------------------ window contents

@Composable
private fun WindowBody(id: String, ui: SkinUi, wide: Boolean, opp: () -> Int) {
    val g = ui.g
    when {
        id.startsWith("p:") -> (listOfNotNull(g.me) + g.opponents).firstOrNull { "p:${it.id}" == id }?.let { PlayerPanel(it, g) }
        id == "bf:you" -> Battlefield(g.me, g, mine = true)
        id == "bf:opp" -> Column {
            if (g.opponents.size > 1) Row(Modifier.fillMaxWidth().background(Xp.beige).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                g.opponents.forEachIndexed { i, p ->
                    XButton(p.name, { OppPicker.set(i) }, Modifier.weight(1f).heightIn(min = 26.dp), hot = i == opp(), size = 10.sp)
                }
            }
            Battlefield(g.opponents.getOrNull(opp().coerceIn(0, (g.opponents.size - 1).coerceAtLeast(0))), g, mine = false)
        }
        id == "info" -> if (wide) GameInfo(g) else LiveInfo(g)
        id == "phases" -> Phases(g)
        id == "stack" -> StackBody(g)
        id == "hand" -> HandBody(ui)
        id == "main" -> MainWindow(ui)
        id == "overview" -> Overview(g)
        id == "zones" -> Zones(ui)
    }
}

@Composable
private fun PlayerPanel(p: TablePlayer, g: SkinGame) {
    Row(Modifier.fillMaxSize().padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
        val art = p.commanderCard?.imageUrl
        Box(Modifier.fillMaxHeight().width(54.dp).clip(RoundedCornerShape(3.dp)).border(2.dp, Color(0xFF7F9DB9), RoundedCornerShape(3.dp)).background(Color(0xFFDDE7F5))) {
            if (art != null) CardArt(commanderArtOnly(art) ?: art, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, fallbackName = p.commanderName)
        }
        Column(Modifier.padding(start = 8.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("❤", color = Color(0xFFD62828), fontSize = 15.sp)
                XText(if (p.isEliminated) " OUT" else " ${p.life}", 17.sp, if (p.life <= 10) Color(0xFFC62828) else Xp.ink, bold = true)
                if (g.snap.players.getOrNull(g.snap.activePlayer)?.id == p.id) XText("  ◀ turn", 10.sp, Color(0xFF2E7D32), bold = true)
            }
            XText("Hand: ${p.hand.size}   Library: ${p.library.size}", 11.sp)
            p.commanderName?.let { XText(it, 10.sp, Xp.muted) }
            val pool = p.manaPool.filterValues { it > 0 }
            if (pool.isNotEmpty()) XText("Mana: " + pool.entries.joinToString(" ") { "${it.key}${it.value}" }, 10.sp, Xp.select, bold = true)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Battlefield(p: TablePlayer?, g: SkinGame, mine: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Xp.felts)) {
        if (p == null) { XText("No opponent", 12.sp, Color.White, modifier = Modifier.padding(8.dp)); return@BoxWithConstraints }
        val cardW = (maxWidth / 8.5f).coerceIn(46.dp, 120.dp)
        val nonLands = p.battlefield.filterNot { it.type.contains("Land", true) }
        val lands = p.battlefield.filter { it.type.contains("Land", true) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (p.battlefield.isEmpty()) XText("No permanents yet.", 12.sp, Color(0xCCFFFFFF))
            listOf(nonLands, lands).filter { it.isNotEmpty() }.forEach { group ->
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    group.forEach { c ->
                        val glow = mine && c.id in g.playable && !g.busy
                        Box(Modifier.border(2.dp, Color(0xFFECE9D8).copy(alpha = 0.7f), RoundedCornerShape(5.dp)).padding(2.dp)) {
                            CardFace(c, Modifier.width(cardW), onClick = { g.tapPermanent(c, mine) }, onLongClick = { g.preview(c) }, glow = glow, cornerRadius = 4f)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, valueColor: Color = Xp.ink, hl: Boolean = false) {
    Row(Modifier.padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        XText("$label ", 12.sp)
        XText(value, 12.sp, valueColor, bold = hl, modifier = if (hl) Modifier.background(Color(0xFF7CD86B)).padding(horizontal = 3.dp) else Modifier)
    }
}

@Composable
private fun GameInfo(g: SkinGame) {
    val prio = g.snap.players.getOrNull(g.snap.priorityPlayer)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
        Field("Turn:", "${g.snap.turnNumber}")
        Field("Phase:", g.step)
        Field("Priority:", if (prio?.id == g.me?.id) "You" else prio?.name ?: "—", hl = prio?.id == g.me?.id)
        Box(Modifier.fillMaxWidth().padding(vertical = 5.dp).height(1.dp).background(Color(0xFFACA899)))
        Field("Actions available:", "${g.actions.size}")
        val pool = g.me?.manaPool.orEmpty()
        Field("Mana:", listOf("W", "U", "B", "R", "G", "C").joinToString(" ") { "$it${pool[it] ?: 0}" })
    }
}

@Composable
private fun LiveInfo(g: SkinGame) {
    val online = g.t.wifiRole != null
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
        XText("Arcana – Live", 12.sp, mono = true)
        XText("Commander (${g.snap.players.size} Players)", 12.sp, mono = true)
        XText("Room: " + if (online) "online" else "forge (offline)", 12.sp, mono = true)
        Row { XText("Status: ", 12.sp, mono = true); XText(if (online) "Multiplayer ●" else "Vs AI ●", 12.sp, Color(0xFF2E8B2E), mono = true) }
        XText("Turn ${g.snap.turnNumber} · ${g.step}", 12.sp, mono = true)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Phases(g: SkinGame) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(6.dp)) {
        ChimeraPhaseStops.PHASES.forEach { ph ->
            val now = g.phase == ph
            val stop = ChimeraPhaseStops.has(ph)
            Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).background(if (now) Color(0xFF6FD45A) else Color.Transparent)
                .combinedClickable(onClick = { ChimeraPhaseStops.set("mine:$ph", !ChimeraPhaseStops.stops.contains("mine:$ph")); ChimeraHaptics.tick() })
                .padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                XText(if (now) "➜" else "", 14.sp, Color(0xFF1B5E20), bold = true, modifier = Modifier.width(20.dp))
                Box(Modifier.size(14.dp).background(Color.White).border(1.dp, Color(0xFF1C5180)), contentAlignment = Alignment.Center) {
                    if (stop) Text("✓", fontSize = 11.sp, color = Color(0xFF21A121), fontWeight = FontWeight.Black)
                }
                XText(ph.lowercase().replaceFirstChar { it.uppercase() }, 13.sp, bold = now, modifier = Modifier.padding(start = 8.dp))
            }
        }
        XText("✓ = stop here on your turn", 10.sp, Xp.muted, modifier = Modifier.padding(top = 4.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StackBody(g: SkinGame) {
    Box(Modifier.fillMaxSize().padding(6.dp).background(Color.White).border(1.dp, Color(0xFF7F9DB9)).combinedClickableNoHold { g.stack() }) {
        if (g.snap.stack.isEmpty()) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("📚", fontSize = 30.sp); XText("The stack is empty.", 12.sp, Xp.muted)
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(6.dp)) {
            g.snap.stack.forEachIndexed { i, s ->
                Column(Modifier.fillMaxWidth().background(if (i == 0) Color(0xFFDCE8FA) else Color.Transparent).padding(4.dp)) {
                    XText((if (i == 0) "▶ " else "") + s.name, 12.sp, bold = i == 0, maxLines = 2)
                    XText(s.controllerName + (if (s.targetNames.isNotEmpty()) " → " + s.targetNames.joinToString() else ""), 10.sp, Xp.muted, maxLines = 2)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HandBody(ui: SkinUi) {
    val g = ui.g
    BoxWithConstraints(Modifier.fillMaxSize().background(Xp.felts)) {
        val cardW = (maxWidth / 3.4f).coerceIn(50.dp, 120.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(6.dp)) {
            if (g.hand.isEmpty()) XText("Your hand is empty.", 12.sp, Color.White)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                g.hand.forEach { c ->
                    val sel = ui.selected?.id == c.id
                    CardFace(c, Modifier.width(cardW).then(if (sel) Modifier.border(3.dp, Color(0xFFFFE066), RoundedCornerShape(5.dp)) else Modifier),
                        onClick = { ui.pick(c) }, onLongClick = { g.preview(c) }, showCounters = false, glow = g.castable(c), tutorialZone = "hand", cornerRadius = 4f)
                }
            }
            XText("Tap a card, then Cast. Hold to read it.", 10.sp, Color(0xCCFFFFFF), modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** The bottom action bar of the landscape desktop. */
@Composable
private fun ActionBar(ui: SkinUi) {
    val g = ui.g
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        XButton("Undo", { g.undo() }, Modifier.weight(0.8f), enabled = g.canUndo, icon = "↶")
        XButton("Actions (${g.actions.size})", { g.openActions() }, Modifier.weight(1.1f), enabled = g.actions.isNotEmpty(), hot = g.actions.isNotEmpty(), icon = "⚙")
        XButton("Cast", { if (ui.selected == null) XpDesk.show("hand") else g.cast(ui.selected) }, Modifier.weight(0.8f), enabled = !g.busy, icon = "✨")
        XButton("Commander", ui.openCommander, Modifier.weight(1.1f), hot = g.commanderGlow, icon = "👑")
        XButton("Library (${g.me?.library?.size ?: 0})", { if (g.snap.topLibraryCard != null) g.t.argentumTopLibraryOpen = true }, Modifier.weight(1f), icon = "📘")
        XButton("Grave (${g.me?.graveyard?.size ?: 0})", { g.graveyard() }, Modifier.weight(0.9f), icon = "🪦")
        XButton("Exile (${g.me?.exile?.size ?: 0})", { g.exile() }, Modifier.weight(0.9f), icon = "🌀")
        XButton("Stack (${g.stackCount})", { g.stack() }, Modifier.weight(0.9f), icon = "📚")
        XButton(g.passLabel, { g.pass() }, Modifier.weight(1.5f), enabled = g.canPass, hot = true, icon = "▶", onHold = { g.holdPass() })
    }
}

// ---- portrait: the Commander window

private object Expanded { val groups = mutableStateMapOf<String, Boolean>() }

private fun typeIcon(c: GameCard): String {
    val t = c.type
    return when {
        c.isCommander -> "👑"
        t.contains("Land", true) -> when {
            t.contains("Forest") -> "🌳"; t.contains("Island") -> "💧"; t.contains("Swamp") -> "💀"; t.contains("Mountain") -> "🔥"; t.contains("Plains") -> "☀️"; else -> "⛰️"
        }
        t.contains("Token", true) || c.isToken -> "🔹"
        t.contains("Creature", true) -> "⚔️"
        t.contains("Planeswalker", true) -> "🌟"
        t.contains("Artifact", true) -> "⚙️"
        t.contains("Enchantment", true) -> "✴️"
        t.contains("Instant", true) -> "⚡"
        t.contains("Sorcery", true) -> "📜"
        else -> "▫️"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MainWindow(ui: SkinUi) {
    val g = ui.g
    Column(Modifier.fillMaxSize()) {
        MenuBar(g)
        Row(Modifier.fillMaxWidth().padding(4.dp).border(1.dp, Color(0xFFACA899)).background(Color(0xFFF7F6F0)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("🃏", fontSize = 24.sp, modifier = Modifier.padding(end = 8.dp))
            XText("Turn ${g.snap.turnNumber} · ${g.step}" + if (g.myTurn) " · YOUR TURN" else "", 14.sp, bold = true, maxLines = 2)
        }
        // players table
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp).border(1.dp, Color(0xFFACA899)).background(Color.White)) {
            (listOfNotNull(g.me) + g.opponents).forEach { p ->
                val me = p.id == g.me?.id
                Row(Modifier.fillMaxWidth().background(if (me) Color(0xFFC9EEC0) else Color.Transparent).padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    XText(if (me) "You" else (if (p.isComputer) "AI – " else "") + p.name, 12.sp, bold = me, mono = true, modifier = Modifier.weight(1.6f))
                    XText(if (p.isEliminated) "out" else "${p.life} life", 12.sp, mono = true, modifier = Modifier.weight(1f)
                        .then(if (p.life <= 10 && !p.isEliminated) Modifier.background(Color(0xFFF7B6B6)) else Modifier))
                    XText("hand ${p.hand.size}", 12.sp, mono = true, modifier = Modifier.weight(1f))
                    XText("lib ${p.library.size}", 12.sp, mono = true, modifier = Modifier.weight(0.9f))
                }
            }
        }
        // mana row
        val pool = g.me?.manaPool.orEmpty()
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            XText("Mana: ", 12.sp)
            listOf("W" to Color(0xFFF8F1CF), "U" to Color(0xFF9FD3F5), "B" to Color(0xFFB9B1AE), "R" to Color(0xFFF2A88C), "G" to Color(0xFF9ED7A4), "C" to Color(0xFFD9D6CF)).forEach { (k, col) ->
                Box(Modifier.padding(end = 4.dp).size(24.dp).clip(CircleShape).background(col).border(1.dp, Color(0xFF55524A), CircleShape), contentAlignment = Alignment.Center) {
                    XText(if ((pool[k] ?: 0) > 0) "${pool[k]}" else k, 10.sp, bold = (pool[k] ?: 0) > 0)
                }
            }
        }
        // phase tabs (tap: stop here on your turn)
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            ChimeraPhaseStops.PHASES.forEach { ph ->
                val now = g.phase == ph
                Box(Modifier.weight(1f).height(28.dp).clip(RoundedCornerShape(3.dp))
                    .background(if (now) Brush.verticalGradient(listOf(Color(0xFF5C93E8), Color(0xFF2A62C9))) else Xp.button)
                    .border(1.dp, Color(0xFF003C74), RoundedCornerShape(3.dp))
                    .combinedClickableNoHold { ChimeraPhaseStops.set("mine:$ph", !ChimeraPhaseStops.stops.contains("mine:$ph")); ChimeraHaptics.tick() },
                    contentAlignment = Alignment.Center) {
                    XText("[" + ph.lowercase().replaceFirstChar { it.uppercase() } + "]" + if (ChimeraPhaseStops.has(ph)) "•" else "", 10.sp,
                        if (now) Color.White else Xp.ink, mono = true)
                }
            }
        }
        // the board as a list
        Column(Modifier.weight(1f).fillMaxWidth().padding(4.dp).border(1.dp, Color(0xFF7F9DB9)).background(Color.White).verticalScroll(rememberScrollState())) {
            BoardGroup("hand", "You · hand (${g.hand.size})", g.hand, g, mine = true, ui = ui, hand = true)
            (g.opponents + listOfNotNull(g.me)).forEach { p ->
                val mine = p.id == g.me?.id
                BoardGroup(p.id, (if (mine) "You" else (if (p.isComputer) "AI · " else "") + p.name) + " · board (${p.battlefield.size})",
                    p.battlefield.sortedBy { it.type.contains("Land", true) }, g, mine, ui)
            }
        }
        // buttons
        Column(Modifier.fillMaxWidth().padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                XButton("Undo", { g.undo() }, Modifier.weight(1f), enabled = g.canUndo, icon = "↶", size = 11.sp)
                XButton("Actions (${g.actions.size})", { g.openActions() }, Modifier.weight(1.2f), enabled = g.actions.isNotEmpty(), icon = "⚡", size = 11.sp)
                XButton("Cast", { g.cast(ui.selected) }, Modifier.weight(1f), enabled = !g.busy && ui.selected != null, icon = "✨", size = 11.sp)
                XButton("Commander", ui.openCommander, Modifier.weight(1.3f), hot = g.commanderGlow, icon = "👑", size = 11.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                XButton("Library\n(${g.me?.library?.size ?: 0})", { if (g.snap.topLibraryCard != null) g.t.argentumTopLibraryOpen = true }, Modifier.weight(1f), icon = "📚", size = 11.sp)
                XButton("Graveyard\n(${g.me?.graveyard?.size ?: 0})", { g.graveyard() }, Modifier.weight(1.1f), icon = "🪦", size = 11.sp)
                XButton("Exile\n(${g.me?.exile?.size ?: 0})", { g.exile() }, Modifier.weight(1f), icon = "✨", size = 11.sp)
                XButton("Stack\n(${g.stackCount})", { g.stack() }, Modifier.weight(1f), icon = "📚", size = 11.sp)
            }
            XButton(g.passLabel + "   |   (Hold: auto-pass)", { g.pass() }, Modifier.fillMaxWidth().heightIn(min = 44.dp), enabled = g.canPass, hot = true,
                icon = "▶", size = 13.sp, onHold = { g.holdPass() })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BoardGroup(key: String, title: String, cards: List<GameCard>, g: SkinGame, mine: Boolean, ui: SkinUi, hand: Boolean = false) {
    val open = Expanded.groups[key] ?: true
    Row(Modifier.fillMaxWidth().background(Color(0xFFC1D2EE)).combinedClickableNoHold { Expanded.groups[key] = !open }.padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(13.dp).background(Color.White).border(1.dp, Color(0xFF7F9DB9)), contentAlignment = Alignment.Center) { XText(if (open) "−" else "+", 10.sp, bold = true) }
        XText(title, 13.sp, bold = true, mono = true, modifier = Modifier.padding(start = 8.dp))
    }
    if (!open) return
    if (cards.isEmpty()) XText("   (empty)", 12.sp, Xp.muted, mono = true)
    cards.forEach { c ->
        val sel = hand && ui.selected?.id == c.id
        val hot = if (hand) g.castable(c) else mine && c.id in g.playable && !g.busy
        Row(Modifier.fillMaxWidth().heightIn(min = 26.dp).background(if (sel) Xp.select else if (hot) Color(0xFFE6F6DF) else Color.Transparent)
            .combinedClickable(onClick = { if (hand) ui.pick(c) else g.tapPermanent(c, mine) }, onLongClick = { g.preview(c) })
            .padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(typeIcon(c), fontSize = 12.sp, modifier = Modifier.width(24.dp))
            val counters = c.counters.filterValues { it > 0 }.entries.joinToString("") { " +${it.value}" }.take(12)
            XText((if (c.isFaceDown) "Face-down card" else c.name) + counters, 12.sp, if (sel) Color.White else Xp.ink, mono = true, modifier = Modifier.weight(1f))
            if (c.power != null && c.toughness != null) XText("${c.power}/${c.toughness}", 12.sp, if (sel) Color.White else Xp.ink, mono = true, modifier = Modifier.padding(horizontal = 6.dp))
            XText(when { c.tapped -> "TAP"; c.summoningSick && c.type.contains("Creature", true) -> "NEW"; else -> "" }, 11.sp,
                if (c.tapped) Xp.ink else Color(0xFF1F4FD1), mono = true, modifier = Modifier.width(30.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Overview(g: SkinGame) {
    Column(Modifier.fillMaxSize().padding(6.dp).background(Color.White).border(1.dp, Color(0xFF7F9DB9)).verticalScroll(rememberScrollState()).padding(4.dp)) {
        (g.opponents + listOfNotNull(g.me)).forEach { p ->
            val mine = p.id == g.me?.id
            val open = Expanded.groups[p.id] ?: true
            Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).background(if (open) Color(0xFFC1D2EE) else Color.Transparent)
                .combinedClickableNoHold { Expanded.groups[p.id] = !open }.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                XText(if (open) "⊟" else "⊞", 13.sp)
                Text("📁", fontSize = 14.sp, modifier = Modifier.padding(horizontal = 6.dp))
                XText((if (mine) "You" else p.name) + " (${p.battlefield.size})", 13.sp, mono = true)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Zones(ui: SkinUi) {
    val g = ui.g
    Column(Modifier.fillMaxSize().padding(6.dp).background(Color.White).border(1.dp, Color(0xFF7F9DB9)).verticalScroll(rememberScrollState()).padding(4.dp)) {
        listOf<Triple<String, String, () -> Unit>>(
            Triple("🂠", "Library (${g.me?.library?.size ?: 0})") { if (g.snap.topLibraryCard != null) g.t.argentumTopLibraryOpen = true },
            Triple("🪦", "Graveyard (${g.me?.graveyard?.size ?: 0})") { g.graveyard() },
            Triple("✨", "Exile (${g.me?.exile?.size ?: 0})") { g.exile() },
            Triple("📚", "Stack (${g.stackCount})") { g.stack() },
            Triple("👑", "Commander") { ui.openCommander() },
            Triple("🂡", "Hand (${g.hand.size})") { XpDesk.show("hand") },
        ).forEach { (icon, label, act) ->
            Row(Modifier.fillMaxWidth().heightIn(min = 38.dp).combinedClickableNoHold(act).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(icon, fontSize = 20.sp, modifier = Modifier.width(34.dp))
                XText(label, 13.sp, mono = true)
            }
        }
    }
}
