package com.geoffrey.cancitroen.history.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Sample agrégé sur un bucket temporel. Chaque palier de rollup en stocke ses
 * propres lignes :
 *  - bucketMs = 60_000      → 1 min   (utilisé pour 6 h → 7 j)
 *  - bucketMs = 1_800_000   → 30 min  (utilisé pour 7 j → 30 j)
 *  - bucketMs = 3_600_000   → 1 h     (utilisé pour 30 j → 90 j)
 *  - bucketMs = 21_600_000  → 6 h     (utilisé > 90 j, conservé indéfiniment)
 *
 *  ts = début du bucket. On stocke moyenne + max (et min pour les T°) afin de
 *  pouvoir refaire des graphes "max par période" même sur la donnée agrégée.
 *  count = nombre de samples raw qui ont composé ce bucket (utile pour les
 *  ré-agrégations en cascade).
 */
@Entity(
    tableName = "aggregated_sample",
    indices = [Index(value = ["bucketMs", "ts"])],
)
data class AggregatedSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val bucketMs: Long,
    val count: Int,

    val speedAvg: Float? = null,
    val speedMax: Float? = null,

    val rpmAvg: Float? = null,
    val rpmMax: Float? = null,

    /** Carburant : on prend la valeur médiane / dernière du bucket */
    val fuelPct: Int? = null,
    val fuelLiters: Float? = null,

    val tCoolantAvg: Int? = null,
    val tCoolantMax: Int? = null,

    val tOilAvg: Int? = null,
    val tOilMax: Int? = null,

    val tExtAvg: Int? = null,
    val tExtMax: Int? = null,

    val oilLevelPct: Int? = null,

    val fuelInstAvg: Float? = null,

    val odoKm: Double? = null,
)
