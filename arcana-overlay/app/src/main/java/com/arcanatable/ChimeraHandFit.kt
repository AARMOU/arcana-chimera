package com.arcanatable

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A Studio hand design in play, fitted to the drawer (owner, Nov 9: "When I reorder the buttons, the resizing resizes
 * everything?"). Only the cards follow the drawer: buttons keep the size and place you gave them (those below the
 * cards move down to make room), and the cards are laid out again in the area your card slots span, as large as the
 * drawer allows and never smaller than the slots you designed. Design units throughout (the canvas scales them to
 * the screen width).
 */
internal object ChimeraHandFit {
    private const val GAP = 8f

    /** [availableHeight]: the drawer's room for the design, in design units. */
    fun fit(elements: List<StudioElement>, cardCount: Int, availableHeight: Float, origin: Float): List<StudioElement> {
        val cards = elements.filter { it.kind == "card" }
        if (cards.isEmpty() || cardCount <= 0 || !availableHeight.isFinite() || availableHeight <= 0f) return elements
        val template = cards.minBy { it.binding.removePrefix("card").toIntOrNull() ?: Int.MAX_VALUE }
        if (template.w <= 0f || template.h <= 0f) return elements
        val designed = cards.filter { !it.id.startsWith("overflow") }.ifEmpty { cards }
        val top = designed.minOf { it.y }
        val bottom = designed.maxOf { it.y + it.h }
        val left = designed.minOf { it.x }
        val regionW = max(designed.maxOf { it.x + it.w } - left, template.w)
        val others = elements.filter { it.kind != "card" }
        val extent = (elements.maxOf { it.y + it.h } - origin).coerceAtLeast(1f)
        val forCards = availableHeight - (extent - (bottom - top)) // the drawer's room minus the buttons' own room
        val aspect = template.w / template.h

        var w = 0f
        var cols = 1
        for (rows in 1..cardCount) {
            val c = ceil(cardCount / rows.toFloat()).toInt()
            val cw = min((regionW - (c - 1) * GAP) / c, ((forCards - (rows - 1) * GAP) / rows) * aspect)
            if (cw > w) { w = cw; cols = c }
        }
        if (w < template.w) { // never smaller than designed: the designed size, extra rows scroll in the drawer
            w = template.w
            cols = floor((regionW + GAP) / (w + GAP)).toInt().coerceAtLeast(1)
        }
        w = min(w, regionW)
        val h = w / aspect
        val rows = ceil(cardCount / cols.toFloat()).toInt()
        val x0 = left + (regionW - (cols * w + (cols - 1) * GAP)) / 2f
        val laid = (0 until cardCount).map { i ->
            template.copy(id = "fit$i", binding = "card$i", x = x0 + (i % cols) * (w + GAP), y = top + (i / cols) * (h + GAP), w = w, h = h)
        }
        val dy = (top + rows * (h + GAP) - GAP) - bottom
        val moved = others.map { if (it.y >= bottom - 1f) it.copy(y = it.y + dy) else it }
        return moved + laid
    }
}
