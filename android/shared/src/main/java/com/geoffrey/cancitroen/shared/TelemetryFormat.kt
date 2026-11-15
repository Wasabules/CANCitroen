package com.geoffrey.cancitroen.shared

/**
 * Les fichiers CSV de l'historique (export de l'Atoto, fichiers du compagnon) : format d'un
 * tableur français, `;` et virgule décimale. Les colonnes se retrouvent par leur nom dans
 * l'en-tête ; seuls le numéro et la date des échantillons ont une place fixe (1re et 2e).
 */
object TelemetryFormat {
    const val SAMPLE_FILE_PREFIX = "echantillons-"
    const val AGGREGATES_FILE = "agregats.csv"
    const val TRIPS_FILE = "trajets.csv"
    const val REFUELS_FILE = "pleins.csv"
    const val GAPS_FILE = "manques.csv"

    fun sampleFile(day: String) = "$SAMPLE_FILE_PREFIX$day.csv"

    /** Un échantillon toutes les 5 s, contact mis. */
    object Samples {
        const val ID = "id"
        const val DATE = "date"
        const val TS = "ts_ms"
        const val TRIP = "trajet"
        const val SPEED = "vitesse_kmh"
        const val RPM = "regime_tr_min"
        const val FUEL_PCT = "jauge_pct"
        const val LITERS = "litres"
        const val RANGE = "autonomie_km"
        const val COOLANT = "t_eau"
        const val OIL = "t_huile"
        const val OUTSIDE = "t_ext"
        const val OIL_LEVEL = "niveau_huile_pct"
        const val ODO = "odometre_km"
        const val CONS = "conso_inst_l100"

        val HEADER = listOf(
            ID, DATE, TS, TRIP, SPEED, RPM, FUEL_PCT, LITERS, RANGE, COOLANT, OIL, OUTSIDE, OIL_LEVEL, ODO, CONS,
        ).joinToString(";")
    }

    /** Une tranche agrégée par l'Atoto quand le détail vieillit. */
    object Aggregates {
        const val START = "debut"
        const val TS = "ts_ms"
        const val DURATION = "duree_s"
        const val COUNT = "echantillons"
        const val SPEED = "vitesse_moy_kmh"
        const val SPEED_MAX = "vitesse_max_kmh"
        const val RPM = "regime_moy"
        const val RPM_MAX = "regime_max"
        const val FUEL_PCT = "jauge_pct"
        const val LITERS = "litres"
        const val COOLANT = "t_eau_moy"
        const val COOLANT_MAX = "t_eau_max"
        const val OIL = "t_huile_moy"
        const val OIL_MAX = "t_huile_max"
        const val OUTSIDE = "t_ext_moy"
        const val OUTSIDE_MAX = "t_ext_max"
        const val OIL_LEVEL = "niveau_huile_pct"
        const val CONS = "conso_moy_l100"
        const val ODO = "odometre_km"

        val HEADER = listOf(
            START, TS, DURATION, COUNT, SPEED, SPEED_MAX, RPM, RPM_MAX, FUEL_PCT, LITERS,
            COOLANT, COOLANT_MAX, OIL, OIL_MAX, OUTSIDE, OUTSIDE_MAX, OIL_LEVEL, CONS, ODO,
        ).joinToString(";")
    }

    object Trips {
        const val START = "debut"
        const val END = "fin"
        const val DISTANCE = "distance_km"
        const val SPEED_AVG = "vitesse_moy_kmh"
        const val SPEED_MAX = "vitesse_max_kmh"
        const val CONS = "conso_moy_l100"
        const val FUEL_START = "jauge_debut"
        const val FUEL_END = "jauge_fin"

        val HEADER = listOf(START, END, DISTANCE, SPEED_AVG, SPEED_MAX, CONS, FUEL_START, FUEL_END).joinToString(";")
    }

    object Refuels {
        const val DATE = "date"
        const val FUEL_BEFORE = "jauge_avant"
        const val FUEL_AFTER = "jauge_apres"
        const val LITERS = "litres"
        const val COST = "montant_eur"
        const val PRICE = "prix_litre_eur"
        const val ODO = "odometre_km"
        const val ORIGIN = "origine"

        val HEADER = listOf(DATE, FUEL_BEFORE, FUEL_AFTER, LITERS, COST, PRICE, ODO, ORIGIN).joinToString(";")
    }

    /** Dates des trajets et des pleins, à la minute, heure locale. */
    const val MINUTE_PATTERN = "yyyy-MM-dd HH:mm"

    /** Dates des échantillons et des agrégats, à la seconde, heure locale. */
    const val SECOND_PATTERN = "yyyy-MM-dd HH:mm:ss"
}
