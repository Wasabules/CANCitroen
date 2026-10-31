package com.geoffrey.cancitroen.ui.screens.stats

import com.geoffrey.cancitroen.history.UnifiedDataPoint
import com.geoffrey.cancitroen.ui.components.aggregateByDay
import com.geoffrey.cancitroen.ui.components.aggregateMean
import java.util.Calendar

private const val DAY_MS = 24L * 3600 * 1000

/**
 * Tout ce que les graphiques déduisent des points d'une fenêtre, calculé hors
 * du thread principal. Comparable par valeur : si rien n'a changé (voiture à
 * l'arrêt), le rafraîchissement de la fenêtre ne recompose rien.
 */
internal data class StatsCharts(
    val hasPoints: Boolean = false,
    val fuel: List<Pair<Double, Double>> = emptyList(),
    val temps: EngineTempData = EngineTempData(hasEnough = false),
    val daily: DailyAverages? = null,
    val rpmBars: List<Pair<String, Double>>? = null,
    val speedGrid: SpeedGrid? = null,
)

internal fun computeStatsCharts(points: List<UnifiedDataPoint>, durationMs: Long) = StatsCharts(
    hasPoints = points.isNotEmpty(),
    fuel = aggregateMean(
        points.mapNotNull { s -> s.fuelPct?.let { pct -> s.ts.toDouble() to pct.toDouble() } },
        targetCount = 200,
    ),
    // Au-delà de 7 j : bar chart "T° max par jour" (lecture santé moteur)
    temps = computeEngineTemp(points, dailyMax = durationMs > 7 * DAY_MS),
    daily = if (durationMs > DAY_MS) computeDailyAverages(points) else null,
    rpmBars = computeRpmBars(points),
    speedGrid = computeSpeedGrid(points),
)

internal data class EngineTempData(
    val hasEnough: Boolean,
    val dailyMax: Boolean = false,
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
        dailyMax = true,
        coolantMaxByDay = aggregateByDay(maxOrRaw({ it.tCoolantMax }, coolantRaw), reducer = { it.max() }),
        oilMaxByDay = aggregateByDay(maxOrRaw({ it.tOilMax }, oilRaw), reducer = { it.max() }),
        extMaxByDay = aggregateByDay(maxOrRaw({ it.tExtMax }, extRaw), reducer = { it.max() }),
    )
}

internal data class DailyAverages(
    val speed: List<Pair<String, Double>>,
    val consumption: List<Pair<String, Double>>,
)

private fun computeDailyAverages(points: List<UnifiedDataPoint>): DailyAverages? {
    val moving = points.filter { (it.speedKmh ?: 0f) > 1f }
    if (moving.size < 5) return null
    return DailyAverages(
        speed = aggregateByDay(
            points = moving.mapNotNull { s -> s.speedKmh?.let { s.ts.toDouble() to it.toDouble() } },
            reducer = { it.average() },
        ),
        consumption = aggregateByDay(
            points = moving.mapNotNull { s -> s.fuelInstL100?.let { s.ts.toDouble() to it.toDouble() } },
            reducer = { it.average() },
        ),
    )
}

private fun computeRpmBars(points: List<UnifiedDataPoint>): List<Pair<String, Double>>? {
    val rpms = points.mapNotNull { it.rpm }.filter { it > 0 }
    if (rpms.isEmpty()) return null
    val buckets = listOf(0..1500, 1500..2500, 2500..3500, 3500..4500, 4500..7000)
    val labels = listOf("<1.5k", "1.5-2.5k", "2.5-3.5k", "3.5-4.5k", ">4.5k")
    val counts = buckets.map { range ->
        rpms.count { (it.toInt() in range.first until range.last) }.toDouble()
    }
    val total = counts.sum().takeIf { it > 0 } ?: return null
    return labels.zip(counts.map { (it / total) * 100.0 })
}

internal class SpeedGrid(val grid: Array<DoubleArray>, val maxValue: Double) {
    override fun equals(other: Any?) =
        other is SpeedGrid && maxValue == other.maxValue && grid.contentDeepEquals(other.grid)
    override fun hashCode() = grid.contentDeepHashCode()
}

/** Vitesse moyenne par (jour de semaine, heure) ; null si aucune donnée en mouvement. */
private fun computeSpeedGrid(points: List<UnifiedDataPoint>): SpeedGrid? {
    val sums = Array(7) { DoubleArray(24) }
    val counts = Array(7) { IntArray(24) }
    val cal = Calendar.getInstance()
    for (s in points) {
        val sp = s.speedKmh ?: continue
        if (sp < 1f) continue   // ignore le stationnaire
        cal.timeInMillis = s.ts
        // Compose Calendar.DAY_OF_WEEK : Sunday=1..Saturday=7. On remappe Lundi=0..Dimanche=6.
        val rawDow = cal.get(Calendar.DAY_OF_WEEK)
        val dow = (rawDow + 5) % 7
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        sums[dow][hour] += sp
        counts[dow][hour]++
    }
    val grid = Array(7) { d ->
        DoubleArray(24) { h ->
            if (counts[d][h] > 0) sums[d][h] / counts[d][h] else 0.0
        }
    }
    val maxVal = grid.maxOf { row -> row.max() }.takeIf { it > 0 } ?: return null
    return SpeedGrid(grid, maxVal)
}
