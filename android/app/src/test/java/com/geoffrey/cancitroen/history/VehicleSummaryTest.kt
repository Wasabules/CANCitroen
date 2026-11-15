package com.geoffrey.cancitroen.history

import com.geoffrey.cancitroen.history.dao.TripResumeCounters
import com.geoffrey.cancitroen.history.entities.AggregatedSample
import com.geoffrey.cancitroen.history.entities.RefuelEvent
import com.geoffrey.cancitroen.history.entities.Trip
import com.geoffrey.cancitroen.history.entities.VehicleSample
import com.geoffrey.cancitroen.shared.SummaryFields
import com.geoffrey.cancitroen.system.GpsFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class VehicleSummaryTest {
    private val minute = 60_000L
    private val day = 24 * 60 * minute
    private val now = Calendar.getInstance().run {
        set(2026, Calendar.OCTOBER, 20, 12, 0, 0)
        timeInMillis
    }

    private fun compute(
        recent: List<VehicleSample> = emptyList(),
        aggregate: AggregatedSample? = null,
        trips: List<Trip> = emptyList(),
        refuels: List<RefuelEvent> = emptyList(),
        factor: Double = 1.0,
    ) = VehicleSummary.compute(now, recent, aggregate, trips, refuels, factor)

    private fun trip(start: Long, km: Double, cons: Float?, ended: Boolean = true) = Trip(
        startTs = start, endTs = if (ended) start + 30 * minute else null,
        startOdoKm = 1000.0, endOdoKm = 1000.0 + km, avgConsL100 = cons,
    )

    @Test fun chaqueValeurVientDuDernierEchantillonQuiLaConnait() {
        val summary = compute(listOf(
            VehicleSample(ts = now - 2 * minute, tOil = 90, fuelPct = 50),
            VehicleSample(ts = now - minute, fuelPct = 0),
        ))
        assertEquals(90, summary.tOil)
        assertEquals("zéro n'est pas une jauge valide", 50, summary.fuelPct)
        assertEquals(now - minute, summary.lastSeenTs)
    }

    @Test fun lesAgregatsPrennentLeRelaisQuandLeBrutEstPurge() {
        val summary = compute(aggregate = AggregatedSample(
            ts = now - day, bucketMs = 60_000, count = 12, fuelPct = 42, odoKm = 1500.0, tCoolantAvg = 88,
        ))
        assertEquals(42, summary.fuelPct)
        assertEquals(1500.0, summary.odoKm!!, 0.0)
        assertEquals(88, summary.tCoolant)
        assertEquals(now - day, summary.lastSeenTs)
    }

    @Test fun moisEnCoursSeulementEtConsoPondereeParLaDistance() {
        val summary = compute(
            trips = listOf(
                trip(now - 40 * day, 500.0, 9f),
                trip(now - 2 * day, 100.0, 6f),
                trip(now - day, 300.0, 8f),
            ),
            refuels = listOf(
                RefuelEvent(ts = now - 40 * day, fuelPctBefore = 10, fuelPctAfter = 90, litersAdded = 30f, totalCost = 55.0),
                RefuelEvent(ts = now - day, fuelPctBefore = 20, fuelPctAfter = 80, litersAdded = 25f, totalCost = 45.0),
            ),
        )
        assertEquals(400.0, summary.monthKm, 0.001)
        assertEquals(2, summary.monthTrips)
        assertEquals(7.5, summary.monthCons!!, 0.001)
        assertEquals(25.0, summary.monthLiters, 0.001)
        assertEquals(45.0, summary.monthCost, 0.001)
    }

    @Test fun correctionDesTicketsAppliqueeALaConso() {
        val summary = compute(trips = listOf(trip(now - day, 100.0, 8f)), factor = 1.1)
        assertEquals(8.8, summary.lastTripCons!!, 0.001)
        assertEquals(8.8, summary.monthCons!!, 0.001)
    }

    @Test fun trajetOuvertEnCoursSeulementSiLaVoitureParleEncore() {
        val open = listOf(trip(now - 5 * minute, 3.0, null, ended = false))
        assertTrue(compute(listOf(VehicleSample(ts = now - minute)), trips = open).tripActive)
        assertFalse(compute(listOf(VehicleSample(ts = now - 30 * minute)), trips = open).tripActive)
        assertFalse(compute(listOf(VehicleSample(ts = now - minute))).tripActive)
    }

    @Test fun dernierTrajetTermineMemeSiUnAutreEstOuvert() {
        val summary = compute(trips = listOf(trip(now - day, 50.0, 7f), trip(now - minute, 1.0, null, ended = false)))
        assertEquals(50.0, summary.lastTrip!!.distanceKm!!, 0.001)
    }

    @Test fun historiqueVide() {
        val summary = compute()
        assertNull(summary.lastSeenTs)
        assertNull(summary.monthCons)
        assertEquals(0, summary.monthTrips)
    }

    @Test fun trajetEnCoursAvecSesSommesEtPositionSurDemande() {
        val open = Trip(id = 7, startTs = now - 20 * minute, startOdoKm = 1000.0)
        val summary = VehicleSummary.compute(
            now = now,
            recent = listOf(VehicleSample(ts = now - minute, odoKm = 1012.5)),
            lastAggregate = null,
            trips = listOf(trip(now - day, 10.0, 6f), open),
            refuels = emptyList(),
            consumptionFactor = 1.1,
            currentCounters = TripResumeCounters(speedSum = 900.0, speedCount = 20, speedMax = 92.0, consSum = 120.0, consCount = 20),
            pricePerLiter = 1.85,
            position = GpsFix(48.85, 2.35, 8f, 90f, now - minute),
        )
        assertTrue(summary.tripActive)
        assertEquals(now - 20 * minute, summary.currentTripStartTs)
        assertEquals(12.5, summary.currentTripKm!!, 1e-9)
        assertEquals(45.0, summary.currentTripAvgSpeed!!, 1e-9)
        assertEquals(6.6, summary.currentTripCons!!, 1e-9)
        val json = org.json.JSONObject(summary.toJson())
        assertEquals(48.85, json.getDouble(SummaryFields.LAT), 0.0)
        assertEquals(1.85, json.getDouble(SummaryFields.PRICE_PER_LITER), 0.0)
        // Le dernier trajet terminé reste celui d'hier.
        assertEquals(now - day, json.getLong(SummaryFields.TRIP_START))
    }

    @Test fun sansTrajetOuvertNiPositionDemandee() {
        val json = org.json.JSONObject(compute(trips = listOf(trip(now - day, 10.0, 6f))).toJson())
        assertFalse(json.has(SummaryFields.CURRENT_START))
        assertFalse(json.has(SummaryFields.LAT))
    }
}
