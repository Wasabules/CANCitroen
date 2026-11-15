package com.geoffrey.cancitroen.companion

/** Une tranche de vitesse : temps passé, distance, conso moyenne pondérée par la distance. */
class SpeedBand(val label: String, val drivingMs: Long, val km: Double, val avgCons: Double?)

/** Un rapport de boîte reconnu à sa démultiplication : km/h à 1 000 tr/min. */
class GearStat(val kmhPer1000: Double, val share: Double, val avgRpm: Double)

class DrivingReport(
    val drivingMs: Long,
    val idleMs: Long,
    val km: Double,
    val bands: List<SpeedBand>,
    val avgRpm: Double?,
    val highRpmShare: Double?,
    val gears: List<GearStat>,
    val liters: Double?,
    val cost: Double?,
    val co2Kg: Double?,
)

/**
 * Analyse de conduite sur le détail à 5 s, sans Android : testée en JVM.
 *
 * Un passage de rapport dure moins d'une seconde : invisible à 5 s, son régime n'est pas
 * mesurable. Le rapport engagé se reconnaît en revanche au rapport vitesse / régime, qui ne
 * prend que quelques valeurs, une par rapport de boîte : on les retrouve dans les données,
 * sans supposer la boîte de la voiture. Ses numéros sont donc relatifs (le plus court vu d'abord).
 */
object DrivingAnalysis {
    /** Au-delà, un écart entre deux lignes est un arrêt, pas du roulage. */
    private const val MAX_STEP_MS = 10_000L
    private const val LAST_STEP_MS = 5_000L
    private const val MOVING_KMH = 3.0
    private const val IDLE_RPM = 400.0
    const val HIGH_RPM = 3_000.0
    private const val GEAR_MIN_KMH = 10.0
    private const val GEAR_MIN_RPM = 1_000.0
    private const val RATIO_BIN = 0.5
    private const val RATIO_MAX = 60.0
    private const val PEAK_MIN_SHARE = 0.04
    private const val PEAK_MERGE = 2.0
    private const val GEAR_TOLERANCE = 0.08
    private const val MAX_GEARS = 6

    private class Band(val label: String, val from: Double, val to: Double) {
        var ms = 0L
        var km = 0.0
        var consKm = 0.0
        var consWeight = 0.0
    }

    /** Null s'il n'y a rien à analyser. [price] : prix du litre, pour le coût des trajets terminés. */
    fun analyze(detail: Detail, trips: List<TripRow>, price: Double?, fuel: FuelType): DrivingReport? {
        val finished = trips.filter { it.endTs != null }
        if (detail.size == 0 && finished.isEmpty()) return null
        val bands = listOf(Band("Ville", 0.0, 50.0), Band("Route", 50.0, 90.0), Band("Voie rapide", 90.0, Double.MAX_VALUE))
        var drivingMs = 0L
        var idleMs = 0L
        var km = 0.0
        var rpmWeighted = 0.0
        var rpmMs = 0L
        var highMs = 0L
        val ratios = DoubleArray(detail.size) { Double.NaN }
        val weights = DoubleArray(detail.size)
        for (i in 0 until detail.size) {
            val step = if (i + 1 < detail.size) (detail.ts[i + 1] - detail.ts[i]).coerceIn(0, MAX_STEP_MS) else LAST_STEP_MS
            val speed = detail.speed[i]
            val rpm = detail.rpm[i]
            if (speed.isNaN() || speed < MOVING_KMH) {
                if (!rpm.isNaN() && rpm > IDLE_RPM) idleMs += step
                continue
            }
            val stepKm = speed * step / 3_600_000.0
            drivingMs += step
            km += stepKm
            val band = bands.first { speed >= it.from && speed < it.to }
            band.ms += step
            band.km += stepKm
            if (!detail.cons[i].isNaN()) {
                band.consKm += detail.cons[i] * stepKm
                band.consWeight += stepKm
            }
            if (!rpm.isNaN()) {
                rpmWeighted += rpm * step
                rpmMs += step
                if (rpm >= HIGH_RPM) highMs += step
                if (speed >= GEAR_MIN_KMH && rpm >= GEAR_MIN_RPM) {
                    ratios[i] = speed / (rpm / 1000.0)
                    weights[i] = step.toDouble()
                }
            }
        }
        val liters = finished.mapNotNull { TripEvents.liters(it.km, it.cons) }.takeIf { it.isNotEmpty() }?.sum()
        return DrivingReport(
            drivingMs = drivingMs,
            idleMs = idleMs,
            km = km,
            bands = bands.map { SpeedBand(it.label, it.ms, it.km, if (it.consWeight > 0) it.consKm / it.consWeight else null) },
            avgRpm = if (rpmMs > 0) rpmWeighted / rpmMs else null,
            highRpmShare = if (rpmMs > 0) highMs.toDouble() / rpmMs else null,
            gears = gears(ratios, weights, detail.rpm),
            liters = liters,
            cost = liters?.let { l -> price?.let { l * it } },
            co2Kg = liters?.let { it * fuel.co2KgPerLiter },
        )
    }

    /** Les démultiplications fréquentes : pics de l'histogramme pondéré par le temps. */
    fun gears(ratios: DoubleArray, weights: DoubleArray, rpm: DoubleArray): List<GearStat> {
        val bins = (RATIO_MAX / RATIO_BIN).toInt()
        val histogram = DoubleArray(bins)
        var total = 0.0
        for (i in ratios.indices) {
            if (ratios[i].isNaN() || ratios[i] >= RATIO_MAX) continue
            histogram[(ratios[i] / RATIO_BIN).toInt()] += weights[i]
            total += weights[i]
        }
        if (total == 0.0) return emptyList()
        val smooth = DoubleArray(bins) { i ->
            (histogram.getOrElse(i - 1) { 0.0 } + 2 * histogram[i] + histogram.getOrElse(i + 1) { 0.0 }) / 4
        }
        val peaks = mutableListOf<Int>()
        for (i in 0 until bins) {
            val left = smooth.getOrElse(i - 1) { 0.0 }
            val right = smooth.getOrElse(i + 1) { 0.0 }
            if (smooth[i] >= left && smooth[i] > right && smooth[i] >= PEAK_MIN_SHARE * total / 2) peaks += i
        }
        // Deux pics trop proches sont un seul rapport : on garde le plus haut.
        val kept = mutableListOf<Int>()
        for (peak in peaks.sortedByDescending { smooth[it] }) {
            if (kept.none { kotlin.math.abs(it - peak) * RATIO_BIN < PEAK_MERGE }) kept += peak
        }
        val centers = kept.take(MAX_GEARS).map { (it + 0.5) * RATIO_BIN }.sorted()
        if (centers.size < 2) return emptyList()
        val gearMs = DoubleArray(centers.size)
        val gearRpm = DoubleArray(centers.size)
        for (i in ratios.indices) {
            if (ratios[i].isNaN()) continue
            val nearest = centers.indices.minBy { kotlin.math.abs(centers[it] - ratios[i]) }
            // Hors de toute démultiplication : embrayage, patinage, changement de rapport.
            if (kotlin.math.abs(centers[nearest] - ratios[i]) > GEAR_TOLERANCE * centers[nearest]) continue
            gearMs[nearest] += weights[i]
            gearRpm[nearest] += rpm[i] * weights[i]
        }
        val assigned = gearMs.sum()
        if (assigned == 0.0) return emptyList()
        return centers.indices.filter { gearMs[it] > 0 }.map {
            GearStat(centers[it], gearMs[it] / assigned, gearRpm[it] / gearMs[it])
        }
    }
}
