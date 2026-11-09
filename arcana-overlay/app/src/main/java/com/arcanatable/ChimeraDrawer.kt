package com.arcanatable

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.nativeCanvas
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Hand drawer opacity (owner, Nov 9: a way to change it, 0-100%). Only the drawer's background fades (and the shade
 * Arcana puts over the table behind it, with it); cards and buttons stay solid. Kept on this phone; set in
 * Arcana Studio → Hand, under the hand palette.
 */
object ChimeraDrawer {
    private const val KEY = "chimera_drawer_opacity"

    private val prefs: SharedPreferences? by lazy {
        runCatching {
            val app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Context
            app.getSharedPreferences("chimera_drawer", Context.MODE_PRIVATE)
        }.getOrNull()
    }

    var opacity by mutableFloatStateOf(prefs?.getFloat(KEY, 1f)?.coerceIn(0f, 1f) ?: 1f)
        private set

    fun set(value: Float) {
        opacity = value.coerceIn(0f, 1f)
        prefs?.edit()?.putFloat(KEY, opacity)?.apply()
    }

    /** One step of 10% up or down. */
    fun step(direction: Int) = set(((opacity * 10f).roundToInt() + direction) / 10f)

    val label: String get() = "${(opacity * 100f).roundToInt()}%"
}

// ---------------------------------------------------------------- background picture (owner, Nov 9: picture, GIFs too)

/** The drawer's background picture: a copy in the app's own files, so it stays when the original is deleted. */
object ChimeraDrawerPicture {
    private const val KEY = "chimera_drawer_picture"
    private val prefs: SharedPreferences? by lazy {
        runCatching {
            val app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Context
            app.getSharedPreferences("chimera_drawer", Context.MODE_PRIVATE)
        }.getOrNull()
    }
    var path by androidx.compose.runtime.mutableStateOf(prefs?.getString(KEY, null)?.takeIf { java.io.File(it).isFile })
        private set
    var problem by androidx.compose.runtime.mutableStateOf<String?>(null)

    /** Copies the picked picture (up to 40 MB) into the app; call off the main thread. */
    fun set(context: Context, uri: android.net.Uri) {
        runCatching {
            val dir = java.io.File(context.filesDir, "chimera").apply { mkdirs() }
            val target = java.io.File(dir, "drawer-${System.currentTimeMillis()}")
            context.contentResolver.openInputStream(uri)!!.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024); var total = 0L
                    while (true) { val n = input.read(buf); if (n < 0) break; total += n
                        if (total > 40L * 1024 * 1024) error("That picture is over 40 MB."); out.write(buf, 0, n) }
                }
            }
            val old = path
            prefs?.edit()?.putString(KEY, target.absolutePath)?.apply()
            path = target.absolutePath; problem = null
            old?.let { java.io.File(it).takeIf { f -> f.absolutePath != target.absolutePath }?.delete() }
        }.onFailure { problem = it.message ?: "Couldn't use that picture." }
    }

    fun clear() {
        path?.let { java.io.File(it).delete() }
        prefs?.edit()?.remove(KEY)?.apply()
        path = null
    }

    /** Decodes the picture; GIF and animated WebP come back animated (Android 9+). Large pictures are scaled down. */
    fun decode(context: Context, file: String): android.graphics.drawable.Drawable? = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(java.io.File(file))) { decoder, info, _ ->
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > 2048) decoder.setTargetSampleSize(((longest + 2047) / 2048).coerceAtLeast(1))
            }
        } else {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = ((longest + 2047) / 2048).coerceAtLeast(1) }
            android.graphics.BitmapFactory.decodeFile(file, opts)?.let { android.graphics.drawable.BitmapDrawable(context.resources, it) }
        }
    }.getOrNull()
}

