package com.arcanatable

import android.content.Context

/**
 * Hand designs for the Studio presets (owner, Nov 9: "dramatically change the layout, while also not getting rid of
 * button text... get really creative"), plus the side-column option. Every design keeps every button with its text,
 * Pass, the first card slot, and the drawer's status line, menu and close. Design units: the canvas is the screen
 * width ([w]) and the hand starts at the origin, like Studio's own starting designs.
 */
internal object ChimeraHandLayouts {
    /** The action buttons in a fixed order (Pass first), and the counters. */
    private val BUTTONS = listOf("pass" to "PASS", "undo" to "UNDO", "actions" to "ACTIONS", "cast" to "CAST")
    private val COUNTERS = listOf("commander" to "COMMANDER", "metric1" to "LIBRARY", "metric2" to "GRAVEYARD", "metric3" to "EXILE")

    private fun origin(w: Float, h: Float) = if (w > h) 64f else (h * .32f).coerceAtLeast(64f)

    private fun e(binding: String, label: String, x: Float, y: Float, w: Float, h: Float, fs: Float = 11f, r: Float = 10f,
        strong: Boolean = false, shape: String = "", color: String? = null, text: String? = null) =
        StudioElement(binding, if (binding in setOf("pass", "undo", "actions", "cast")) "button" else "metric", label, x, y, w, h,
            fs = fs, r = r, strong = strong, binding = binding, color = color, textColor = text, shape = shape)

    private fun top(w: Float, o: Float, text: String? = null) = listOf(
        StudioElement("chimera-status", "status", "Hand 7 · Main 1 · 2 playable", 12f, o + 6f, (w / 2f - 40f).coerceAtLeast(96f), 24f, fs = 11f, r = 0f,
            binding = "status", textColor = text),
        StudioElement("chimera-menu", "icon", "⋮", w - 88f, o + 2f, 36f, 32f, fs = 16f, r = 8f, binding = "menu", textColor = text),
        StudioElement("chimera-close", "icon", "✕", w - 46f, o + 2f, 36f, 32f, fs = 15f, r = 8f, binding = "close", textColor = text))

    /** Two card slots spanning [x0]..[x1]: the drawer lays the real hand out in that area. */
    private fun slots(x0: Float, x1: Float, y: Float, cw: Float = 92f, ch: Float = 132f, r: Float = 5f): List<StudioElement> {
        val first = StudioElement("card0", "card", "Card 1", x0, y, cw, ch, r = r, binding = "card0")
        return if (x1 - x0 >= cw * 2 + 8f) listOf(first, StudioElement("card1", "card", "Card 2", x1 - cw, y, cw, ch, r = r, binding = "card1"))
            else listOf(first)
    }

    /** One row of [items] across [x0]..[x1], widths by weight. */
    private fun row(items: List<Pair<String, String>>, x0: Float, x1: Float, y: Float, h: Float, gap: Float = 6f, fs: Float = 11f,
        shape: String = "", weights: Map<String, Float> = emptyMap(), strongPass: Boolean = true): List<StudioElement> {
        val total = items.sumOf { (weights[it.first] ?: 1f).toDouble() }.toFloat()
        val unit = (x1 - x0 - gap * (items.size - 1)) / total
        var x = x0
        return items.map { (b, label) ->
            val wgt = (weights[b] ?: 1f) * unit
            e(b, label, x, y, wgt, h, fs = fs, shape = shape, strong = strongPass && b == "pass").also { x += wgt + gap }
        }
    }

    private fun column(items: List<Pair<String, String>>, x: Float, y0: Float, w: Float, h: Float, gap: Float = 6f, fs: Float = 11f,
        shape: String = "", passH: Float = h): List<StudioElement> {
        var y = y0
        return items.map { (b, label) ->
            val hh = if (b == "pass") passH else h
            e(b, label, x, y, w, hh, fs = fs, shape = shape, strong = b == "pass").also { y += hh + gap }
        }
    }

