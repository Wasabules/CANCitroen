package com.geoffrey.cancitroen.history.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.geoffrey.cancitroen.history.entities.AggregatedSample
import com.geoffrey.cancitroen.history.entities.RefuelEvent
import com.geoffrey.cancitroen.history.entities.Trip
import com.geoffrey.cancitroen.history.entities.VehicleSample
import kotlinx.coroutines.flow.Flow

/**
 * Agrégats live d'un trip pour [ActiveTripCard]. Champs nullables car Room
 * remonte null si la table n'a aucun row matchant tripId.
 */
data class TripLiveAggregates(
    val maxOdoKm: Double? = null,
    val maxSpeedKmh: Double? = null,
    val avgSpeedKmh: Double? = null,
    val avgConsL100: Double? = null,
)

/**
 * Compteurs utilisés au resume d'un trip après crash/restart du service. Évite
 * de matérialiser toute la liste des samples en RAM juste pour ressommer.
 */
data class TripResumeCounters(
    val speedSum: Double? = null,
    val speedCount: Int = 0,
    val speedMax: Double? = null,
    val consSum: Double? = null,
    val consCount: Int = 0,
)

@Dao
interface HistoryDao {

    // ── VehicleSample ──
    @Insert
    suspend fun insertSample(sample: VehicleSample): Long

    @Query("SELECT * FROM vehicle_sample WHERE ts >= :sinceMs ORDER BY ts ASC")
    fun samplesSince(sinceMs: Long): Flow<List<VehicleSample>>

    /** Fenêtre bornée des deux côtés (Stats ≤ 6 h) : une plage passée ne charge pas tout jusqu'à maintenant. */
    @Query("SELECT * FROM vehicle_sample WHERE ts >= :fromMs AND ts <= :toMs ORDER BY ts ASC")
    fun samplesInRangeFlow(fromMs: Long, toMs: Long): Flow<List<VehicleSample>>

    /** Sous-ensemble borné dans le temps — utilisé par les UI live pour ne
     *  pas materialiser des milliers de rows sur un trip de plusieurs heures. */
    @Query("SELECT * FROM vehicle_sample WHERE tripId = :tripId AND ts >= :sinceMs ORDER BY ts ASC")
    fun samplesForTripSince(tripId: Long, sinceMs: Long): Flow<List<VehicleSample>>

    /**
     * Agrégats calculés côté SQL — bien plus rapide que de matérialiser tous
     * les samples et de faire AVG en Kotlin sur 3000+ lignes à chaque
     * recompose. Returné en data class via Room ColumnInfo.
     */
    @Query("""SELECT
                MAX(odoKm)        AS maxOdoKm,
                MAX(speedKmh)     AS maxSpeedKmh,
                AVG(speedKmh)     AS avgSpeedKmh,
                AVG(fuelInstL100) AS avgConsL100
              FROM vehicle_sample WHERE tripId = :tripId""")
    fun tripLiveAggregates(tripId: Long): Flow<TripLiveAggregates?>

    @Query("SELECT * FROM vehicle_sample WHERE tripId = :tripId ORDER BY ts ASC")
    suspend fun samplesForTripOnce(tripId: Long): List<VehicleSample>

    /**
     * Compteurs agrégés calculés en SQL pour la reprise d'un trip après
     * restart du service — un trip de 4h = 2880 rows et il était pénible
     * de tout charger en RAM juste pour faire `.sum() / .count()` en Kotlin.
     */
    @Query("""SELECT
                SUM(speedKmh)     AS speedSum,
                COUNT(speedKmh)   AS speedCount,
                MAX(speedKmh)     AS speedMax,
                SUM(fuelInstL100) AS consSum,
                COUNT(fuelInstL100) AS consCount
              FROM vehicle_sample WHERE tripId = :tripId""")
    suspend fun tripResumeCounters(tripId: Long): TripResumeCounters?

    /** Récupère un range raw, sans Flow (pour le rollup). */
    @Query("SELECT * FROM vehicle_sample WHERE ts >= :fromMs AND ts < :toMs ORDER BY ts ASC")
    suspend fun samplesInRange(fromMs: Long, toMs: Long): List<VehicleSample>

    /** Pour purge : supprime ce qui est plus vieux que ts. */
    @Query("DELETE FROM vehicle_sample WHERE ts < :ts")
    suspend fun deleteSamplesOlderThan(ts: Long): Int

    @Query("SELECT MIN(ts) FROM vehicle_sample")
    suspend fun oldestSampleTs(): Long?

    // ── AggregatedSample (rollup) ──
    @Insert
    suspend fun insertAggregates(items: List<AggregatedSample>)


    @Query("""SELECT * FROM aggregated_sample
              WHERE bucketMs = :bucketMs AND ts >= :fromMs AND ts < :toMs
              ORDER BY ts ASC""")
    suspend fun aggregatesInRangeOnce(bucketMs: Long, fromMs: Long, toMs: Long): List<AggregatedSample>

    /** Toutes les couches d'agrégats sur la plage (elles ne se recouvrent pas : le rollup supprime la source). */
    @Query("SELECT * FROM aggregated_sample WHERE ts >= :fromMs AND ts < :toMs ORDER BY ts ASC")
    fun aggregatesAllInRange(fromMs: Long, toMs: Long): Flow<List<AggregatedSample>>

    @Query("SELECT MIN(ts) FROM aggregated_sample WHERE bucketMs = :bucketMs")
    suspend fun oldestAggregateTs(bucketMs: Long): Long?

    @Query("SELECT MAX(ts) FROM aggregated_sample WHERE bucketMs = :bucketMs")
    suspend fun newestAggregateTs(bucketMs: Long): Long?

    @Query("DELETE FROM aggregated_sample WHERE bucketMs = :bucketMs AND ts < :ts")
    suspend fun deleteAggregatesOlderThan(bucketMs: Long, ts: Long): Int

    // ── RefuelEvent ──
    @Insert
    suspend fun insertRefuel(event: RefuelEvent): Long

    @Query("SELECT * FROM refuel_event WHERE ts >= :sinceMs ORDER BY ts DESC")
    fun refuelsSince(sinceMs: Long): Flow<List<RefuelEvent>>

    @Query("SELECT * FROM refuel_event ORDER BY ts DESC LIMIT 1")
    suspend fun lastRefuel(): RefuelEvent?

    // ── Trip ──
    @Insert
    suspend fun insertTrip(trip: Trip): Long

    @Update
    suspend fun updateTrip(trip: Trip)

    @Query("SELECT * FROM trip ORDER BY startTs DESC LIMIT :limit")
    fun recentTrips(limit: Int = 50): Flow<List<Trip>>

    @Query("SELECT * FROM trip WHERE startTs >= :sinceMs ORDER BY startTs DESC")
    fun tripsSince(sinceMs: Long): Flow<List<Trip>>

    @Query("SELECT * FROM trip WHERE id = :id")
    suspend fun tripById(id: Long): Trip?

    @Query("SELECT * FROM trip WHERE endTs IS NULL ORDER BY startTs DESC LIMIT 1")
    suspend fun activeTrip(): Trip?

    @Query("DELETE FROM trip WHERE id = :id")
    suspend fun deleteTrip(id: Long)
}
