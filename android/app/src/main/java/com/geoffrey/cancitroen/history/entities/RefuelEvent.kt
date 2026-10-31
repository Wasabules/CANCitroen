package com.geoffrey.cancitroen.history.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Plein détecté (voir RefuelDetector) ou ajouté à la main. Conservé indéfiniment
 * (ne fait pas partie de la purge). Litres, coût et prix sont des estimations
 * tant que [userEntered] est faux, puis les valeurs du ticket.
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
    /** Le ticket reste à saisir : l'app le demandera à l'arrêt. */
    @ColumnInfo(defaultValue = "0") val awaitingInput: Boolean = false,
    /** Litres et montant viennent du ticket (utilisables pour calibrer le réservoir). */
    @ColumnInfo(defaultValue = "0") val userEntered: Boolean = false,
    /** Ajouté à la main : la jauge avant le plein est déduite des litres, pas mesurée. */
    @ColumnInfo(defaultValue = "0") val manual: Boolean = false,
)
