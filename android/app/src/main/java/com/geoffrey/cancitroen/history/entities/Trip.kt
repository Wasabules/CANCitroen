package com.geoffrey.cancitroen.history.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Trajet : début (contact ON ou speed > 5) → fin (speed=0 prolongé ou contact OFF).
 * Mis à jour pendant qu'il est en cours, puis figé à la fermeture.
 */
@Entity(
    tableName = "trip",
    indices = [Index("startTs")],
)
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTs: Long,
    val endTs: Long? = null,
    val startOdoKm: Double? = null,
    val endOdoKm: Double? = null,
    val startFuelPct: Int? = null,
    val endFuelPct: Int? = null,
    val maxSpeedKmh: Float? = null,
    val avgSpeedKmh: Float? = null,
    val avgConsL100: Float? = null,
) {
    val durationMs: Long? get() = endTs?.let { it - startTs }
    val distanceKm: Double? get() {
        val end = endOdoKm ?: return null
        val start = startOdoKm ?: return null
        return end - start
    }
}
