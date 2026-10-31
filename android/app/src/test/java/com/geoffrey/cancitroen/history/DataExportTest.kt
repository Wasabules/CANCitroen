package com.geoffrey.cancitroen.history

import com.geoffrey.cancitroen.history.entities.AggregatedSample
import com.geoffrey.cancitroen.history.entities.RefuelEvent
import com.geoffrey.cancitroen.history.entities.Trip
import com.geoffrey.cancitroen.history.entities.VehicleSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DataExportTest {

    @Test
    fun echantillonsAvecNumeroEtValeursAbsentes() {
        val lines = DataExport.samplesCsv(listOf(
            VehicleSample(id = 7, ts = 0L, speedKmh = 52.5f, rpm = 2100f, fuelPct = 40, odoKm = 1000.5),
            VehicleSample(id = 8, ts = 5000L),
        )).trim().lines()
        val columns = lines[0].split(";").size
        assertEquals(3, lines.size)
        assertTrue(lines.all { it.split(";").size == columns })
        assertEquals("7", lines[1].split(";")[0])
        assertEquals("52,5", lines[1].split(";")[4])
        assertEquals("8", lines[2].split(";")[0])
        assertEquals("", lines[2].split(";")[4])
    }

    @Test
    fun agregatsAvecDureeDuBucket() {
        val lines = DataExport.aggregatesCsv(listOf(
            AggregatedSample(ts = 0L, bucketMs = 60_000L, count = 12, speedAvg = 30f),
        )).trim().lines()
        assertEquals(lines[0].split(";").size, lines[1].split(";").size)
        assertEquals("0", lines[1].split(";")[1])
        assertEquals("60", lines[1].split(";")[2])
        assertEquals("12", lines[1].split(";")[3])
    }

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