/** Draws an Android drawable in Compose, animating GIFs (the drawable asks to be redrawn; this redraws). */
private class ChimeraDrawablePainter(val drawable: android.graphics.drawable.Drawable) :
    androidx.compose.ui.graphics.painter.Painter(), androidx.compose.runtime.RememberObserver {
    private var tick by androidx.compose.runtime.mutableIntStateOf(0)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val callback = object : android.graphics.drawable.Drawable.Callback {
        override fun invalidateDrawable(who: android.graphics.drawable.Drawable) { tick++ }
        override fun scheduleDrawable(who: android.graphics.drawable.Drawable, what: Runnable, `when`: Long) { handler.postAtTime(what, `when`) }
        override fun unscheduleDrawable(who: android.graphics.drawable.Drawable, what: Runnable) { handler.removeCallbacks(what) }
    }
    override val intrinsicSize: androidx.compose.ui.geometry.Size
        get() = if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0)
            androidx.compose.ui.geometry.Size(drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        else androidx.compose.ui.geometry.Size.Unspecified
    override fun applyAlpha(alpha: Float): Boolean { drawable.alpha = (alpha * 255).roundToInt().coerceIn(0, 255); return true }
    override fun androidx.compose.ui.graphics.drawscope.DrawScope.onDraw() {
        tick // read, so each animation frame redraws
        drawIntoCanvas2 { canvas ->
            drawable.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
            drawable.draw(canvas)
        }
    }
    private inline fun androidx.compose.ui.graphics.drawscope.DrawScope.drawIntoCanvas2(block: (android.graphics.Canvas) -> Unit) =
        drawContext.canvas.nativeCanvas.let(block)
    override fun onRemembered() {
        drawable.callback = callback
        drawable.setVisible(true, true)
        (drawable as? android.graphics.drawable.Animatable)?.start()
    }
    override fun onForgotten() {
        (drawable as? android.graphics.drawable.Animatable)?.stop()
        drawable.setVisible(false, false)
        drawable.callback = null
        handler.removeCallbacksAndMessages(null)
    }
    override fun onAbandoned() = onForgotten()
}

/** The drawer's picture as a modifier (nothing without one); faded with the drawer's opacity. */
@androidx.compose.runtime.Composable
fun chimeraDrawerPicture(): androidx.compose.ui.Modifier {
    val context = androidx.compose.ui.platform.LocalContext.current
    val path = ChimeraDrawerPicture.path
    var drawable by androidx.compose.runtime.remember(path) { androidx.compose.runtime.mutableStateOf<android.graphics.drawable.Drawable?>(null) }
    androidx.compose.runtime.LaunchedEffect(path) {
        drawable = path?.let { p -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ChimeraDrawerPicture.decode(context, p) } }
    }
    val d = drawable ?: return androidx.compose.ui.Modifier
    val painter = androidx.compose.runtime.remember(d) { ChimeraDrawablePainter(d) }
    return androidx.compose.ui.Modifier.paint(painter, contentScale = androidx.compose.ui.layout.ContentScale.Crop, alpha = ChimeraDrawer.opacity)
}

/** Studio → Hand: choose or remove the drawer's picture, and its opacity. */
@androidx.compose.runtime.Composable
internal fun ChimeraDrawerControls() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch(kotlinx.coroutines.Dispatchers.IO) { ChimeraDrawerPicture.set(context, uri) }
    }
    StudioStepper("Drawer opacity", ChimeraDrawer.label, "chimera-drawer-opacity", { ChimeraDrawer.step(-1) }, { ChimeraDrawer.step(1) })
    androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())) {
        StudioChip(if (ChimeraDrawerPicture.path == null) "Drawer picture or GIF…" else "Change drawer picture…", true, "chimera-drawer-picture") {
            pick.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        if (ChimeraDrawerPicture.path != null) StudioChip("Remove picture", true, "chimera-drawer-picture-remove") { ChimeraDrawerPicture.clear() }
    }
    ChimeraDrawerPicture.problem?.let { androidx.compose.material3.Text(it, color = androidx.compose.ui.graphics.Color(0xFFE9978B), fontSize = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp)) }
}
