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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geoffrey.cancitroen.ui.theme.GlassBorder

/** Intervalles des axes : 5 graduations. */
private const val AXIS_STEPS = 4

/** Une série de points avec son nom (pour la légende) et sa couleur. */
data class ChartSeries(
    val name: String,
    val color: Color,
    val points: List<Pair<Double, Double>>,
    val fillAlpha: Float = 0.15f,
)

/**
 * Line chart multi-séries fait main, avec axes X/Y et scrub tactile :
 *  - Trace une ou plusieurs courbes lisses + aires
 *  - Markers verticaux à des positions x données (ex: pleins essence)
 *  - Axe Y : 5 ticks + labels formatés
 *  - Axe X : 5 ticks + labels formatés
 *  - Légende au-dessus si > 1 série
 *  - **Touch-scrub** : presse-glisse → crosshair vertical + tooltip avec valeurs
 *    de chaque série au point le plus proche
 */
@Composable
fun MiniLineChart(
    points: List<Pair<Double, Double>>,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    fillAlpha: Float = 0.18f,
    yMin: Double? = null,
    yMax: Double? = null,
    markers: List<Double> = emptyList(),
    markerColor: Color = Color(0xFFFFB74D),
    xAxisFormatter: (Double) -> String = { "%.0f".format(it) },
    yAxisFormatter: (Double) -> String = { "%.0f".format(it) },
    tooltipFormatter: (Pair<Double, Double>) -> String = { (x, y) ->
        "${xAxisFormatter(x)}  ·  ${yAxisFormatter(y)}"
    },
) {
    MultiLineChart(
        series = listOf(ChartSeries("", color, points, fillAlpha)),
        modifier = modifier, height = height,
        yMin = yMin, yMax = yMax,
        markers = markers, markerColor = markerColor,
        xAxisFormatter = xAxisFormatter,
        yAxisFormatter = yAxisFormatter,
        tooltipFormatter = { x, ys ->
            val v = ys.firstOrNull() ?: return@MultiLineChart ""
            tooltipFormatter(x to v)
        },
    )
}