    private fun layout(w: Float, h: Float, o: Float, els: List<StudioElement>, cards: String, theme: Map<String, String>) =
        StudioLayout(els, theme = theme, canvasWidth = w.coerceIn(240f, 2000f), canvasHeight = h.coerceIn(240f, 4000f), originY = o,
            chimera = ChimeraHandDesign.VERSION, cards = cards)

    private fun theme(bg: String, panel: String, raised: String, accent: String, text: String, muted: String, gold: String) =
        mapOf("background" to bg, "panel" to panel, "raised" to raised, "accent" to accent, "text" to text, "muted" to muted, "gold" to gold)

    // ---------------------------------------------------------------- the option: buttons in a side column

    /** Any design with its buttons and counters stacked in a column on the left and the cards filling the rest. */
    fun sideColumn(base: StudioLayout): StudioLayout {
        val w = base.canvasWidth; val o = base.originY
        val y0 = o + 40f
        val cw = (w * 0.27f).coerceIn(120f, 220f)
        val keep = base.elements.associateBy { it.binding }
        val stack = (BUTTONS + COUNTERS).mapNotNull { (b, label) -> keep[b] ?: e(b, label, 0f, 0f, 1f, 1f) }
        var y = y0
        val placed = stack.map { el ->
            val hh = if (el.binding == "pass") 56f else 44f
            el.copy(x = 12f, y = y, w = cw, h = hh, visible = true).also { y += hh + 6f }
        }
        val tops = base.elements.filter { it.binding in setOf("status", "menu", "close") }
        val template = base.elements.filter { it.kind == "card" }.minByOrNull { it.binding.removePrefix("card").toIntOrNull() ?: 99 }
        val cards = slots(12f + cw + 12f, w - 12f, y0, template?.w ?: 92f, template?.h ?: 132f, template?.r ?: 5f)
        val others = base.elements.filter { it.kind != "card" && it.binding !in setOf("status", "menu", "close") && stack.none { s -> s.id == it.id } }
            .map { it.copy(x = 12f + cw + 12f, y = y0 + (template?.h ?: 132f) + 10f) }
        return base.copy(elements = (tops.ifEmpty { top(w, o) } + placed + cards + others).take(64), chimera = ChimeraHandDesign.VERSION)
    }

    // ---------------------------------------------------------------- the presets' hands

