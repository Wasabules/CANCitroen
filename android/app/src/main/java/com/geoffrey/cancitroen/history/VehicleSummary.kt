package com.geoffrey.cancitroen.history

import com.geoffrey.cancitroen.history.entities.AggregatedSample
import com.geoffrey.cancitroen.history.entities.RefuelEvent
import com.geoffrey.cancitroen.history.entities.Trip
import com.geoffrey.cancitroen.history.entities.VehicleSample
import org.json.JSONObject
import java.util.Calendar

/** L'état de la voiture vu depuis l'extérieur : dernières valeurs, dernier trajet, dernier plein, mois en cours. */
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
) {
    fun toJson(): String = JSONObject().apply {
        put("lastSeenTs", lastSeenTs)
        put("fuelPct", fuelPct)
        put("fuelLiters", fuelLiters?.toDouble())
        put("rangeKm", rangeKm)
        put("odoKm", odoKm)
        put("tCoolant", tCoolant)
        put("tOil", tOil)
        put("tExt", tExt)
        put("oilLevelPct", oilLevelPct)
        put("tripActive", tripActive)
        lastTrip?.let { trip ->
            put("tripStartTs", trip.startTs)
            put("tripEndTs", trip.endTs)
            put("tripKm", trip.distanceKm)
            put("tripAvgSpeed", trip.avgSpeedKmh?.toDouble())
            put("tripMaxSpeed", trip.maxSpeedKmh?.toDouble())
            put("tripCons", lastTripCons)
        }
        lastRefuel?.let { refuel ->
            put("refuelTs", refuel.ts)
            put("refuelLiters", refuel.litersAdded.toDouble())
            put("refuelCost", refuel.totalCost)
            put("refuelPrice", refuel.pricePerLiter)
            put("refuelEstimated", !refuel.userEntered)
        }
        put("monthKm", monthKm)
        put("monthTrips", monthTrips)
        put("monthCons", monthCons)
        put("monthLiters", monthLiters)
        put("monthCost", monthCost)
    }.toString()

    companion object {
        /** Sans échantillon depuis ce délai, un trajet resté ouvert n'est plus « en cours ». */
        private const val ACTIVE_TRIP_MAX_SILENCE_MS = 10L * 60_000L
        const val RECENT_SAMPLES = 120

        /**
         * [recent] dans l'ordre chronologique : chaque valeur vient du dernier échantillon
         * qui la connaît, puis du dernier agrégat. [consumptionFactor] : correction issue
         * des tickets, appliquée ici comme à l'écran (l'historique garde la valeur de la voiture).
         */
        fun compute(
            now: Long,
            recent: List<VehicleSample>,
            lastAggregate: AggregatedSample?,
            trips: List<Trip>,
            refuels: List<RefuelEvent>,
            consumptionFactor: Double,
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
                tripActive = trips.lastOrNull()?.endTs == null && trips.isNotEmpty() &&
                    lastSeen != null && now - lastSeen < ACTIVE_TRIP_MAX_SILENCE_MS,
                lastTrip = lastTrip,
                lastTripCons = lastTrip?.avgConsL100?.let { it * consumptionFactor },
                lastRefuel = refuels.lastOrNull(),
                monthKm = monthTrips.sumOf { it.distanceKm?.coerceAtLeast(0.0) ?: 0.0 },
                monthTrips = monthTrips.size,
                monthCons = if (consKm > 0) consLiters / consKm * consumptionFactor else null,
                monthLiters = monthRefuels.sumOf { it.litersAdded.toDouble() },
                monthCost = monthRefuels.sumOf { it.totalCost ?: 0.0 },
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
