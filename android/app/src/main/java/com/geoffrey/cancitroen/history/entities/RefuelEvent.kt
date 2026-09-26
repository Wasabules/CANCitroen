package com.geoffrey.cancitroen.history.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Plein détecté automatiquement (saut de fuelPct ≥ 20% en moins de 10 min).
 * Conservé indéfiniment (ne fait pas partie de la purge).
 */
@Entity(
    tableName = "refuel_event",
    indices = [Index("ts")],
)
data class RefuelEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val fuelPctBefore: Int,
    val fuelPctAfter: Int,
    val litersAdded: Float,
    val odoKm: Double? = null,
    /** Prix au litre au moment du plein (€). Null = pas connu / non historisé. */
    val pricePerLiter: Double? = null,
    /** Coût total estimé (litersAdded × pricePerLiter). Pré-calculé pour requêtes rapides. */
    val totalCost: Double? = null,
)
