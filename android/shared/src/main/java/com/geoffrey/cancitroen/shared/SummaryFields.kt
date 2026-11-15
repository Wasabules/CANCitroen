package com.geoffrey.cancitroen.shared

/**
 * Les champs du résumé JSON de l'Atoto (`/telemetry/summary`), lu par le compagnon pour son
 * écran, Android Auto et Home Assistant. Un champ inconnu vaut null : jamais de valeur inventée.
 */
object SummaryFields {
    /** Instant du dernier échantillon. */
    const val LAST_SEEN = "lastSeenTs"
    const val FUEL_PCT = "fuelPct"
    const val FUEL_LITERS = "fuelLiters"
    const val RANGE = "rangeKm"
    const val ODO = "odoKm"
    const val COOLANT = "tCoolant"
    const val OIL = "tOil"
    const val OUTSIDE = "tExt"
    const val OIL_LEVEL = "oilLevelPct"

    /** Un trajet est ouvert et l'Atoto enregistre encore. */
    const val TRIP_ACTIVE = "tripActive"

    // Dernier trajet terminé.
    const val TRIP_START = "tripStartTs"
    const val TRIP_END = "tripEndTs"
    const val TRIP_KM = "tripKm"
    const val TRIP_AVG_SPEED = "tripAvgSpeed"
    const val TRIP_MAX_SPEED = "tripMaxSpeed"
    const val TRIP_CONS = "tripCons"

    // Trajet en cours, s'il y en a un : de quoi le clore côté téléphone si l'Atoto s'éteint avant.
    const val CURRENT_START = "currentTripStartTs"
    const val CURRENT_KM = "currentTripKm"
    const val CURRENT_AVG_SPEED = "currentTripAvgSpeed"
    const val CURRENT_MAX_SPEED = "currentTripMaxSpeed"
    const val CURRENT_CONS = "currentTripCons"

    // Dernier plein.
    const val REFUEL_TS = "refuelTs"
    const val REFUEL_LITERS = "refuelLiters"
    const val REFUEL_COST = "refuelCost"
    const val REFUEL_PRICE = "refuelPrice"
    const val REFUEL_ESTIMATED = "refuelEstimated"

    // Mois en cours.
    const val MONTH_KM = "monthKm"
    const val MONTH_TRIPS = "monthTrips"
    const val MONTH_CONS = "monthCons"
    const val MONTH_LITERS = "monthLiters"
    const val MONTH_COST = "monthCost"

    /** Prix du litre retenu par l'Atoto (dernier ticket ou réglage) : coût d'un trajet. */
    const val PRICE_PER_LITER = "pricePerLiter"

    // Position GPS, seulement si le téléphone l'a demandée (LinkProtocol.WITH_POSITION).
    const val LAT = "lat"
    const val LON = "lon"
    const val GPS_ACCURACY = "gpsAccuracyM"
    const val GPS_BEARING = "gpsBearing"
    const val GPS_TS = "gpsTs"
}
