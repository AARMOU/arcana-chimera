package com.arcanatable

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Built-in backgrounds (owner, Nov 9: "built in (tasteful) backgrounds"), drawn in code on the phone the first time
 * one is used, so they're small to ship, sharp at any size and free of licensing questions. They work for the table
 * (Studio → Table → Background) and the hand drawer (Studio → Hand). A picture of your own still replaces them.
 */
internal object ChimeraBackdrops {
    val ALL = listOf("felt" to "Felt", "parchment" to "Parchment", "slate" to "Slate", "wood" to "Dark wood",
        "starfield" to "Starfield", "dusk" to "Dusk", "neon" to "Neon grid")
    private const val W = 720
    private const val H = 1280

    /** For the table: a file in Studio's background folder; returns its name (call off the main thread when you can). */
    fun ensureTable(context: Context, id: String): String {
        val name = "bg-chimera-$id.jpg"
        val file = File(File(context.filesDir, "studio-backdrops").apply { mkdirs() }, name)
        if (!file.isFile || file.length() == 0L) write(file, render(id))
        return name
    }

    /** For the hand drawer: the file path to use as its picture. */
    fun ensureDrawer(context: Context, id: String): String {
        val file = File(File(context.filesDir, "chimera").apply { mkdirs() }, "builtin-$id.jpg")
        if (!file.isFile || file.length() == 0L) write(file, render(id))
        return file.absolutePath
    }

    fun isBuiltIn(name: String?) = name != null && (name.startsWith("bg-chimera-") || File(name).name.startsWith("builtin-"))

    private fun write(file: File, bitmap: Bitmap) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        tmp.renameTo(file)
    }

    // ---------------------------------------------------------------- drawing

    private fun hash(x: Int, y: Int, seed: Int): Float {
        var h = x * 374761393 + y * 668265263 + seed * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xffff) / 65535f
    }

    private fun noise(x: Float, y: Float, seed: Int): Float {
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        val fx = x - x0; val fy = y - y0
        val sx = fx * fx * (3 - 2 * fx); val sy = fy * fy * (3 - 2 * fy)
        val a = hash(x0, y0, seed); val b = hash(x0 + 1, y0, seed)
        val c = hash(x0, y0 + 1, seed); val d = hash(x0 + 1, y0 + 1, seed)
        return (a + (b - a) * sx) + ((c + (d - c) * sx) - (a + (b - a) * sx)) * sy
    }

    private fun fbm(x: Float, y: Float, seed: Int, octaves: Int = 4): Float {
        var sum = 0f; var amp = 0.5f; var f = 1f
        repeat(octaves) { sum += noise(x * f, y * f, seed + it) * amp; amp *= 0.5f; f *= 2f }
        return sum // ~0..1
    }

    private fun rgb(r: Float, g: Float, b: Float): Int =
        (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)

    fun render(id: String): Bitmap {
        val px = IntArray(W * H)
        for (y in 0 until H) for (x in 0 until W) {
            val u = x / W.toFloat(); val v = y / H.toFloat()
            val dx = u - 0.5f; val dy = (v - 0.5f) * 1.2f
            val vignette = (1f - 0.9f * (dx * dx + dy * dy)).coerceIn(0.35f, 1f)
            val grain = hash(x, y, 7) - 0.5f
            px[y * W + x] = when (id) {
                "felt" -> {
                    val n = fbm(x / 140f, y / 140f, 11) * 0.6f + grain * 0.25f
                    val k = (0.78f + 0.4f * n) * vignette
                    rgb(22f * k, 64f * k, 42f * k)
                }
                "parchment" -> {
                    val n = fbm(x / 120f, y / 120f, 21)
                    val fibre = fbm(x / 6f, y / 90f, 23, 2) * 0.08f
                    val k = (0.86f + 0.15f * n - fibre + grain * 0.05f) * (0.55f + 0.45f * vignette)
                    rgb(206f * k, 180f * k, 132f * k)
                }
                "slate" -> {
                    val n = fbm(x / 200f + y / 400f, y / 120f, 31)
                    val streak = sin((x + y) / 9f + n * 7f) * 0.035f
                    val k = (0.8f + 0.35f * n + streak + grain * 0.06f) * vignette
                    rgb(46f * k, 54f * k, 63f * k)
                }
                "wood" -> {
                    val warp = fbm(x / 260f, y / 34f, 41) * 14f
                    val ring = 0.5f + 0.5f * sin(y / 7f + warp)
                    val k = (0.7f + 0.22f * ring + 0.12f * fbm(x / 30f, y / 3f, 43, 2) + grain * 0.05f) * vignette
                    rgb(70f * k, 42f * k, 25f * k)
                }
                "starfield" -> {
                    val neb = fbm(x / 300f, y / 300f, 51)
                    val glow = ((neb - 0.45f) * 2.2f).coerceIn(0f, 1f)
                    rgb(8f + 26f * glow * (1f - v) + 4f * v, 10f + 14f * glow * v, 24f + 34f * glow + 6f * v)
                }
                "dusk" -> {
                    val k = 0.9f + 0.1f * fbm(x / 220f, y / 220f, 61) + grain * 0.04f
                    rgb((44f - 30f * v) * k * vignette + 6f, (24f + 34f * v) * k * vignette + 4f, (60f + 12f * v) * k * vignette + 8f)
                }
                "neon" -> {
                    val gx = abs(((x + 24) % 48) - 24) / 24f; val gy = abs(((y + 24) % 48) - 24) / 24f
                    val line = maxOf((1f - gx * 6f).coerceIn(0f, 1f), (1f - gy * 6f).coerceIn(0f, 1f))
                    val fade = (0.25f + 0.75f * v) * vignette
                    rgb(10f + (190f * (1f - v) + 20f * v) * line * fade * 0.55f, 6f + (40f + 150f * v) * line * fade * 0.5f,
                        20f + (200f * (1f - v) + 230f * v) * line * fade * 0.55f)
                }
                else -> rgb(24f * vignette, 28f * vignette, 30f * vignette)
            }
        }
        if (id == "starfield") { // stars, a few brighter with a soft halo
            var s = 12345L
            fun rnd(): Float { s = s * 6364136223846793005L + 1442695040888963407L; return ((s ushr 33) and 0xffffff) / 16777216f }
            repeat(1400) {
                val x = (rnd() * W).toInt(); val y = (rnd() * H).toInt(); val b = rnd()
                val size = if (b > 0.97f) 2 else if (b > 0.8f) 1 else 0
                for (oy in -size..size) for (ox in -size..size) {
                    val xx = x + ox; val yy = y + oy
                    if (xx !in 0 until W || yy !in 0 until H) continue
                    val fall = 1f - sqrt((ox * ox + oy * oy).toFloat()) / (size + 1f)
                    val add = (90f + 165f * b) * fall
                    val p = px[yy * W + xx]
                    px[yy * W + xx] = rgb(((p shr 16) and 255) + add, ((p shr 8) and 255) + add, (p and 255) + add * 1.05f)
                }
            }
        }
        return Bitmap.createBitmap(px, W, H, Bitmap.Config.ARGB_8888)
    }
}
