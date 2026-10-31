package com.geoffrey.cancitroen.history

import com.geoffrey.cancitroen.history.entities.RefuelEvent
import com.geoffrey.cancitroen.history.entities.Trip
import kotlin.math.abs
import kotlin.math.roundToInt

/** Ce que les tickets de plein apprennent sur le prix et le réservoir. */
object FuelCalibration {

    /** Sous cette hausse de jauge, l'arrondi au % fausse trop le calcul. */
    const val MIN_USABLE_RISE_PCT = 20
    /** La jauge plafonne à 100 % alors qu'on peut encore remplir : hausse sous-estimée. */
    const val SATURATED_PCT = 99
    const val MIN_USABLE_FILLS = 3
    private const val MAX_FILLS = 10
    /** Deux pleins finis à ce niveau près ont le même contenu : le second vaut ce qui a été brûlé. */
    private const val SAME_LEVEL_PCT = 2
    /** Sous cette distance, l'arrondi de la jauge domine la mesure de conso. */
    private const val MIN_TANK_KM = 30.0
    /** Part de la distance entre deux pleins couverte par des trajets enregistrés. */
    private const val MIN_TRIP_COVERAGE = 0.7
    /** Un plein détecté dans ce délai après un ajout manuel est le même plein. */
    private const val MANUAL_MERGE_MS = 2L * 3_600_000L

    /** [liters] null tant qu'il n'y a pas [MIN_USABLE_FILLS] pleins exploitables. */
    data class CapacityEstimate(val fills: Int, val liters: Double?)

