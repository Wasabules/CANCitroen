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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
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
    // Pre-calcul des bornes : 4 passes sur potentiellement 12k points
    // (4 séries × 3000 pts), mémoïsé par série. Évite de re-itérer à chaque
    // recompose (notamment pendant le scrub où la chart redraw souvent).
    data class ChartBounds(
        val allPts: List<Pair<Double, Double>>,
        val xMin: Double, val xMax: Double,
        val yLo: Double, val yHi: Double,
    )
    val bounds = remember(series, yMin, yMax) {
        val all = series.flatMap { it.points }
        if (all.size < 2) null
        else {
            val xMn = all.minOf { it.first }
            val xMx = all.maxOf { it.first }
            ChartBounds(
                allPts = all,
                xMin = xMn,
                xMax = xMx,
                yLo = yMin ?: all.minOf { it.second },
                yHi = yMax ?: all.maxOf { it.second },
            )
        }
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
    val allPts = bounds.allPts
    val xMin = bounds.xMin
    val xMax = bounds.xMax
    val yLo = bounds.yLo
    val yHi = bounds.yHi
    val xRange = (xMax - xMin).takeIf { it > 0 } ?: 1.0
    val yRange = (yHi - yLo).takeIf { it > 0 } ?: 1.0

    // 32 entrées : le cache par défaut (8) est plus petit que le nombre de
    // libellés (10-14) → tout était re-mesuré à chaque dessin.
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val labelStyle = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
    val tooltipStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp)
    val seriesNameStyle = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)

    // x du curseur pendant le scrub (en valeur data, pas px)
    var hoverX by remember { mutableStateOf<Double?>(null) }

    // Hauteur de la légende (si plus d'une série)
    val legendH = if (series.size > 1) 22f else 0f

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(series) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val padL = 36f
                    val padR = 8f
                    val plotL = padL
                    val plotW = size.width - padL - padR
                    fun update(xPx: Float) {
                        if (plotW <= 0f) return
                        val xData = xMin + ((xPx - plotL).coerceIn(0f, plotW) / plotW) * xRange
                        hoverX = xData
                    }
                    update(down.position.x)
                    do {
                        val ev = awaitPointerEvent(PointerEventPass.Main)
                        ev.changes.firstOrNull()?.let { ch ->
                            update(ch.position.x)
                            ch.consume()
                        }
                    } while (ev.changes.any { it.pressed })
                    hoverX = null
                }
            },
    ) {
        // Layout : padding pour axes + labels
        val padL = 36f      // place pour les labels de l'axe Y
        val padR = 8f
        val padT = 8f + legendH
        val padB = 22f      // place pour les labels de l'axe X
        val plotL = padL
        val plotT = padT
        val plotW = size.width - padL - padR
        val plotH = size.height - padT - padB

        // ── Légende (si multi-séries) ──
        if (series.size > 1) {
            var lx = plotL
            for (s in series) {
                if (s.name.isBlank()) continue
                drawCircle(color = s.color, radius = 5f,
                    center = Offset(lx + 5f, padT - legendH / 2f))
                val measured = measurer.measure(s.name, seriesNameStyle)
                drawText(
                    textMeasurer = measurer,
                    text = s.name,
                    topLeft = Offset(lx + 14f, padT - legendH / 2f - measured.size.height / 2f),
                    style = seriesNameStyle,
                )
                lx += 14f + measured.size.width + 16f
            }
        }

        fun mapX(x: Double): Float = plotL + ((x - xMin) / xRange).toFloat() * plotW
        fun mapY(y: Double): Float = plotT + plotH - ((y - yLo) / yRange).toFloat() * plotH

        // ── Axe Y : grille horizontale + labels ──
        val yTicks = 4
        for (i in 0..yTicks) {
            val frac = i / yTicks.toFloat()
            val yPx = plotT + plotH * (1f - frac)
            drawLine(
                color = GlassBorder.copy(alpha = 0.35f),
                start = Offset(plotL, yPx),
                end = Offset(plotL + plotW, yPx),
                strokeWidth = 1f,
            )
            val yVal = yLo + yRange * frac
            val text = yAxisFormatter(yVal)
            val measured = measurer.measure(text, labelStyle)
            drawText(
                textMeasurer = measurer,
                text = text,
                topLeft = Offset(
                    plotL - measured.size.width - 4f,
                    yPx - measured.size.height / 2f,
                ),
                style = labelStyle,
            )
        }

        // ── Axe X : 5 ticks ──
        val xTicks = 4
        for (i in 0..xTicks) {
            val frac = i / xTicks.toFloat()
            val xPx = plotL + plotW * frac
            // mini-tick visuel
            drawLine(
                color = GlassBorder.copy(alpha = 0.4f),
                start = Offset(xPx, plotT + plotH),
                end = Offset(xPx, plotT + plotH + 4f),
                strokeWidth = 1f,
            )
            val xVal = xMin + xRange * frac
            val text = xAxisFormatter(xVal)
            val measured = measurer.measure(text, labelStyle)
            // Centrage horizontal du label, avec recadrage aux bords
            val labelX = (xPx - measured.size.width / 2f)
                .coerceIn(plotL, plotL + plotW - measured.size.width)
            drawText(
                textMeasurer = measurer,
                text = text,
                topLeft = Offset(labelX, plotT + plotH + 6f),
                style = labelStyle,
            )
        }

        // ── Markers verticaux (pleins) ──
        for (mx in markers) {
            if (mx in xMin..xMax) {
                val xPx = mapX(mx)
                drawLine(
                    color = markerColor.copy(alpha = 0.7f),
                    start = Offset(xPx, plotT),
                    end = Offset(xPx, plotT + plotH),
                    strokeWidth = 2f,
                )
            }
        }

        // ── Courbes + aires (multi-séries) ──
        for (s in series) {
            if (s.points.size < 2) continue
            val linePath = Path()
            val areaPath = Path()
            s.points.forEachIndexed { i, (x, y) ->
                val px = mapX(x)
                val py = mapY(y)
                if (i == 0) {
                    linePath.moveTo(px, py)
                    areaPath.moveTo(px, plotT + plotH)
                    areaPath.lineTo(px, py)
                } else {
                    linePath.lineTo(px, py)
                    areaPath.lineTo(px, py)
                }
            }
            areaPath.lineTo(mapX(s.points.last().first), plotT + plotH)
            areaPath.close()

            drawPath(path = areaPath, color = s.color.copy(alpha = s.fillAlpha))
            drawPath(
                path = linePath,
                color = s.color,
                style = Stroke(width = 3f, cap = StrokeCap.Round),
            )
        }

        // ── Hover crosshair + tooltip ──
        hoverX?.let { xv ->
            val xp = mapX(xv)
            // Pour chaque série, le sample le plus proche en x
            data class Hit(val series: ChartSeries, val x: Double, val y: Double)
            val hits = series.mapNotNull { s ->
                if (s.points.isEmpty()) return@mapNotNull null
                val pt = s.points.minBy { kotlin.math.abs(it.first - xv) }
                Hit(s, pt.first, pt.second)
            }
            if (hits.isEmpty()) return@let

            // Crosshair vertical
            drawLine(
                color = Color.White.copy(alpha = 0.4f),
                start = Offset(xp, plotT),
                end = Offset(xp, plotT + plotH),
                strokeWidth = 1.5f,
            )
            // Un dot par série au point le plus proche
            for (h in hits) {
                val px = mapX(h.x)
                val py = mapY(h.y)
                drawCircle(color = h.series.color, radius = 6f, center = Offset(px, py))
                drawCircle(color = Color.White, radius = 6f, center = Offset(px, py),
                    style = Stroke(width = 2f))
            }

            // Tooltip
            val text = tooltipFormatter(hits.first().x, hits.map { it.y })
            val measured = measurer.measure(text, tooltipStyle)
            val tooltipPad = 8f
            val ttW = measured.size.width + tooltipPad * 2
            val ttH = measured.size.height + tooltipPad * 1.4f
            // Placement : à droite du point si dispo, sinon à gauche
            val ttX = if (xp + 12f + ttW < plotL + plotW) xp + 12f
                      else xp - 12f - ttW
            // Centre vertical sur le 1er point (pour multi-séries, c'est arbitraire)
            val firstY = mapY(hits.first().y)
            val ttY = (firstY - ttH / 2f).coerceIn(plotT, plotT + plotH - ttH)
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
                topLeft = Offset(ttX + tooltipPad, ttY + tooltipPad * 0.7f),
                style = tooltipStyle,
            )
        }
    }
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
