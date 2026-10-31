package com.geoffrey.cancitroen.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import kotlin.math.abs

/** Appui maintenu avant l'apparition du curseur. */
private const val SCRUB_HOLD_MS = 150L
private const val TOOLTIP_PAD = 8f
/** Presque opaque : le texte passe par-dessus les courbes. */
private val TooltipBg = Color(0xF0101A20)

/**
 * Curseur des graphiques : appui maintenu puis glissement. Un geste immédiat
 * n'est pas consommé et reste au défilement de la page et au pager ; un
 * graphique couvrant la largeur de l'écran ne doit pas les bloquer.
 * [position] : doigt en pixels, null hors du curseur. Elle est lue au dessin,
 * donc le curseur ne provoque pas de recomposition.
 */
internal fun Modifier.chartScrub(position: MutableState<Offset?>): Modifier = pointerInput(position) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val left = withTimeoutOrNull(SCRUB_HOLD_MS) {
            awaitLeave(down.id, down.position, viewConfiguration.touchSlop)
        }
        if (left != null) return@awaitEachGesture

        position.value = down.position
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Main).changes
                .firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            position.value = change.position
            change.consume()
        }
        position.value = null
    }
}

/** Revient quand le doigt se lève ou s'éloigne de plus de [slop]. */
private suspend fun AwaitPointerEventScope.awaitLeave(id: PointerId, start: Offset, slop: Float) {
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Main).changes
            .firstOrNull { it.id == id } ?: return
        if (!change.pressed) return
        val moved = change.position - start
        if (abs(moved.x) > slop || abs(moved.y) > slop) return
    }
}

internal fun tooltipSize(text: TextLayoutResult) = Size(
    text.size.width + TOOLTIP_PAD * 2,
    text.size.height + TOOLTIP_PAD * 1.4f,
)

internal fun DrawScope.drawChartTooltip(text: TextLayoutResult, topLeft: Offset) {
    val box = tooltipSize(text)
    val corner = CornerRadius(6f)
    drawRoundRect(color = TooltipBg, topLeft = topLeft, size = box, cornerRadius = corner)
    drawRoundRect(
        color = Color.White.copy(alpha = 0.2f),
        topLeft = topLeft,
        size = box,
        cornerRadius = corner,
        style = Stroke(width = 1f),
    )
    drawText(text, topLeft = topLeft + Offset(TOOLTIP_PAD, TOOLTIP_PAD * 0.7f))
}
