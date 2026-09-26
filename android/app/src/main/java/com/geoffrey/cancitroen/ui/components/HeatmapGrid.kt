package com.geoffrey.cancitroen.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.geoffrey.cancitroen.ui.theme.Text as TextColor

/**
 * Heatmap rectangulaire (rows × cols), valeurs ∈ [0, maxValue] mappées sur une
 * échelle de couleur cyan transparent → cyan vif. Tap-and-hold sur une cellule
 * affiche un tooltip.
 */
@Composable
fun HeatmapGrid(
    grid: Array<DoubleArray>,        // [row][col]
    rowLabels: List<String>,
    maxValue: Double,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
    colLabelsEvery: Int = 4,
    color: Color = MaterialTheme.colorScheme.primary,
    tooltipFormatter: (row: Int, col: Int, value: Double) -> String = { r, c, v ->
        "[$r,$c] ${"%.1f".format(v)}"
    },
) {
    val rows = grid.size
    val cols = if (rows > 0) grid[0].size else 0
    if (rows == 0 || cols == 0) return

    // 32 entrées : le cache par défaut (8) est plus petit que le nombre de
    // libellés (10-14) → tout était re-mesuré à chaque dessin.
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val labelStyle = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
    val tooltipStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp)

    var hover by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(grid) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val padL = 32f
                    val padT = 4f
                    val padB = 16f
                    fun update(x: Float, y: Float) {
                        val plotW = size.width - padL
                        val plotH = size.height - padT - padB
                        if (plotW <= 0 || plotH <= 0) return
                        val xRel = ((x - padL) / plotW).coerceIn(0f, 0.9999f)
                        val yRel = ((y - padT) / plotH).coerceIn(0f, 0.9999f)
                        hover = (yRel * rows).toInt() to (xRel * cols).toInt()
                    }
                    update(down.position.x, down.position.y)
                    do {
                        val ev = awaitPointerEvent(PointerEventPass.Main)
                        ev.changes.firstOrNull()?.let { ch ->
                            update(ch.position.x, ch.position.y)
                            ch.consume()
                        }
                    } while (ev.changes.any { it.pressed })
                    hover = null
                }
            },
    ) {
        val padL = 32f
        val padT = 4f
        val padB = 16f
        val plotW = size.width - padL
        val plotH = size.height - padT - padB
        val cellW = plotW / cols
        val cellH = plotH / rows
        val cellGap = 2f

        // Cellules
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val v = grid[r][c]
                val intensity = if (maxValue > 0) (v / maxValue).toFloat().coerceIn(0f, 1f) else 0f
                val cellColor = if (v == 0.0) GlassBg.copy(alpha = 0.4f)
                else color.copy(alpha = (0.15f + 0.75f * intensity))
                drawRect(
                    color = cellColor,
                    topLeft = Offset(padL + c * cellW + cellGap / 2, padT + r * cellH + cellGap / 2),
                    size = Size(cellW - cellGap, cellH - cellGap),
                )
            }
        }

        // Labels rows (gauche)
        for (r in 0 until rows) {
            val label = rowLabels.getOrNull(r) ?: r.toString()
            val measured = measurer.measure(label, labelStyle)
            drawText(
                textMeasurer = measurer,
                text = label,
                topLeft = Offset(
                    padL - measured.size.width - 4f,
                    padT + r * cellH + cellH / 2f - measured.size.height / 2f,
                ),
                style = labelStyle,
            )
        }

        // Labels cols (en bas, tous les colLabelsEvery)
        for (c in 0 until cols) {
            if (c % colLabelsEvery != 0) continue
            val label = "%02d".format(c)
            val measured = measurer.measure(label, labelStyle)
            drawText(
                textMeasurer = measurer,
                text = label,
                topLeft = Offset(
                    padL + c * cellW + cellW / 2f - measured.size.width / 2f,
                    padT + plotH + 2f,
                ),
                style = labelStyle,
            )
        }

        // Tooltip
        hover?.let { (r, c) ->
            val v = grid.getOrNull(r)?.getOrNull(c) ?: return@let
            val cellCx = padL + c * cellW + cellW / 2f
            val cellCy = padT + r * cellH + cellH / 2f
            // Highlight cellule
            drawRect(
                color = Color.White.copy(alpha = 0.5f),
                topLeft = Offset(padL + c * cellW + cellGap / 2, padT + r * cellH + cellGap / 2),
                size = Size(cellW - cellGap, cellH - cellGap),
                style = Stroke(width = 2f),
            )
            // Tooltip
            val text = tooltipFormatter(r, c, v)
            val measured = measurer.measure(text, tooltipStyle)
            val ttPad = 8f
            val ttW = measured.size.width + ttPad * 2
            val ttH = measured.size.height + ttPad * 1.4f
            val ttX = (cellCx - ttW / 2f).coerceIn(padL, padL + plotW - ttW)
            val ttY = (cellCy - ttH - 8f).coerceAtLeast(padT)
            drawRoundRect(
                color = GlassBg,
                topLeft = Offset(ttX, ttY),
                size = Size(ttW, ttH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f),
            )
            drawRoundRect(
                color = Color.White.copy(alpha = 0.2f),
                topLeft = Offset(ttX, ttY),
                size = Size(ttW, ttH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f),
                style = Stroke(width = 1f),
            )
            drawText(
                textMeasurer = measurer,
                text = text,
                topLeft = Offset(ttX + ttPad, ttY + ttPad * 0.7f),
                style = tooltipStyle,
            )
        }
    }
}