    /**
     * Capacité déduite des pleins saisis : litres ÷ hausse de jauge, sur les
     * [MAX_FILLS] plus récents. Médiane : une faute de frappe ne fausse rien.
     */
    fun estimateCapacity(refuels: List<RefuelEvent>): CapacityEstimate {
        val capacities = refuels
            .filter {
                it.userEntered && !it.manual &&
                    it.fuelPctAfter - it.fuelPctBefore >= MIN_USABLE_RISE_PCT &&
                    it.fuelPctAfter < SATURATED_PCT
            }
            .sortedByDescending { it.ts }
            .take(MAX_FILLS)
            .map { it.litersAdded / (it.fuelPctAfter - it.fuelPctBefore) * 100.0 }
        if (capacities.size < MIN_USABLE_FILLS) return CapacityEstimate(capacities.size, null)
        return CapacityEstimate(capacities.size, Math.round(median(capacities) * 10) / 10.0)
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /** Carburant brûlé entre deux pleins ; [exact] : mesuré au ticket plutôt qu'à la jauge. */
    data class TankConsumption(
        val fromTs: Long,
        val ts: Long,
        val km: Double,
        val liters: Double,
        val exact: Boolean,
    ) {
        val l100: Double get() = liters / km * 100.0
    }

    /**
     * Conso réelle entre pleins successifs. Si les deux finissent au même niveau,
     * le ticket du second donne ce qui a été brûlé ; sinon (appoint), c'est la
     * baisse de jauge entre les deux, car les litres du ticket n'y correspondent pas.
     */
    fun tankConsumptions(refuels: List<RefuelEvent>, capacityLiters: Float): List<TankConsumption> =
        refuels.sortedBy { it.ts }.zipWithNext().mapNotNull { (a, b) ->
            val km = (b.odoKm ?: return@mapNotNull null) - (a.odoKm ?: return@mapNotNull null)
            if (km < MIN_TANK_KM) return@mapNotNull null
            val sameLevel = b.userEntered && a.fuelPctAfter > 0 && b.fuelPctAfter > 0 &&
                abs(a.fuelPctAfter - b.fuelPctAfter) <= SAME_LEVEL_PCT
            val liters = when {
                sameLevel -> b.litersAdded.toDouble()
                a.fuelPctAfter > 0 && b.fuelPctBefore > 0 ->
                    (a.fuelPctAfter - b.fuelPctBefore) / 100.0 * capacityLiters
                else -> return@mapNotNull null
            }
            TankConsumption(a.ts, b.ts, km, liters, sameLevel).takeIf { it.l100 in 2.0..25.0 }
        }

    /** [factor] null tant qu'il n'y a pas [MIN_USABLE_FILLS] mesures comparables. */
    data class ConsumptionCheck(
        val fills: Int,
        val realL100: Double? = null,
        val shownL100: Double? = null,
        val factor: Double? = null,
    )

    /**
     * Compare la conso réelle à celle indiquée par la voiture sur les mêmes
     * trajets. Médiane sur les [MAX_FILLS] mesures récentes : un plein mal
     * détecté ou un ticket erroné ne fausse rien.
     */
    fun consumptionCheck(
        refuels: List<RefuelEvent>,
        trips: List<Trip>,
        capacityLiters: Float,
    ): ConsumptionCheck {
        data class Measure(val real: Double, val shown: Double)
        val measures = tankConsumptions(refuels, capacityLiters)
            .filter { it.exact || it.liters / capacityLiters * 100 >= MIN_USABLE_RISE_PCT }
            .sortedByDescending { it.ts }
            .mapNotNull { tank ->
                val covered = trips.filter { (it.distanceKm ?: 0.0) > 0 && it.avgConsL100 != null && it.startTs in tank.fromTs until tank.ts }
                val km = covered.sumOf { it.distanceKm!! }
                if (km < tank.km * MIN_TRIP_COVERAGE) return@mapNotNull null
                val shown = covered.sumOf { it.avgConsL100!! * it.distanceKm!! } / km
                Measure(tank.l100, shown).takeIf { shown > 0 && it.real / shown in 0.5..2.0 }
            }
            .take(MAX_FILLS)
        if (measures.size < MIN_USABLE_FILLS) return ConsumptionCheck(measures.size)
        return ConsumptionCheck(
            fills = measures.size,
            realL100 = median(measures.map { it.real }),
            shownL100 = median(measures.map { it.shown }),
            factor = Math.round(median(measures.map { it.real / it.shown }) * 100) / 100.0,
        )
    }

    /** Coût estimé d'un trajet, au prix du dernier ticket connu à cette date. */
    fun tripCost(
        trip: Trip,
        refuels: List<RefuelEvent>,
        fallbackPrice: Double,
        consumptionFactor: Double,
    ): Double? {
        val cons = trip.avgConsL100?.takeIf { it > 0 } ?: return null
        val km = trip.distanceKm?.takeIf { it > 0 } ?: return null
        val price = refuels
            .filter { it.userEntered && it.pricePerLiter != null && it.ts <= trip.startTs }
            .maxByOrNull { it.ts }
            ?.pricePerLiter ?: fallbackPrice
        return cons * km / 100 * consumptionFactor * price
    }

    /** Jauge avant un plein ajouté à la main, déduite des litres (0 = jauge inconnue). */
    fun manualLevelBefore(levelAfter: Int, liters: Double, capacityLiters: Float): Int =
        if (levelAfter <= 0) 0
        else (levelAfter - liters / capacityLiters * 100).roundToInt().coerceAtLeast(0)

    /**
     * Si le plein qu'on vient de détecter a déjà été saisi à la main, renvoie
     * cette saisie complétée par la jauge mesurée, pour ne pas le compter deux fois.
     */
    fun mergeWithManual(last: RefuelEvent?, before: Int, after: Int, nowMs: Long): RefuelEvent? =
        last?.takeIf { it.manual && nowMs - it.ts < MANUAL_MERGE_MS }
            ?.copy(fuelPctBefore = before, fuelPctAfter = after, manual = false)

    /** Prix au litre du ticket, null si la saisie n'a pas de sens. */
    fun pricePerLiter(liters: Double?, amount: Double?): Double? {
        if (liters == null || amount == null) return null
        if (liters !in 1.0..100.0 || amount !in 1.0..500.0) return null
        return (amount / liters).takeIf { it in 0.5..5.0 }
    }

    /** Remplace les estimations du plein par les valeurs du ticket. */
    fun applyReceipt(event: RefuelEvent, liters: Double, amount: Double) = event.copy(
        litersAdded = liters.toFloat(),
        totalCost = amount,
        pricePerLiter = amount / liters,
        awaitingInput = false,
        userEntered = true,
    )
}

/** Saisie d'un nombre au pavé : virgule française, 3 chiffres + 2 décimales. */
object ReceiptEntry {
    fun append(current: String, key: Char): String {
        val comma = current.indexOf(',')
        return when {
            key == ',' -> when {
                comma >= 0 -> current
                current.isEmpty() -> "0,"
                else -> "$current,"
            }
            !key.isDigit() -> current
            comma >= 0 -> if (current.length - comma > 2) current else current + key
            current == "0" -> key.toString()
            current.length >= 3 -> current
            else -> current + key
        }
    }

    fun parse(text: String): Double? = text.replace(',', '.').toDoubleOrNull()
}