    fun forPreset(name: String, w: Float, h: Float, keepColours: Boolean): StudioLayout? {
        val o = origin(w, h); val y0 = o + 40f
        fun t(m: Map<String, String>) = if (keepColours) emptyMap() else m
        return when (name) {
            "Classic" -> null // Arcana as it ships: the hand you had before trying presets
            "Duelist's Gauntlet" -> { // one hand on the folded phone: Pass under the thumb, cards fanned above
                val badges = row(COUNTERS, 12f, w - 12f, y0, 30f, fs = 9f, shape = "pill", strongPass = false)
                val cy = y0 + 40f
                val by = cy + 132f + 12f
                val passW = (w * 0.34f).coerceIn(120f, 260f)
                val left = row(BUTTONS.drop(1), 12f, w - 24f - passW, by, 46f, fs = 11f, shape = "pill")
                val pass = e("pass", "PASS", w - 12f - passW, by - 8f, passW, 62f, fs = 13f, strong = true, shape = "pill")
                layout(w, h, o, top(w, o) + badges + slots(12f, w - 12f, cy) + left + pass, "fan",
                    t(theme("#101518", "#161D21", "#222C31", "#2EC4B6", "#E6F1F0", "#8FA3A8", "#D9B65B")))
            }
            "War Table" -> { // an officer's console: buttons in a column, cards in big rows, counters as a ticker
                val cw = (w * 0.27f).coerceIn(120f, 220f)
                val col = column(BUTTONS + COUNTERS.take(1), 12f, y0, cw, 44f, passH = 58f, fs = 11f)
                val rx = 12f + cw + 12f
                val ticker = row(COUNTERS.drop(1), rx, w - 12f, y0 + 132f + 10f, 34f, fs = 9f, shape = "square", strongPass = false)
                layout(w, h, o, top(w, o) + col + slots(rx, w - 12f, y0) + ticker, "grid",
                    t(theme("#0E1A13", "#13241A", "#1D3426", "#C9A24A", "#F1E8D0", "#9DAA94", "#E3C46B")))
            }
            "Grimoire" -> { // a spellbook: cards on the page, labelled tabs down the right edge like bookmarks
                val tw = (w * 0.25f).coerceIn(100f, 180f)
                val tabs = column(BUTTONS + COUNTERS, w - 12f - tw, y0, tw, 38f, gap = 4f, fs = 10f, passH = 50f)
                    .map { it.copy(r = 4f) }
                layout(w, h, o, top(w, o) + tabs + slots(12f, w - 24f - tw, y0, r = 3f), "grid",
                    t(theme("#1E1410", "#2A1C16", "#3B2820", "#8E2C2C", "#F3E6C8", "#B9A586", "#D8B76A")))
            }
            "Arcade Cabinet" -> { // chunky cabinet controls along the bottom, scoreboard counters on top
                val score = row(COUNTERS, 12f, w - 12f, y0, 32f, fs = 10f, shape = "square", strongPass = false)
                    .map { it.copy(color = "#000000", textColor = "#19E3FF") }
                val cy = y0 + 42f
                val by = cy + 132f + 12f
                val controls = row(listOf("undo" to "UNDO", "actions" to "ACTIONS", "pass" to "PASS", "cast" to "CAST"), 12f, w - 12f, by, 58f,
                    fs = 12f, shape = "square", weights = mapOf("pass" to 1.8f))
                layout(w, h, o, top(w, o) + score + slots(12f, w - 12f, cy) + controls, "row",
                    t(theme("#0B0614", "#120A1F", "#24113A", "#FF2BD6", "#E8F7FF", "#8A7CA8", "#19E3FF")))
            }
            "Glass Cockpit" -> { // see-through drawer, a slim Pass bar on top, thin controls hugging the bottom
                val pass = e("pass", "PASS", 12f, y0, w - 24f, 34f, fs = 11f, strong = true, shape = "pill")
                val cy = y0 + 44f
                val by = cy + 150f + 12f
                val bottom = row(listOf("undo" to "UNDO", "actions" to "ACTIONS", "metric1" to "LIBRARY", "metric2" to "GRAVEYARD",
                    "metric3" to "EXILE", "cast" to "CAST", "commander" to "COMMANDER"), 12f, w - 12f, by, 42f, gap = 5f, fs = 9f, shape = "pill", strongPass = false)
                layout(w, h, o, top(w, o) + pass + slots(12f, w - 12f, cy, 104f, 150f) + bottom, "catalogue",
                    t(theme("#07121E", "#0B1B2B", "#12283D", "#5CC8FF", "#E4F3FF", "#87A3BA", "#9AD8FF")))
            }
            "Command Center" -> { // dense: small cards, every counter, tight spacing
                val r1 = row(BUTTONS, 12f, w - 12f, y0, 40f, gap = 4f, fs = 10f)
                val r2 = row(COUNTERS, 12f, w - 12f, y0 + 44f, 40f, gap = 4f, fs = 9f, strongPass = false)
                layout(w, h, o, top(w, o) + r1 + r2 + slots(12f, w - 12f, y0 + 92f, 72f, 103f), "grid",
                    t(theme("#12161B", "#181E25", "#232B34", "#6FA8DC", "#E3E9EF", "#8D99A6", "#C9B26B")))
            }
            "Big & Bold" -> { // oversized buttons with large text, big cards, maximum contrast
                val r1 = row(BUTTONS, 12f, w - 12f, y0, 64f, gap = 8f, fs = 15f)
                val r2 = row(COUNTERS, 12f, w - 12f, y0 + 72f, 64f, gap = 8f, fs = 13f, strongPass = false)
                layout(w, h, o, top(w, o) + r1 + r2 + slots(12f, w - 12f, y0 + 146f, 120f, 172f), "grid",
                    t(theme("#0A0A0A", "#111111", "#262626", "#F2C14E", "#FFFFFF", "#A8A8A8", "#F2C14E")))
            }
            "Zen Garden" -> { // one calm row of cards, soft pills, room to breathe
                val r1 = row(BUTTONS, 16f, w - 16f, y0 + 132f + 16f, 42f, gap = 10f, fs = 11f, shape = "pill", weights = mapOf("pass" to 1.6f))
                val r2 = row(COUNTERS, 16f, w - 16f, y0 + 132f + 68f, 42f, gap = 10f, fs = 9f, shape = "pill", strongPass = false)
                layout(w, h, o, top(w, o) + slots(16f, w - 16f, y0) + r1 + r2, "row",
                    t(theme("#1B1D18", "#23261F", "#30342B", "#9DB08A", "#EEE6D3", "#A9A38F", "#D8C79A")))
            }
            "Battlefield First" -> { // a short drawer: every button on one compact strip, cards in a row
                val strip = row(BUTTONS + COUNTERS, 12f, w - 12f, y0, 38f, gap = 4f, fs = 9f, weights = mapOf("pass" to 1.4f, "commander" to 1.3f, "metric2" to 1.2f))
                layout(w, h, o, top(w, o) + strip + slots(12f, w - 12f, y0 + 46f, 77f, 110f), "row", emptyMap())
            }
            else -> null
        }?.let(ChimeraHandDesign::upgrade)?.takeIf { it.problems().isEmpty() }
    }
}

