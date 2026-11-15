package com.geoffrey.cancitroen.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingAnalysisTest {
    /** Une ligne toutes les 5 s : vitesse, régime, conso. */
    private fun detail(vararg rows: Triple<Double, Double, Double?>, startTs: Long = 0, stepMs: Long = 5_000): Detail {
        val builder = DetailBuilder()
        rows.forEachIndexed { i, (speed, rpm, cons) -> builder.add(startTs + i * stepMs, speed, rpm, cons) }
        return builder.build()
    }

    private fun repeat(n: Int, row: Triple<Double, Double, Double?>) = Array(n) { row }

    @Test fun tranchesDeVitesseRalentiEtDistance() {
        val rows = repeat(12, Triple(36.0, 2000.0, 7.0)) + repeat(12, Triple(108.0, 3200.0, 5.0)) + repeat(6, Triple(0.0, 800.0, null))
        val report = DrivingAnalysis.analyze(detail(*rows), emptyList(), null, FuelType.PETROL)!!
        // 60 s à 36 km/h = 0,6 km ; 60 s à 108 km/h = 1,8 km.
        assertEquals(2.4, report.km, 1e-9)
        assertEquals(120_000L, report.drivingMs)
        assertEquals(30_000L, report.idleMs)
        val city = report.bands.first { it.label == "Ville" }
        assertEquals(60_000L, city.drivingMs)
        assertEquals(7.0, city.avgCons!!, 1e-9)
        assertEquals(1.8, report.bands.first { it.label == "Voie rapide" }.km, 1e-9)
        assertEquals(2600.0, report.avgRpm!!, 1e-9)
        assertEquals(0.5, report.highRpmShare!!, 1e-9)
    }

    @Test fun unTrouNeCompteNiCommeRoulageNiCommeDistance() {
        // Deux lignes séparées d'une heure : l'écart est plafonné à 10 s.
        val report = DrivingAnalysis.analyze(
            detail(Triple(50.0, 2000.0, null), Triple(50.0, 2000.0, null), stepMs = 3_600_000),
            emptyList(), null, FuelType.PETROL,
        )!!
        assertEquals(15_000L, report.drivingMs)
    }

    @Test fun rapportsReconnusALaDemultiplication() {
        // 3e (21 km/h à 1 000 tr/min) et 5e (35), avec quelques lignes d'embrayage : un patinage
        // donne des démultiplications dispersées, pas un rapport de plus.
        val clutch = arrayOf(
            Triple(30.0, 3000.0, null), Triple(25.0, 3100.0, null), Triple(33.0, 2900.0, null),
            Triple(20.0, 3300.0, null), Triple(38.0, 3050.0, null),
        )
        val rows = repeat(40, Triple(42.0, 2000.0, null)) + repeat(20, Triple(42.0, 2100.0, null)) +
            repeat(30, Triple(105.0, 3000.0, null)) + clutch
        val gears = DrivingAnalysis.analyze(detail(*rows), emptyList(), null, FuelType.PETROL)!!.gears
        assertEquals(2, gears.size)
        assertEquals(21.0, gears[0].kmhPer1000, 1.0)
        assertEquals(35.0, gears[1].kmhPer1000, 1.0)
        assertEquals(2.0 / 3, gears[0].share, 0.01)
        assertEquals(3000.0, gears[1].avgRpm, 1e-9)
    }

    @Test fun unSeulRapportVuNeFaitPasDeListe() {
        val gears = DrivingAnalysis.analyze(detail(*repeat(30, Triple(42.0, 2000.0, null))), emptyList(), null, FuelType.PETROL)!!.gears
        assertTrue(gears.isEmpty())
    }

    @Test fun carburantCoutEtCo2DesTrajetsTermines() {
        val trips = listOf(
            TripRow(0, 1000, 20.0, 40.0, 90.0, 6.0, 50, 48),
            TripRow(2000, null, 5.0, 30.0, 50.0, 8.0, 48, null),
        )
        val report = DrivingAnalysis.analyze(detail(), trips, 1.9, FuelType.PETROL)!!
        assertEquals(1.2, report.liters!!, 1e-9)
        assertEquals(2.28, report.cost!!, 1e-9)
        assertEquals(2.772, report.co2Kg!!, 1e-9)
    }

    @Test fun rienAAnalyser() {
        assertNull(DrivingAnalysis.analyze(detail(), emptyList(), null, FuelType.PETROL))
    }
}
