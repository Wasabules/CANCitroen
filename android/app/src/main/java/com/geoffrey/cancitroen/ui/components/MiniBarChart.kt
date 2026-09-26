package com.geoffrey.cancitroen.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geoffrey.cancitroen.ui.theme.Dim
import com.geoffrey.cancitroen.ui.theme.GlassBg
import com.geoffrey.cancitroen.ui.theme.GlassBorder
import com.geoffrey.cancitroen.ui.theme.Text as TextColor

/**
 * Bar chart minimaliste avec axes + scrub.
 * Chaque barre a un label texte (axe X discret) et une valeur Y.
 */
@Composable
fun MiniBarChart(
    bars: List<Pair<String, Double>>,    // (label, value)
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    yAxisFormatter: (Double) -> String = { "%.1f".format(it) },
    tooltipFormatter: (String, Double) -> String = { l, v ->
        "$l  ·  ${yAxisFormatter(v)}"
    },
) {
    if (bars.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth().height(height),
            contentAlignment = Alignment.Center) {
            Text("Aucune donnée", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    val yLo = 0.0
    val yHi = (bars.maxOf { it.second } * 1.1).takeIf { it > 0 } ?: 1.0
    val yRange = yHi - yLo

    // 32 entrées : le cache par défaut (8) est plus petit que le nombre de
    // libellés (10-14) → tout était re-mesuré à chaque dessin.
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val labelStyle = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
    val tooltipStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp)

    var hoverIdx by remember { mutableStateOf<Int?>(null) }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(bars) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val padL = 36f
                    val padR = 8f
                    fun update(xPx: Float) {
                        val plotW = size.width - padL - padR
                        if (plotW <= 0f) return
                        val xRel = (xPx - padL).coerceIn(0f, plotW)
                        val idx = ((xRel / plotW) * bars.size).toInt()
                            .coerceIn(0, bars.size - 1)
                        hoverIdx = idx
                    }
                    update(down.position.x)
                    do {
                        val ev = awaitPointerEvent(PointerEventPass.Main)
                        ev.changes.firstOrNull()?.let { ch ->
                            update(ch.position.x)
                            ch.consume()
                        }
                    } while (ev.changes.any { it.pressed })
                    hoverIdx = null
                }
            },
    ) {
        val padL = 36f
        val padR = 8f
        val padT = 8f
        val padB = 22f
        val plotL = padL
        val plotT = padT
        val plotW = size.width - padL - padR
        val plotH = size.height - padT - padB
        val barSpacing = 4f
        val barW = (plotW - barSpacing * (bars.size - 1)) / bars.size

        fun barX(i: Int) = plotL + i * (barW + barSpacing)
        fun mapY(y: Double) = plotT + plotH - ((y - yLo) / yRange).toFloat() * plotH

        // Axe Y (5 ticks)
        val yTicks = 4
        for (i in 0..yTicks) {
            val frac = i / yTicks.toFloat()
            val yPx = plotT + plotH * (1f - frac)
            drawLine(GlassBorder.copy(alpha = 0.3f),
                Offset(plotL, yPx), Offset(plotL + plotW, yPx), 1f)
            val yVal = yLo + yRange * frac
            val text = yAxisFormatter(yVal)
            val measured = measurer.measure(text, labelStyle)
            drawText(measurer, text,
                topLeft = Offset(plotL - measured.size.width - 4f,
                    yPx - measured.size.height / 2f),
                style = labelStyle)
        }

        // Barres
        for (i in bars.indices) {
            val (_, v) = bars[i]
            val left = barX(i)
            val top = mapY(v)
            val right = left + barW
            val bot = plotT + plotH
            val isHover = hoverIdx == i
            drawRect(
                color = if (isHover) color else color.copy(alpha = 0.85f),
                topLeft = Offset(left, top),
                size = Size(barW, bot - top),
            )
        }

        // Axe X : labels (à intervalles selon le nombre de barres)
        val labelEvery = when {
            bars.size <= 7 -> 1
            bars.size <= 15 -> 2
            bars.size <= 30 -> 5
            else -> 10
        }
        for (i in bars.indices) {
            if (i % labelEvery != 0 && i != bars.lastIndex) continue
            val text = bars[i].first
            val measured = measurer.measure(text, labelStyle)
            val cx = barX(i) + barW / 2f - measured.size.width / 2f
            drawText(measurer, text,
                topLeft = Offset(cx.coerceIn(plotL, plotL + plotW - measured.size.width),
                    plotT + plotH + 6f),
                style = labelStyle)
        }

        // Tooltip
        hoverIdx?.let { idx ->
            val (lbl, v) = bars[idx]
            val cx = barX(idx) + barW / 2f
            val text = tooltipFormatter(lbl, v)
            val measured = measurer.measure(text, tooltipStyle)
            val ttPad = 8f
            val ttW = measured.size.width + ttPad * 2
            val ttH = measured.size.height + ttPad * 1.4f
            val ttX = (cx - ttW / 2f).coerceIn(plotL, plotL + plotW - ttW)
            val ttY = (mapY(v) - ttH - 8f).coerceAtLeast(plotT)
            drawRoundRect(GlassBg, Offset(ttX, ttY), Size(ttW, ttH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f))
            drawRoundRect(Color.White.copy(alpha = 0.2f),
                Offset(ttX, ttY), Size(ttW, ttH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f),
                style = Stroke(width = 1f))
            drawText(measurer, text,
                topLeft = Offset(ttX + ttPad, ttY + ttPad * 0.7f),
                style = tooltipStyle)
        }
    }
}
