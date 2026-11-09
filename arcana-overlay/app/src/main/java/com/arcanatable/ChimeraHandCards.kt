package com.arcanatable

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Owner, Nov 9: two more ways to hold your hand, for the native drawer ("Fan", "Catalogue" in the hand options) and for
 * Studio hand designs (Studio → Hand → Cards). Both fit the space they are given; tap selects (Cast and Actions use
 * it), holding previews, and playable cards keep their glow.
 */
@Composable
internal fun ChimeraHandCards(style: String, cards: List<GameCard>, playable: Set<Int>, selected: Int?, highlighted: Int?,
    onCard: (GameCard) -> Unit, onPreview: (GameCard) -> Unit, modifier: Modifier = Modifier) {
    if (cards.isEmpty()) return
    if (style == "fan") ChimeraFanHand(cards, playable, selected, highlighted, onCard, onPreview, modifier)
    else ChimeraCatalogueHand(cards, playable, selected, highlighted, onCard, onPreview, modifier)
}

/** Cards along a gentle arc, overlapping more as the hand grows; the selected card lifts out of the fan. */
@Composable
private fun ChimeraFanHand(cards: List<GameCard>, playable: Set<Int>, selected: Int?, highlighted: Int?,
    onCard: (GameCard) -> Unit, onPreview: (GameCard) -> Unit, modifier: Modifier) {
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds().testTag("hand-fan")) {
        val n = cards.size
        val lift = maxHeight * 0.1f
        val arc = maxHeight * 0.07f
        val cardH = min((maxHeight - lift - arc * 0.5f).value, (maxWidth * 0.9f).value / 0.716f).dp
        val cardW = cardH * 0.716f
        val spread = min(5f * (n - 1), 34f)
        val step = if (n <= 1) 0.dp else minOf(cardW * 0.82f, (maxWidth - cardW) / (n - 1))
        val total = cardW + step * (n - 1)
        val x0 = (maxWidth - total) / 2
        cards.forEachIndexed { i, card ->
            val t = if (n <= 1) 0f else i / (n - 1f) * 2f - 1f // -1 .. 1 across the fan
            val up = card.id == selected
            val angle = if (up) t * spread / 2f * 0.3f else t * spread / 2f
            val y = lift + arc * (t * t) - if (up) lift else 0.dp
            CardFace(card,
                Modifier.offset(x0 + step * i, y).width(cardW).zIndex(if (up) 1000f else i.toFloat())
                    .graphicsLayer { rotationZ = angle; transformOrigin = TransformOrigin(0.5f, 1.25f) }
                    .then(if (up || card.id == highlighted) Modifier.border(3.dp, Mint, RoundedCornerShape(8.dp)) else Modifier)
                    .tutorialTarget("hand-card:${card.name}"),
                onClick = { onCard(card) }, onLongClick = { onPreview(card) }, showCounters = false,
                glow = card.id in playable, tutorialZone = "hand")
        }
    }
}

/**
 * The owner's reference picture, sized for the drawer: one card large in the middle, its neighbours peeking in at the
 * sides, smaller and dimmed. Swipe to move through the hand; tap the middle card to select it, a side card to bring
 * it to the middle; hold to preview. A one-line caption names the middle card.
 */
@Composable
private fun ChimeraCatalogueHand(cards: List<GameCard>, playable: Set<Int>, selected: Int?, highlighted: Int?,
    onCard: (GameCard) -> Unit, onPreview: (GameCard) -> Unit, modifier: Modifier) {
    val n = cards.size
    val pos = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(n) { if (pos.value > n - 1f) pos.snapTo((n - 1f).coerceAtLeast(0f)) }
    LaunchedEffect(highlighted, selected) {
        val i = cards.indexOfFirst { it.id == (highlighted ?: selected) }
        if (i >= 0) pos.animateTo(i.toFloat(), spring(stiffness = 300f))
    }
    BoxWithConstraints(modifier.fillMaxSize().testTag("hand-catalogue")) {
        val caption: Dp = 20.dp
        val cardH = min((maxHeight - caption - 6.dp).value, (maxWidth * 0.62f).value / 0.716f).coerceAtLeast(40f).dp
        val cardW = cardH * 0.716f
        val step = cardW * 0.6f
        val stepPx = with(LocalDensity.current) { step.toPx() }.coerceAtLeast(1f)
        val centre = (maxWidth - cardW) / 2
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(cardH).clipToBounds().pointerInput(n, stepPx) {
                detectHorizontalDragGestures(
                    onDragEnd = { scope.launch { pos.animateTo(pos.value.roundToInt().coerceIn(0, n - 1).toFloat(), spring(stiffness = 300f)) } },
                    onDragCancel = { scope.launch { pos.animateTo(pos.value.roundToInt().coerceIn(0, n - 1).toFloat()) } },
                    onHorizontalDrag = { change, dx -> change.consume(); scope.launch { pos.snapTo((pos.value - dx / stepPx).coerceIn(0f, n - 1f)) } })
            }) {
                cards.withIndex().sortedByDescending { abs(it.index - pos.value) }.forEach { (i, card) ->
                    val d = i - pos.value
                    if (abs(d) > 4f) return@forEach
                    val near = min(abs(d), 1.5f)
                    val focused = abs(d) < 0.5f
                    CardFace(card,
                        Modifier.offset(centre + step * d, 0.dp).width(cardW).zIndex(-abs(d))
                            .graphicsLayer { val s = 1f - 0.16f * near; scaleX = s; scaleY = s; alpha = 1f - 0.3f * near }
                            .then(if (focused && (card.id == selected || card.id == highlighted)) Modifier.border(3.dp, Mint, RoundedCornerShape(8.dp)) else Modifier)
                            .tutorialTarget("hand-card:${card.name}"),
                        onClick = { if (focused) onCard(card) else scope.launch { pos.animateTo(i.toFloat(), spring(stiffness = 300f)) } },
                        onLongClick = { onPreview(card) }, showCounters = false, glow = card.id in playable, tutorialZone = "hand")
                }
            }
            val current = cards.getOrNull(pos.value.roundToInt().coerceIn(0, n - 1))
            if (current != null) Box(Modifier.fillMaxWidth().height(caption).padding(top = 3.dp), contentAlignment = Alignment.Center) {
                val text = listOfNotNull(current.name, current.manaCost?.takeIf { it.isNotBlank() }, if (current.id in playable) "Playable" else null,
                    "${pos.value.roundToInt().coerceIn(0, n - 1) + 1}/$n").joinToString("  ·  ")
                ManaSymbolText(text, color = if (current.id in playable) Mint else SoftWhite, fontSize = 11.sp)
            }
        }
    }
}

