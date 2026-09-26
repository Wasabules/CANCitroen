package com.geoffrey.cancitroen.history

import com.geoffrey.cancitroen.history.dao.HistoryDao
import com.geoffrey.cancitroen.history.entities.AggregatedSample
import com.geoffrey.cancitroen.history.entities.VehicleSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Type pivot pour l'UI : qu'on regarde du raw 5 s ou des agrégats 6 h, on
 * manipule les mêmes champs.
 */
data class UnifiedDataPoint(
    val ts: Long,
    val speedKmh: Float?,
    val rpm: Float?,
    val fuelPct: Int?,
    val fuelLiters: Float?,
    val tCoolant: Int?,
    val tCoolantMax: Int?,
    val tOil: Int?,
    val tOilMax: Int?,
    val tExt: Int?,
    val tExtMax: Int?,
    val oilLevelPct: Int?,
    val fuelInstL100: Float?,
    val odoKm: Double?,
)

private fun VehicleSample.toUnified() = UnifiedDataPoint(
    ts = ts,
    speedKmh = speedKmh,
    rpm = rpm,
    fuelPct = fuelPct,
    fuelLiters = fuelLiters,
    tCoolant = tCoolant,
    tCoolantMax = tCoolant,        // raw : avg = max = la valeur instantanée
    tOil = tOil,
    tOilMax = tOil,
    tExt = tExt,
    tExtMax = tExt,
    oilLevelPct = oilLevelPct,
    fuelInstL100 = fuelInstL100,
    odoKm = odoKm,
)

private fun AggregatedSample.toUnified() = UnifiedDataPoint(
    ts = ts,
    speedKmh = speedAvg,
    rpm = rpmAvg,
    fuelPct = fuelPct,
    fuelLiters = fuelLiters,
    tCoolant = tCoolantAvg,
    tCoolantMax = tCoolantMax,
    tOil = tOilAvg,
    tOilMax = tOilMax,
    tExt = tExtAvg,
    tExtMax = tExtMax,
    oilLevelPct = oilLevelPct,
    fuelInstL100 = fuelInstAvg,
    odoKm = odoKm,
)

/** Wrapper autour du DAO qui choisit la bonne granularité selon la durée demandée. */
class HistoryRepository(private val dao: HistoryDao) {

    /**
     * Choisit la table selon la durée :
     *   ≤ 6 h          → raw 5 s
     *   ≤ 7 j          → 1 min
     *   ≤ 30 j         → 30 min
     *   ≤ 90 j         → 1 h
     *    > 90 j        → 6 h
     */
    fun pointsForRange(fromMs: Long, toMs: Long): Flow<List<UnifiedDataPoint>> {
        val durationMs = toMs - fromMs
        val bucketMs = pickBucket(durationMs)
        val flow = if (bucketMs == null) {
            // Raw
            dao.samplesInRangeFlow(fromMs, toMs).map { list -> list.map { it.toUnified() } }
        } else {
            // La rétention est étagée (raw 6 h → 1 min 7 j → 30 min 30 j → 1 h
            // 90 j → 6 h) : une fenêtre recouvre plusieurs couches. On les lit
            // toutes et on regroupe au pas de la vue — sinon « 24 h » ignorait
            // les 6 dernières heures (encore en raw) et « 30 j » la dernière semaine.
            combine(
                dao.samplesInRangeFlow(fromMs, toMs),
                dao.aggregatesAllInRange(fromMs, toMs),
            ) { raw, agg -> rebucket(raw, agg, bucketMs) }
        }
        // Conversion hors du main thread (jusqu'à ~10 000 points sur 7 jours).
        return flow.flowOn(Dispatchers.Default)
    }

    /** Regroupe raw + agrégats (toutes tailles) en buckets de [bucketMs], dans l'ordre. */
    internal fun rebucket(
        raw: List<VehicleSample>,
        agg: List<AggregatedSample>,
        bucketMs: Long,
    ): List<UnifiedDataPoint> =
        (agg + raw.map { it.asUnitAggregate() })
            .sortedBy { it.ts }
            .groupBy { Math.floorDiv(it.ts, bucketMs) * bucketMs }
            .map { (ts, list) -> mergeAggregates(ts, bucketMs, list).toUnified() }

    /** Granularité retenue (utile pour afficher "résolution X min" dans l'UI si besoin). */
    fun pickBucket(durationMs: Long): Long? = when {
        durationMs <= 6L * 3600_000           -> null    // raw
        durationMs <= 7L * 24L * 3600_000     -> RollupAggregator.ONE_MIN
        durationMs <= 30L * 24L * 3600_000    -> RollupAggregator.THIRTY_MIN
        durationMs <= 90L * 24L * 3600_000    -> RollupAggregator.ONE_HOUR
        else                                  -> RollupAggregator.SIX_HOURS
    }

    fun bucketLabel(bucketMs: Long?): String = when (bucketMs) {
        null -> "5 s"
        RollupAggregator.ONE_MIN -> "1 min"
        RollupAggregator.THIRTY_MIN -> "30 min"
        RollupAggregator.ONE_HOUR -> "1 h"
        RollupAggregator.SIX_HOURS -> "6 h"
        else -> "${bucketMs / 60_000} min"
    }
}
