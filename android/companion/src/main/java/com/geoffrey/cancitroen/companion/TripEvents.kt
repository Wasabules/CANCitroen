package com.geoffrey.cancitroen.companion

import com.geoffrey.cancitroen.shared.SummaryFields as F
import org.json.JSONObject

/** Carburant de la voiture : il fixe le CO₂ émis par litre brûlé. */
enum class FuelType(val label: String, val co2KgPerLiter: Double) {
    PETROL("Essence", 2.31),
    DIESEL("Gazole", 2.64),
}

/**
 * Événements Home Assistant (`fire_event` de `mobile_app`) tirés des résumés de l'Atoto, sans
 * Android : testés en JVM. Chaque trajet et chaque plein n'est annoncé qu'une fois, repéré par
 * son instant de début ; au premier résumé, rien n'est annoncé : l'historique n'est pas un événement.
 */
object TripEvents {
    const val TRIP_ENDED = "cancitroen_trajet_termine"
    const val REFUEL = "cancitroen_plein"

    class Event(val type: String, val data: JSONObject)

    /** Derniers trajet et plein annoncés. */
    data class State(val tripStart: Long = 0, val refuelTs: Long = 0, val initialized: Boolean = false)

    class Result(val events: List<Event>, val state: State)

    /** Un trajet clos par l'Atoto, un plein détecté : ce qui n'a pas encore été annoncé. */
    fun fromSummary(summary: JSONObject, state: State, fuel: FuelType): Result {
        val closedStart = long(summary, F.TRIP_START) ?: 0
        val refuel = long(summary, F.REFUEL_TS) ?: 0
        if (!state.initialized) return Result(emptyList(), State(closedStart, refuel, true))
        val events = mutableListOf<Event>()
        var next = state
        if (closedStart > state.tripStart) {
            val end = long(summary, F.TRIP_END) ?: closedStart
            events += tripEvent(
                closedStart, end, number(summary, F.TRIP_KM), number(summary, F.TRIP_CONS),
                number(summary, F.TRIP_AVG_SPEED), number(summary, F.TRIP_MAX_SPEED),
                price(summary), fuel, provisional = false,
            )
            next = next.copy(tripStart = closedStart)
        }
        if (refuel > state.refuelTs) {
            events += Event(REFUEL, JSONObject().apply {
                put("date", HaPayloads.isoTime(refuel))
                put("litres", number(summary, F.REFUEL_LITERS)?.let { HaPayloads.round(it, 2) })
                put("montant_eur", number(summary, F.REFUEL_COST)?.let { HaPayloads.round(it, 2) })
                put("prix_litre_eur", number(summary, F.REFUEL_PRICE)?.let { HaPayloads.round(it, 3) })
                put("estimation", summary.optBoolean(F.REFUEL_ESTIMATED, true))
            })
            next = next.copy(refuelTs = refuel)
        }
        return Result(events, next)
    }

    /**
     * Fin du suivi, l'Atoto ne répond plus : le trajet en cours n'est peut-être pas encore clos
     * de son côté (il le sera au prochain démarrage). On l'annonce avec ses derniers chiffres,
     * arrivée = dernier contact ; sa clôture par l'Atoto ne sera pas annoncée une seconde fois.
     */
    fun fromSessionEnd(summary: JSONObject, state: State, endTs: Long, fuel: FuelType): Result {
        if (!state.initialized || !summary.optBoolean(F.TRIP_ACTIVE)) return Result(emptyList(), state)
        val start = long(summary, F.CURRENT_START) ?: return Result(emptyList(), state)
        if (start <= state.tripStart) return Result(emptyList(), state)
        val event = tripEvent(
            start, endTs, number(summary, F.CURRENT_KM), number(summary, F.CURRENT_CONS),
            number(summary, F.CURRENT_AVG_SPEED), number(summary, F.CURRENT_MAX_SPEED),
            price(summary), fuel, provisional = true,
        )
        return Result(listOf(event), state.copy(tripStart = start))
    }

    /** Litres brûlés sur un trajet : distance et consommation moyenne. */
    fun liters(km: Double?, cons: Double?): Double? = if (km != null && cons != null && km > 0) km * cons / 100 else null

    private fun tripEvent(
        start: Long,
        end: Long,
        km: Double?,
        cons: Double?,
        avgSpeed: Double?,
        maxSpeed: Double?,
        price: Double?,
        fuel: FuelType,
        provisional: Boolean,
    ): Event {
        val liters = liters(km, cons)
        return Event(TRIP_ENDED, JSONObject().apply {
            put("depart", HaPayloads.isoTime(start))
            put("arrivee", HaPayloads.isoTime(end))
            put("duree_min", ((end - start) / 60_000).coerceAtLeast(0))
            put("distance_km", km?.let { HaPayloads.round(it, 1) })
            put("conso_l100", cons?.let { HaPayloads.round(it, 1) })
            put("vitesse_moyenne_kmh", avgSpeed?.let { HaPayloads.round(it, 0) })
            put("vitesse_max_kmh", maxSpeed?.let { HaPayloads.round(it, 0) })
            put("litres", liters?.let { HaPayloads.round(it, 2) })
            put("cout_eur", liters?.let { l -> price?.let { HaPayloads.round(l * it, 2) } })
            put("co2_kg", liters?.let { HaPayloads.round(it * fuel.co2KgPerLiter, 1) })
            // Provisoire : chiffres du dernier contact, avant la clôture par l'Atoto.
            put("provisoire", provisional)
        })
    }

    private fun price(summary: JSONObject) = number(summary, F.REFUEL_PRICE) ?: number(summary, F.PRICE_PER_LITER)

    private fun number(summary: JSONObject, key: String): Double? =
        if (!summary.has(key) || summary.isNull(key)) null else summary.optDouble(key).takeUnless { it.isNaN() }

    private fun long(summary: JSONObject, key: String): Long? = number(summary, key)?.toLong()?.takeIf { it > 0 }
}