@Composable
fun MultiLineChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    yMin: Double? = null,
    yMax: Double? = null,
    markers: List<Double> = emptyList(),
    markerColor: Color = Color(0xFFFFB74D),
    xAxisFormatter: (Double) -> String = { "%.0f".format(it) },
    yAxisFormatter: (Double) -> String = { "%.0f".format(it) },
    /** (x, list<y>) → texte tooltip. La taille de list<y> = nombre de séries. */
    tooltipFormatter: (Double, List<Double>) -> String = { x, ys ->
        "${xAxisFormatter(x)}\n" + ys.mapIndexed { i, v ->
            "${series[i].name}: ${yAxisFormatter(v)}"
        }.joinToString("\n")
    },
) {
    // Bornes mémoïsées : la détection du changement compare les données, pas
    // seulement l'instance, car les sections recréent la liste de séries.
    data class ChartBounds(val xMin: Double, val xMax: Double, val yLo: Double, val yHi: Double)
    val bounds = remember(series, yMin, yMax) {
        val all = series.flatMap { it.points }
        if (all.size < 2) null
        else ChartBounds(
            xMin = all.minOf { it.first },
            xMax = all.maxOf { it.first },
            yLo = yMin ?: all.minOf { it.second },
            yHi = yMax ?: all.maxOf { it.second },
        )
    }
    if (bounds == null) {
        Box(
            modifier = modifier.fillMaxWidth().height(height),
            contentAlignment = Alignment.Center,
        ) {
            Text("Pas assez de données",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    val (xMin, xMax, yLo, yHi) = bounds
    val xRange = (xMax - xMin).takeIf { it > 0 } ?: 1.0
    val yRange = (yHi - yLo).takeIf { it > 0 } ?: 1.0

    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
    val tooltipStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp)
    val seriesNameStyle = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)

    val hover = remember { mutableStateOf<Offset?>(null) }

    // Hauteur de la légende (si plus d'une série)
    val legendH = if (series.size > 1) 22f else 0f

    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .chartScrub(hover)
            .drawWithCache {
                // Courbes et libellés préparés une fois par taille et jeu de
                // données : le curseur ne redessine que le tooltip par-dessus.
                val plotL = 36f     // place pour les labels de l'axe Y
                val padT = 8f + legendH
                val plotT = padT
                val plotW = size.width - plotL - 8f
                val plotH = size.height - padT - 22f     // 22 : labels de l'axe X
                val plotB = plotT + plotH

                fun mapX(x: Double): Float = plotL + ((x - xMin) / xRange).toFloat() * plotW
                fun mapY(y: Double): Float = plotB - ((y - yLo) / yRange).toFloat() * plotH

                val legend = if (series.size > 1) {
                    series.filter { it.name.isNotBlank() }.map { it to measurer.measure(it.name, seriesNameStyle) }
                } else emptyList()
                val yLabels = (0..AXIS_STEPS).map { i ->
                    measurer.measure(yAxisFormatter(yLo + yRange * i / AXIS_STEPS), labelStyle)
                }
                val xLabels = (0..AXIS_STEPS).map { i ->
                    measurer.measure(xAxisFormatter(xMin + xRange * i / AXIS_STEPS), labelStyle)
                }
                val curves = series.filter { it.points.size >= 2 }.map { s ->
                    val line = Path()
                    val area = Path()
                    s.points.forEachIndexed { i, (x, y) ->
                        val px = mapX(x)
                        val py = mapY(y)
                        if (i == 0) {
                            line.moveTo(px, py)
                            area.moveTo(px, plotB)
                            area.lineTo(px, py)
                        } else {
                            line.lineTo(px, py)
                            area.lineTo(px, py)
                        }
                    }
                    area.lineTo(mapX(s.points.last().first), plotB)
                    area.close()
                    Triple(s, line, area)
                }
                val markerXs = markers.filter { it in xMin..xMax }.map { mapX(it) }
                val lineStroke = Stroke(width = 3f, cap = StrokeCap.Round)

                onDrawBehind {
                    // ── Légende (si multi-séries) ──
                    var lx = plotL
                    for ((s, label) in legend) {
                        drawCircle(color = s.color, radius = 5f,
                            center = Offset(lx + 5f, padT - legendH / 2f))
                        drawText(label, topLeft = Offset(lx + 14f, padT - legendH / 2f - label.size.height / 2f))
                        lx += 14f + label.size.width + 16f
                    }

                    // ── Axe Y : grille horizontale + labels ──
                    yLabels.forEachIndexed { i, label ->
                        val yPx = plotB - plotH * i / AXIS_STEPS
                        drawLine(
                            color = GlassBorder,
                            start = Offset(plotL, yPx),
                            end = Offset(plotL + plotW, yPx),
                            strokeWidth = 1f,
                        )
                        drawText(label, topLeft = Offset(plotL - label.size.width - 4f, yPx - label.size.height / 2f))
                    }

                    // ── Axe X : 5 ticks ──
                    xLabels.forEachIndexed { i, label ->
                        val xPx = plotL + plotW * i / AXIS_STEPS
                        drawLine(
                            color = GlassBorder,
                            start = Offset(xPx, plotB),
                            end = Offset(xPx, plotB + 4f),
                            strokeWidth = 1f,
                        )
                        // Centrage horizontal du label, avec recadrage aux bords
                        val labelX = (xPx - label.size.width / 2f)
                            .coerceIn(plotL, plotL + plotW - label.size.width)
                        drawText(label, topLeft = Offset(labelX, plotB + 6f))
                    }

                    // ── Markers verticaux (pleins) ──
                    for (xPx in markerXs) {
                        drawLine(
                            color = markerColor.copy(alpha = 0.7f),
                            start = Offset(xPx, plotT),
                            end = Offset(xPx, plotB),
                            strokeWidth = 2f,
                        )
                    }

                    // ── Courbes + aires (multi-séries) ──
                    for ((s, line, area) in curves) {
                        drawPath(path = area, color = s.color.copy(alpha = s.fillAlpha))
                        drawPath(path = line, color = s.color, style = lineStroke)
                    }

                    // ── Hover crosshair + tooltip ──
                    val finger = hover.value ?: return@onDrawBehind
                    val xv = xMin + ((finger.x - plotL).coerceIn(0f, plotW) / plotW) * xRange
                    // Pour chaque série, le sample le plus proche en x
                    val hits = series.filter { it.points.isNotEmpty() }.map { s ->
                        s to s.points.minBy { kotlin.math.abs(it.first - xv) }
                    }
                    if (hits.isEmpty()) return@onDrawBehind

                    // Crosshair vertical, calé sur le point affiché
                    val xp = mapX(hits.first().second.first)
                    drawLine(
                        color = Color.White.copy(alpha = 0.4f),
                        start = Offset(xp, plotT),
                        end = Offset(xp, plotB),
                        strokeWidth = 1.5f,
                    )
                    // Un dot par série au point le plus proche
                    for ((s, pt) in hits) {
                        val center = Offset(mapX(pt.first), mapY(pt.second))
                        drawCircle(color = s.color, radius = 6f, center = center)
                        drawCircle(color = Color.White, radius = 6f, center = center, style = Stroke(width = 2f))
                    }

                    // Tooltip
                    val text = measurer.measure(
                        tooltipFormatter(hits.first().second.first, hits.map { it.second.second }),
                        tooltipStyle,
                    )
                    val box = tooltipSize(text)
                    // Placement : à droite du point si dispo, sinon à gauche
                    val ttX = if (xp + 12f + box.width < plotL + plotW) xp + 12f else xp - 12f - box.width
                    // Centre vertical sur le 1er point (pour multi-séries, c'est arbitraire)
                    val ttY = (mapY(hits.first().second.second) - box.height / 2f)
                        .coerceIn(plotT, (plotB - box.height).coerceAtLeast(plotT))
                    drawChartTooltip(text, Offset(ttX, ttY))
                }
            },
    )
}

/** Helper pour formater un timestamp epoch (ms) → "HH:mm" ou "dd MMM" selon la fenêtre. */
fun formatTsForWindow(tsMs: Double, windowMs: Long): String {
    val pattern = when {
        windowMs <= 24L * 3600 * 1000 -> "HH:mm"
        windowMs <= 7L * 24 * 3600 * 1000 -> "EEE HH'h'"
        else -> "d MMM"
    }
    return dateFormat(pattern).format(java.util.Date(tsMs.toLong()))
}

/** Helper tooltip : "Sam 9 12:34 — 76%". */
fun formatTooltipTs(tsMs: Double): String =
    dateFormat("EEE d HH:mm").format(java.util.Date(tsMs.toLong()))

/**
 * Formateurs réutilisés (appelés pour chaque libellé d'axe à chaque dessin).
 * SimpleDateFormat n'est pas thread-safe → un jeu par thread.
 */
private val dateFormats = ThreadLocal.withInitial { HashMap<String, java.text.SimpleDateFormat>() }

private fun dateFormat(pattern: String): java.text.SimpleDateFormat =
    dateFormats.get()!!.getOrPut(pattern) { java.text.SimpleDateFormat(pattern, java.util.Locale.FRANCE) }
        .apply { timeZone = java.util.TimeZone.getDefault() }
