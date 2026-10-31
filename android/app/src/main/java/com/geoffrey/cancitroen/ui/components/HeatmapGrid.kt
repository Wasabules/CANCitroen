package com.geoffrey.cancitroen.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geoffrey.cancitroen.ui.theme.GlassBg

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

    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
    val tooltipStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp)

    val hover = remember { mutableStateOf<Offset?>(null) }

    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .chartScrub(hover)
            .drawWithCache {
                // Couleurs et libellés préparés une fois par taille et grille.
                val padL = 32f
                val padT = 4f
                val plotW = size.width - padL
                val plotH = size.height - padT - 16f
                val cellW = plotW / cols
                val cellH = plotH / rows
                val cellGap = 2f
                val cell = Size(cellW - cellGap, cellH - cellGap)

                fun cellTopLeft(r: Int, c: Int) = Offset(padL + c * cellW + cellGap / 2, padT + r * cellH + cellGap / 2)

                val cellColors = Array(rows) { r ->
                    Array(cols) { c ->
                        val v = grid[r][c]
                        val intensity = if (maxValue > 0) (v / maxValue).toFloat().coerceIn(0f, 1f) else 0f
                        if (v == 0.0) GlassBg else color.copy(alpha = 0.15f + 0.75f * intensity)
                    }
                }
                val rowTexts = (0 until rows).map { r ->
                    val label = measurer.measure(rowLabels.getOrNull(r) ?: r.toString(), labelStyle)
                    label to Offset(
                        padL - label.size.width - 4f,
                        padT + r * cellH + cellH / 2f - label.size.height / 2f,
                    )
                }
                val colTexts = (0 until cols step colLabelsEvery).map { c ->
                    val label = measurer.measure("%02d".format(c), labelStyle)
                    label to Offset(padL + c * cellW + cellW / 2f - label.size.width / 2f, padT + plotH + 2f)
                }

                onDrawBehind {
                    for (r in 0 until rows) {
                        for (c in 0 until cols) {
                            drawRect(color = cellColors[r][c], topLeft = cellTopLeft(r, c), size = cell)
                        }
                    }
                    for ((label, topLeft) in rowTexts) drawText(label, topLeft = topLeft)
                    for ((label, topLeft) in colTexts) drawText(label, topLeft = topLeft)

                    // Tooltip
                    val finger = hover.value ?: return@onDrawBehind
                    val r = (((finger.y - padT) / plotH).coerceIn(0f, 0.9999f) * rows).toInt()
                    val c = (((finger.x - padL) / plotW).coerceIn(0f, 0.9999f) * cols).toInt()
                    // Highlight cellule
                    drawRect(
                        color = Color.White.copy(alpha = 0.5f),
                        topLeft = cellTopLeft(r, c),
                        size = cell,
                        style = Stroke(width = 2f),
                    )
                    val text = measurer.measure(tooltipFormatter(r, c, grid[r][c]), tooltipStyle)
                    val box = tooltipSize(text)
                    val cellCx = padL + c * cellW + cellW / 2f
                    val cellCy = padT + r * cellH + cellH / 2f
                    drawChartTooltip(text, Offset(
                        (cellCx - box.width / 2f).coerceIn(padL, (padL + plotW - box.width).coerceAtLeast(padL)),
                        (cellCy - box.height - 8f).coerceAtLeast(padT),
                    ))
                }
            },
    )
}
