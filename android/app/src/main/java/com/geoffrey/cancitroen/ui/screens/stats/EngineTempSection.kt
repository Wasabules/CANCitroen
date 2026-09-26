package com.geoffrey.cancitroen.ui.screens.stats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.geoffrey.cancitroen.history.UnifiedDataPoint
import com.geoffrey.cancitroen.ui.components.ChartSeries
import com.geoffrey.cancitroen.ui.components.GlassCard
import com.geoffrey.cancitroen.ui.components.MiniBarChart
import com.geoffrey.cancitroen.ui.components.MultiLineChart
import com.geoffrey.cancitroen.ui.components.TimeRange
import com.geoffrey.cancitroen.ui.components.aggregateByDay
import com.geoffrey.cancitroen.ui.components.aggregateMean
import com.geoffrey.cancitroen.ui.components.formatTooltipTs
import com.geoffrey.cancitroen.ui.components.formatTsForWindow
import com.geoffrey.cancitroen.ui.theme.Dim

@Composable
internal fun EngineTempSection(points: List<UnifiedDataPoint>, range: TimeRange) {
    // Au-delà de 7 j : passe en bar chart "T° max par jour" (lecture santé moteur)
    val showDailyMax = range.durationMs > 7L * 24 * 3600 * 1000
    // Calculé une fois par jeu de points, pas à chaque recomposition.
    val d = remember(points, showDailyMax) { computeEngineTemp(points, showDailyMax) }
    if (!d.hasEnough) return

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Text("🌡️ Températures moteur",
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium)
            if (showDailyMax) {
                Text("T° max atteinte chaque jour (eau / huile)",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))

            if (showDailyMax) {
                val coolantMaxByDay = d.coolantMaxByDay
                val oilMaxByDay = d.oilMaxByDay
                val extMaxByDay = d.extMaxByDay
                if (coolantMaxByDay.isNotEmpty()) {
                    Text("T° eau max", color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    MiniBarChart(
                        bars = coolantMaxByDay,
                        color = MaterialTheme.colorScheme.primary,
                        height = 130.dp,
                        yAxisFormatter = { "${it.toInt()}°" },
                        tooltipFormatter = { l, v -> "$l  ·  max ${v.toInt()}°C" },
                    )
                }
                if (oilMaxByDay.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("T° huile max",
                        color = Color(0xFFEF5350).copy(alpha = 0.9f),
                        style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    MiniBarChart(
                        bars = oilMaxByDay,
                        color = Color(0xFFEF5350),
                        height = 130.dp,
                        yAxisFormatter = { "${it.toInt()}°" },
                        tooltipFormatter = { l, v -> "$l  ·  max ${v.toInt()}°C" },
                    )
                }
                if (extMaxByDay.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("T° extérieure max",
                        color = Color(0xFFFFA726).copy(alpha = 0.9f),
                        style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    MiniBarChart(
                        bars = extMaxByDay,
                        color = Color(0xFFFFA726),
                        height = 130.dp,
                        yAxisFormatter = { "${it.toInt()}°" },
                        tooltipFormatter = { l, v -> "$l  ·  max ${v.toInt()}°C" },
                    )
                }
            } else {
                // Courbe agrégée — 3 séries superposées : eau, huile, ext
                val coolant = d.coolant
                val oil = d.oil
                val ext = d.ext
                val series = mutableListOf<ChartSeries>().apply {
                    if (coolant.size >= 2) add(ChartSeries("T° eau", MaterialTheme.colorScheme.primary, coolant, fillAlpha = 0.12f))
                    if (oil.size >= 2) add(ChartSeries("T° huile", Color(0xFFEF5350), oil, fillAlpha = 0.10f))
                    if (ext.size >= 2) add(ChartSeries("T° ext", Color(0xFFFFA726), ext, fillAlpha = 0.10f))
                }
                MultiLineChart(
                    series = series,
                    height = 180.dp,
                    // Y dynamique : couvre de la T° ext min (peut être négative l'hiver)
                    // jusqu'à 130°C pour afficher d'éventuels pics moteur
                    yMin = (ext.minOfOrNull { it.second } ?: 0.0).coerceAtMost(0.0),
                    yMax = 130.0,
                    xAxisFormatter = { formatTsForWindow(it, range.durationMs) },
                    yAxisFormatter = { "${it.toInt()}°" },
                    tooltipFormatter = { x, ys ->
                        buildString {
                            append(formatTooltipTs(x))
                            var idx = 0
                            if (coolant.size >= 2) {
                                ys.getOrNull(idx)?.let { append("\nT° eau : ${it.toInt()}°C") }
                                idx++
                            }
                            if (oil.size >= 2) {
                                ys.getOrNull(idx)?.let { append("\nT° huile : ${it.toInt()}°C") }
                                idx++
                            }
                            if (ext.size >= 2) {
                                ys.getOrNull(idx)?.let { append("\nT° ext : ${it.toInt()}°C") }
                            }
                        }
                    },
                )
            }
        }
    }
}

private class EngineTempData(
    val hasEnough: Boolean,
    val coolantMaxByDay: List<Pair<String, Double>> = emptyList(),
    val oilMaxByDay: List<Pair<String, Double>> = emptyList(),
    val extMaxByDay: List<Pair<String, Double>> = emptyList(),
    val coolant: List<Pair<Double, Double>> = emptyList(),
    val oil: List<Pair<Double, Double>> = emptyList(),
    val ext: List<Pair<Double, Double>> = emptyList(),
)

private fun computeEngineTemp(points: List<UnifiedDataPoint>, dailyMax: Boolean): EngineTempData {
    fun series(sel: (UnifiedDataPoint) -> Int?) =
        points.mapNotNull { p -> sel(p)?.let { p.ts.toDouble() to it.toDouble() } }
    val coolantRaw = series { it.tCoolant }
    val oilRaw = series { it.tOil }
    val extRaw = series { it.tExt }
    if (coolantRaw.size + oilRaw.size + extRaw.size < 2) return EngineTempData(hasEnough = false)
    if (!dailyMax) {
        return EngineTempData(
            hasEnough = true,
            coolant = aggregateMean(coolantRaw, 200),
            oil = aggregateMean(oilRaw, 200),
            ext = aggregateMean(extRaw, 200),
        )
    }
    // Pour les rollups, utiliser tCoolantMax/tOilMax/tExtMax si dispo (sinon = valeur instantanée)
    fun maxOrRaw(sel: (UnifiedDataPoint) -> Int?, raw: List<Pair<Double, Double>>) =
        series(sel).ifEmpty { raw }
    return EngineTempData(
        hasEnough = true,
        coolantMaxByDay = aggregateByDay(maxOrRaw({ it.tCoolantMax }, coolantRaw), reducer = { it.max() }),
        oilMaxByDay = aggregateByDay(maxOrRaw({ it.tOilMax }, oilRaw), reducer = { it.max() }),
        extMaxByDay = aggregateByDay(maxOrRaw({ it.tExtMax }, extRaw), reducer = { it.max() }),
    )
}
