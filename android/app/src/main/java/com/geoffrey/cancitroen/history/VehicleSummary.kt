package com.geoffrey.cancitroen.history

import com.geoffrey.cancitroen.history.dao.TripResumeCounters
import com.geoffrey.cancitroen.history.entities.AggregatedSample
import com.geoffrey.cancitroen.history.entities.RefuelEvent
import com.geoffrey.cancitroen.history.entities.Trip
import com.geoffrey.cancitroen.history.entities.VehicleSample
import com.geoffrey.cancitroen.shared.SummaryFields as F
import com.geoffrey.cancitroen.system.GpsFix
import org.json.JSONObject
import java.util.Calendar

/**
 * L'état de la voiture vu depuis l'extérieur : dernières valeurs, trajet en cours et dernier
 * trajet, dernier plein, mois en cours ; la position seulement si le téléphone l'a demandée.
 * Champs JSON : `SummaryFields`, partagé avec le compagnon.
 */
data class VehicleSummary(
    val lastSeenTs: Long? = null,
    val fuelPct: Int? = null,
    val fuelLiters: Float? = null,
    val rangeKm: Int? = null,
    val odoKm: Double? = null,
    val tCoolant: Int? = null,
    val tOil: Int? = null,
    val tExt: Int? = null,
    val oilLevelPct: Int? = null,
    val tripActive: Boolean = false,
    val lastTrip: Trip? = null,
    val lastTripCons: Double? = null,
    val lastRefuel: RefuelEvent? = null,
    val monthKm: Double = 0.0,
    val monthTrips: Int = 0,
    val monthCons: Double? = null,
    val monthLiters: Double = 0.0,
    val monthCost: Double = 0.0,
    val currentTripStartTs: Long? = null,
    val currentTripKm: Double? = null,
    val currentTripAvgSpeed: Double? = null,
    val currentTripMaxSpeed: Double? = null,
    val currentTripCons: Double? = null,
    val pricePerLiter: Double? = null,
    val position: GpsFix? = null,
) {
    fun toJson(): String = JSONObject().apply {
        put(F.LAST_SEEN, lastSeenTs)
        put(F.FUEL_PCT, fuelPct)
        put(F.FUEL_LITERS, fuelLiters?.toDouble())
        put(F.RANGE, rangeKm)
        put(F.ODO, odoKm)
        put(F.COOLANT, tCoolant)
        put(F.OIL, tOil)
        put(F.OUTSIDE, tExt)
        put(F.OIL_LEVEL, oilLevelPct)
        put(F.TRIP_ACTIVE, tripActive)
        lastTrip?.let { trip ->
            put(F.TRIP_START, trip.startTs)
            put(F.TRIP_END, trip.endTs)
            put(F.TRIP_KM, trip.distanceKm)
            put(F.TRIP_AVG_SPEED, trip.avgSpeedKmh?.toDouble())
            put(F.TRIP_MAX_SPEED, trip.maxSpeedKmh?.toDouble())
            put(F.TRIP_CONS, lastTripCons)
        }
        currentTripStartTs?.let {
            put(F.CURRENT_START, it)
            put(F.CURRENT_KM, currentTripKm)
            put(F.CURRENT_AVG_SPEED, currentTripAvgSpeed)
            put(F.CURRENT_MAX_SPEED, currentTripMaxSpeed)
            put(F.CURRENT_CONS, currentTripCons)
        }
        lastRefuel?.let { refuel ->
            put(F.REFUEL_TS, refuel.ts)
            put(F.REFUEL_LITERS, refuel.litersAdded.toDouble())
            put(F.REFUEL_COST, refuel.totalCost)
            put(F.REFUEL_PRICE, refuel.pricePerLiter)
            put(F.REFUEL_ESTIMATED, !refuel.userEntered)
        }
        put(F.MONTH_KM, monthKm)
        put(F.MONTH_TRIPS, monthTrips)
        put(F.MONTH_CONS, monthCons)
        put(F.MONTH_LITERS, monthLiters)
        put(F.MONTH_COST, monthCost)
        put(F.PRICE_PER_LITER, pricePerLiter)
        position?.let { fix ->
            put(F.LAT, fix.lat)
            put(F.LON, fix.lon)
            put(F.GPS_ACCURACY, fix.accuracyM?.toDouble())
            put(F.GPS_BEARING, fix.bearing?.toDouble())
            put(F.GPS_TS, fix.ts)
        }
    }.toString()

    companion object {
        /** Sans échantillon depuis ce délai, un trajet resté ouvert n'est plus « en cours ». */
        private const val ACTIVE_TRIP_MAX_SILENCE_MS = 10L * 60_000L
        const val RECENT_SAMPLES = 120

        /**
         * [recent] dans l'ordre chronologique : chaque valeur vient du dernier échantillon
         * qui la connaît, puis du dernier agrégat. [consumptionFactor] : correction issue
         * des tickets, appliquée ici comme à l'écran (l'historique garde la valeur de la voiture).
         * [currentCounters] : sommes des échantillons du trajet en cours, s'il y en a un.
         */
        fun compute(
            now: Long,
            recent: List<VehicleSample>,
            lastAggregate: AggregatedSample?,
            trips: List<Trip>,
            refuels: List<RefuelEvent>,
            consumptionFactor: Double,
            currentCounters: TripResumeCounters? = null,
            pricePerLiter: Double? = null,
            position: GpsFix? = null,
        ): VehicleSummary {
            fun <T : Any> latest(sample: (VehicleSample) -> T?, aggregate: (AggregatedSample) -> T?): T? =
                recent.lastOrNull { sample(it) != null }?.let(sample) ?: lastAggregate?.let(aggregate)

            val lastSeen = recent.lastOrNull()?.ts ?: lastAggregate?.ts
            val monthStart = startOfMonth(now)
            val monthTrips = trips.filter { it.startTs >= monthStart && it.endTs != null }
            val monthRefuels = refuels.filter { it.ts >= monthStart }
            var consKm = 0.0
            var consLiters = 0.0
            for (trip in monthTrips) {
                val km = trip.distanceKm?.takeIf { it > 0 } ?: continue
                val cons = trip.avgConsL100 ?: continue
                consKm += km
                consLiters += km * cons
            }
            val lastTrip = trips.lastOrNull { it.endTs != null }
            val active = trips.lastOrNull()?.endTs == null && trips.isNotEmpty() &&
                lastSeen != null && now - lastSeen < ACTIVE_TRIP_MAX_SILENCE_MS
            val current = trips.lastOrNull()?.takeIf { active }
            val odoNow = recent.lastOrNull { it.odoKm != null }?.odoKm
            return VehicleSummary(
                lastSeenTs = lastSeen,
                fuelPct = latest({ s -> s.fuelPct?.takeIf { it in 1..100 } }, { a -> a.fuelPct?.takeIf { it in 1..100 } }),
                fuelLiters = latest({ it.fuelLiters }, { it.fuelLiters }),
                rangeKm = recent.lastOrNull { it.rangeKm != null }?.rangeKm,
                odoKm = latest({ it.odoKm }, { it.odoKm }),
                tCoolant = latest({ it.tCoolant }, { it.tCoolantAvg }),
                tOil = latest({ it.tOil }, { it.tOilAvg }),
                tExt = latest({ it.tExt }, { it.tExtAvg }),
                oilLevelPct = latest({ it.oilLevelPct }, { it.oilLevelPct }),
                tripActive = active,
                lastTrip = lastTrip,
                lastTripCons = lastTrip?.avgConsL100?.let { it * consumptionFactor },
                lastRefuel = refuels.lastOrNull(),
                monthKm = monthTrips.sumOf { it.distanceKm?.coerceAtLeast(0.0) ?: 0.0 },
                monthTrips = monthTrips.size,
                monthCons = if (consKm > 0) consLiters / consKm * consumptionFactor else null,
                monthLiters = monthRefuels.sumOf { it.litersAdded.toDouble() },
                monthCost = monthRefuels.sumOf { it.totalCost ?: 0.0 },
                currentTripStartTs = current?.startTs,
                currentTripKm = current?.startOdoKm?.let { start -> odoNow?.let { (it - start).coerceAtLeast(0.0) } },
                currentTripAvgSpeed = currentCounters?.takeIf { current != null && it.speedCount > 0 }
                    ?.let { (it.speedSum ?: 0.0) / it.speedCount },
                currentTripMaxSpeed = currentCounters?.takeIf { current != null }?.speedMax,
                currentTripCons = currentCounters?.takeIf { current != null && it.consCount > 0 }
                    ?.let { (it.consSum ?: 0.0) / it.consCount * consumptionFactor },
                pricePerLiter = pricePerLiter,
                position = position,
            )
        }

        private fun startOfMonth(now: Long): Long = Calendar.getInstance().run {
            timeInMillis = now
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            timeInMillis
        }
    }
}
