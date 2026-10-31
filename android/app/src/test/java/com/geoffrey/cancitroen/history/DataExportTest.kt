package com.geoffrey.cancitroen.history

import com.geoffrey.cancitroen.history.entities.RefuelEvent
import com.geoffrey.cancitroen.history.entities.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DataExportTest {

    @Test
    fun pleinsAuFormatTableurFrancais() {
        val lines = DataExport.refuelsCsv(listOf(
            RefuelEvent(ts = 0L, fuelPctBefore = 30, fuelPctAfter = 90, litersAdded = 24.5f, odoKm = 1000.5,
                pricePerLiter = 1.872, totalCost = 45.86, userEntered = true),
            RefuelEvent(ts = 0L, fuelPctBefore = 0, fuelPctAfter = 0, litersAdded = 5f, manual = true, userEntered = true),
            RefuelEvent(ts = 0L, fuelPctBefore = 30, fuelPctAfter = 50, litersAdded = 8f),
        )).trim().lines()
        assertEquals(4, lines.size)
        assertEquals(8, lines[0].split(";").size)
        assertTrue(lines[1], lines[1].endsWith(";30;90;24,50;45,86;1,872;1000,5;ticket"))
        assertTrue(lines[2], lines[2].endsWith(";manuel"))
        assertTrue(lines[3], lines[3].endsWith(";estimation"))
    }

    @Test
    fun trajetEnCoursSansFinNiDistance() {
        val lines = DataExport.tripsCsv(listOf(Trip(startTs = 0L, startFuelPct = 70))).trim().lines()
        assertEquals(8, lines[1].split(";").size)
        assertTrue(lines[1], lines[1].endsWith(";;;;;;70;"))
    }
}
