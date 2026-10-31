package com.geoffrey.cancitroen.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geoffrey.cancitroen.ui.theme.GlassBorder

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
    val yHi = (bars.maxOf { it.second } * 1.1).takeIf { it > 0 } ?: 1.0

    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
    val tooltipStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp)

    val hover = remember { mutableStateOf<Offset?>(null) }

    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .chartScrub(hover)
            .drawWithCache {
                // Libellés préparés une fois par taille et jeu de données.
                val plotL = 36f
                val plotT = 8f
                val plotW = size.width - plotL - 8f
                val plotH = size.height - plotT - 22f
                val plotB = plotT + plotH
                val barSpacing = 4f
                val barW = (plotW - barSpacing * (bars.size - 1)) / bars.size

                fun barX(i: Int) = plotL + i * (barW + barSpacing)
                fun mapY(y: Double) = plotB - (y / yHi).toFloat() * plotH

                val yTicks = 4
                val yLabels = (0..yTicks).map { i -> measurer.measure(yAxisFormatter(yHi * i / yTicks), labelStyle) }
                // Axe X : labels (à intervalles selon le nombre de barres)
                val labelEvery = when {
                    bars.size <= 7 -> 1
                    bars.size <= 15 -> 2
                    bars.size <= 30 -> 5
                    else -> 10
                }
                val xLabels = bars.indices
                    .filter { it % labelEvery == 0 || it == bars.lastIndex }
                    .map { i ->
                        val label = measurer.measure(bars[i].first, labelStyle)
                        val cx = barX(i) + barW / 2f - label.size.width / 2f
                        label to Offset(cx.coerceIn(plotL, plotL + plotW - label.size.width), plotB + 6f)
                    }

                onDrawBehind {
                    val hoverIdx = hover.value?.let { finger ->
                        (((finger.x - plotL).coerceIn(0f, plotW) / plotW) * bars.size).toInt()
                            .coerceIn(0, bars.size - 1)
                    }

                    // Axe Y (5 ticks)
                    yLabels.forEachIndexed { i, label ->
                        val yPx = plotB - plotH * i / yTicks
                        drawLine(GlassBorder,
                            Offset(plotL, yPx), Offset(plotL + plotW, yPx), 1f)
                        drawText(label, topLeft = Offset(plotL - label.size.width - 4f, yPx - label.size.height / 2f))
                    }

                    // Barres
                    for (i in bars.indices) {
                        val top = mapY(bars[i].second)
                        drawRect(
                            color = if (hoverIdx == i) color else color.copy(alpha = 0.85f),
                            topLeft = Offset(barX(i), top),
                            size = Size(barW, plotB - top),
                        )
                    }

                    for ((label, topLeft) in xLabels) drawText(label, topLeft = topLeft)

                    // Tooltip
                    if (hoverIdx != null) {
                        val (lbl, v) = bars[hoverIdx]
                        val text = measurer.measure(tooltipFormatter(lbl, v), tooltipStyle)
                        val box = tooltipSize(text)
                        val cx = barX(hoverIdx) + barW / 2f
                        drawChartTooltip(text, Offset(
                            (cx - box.width / 2f).coerceIn(plotL, (plotL + plotW - box.width).coerceAtLeast(plotL)),
                            (mapY(v) - box.height - 8f).coerceAtLeast(plotT),
                        ))
                    }
                }
            },
    )
}
