package com.geoffrey.cancitroen.ui.screens.stats

import com.geoffrey.cancitroen.history.UnifiedDataPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsChartsTest {

    private val hour = 3_600_000L
    private val t0 = 1_790_000_000_000L

    private fun points(n: Int, speed: Float = 60f) = (0 until n).map { i ->
        UnifiedDataPoint(
            ts = t0 + i * 60_000L,
            speedKmh = speed,
            rpm = 2000f,
            fuelPct = 70,
            fuelLiters = 29f,
            tCoolant = 90,
            tCoolantMax = 95,
            tOil = 95,
            tOilMax = 100,
            tExt = 18,
            tExtMax = 20,
            oilLevelPct = 80,
            fuelInstL100 = 6f,
            odoKm = 100_000.0,
        )
    }

    @Test
    fun memesPointsDonnentDesGraphiquesEgaux() {
        // Fenêtre recalée sans donnée nouvelle (voiture à l'arrêt) : pas de recomposition.
        assertEquals(computeStatsCharts(points(500), 24 * hour), computeStatsCharts(points(500), 24 * hour))
    }

    @Test
    fun nouvellesDonneesChangentLesGraphiques() {
        assertNotEquals(computeStatsCharts(points(500), 24 * hour), computeStatsCharts(points(500, 90f), 24 * hour))
    }

    @Test
    fun courbesLimiteesEnNombreDePoints() {
        val charts = computeStatsCharts(points(5_000), 24 * hour)
        assertTrue(charts.fuel.size <= 200)
        assertTrue(charts.temps.coolant.size <= 200)
    }

    @Test
    fun presentationSelonLaDureeDeLaFenetre() {
        val day = computeStatsCharts(points(100), 24 * hour)
        assertNull(day.daily)
        assertFalse(day.temps.dailyMax)

        val week = computeStatsCharts(points(100), 7 * 24 * hour)
        assertNotNull(week.daily)
        assertFalse(week.temps.dailyMax)

        val month = computeStatsCharts(points(100), 30 * 24 * hour)
        assertTrue(month.temps.dailyMax)
    }

    @Test
    fun aucunPoint() {
        val charts = computeStatsCharts(emptyList(), 24 * hour)
        assertFalse(charts.hasPoints)
        assertFalse(charts.temps.hasEnough)
        assertNull(charts.rpmBars)
        assertNull(charts.speedGrid)
    }
}
