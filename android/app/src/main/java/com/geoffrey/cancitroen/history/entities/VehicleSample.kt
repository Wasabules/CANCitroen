package com.geoffrey.cancitroen.history.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Échantillon véhicule à un instant T.
 * Écrit toutes les 5 s par le recorder quand le contact est ON.
 * Purge automatique au-delà de 30 jours.
 *
 * tripId est une FK vers Trip avec ON DELETE SET NULL : si un trip est
 * supprimé, les samples restent (utiles pour l'historique global) mais
 * leur lien au trip est rompu proprement.
 */
@Entity(
    tableName = "vehicle_sample",
    indices = [Index("ts"), Index("tripId")],
    foreignKeys = [
        ForeignKey(
            entity = Trip::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class VehicleSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Timestamp epoch ms */
    val ts: Long,
    /** ID du trajet (null si hors trajet) */
    val tripId: Long? = null,

    val speedKmh: Float? = null,
    val rpm: Float? = null,
    val fuelPct: Int? = null,
    val fuelLiters: Float? = null,
    val rangeKm: Int? = null,
    val tCoolant: Int? = null,
    val tOil: Int? = null,
    val tExt: Int? = null,
    val oilLevelPct: Int? = null,
    val odoKm: Double? = null,
    /** Conso instantanée l/100 km */
    val fuelInstL100: Float? = null,
)
