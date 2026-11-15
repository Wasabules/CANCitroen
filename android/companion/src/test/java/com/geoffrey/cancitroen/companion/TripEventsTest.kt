package com.geoffrey.cancitroen.companion

import com.geoffrey.cancitroen.shared.SummaryFields as F
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripEventsTest {
    private val minute = 60_000L

    private fun summary(vararg pairs: Pair<String, Any>) = JSONObject().apply { pairs.forEach { put(it.first, it.second) } }

    @Test fun premierResumeNAnnonceRien() {
        val result = TripEvents.fromSummary(
            summary(F.TRIP_START to 1000L, F.REFUEL_TS to 500L), TripEvents.State(), FuelType.PETROL,
        )
        assertTrue(result.events.isEmpty())
        assertEquals(TripEvents.State(1000, 500, true), result.state)
    }

    @Test fun trajetClosEtPleinAnnoncesUneFois() {
        val state = TripEvents.State(1000, 500, true)
        val later = summary(
            F.TRIP_START to 10 * minute, F.TRIP_END to 40 * minute, F.TRIP_KM to 20.0, F.TRIP_CONS to 6.0,
            F.REFUEL_TS to 9 * minute, F.REFUEL_LITERS to 30.5, F.REFUEL_PRICE to 1.8,
        )
        val first = TripEvents.fromSummary(later, state, FuelType.PETROL)
        assertEquals(listOf(TripEvents.TRIP_ENDED, TripEvents.REFUEL), first.events.map { it.type })
        val trip = first.events.first().data
        assertEquals(30L, trip.getLong("duree_min"))
        assertEquals(1.2, trip.getDouble("litres"), 1e-9)
        // Prix du dernier ticket : 1,2 L × 1,8 €.
        assertEquals(2.16, trip.getDouble("cout_eur"), 1e-9)
        assertEquals(2.8, trip.getDouble("co2_kg"), 1e-9)
        assertFalse(trip.getBoolean("provisoire"))
        assertTrue(TripEvents.fromSummary(later, first.state, FuelType.PETROL).events.isEmpty())
    }

    @Test fun finDeSuiviAnnonceLeTrajetEnCoursPuisSaClotureNonRepetee() {
        val state = TripEvents.State(1000, 0, true)
        val driving = summary(
            F.TRIP_START to 1000L, F.TRIP_ACTIVE to true, F.CURRENT_START to 50 * minute,
            F.CURRENT_KM to 12.0, F.CURRENT_CONS to 5.0, F.PRICE_PER_LITER to 2.0,
        )
        val end = TripEvents.fromSessionEnd(driving, state, 80 * minute, FuelType.DIESEL)
        val event = end.events.single().data
        assertTrue(event.getBoolean("provisoire"))
        assertEquals(30L, event.getLong("duree_min"))
        // Sans ticket : prix réglé sur l'Atoto. 0,6 L × 2 € ; gazole 2,64 kg/L.
        assertEquals(1.2, event.getDouble("cout_eur"), 1e-9)
        assertEquals(1.6, event.getDouble("co2_kg"), 1e-9)
        // L'Atoto clôt ce même trajet au démarrage suivant : déjà annoncé.
        val closed = summary(F.TRIP_START to 50 * minute, F.TRIP_END to 81 * minute, F.TRIP_KM to 12.3)
        assertTrue(TripEvents.fromSummary(closed, end.state, FuelType.DIESEL).events.isEmpty())
    }

    @Test fun finDeSuiviSansTrajetOuvert() {
        val state = TripEvents.State(1000, 0, true)
        assertTrue(TripEvents.fromSessionEnd(summary(F.TRIP_ACTIVE to false), state, 0, FuelType.PETROL).events.isEmpty())
    }

    @Test fun litres() {
        assertEquals(1.24, TripEvents.liters(20.0, 6.2)!!, 1e-9)
        assertNull(TripEvents.liters(null, 6.2))
        assertNull(TripEvents.liters(0.0, 6.2))
    }
}