/** What each preset sets besides Arcana's own look values: its hand, the drawer's opacity and a built-in background. */
internal object ChimeraPresets {
    data class Before(val hand: StudioLayout?, val opacity: Float)

    private val opacity = mapOf("Glass Cockpit" to 0.25f, "Zen Garden" to 0.9f)
    private val backdrop = mapOf("War Table" to "felt", "Grimoire" to "wood", "Arcade Cabinet" to "neon", "Glass Cockpit" to "starfield",
        "Command Center" to "slate", "Zen Garden" to "dusk")
    /** Table colours for presets with a look of their own (the hand designs carry theirs). */
    private val tableTheme = mapOf(
        "Duelist's Gauntlet" to mapOf("background" to "#0C1114", "panel" to "#141B1F", "raised" to "#1F292E", "accent" to "#2EC4B6", "text" to "#E6F1F0", "muted" to "#8FA3A8", "gold" to "#D9B65B"),
        "War Table" to mapOf("background" to "#0B150F", "panel" to "#112018", "raised" to "#1B3024", "accent" to "#C9A24A", "text" to "#F1E8D0", "muted" to "#9DAA94", "gold" to "#E3C46B"),
        "Grimoire" to mapOf("background" to "#171009", "panel" to "#241812", "raised" to "#35241C", "accent" to "#A13A3A", "text" to "#F3E6C8", "muted" to "#B9A586", "gold" to "#D8B76A"),
        "Arcade Cabinet" to mapOf("background" to "#08040F", "panel" to "#120A1F", "raised" to "#24113A", "accent" to "#FF2BD6", "text" to "#E8F7FF", "muted" to "#8A7CA8", "gold" to "#19E3FF"),
        "Glass Cockpit" to mapOf("background" to "#050D16", "panel" to "#0B1B2B", "raised" to "#12283D", "accent" to "#5CC8FF", "text" to "#E4F3FF", "muted" to "#87A3BA", "gold" to "#9AD8FF"),
        "Zen Garden" to mapOf("background" to "#171914", "panel" to "#23261F", "raised" to "#30342B", "accent" to "#9DB08A", "text" to "#EEE6D3", "muted" to "#A9A38F", "gold" to "#D8C79A"),
    )

    fun opacity(name: String) = opacity[name] ?: 1f

    /** The controls project with the preset's own colours and background (unless you keep your colours). */
    fun project(context: Context, project: StudioControlProject, name: String, keepColours: Boolean): StudioControlProject {
        if (keepColours) return project
        val withTheme = tableTheme[name]?.let { project.copy(theme = it) } ?: project
        val bg = backdrop[name] ?: return withTheme.copy(backdrop = null)
        return runCatching { withTheme.copy(backdrop = ChimeraBackdrops.ensureTable(context, bg), backdropFit = "Fill", backdropDim = 0.2f) }.getOrDefault(withTheme)
    }
}
