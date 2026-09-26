package com.geoffrey.cancitroen.history

import android.util.Log
import androidx.room.withTransaction
import com.geoffrey.cancitroen.history.HistoryDatabase
import com.geoffrey.cancitroen.history.dao.HistoryDao
import com.geoffrey.cancitroen.history.entities.AggregatedSample
import com.geoffrey.cancitroen.history.entities.VehicleSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Rollup en cascade des samples véhicule pour limiter le stockage à long terme.
 *
 *  Paliers (du plus fin au plus large) :
 *
 *    raw (5 s)        gardé sur 0 –   6 h
 *    1 min            gardé sur 6 h –   7 j
 *    30 min           gardé sur 7 j –  30 j
 *    1 h              gardé sur 30 j – 90 j
 *    6 h              gardé indéfiniment au-delà de 90 j
 *
 *  À chaque tick (toutes les 30 min en pratique), pour chaque palier :
 *   1. Sélectionne les "lignes sources" plus vieilles que le seuil de transition
 *   2. Les groupe par bucket (taille du palier supérieur), calcule moyennes/max
 *   3. Insère dans la table cible
 *   4. Supprime les sources désormais agrégées
 */
class RollupAggregator(
    private val dao: HistoryDao,
    private val db: HistoryDatabase,
) {
    companion object {
        private const val TAG = "Rollup"
        private const val ROLLUP_INTERVAL_MS = 30L * 60_000L  // 30 min

        const val ONE_MIN = 60_000L
        const val THIRTY_MIN = 30L * 60_000L
        const val ONE_HOUR = 60L * 60_000L
        const val SIX_HOURS = 6L * 60L * 60_000L

        private const val RAW_RETENTION_MS = 6L * 60L * 60_000L            // 6 h
        private const val MIN1_RETENTION_MS = 7L * 24L * 60L * 60_000L     // 7 j
        private const val MIN30_RETENTION_MS = 30L * 24L * 60L * 60_000L   // 30 j
        private const val H1_RETENTION_MS = 90L * 24L * 60L * 60_000L      // 90 j
    }

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            // Premier rollup peu après le démarrage (5 min) pour traiter ce qui s'est
            // accumulé pendant que le service était down.
            delay(5L * 60_000L)
            while (isActive) {
                runCatching { runOnce() }
                    .onFailure { Log.e(TAG, "Rollup error", it) }
                delay(ROLLUP_INTERVAL_MS)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    private fun alignDown(ts: Long, bucket: Long): Long = ts - Math.floorMod(ts, bucket)

    suspend fun runOnce() {
        val now = System.currentTimeMillis()

        // Tier 1 : raw → 1min, pour les raw plus vieux que 6h
        rollupRawTo1Min(now - RAW_RETENTION_MS)

        // Tier 2 : 1min → 30min, pour les 1min plus vieux que 7j
        rollupAggToHigher(
            sourceBucket = ONE_MIN,
            targetBucket = THIRTY_MIN,
            rawCutoff = now - MIN1_RETENTION_MS,
        )

        // Tier 3 : 30min → 1h
        rollupAggToHigher(
            sourceBucket = THIRTY_MIN,
            targetBucket = ONE_HOUR,
            rawCutoff = now - MIN30_RETENTION_MS,
        )

        // Tier 4 : 1h → 6h
        rollupAggToHigher(
            sourceBucket = ONE_HOUR,
            targetBucket = SIX_HOURS,
            rawCutoff = now - H1_RETENTION_MS,
        )
    }

    /** Roll up raw samples plus vieux que [olderThan] vers des buckets 1 minute.
     *  Tout dans une transaction Room pour éviter qu'un crash entre INSERT et
     *  DELETE laisse la DB incohérente. */
    private suspend fun rollupRawTo1Min(cutoff: Long) = db.withTransaction {
        // Seuil aligné sur la minute : sinon la minute à cheval est agrégée en
        // deux passes → deux lignes (bucketMs, ts) identiques (points doublés).
        val olderThan = alignDown(cutoff, ONE_MIN)
        val sinceTs = dao.oldestSampleTs() ?: return@withTransaction
        if (sinceTs >= olderThan) return@withTransaction

        val raw = dao.samplesInRange(sinceTs, olderThan)
        if (raw.isEmpty()) return@withTransaction

        val byBucket = raw.groupBy { (it.ts / ONE_MIN) * ONE_MIN }
        val aggregates = byBucket.map { (bucketTs, list) ->
            val speeds = list.mapNotNull { it.speedKmh }
            val rpms = list.mapNotNull { it.rpm }
            val tCool = list.mapNotNull { it.tCoolant }
            val tOil = list.mapNotNull { it.tOil }
            val tExt = list.mapNotNull { it.tExt }
            val cons = list.mapNotNull { it.fuelInstL100 }
            AggregatedSample(
                ts = bucketTs,
                bucketMs = ONE_MIN,
                count = list.size,
                speedAvg = speeds.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
                speedMax = speeds.maxOrNull(),
                rpmAvg = rpms.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
                rpmMax = rpms.maxOrNull(),
                fuelPct = list.lastOrNull()?.fuelPct,
                fuelLiters = list.lastOrNull()?.fuelLiters,
                tCoolantAvg = tCool.takeIf { it.isNotEmpty() }?.average()?.toInt(),
                tCoolantMax = tCool.maxOrNull(),
                tOilAvg = tOil.takeIf { it.isNotEmpty() }?.average()?.toInt(),
                tOilMax = tOil.maxOrNull(),
                tExtAvg = tExt.takeIf { it.isNotEmpty() }?.average()?.toInt(),
                tExtMax = tExt.maxOrNull(),
                oilLevelPct = list.lastOrNull()?.oilLevelPct,
                fuelInstAvg = cons.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
                odoKm = list.lastOrNull()?.odoKm,
            )
        }
        dao.insertAggregates(aggregates)
        val deleted = dao.deleteSamplesOlderThan(olderThan)
        Log.i(TAG, "raw → 1min : ${aggregates.size} buckets créés, $deleted raw supprimés")
    }

    /** Roll up un palier vers le suivant (1min → 30min, etc.) — en transaction. */
    private suspend fun rollupAggToHigher(
        sourceBucket: Long,
        targetBucket: Long,
        rawCutoff: Long,
    ) = db.withTransaction {
        // Même raison que rollupRawTo1Min : jamais de bucket cible coupé en deux.
        val cutoff = alignDown(rawCutoff, targetBucket)
        val sinceTs = dao.oldestAggregateTs(sourceBucket) ?: return@withTransaction
        if (sinceTs >= cutoff) return@withTransaction

        val src = dao.aggregatesInRangeOnce(sourceBucket, sinceTs, cutoff)
        if (src.isEmpty()) return@withTransaction

        val byBucket = src.groupBy { (it.ts / targetBucket) * targetBucket }
        val aggregates = byBucket.map { (bucketTs, list) -> mergeAggregates(bucketTs, targetBucket, list) }
        dao.insertAggregates(aggregates)
        val deleted = dao.deleteAggregatesOlderThan(sourceBucket, cutoff)
        Log.i(TAG, "${sourceBucket / ONE_MIN}min → ${targetBucket / ONE_MIN}min : " +
            "${aggregates.size} buckets, $deleted sources supprimées")
    }
}

/**
 * Fusionne des buckets (dans l'ordre chronologique) en un seul bucket de
 * [bucketMs] démarrant à [bucketTs] : moyennes pondérées par `count`, max des
 * max, dernière valeur pour les niveaux (carburant, huile, odo). Partagé par
 * le rollup et par [HistoryRepository] (regroupement au vol pour l'UI).
 */
internal fun mergeAggregates(bucketTs: Long, bucketMs: Long, list: List<AggregatedSample>) = AggregatedSample(
    ts = bucketTs,
    bucketMs = bucketMs,
    count = list.sumOf { it.count },
    speedAvg = list.weightedAvgFloat(AggregatedSample::speedAvg),
    speedMax = list.mapNotNull { it.speedMax }.maxOrNull(),
    rpmAvg = list.weightedAvgFloat(AggregatedSample::rpmAvg),
    rpmMax = list.mapNotNull { it.rpmMax }.maxOrNull(),
    fuelPct = list.lastOrNull()?.fuelPct,
    fuelLiters = list.lastOrNull()?.fuelLiters,
    tCoolantAvg = list.weightedAvgInt(AggregatedSample::tCoolantAvg),
    tCoolantMax = list.mapNotNull { it.tCoolantMax }.maxOrNull(),
    tOilAvg = list.weightedAvgInt(AggregatedSample::tOilAvg),
    tOilMax = list.mapNotNull { it.tOilMax }.maxOrNull(),
    tExtAvg = list.weightedAvgInt(AggregatedSample::tExtAvg),
    tExtMax = list.mapNotNull { it.tExtMax }.maxOrNull(),
    oilLevelPct = list.lastOrNull()?.oilLevelPct,
    fuelInstAvg = list.weightedAvgFloat(AggregatedSample::fuelInstAvg),
    odoKm = list.lastOrNull()?.odoKm,
)

/** Un sample brut vu comme un bucket de 1 échantillon (pour [mergeAggregates]). */
internal fun VehicleSample.asUnitAggregate() = AggregatedSample(
    ts = ts,
    bucketMs = 0,
    count = 1,
    speedAvg = speedKmh, speedMax = speedKmh,
    rpmAvg = rpm, rpmMax = rpm,
    fuelPct = fuelPct, fuelLiters = fuelLiters,
    tCoolantAvg = tCoolant, tCoolantMax = tCoolant,
    tOilAvg = tOil, tOilMax = tOil,
    tExtAvg = tExt, tExtMax = tExt,
    oilLevelPct = oilLevelPct,
    fuelInstAvg = fuelInstL100,
    odoKm = odoKm,
)

/** Moyenne pondérée par count, en sautant les nuls. */
private fun List<AggregatedSample>.weightedAvgFloat(
    selector: (AggregatedSample) -> Float?,
): Float? {
    var sum = 0.0
    var w = 0
    for (item in this) {
        val v = selector(item) ?: continue
        sum += v.toDouble() * item.count
        w += item.count
    }
    return if (w > 0) (sum / w).toFloat() else null
}

private fun List<AggregatedSample>.weightedAvgInt(
    selector: (AggregatedSample) -> Int?,
): Int? {
    var sum = 0.0
    var w = 0
    for (item in this) {
        val v = selector(item) ?: continue
        sum += v.toDouble() * item.count
        w += item.count
    }
    return if (w > 0) (sum / w).toInt() else null
}
