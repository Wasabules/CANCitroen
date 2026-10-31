package com.geoffrey.cancitroen.history

import com.geoffrey.cancitroen.history.entities.RefuelEvent
import com.geoffrey.cancitroen.history.entities.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class FuelCalibrationTest {

    private fun fill(before: Int, after: Int, liters: Float, ts: Long = 0, entered: Boolean = true) =
        RefuelEvent(ts = ts, fuelPctBefore = before, fuelPctAfter = after, litersAdded = liters, userEntered = entered)

    @Test
    fun pasDEstimationAvantTroisPleinsExploitables() {
        val e = FuelCalibration.estimateCapacity(listOf(fill(20, 70, 21f), fill(30, 80, 21f)))
        assertEquals(2, e.fills)
        assertNull(e.liters)
    }

    @Test
    fun medianeDesPleinsExploitables() {
        val e = FuelCalibration.estimateCapacity(
            listOf(fill(20, 70, 21f), fill(30, 80, 21.5f), fill(10, 60, 20.5f))
        )
        assertEquals(42.0, e.liters!!, 0.001)
    }

    @Test
    fun uneFauteDeFrappeNeFausseRien() {
        val e = FuelCalibration.estimateCapacity(
            listOf(fill(20, 70, 21f), fill(30, 80, 21f), fill(10, 60, 210f))
        )
        assertEquals(42.0, e.liters!!, 0.001)
    }

    @Test
    fun ignoreEstimationsPetitsPleinsEtJaugeSaturee() {
        val e = FuelCalibration.estimateCapacity(
            listOf(
                fill(20, 70, 21f, entered = false),   // pas un ticket
                fill(50, 60, 4f),                     // hausse trop faible
                fill(40, 100, 30f),                   // jauge plafonnée
            )
        )
        assertEquals(0, e.fills)
    }

    @Test
    fun prixAuLitre() {
        assertEquals(1.85, FuelCalibration.pricePerLiter(20.0, 37.0)!!, 0.001)
        assertNull(FuelCalibration.pricePerLiter(null, 37.0))
        assertNull(FuelCalibration.pricePerLiter(37.0, 20.0 * 100))   // montant hors limites
        assertNull(FuelCalibration.pricePerLiter(40.0, 10.0))         // 0,25 €/L : inversé ?
    }

    @Test
    fun leTicketRemplaceLesEstimations() {
        val r = FuelCalibration.applyReceipt(fill(20, 70, 20.5f, entered = false).copy(awaitingInput = true), 25.0, 50.0)
        assertEquals(25f, r.litersAdded)
        assertEquals(50.0, r.totalCost!!, 0.001)
        assertEquals(2.0, r.pricePerLiter!!, 0.001)
        assertEquals(false, r.awaitingInput)
        assertEquals(true, r.userEntered)
    }

    @Test
    fun saisieAuPave() {
        fun type(s: String) = s.fold("") { acc, c -> ReceiptEntry.append(acc, c) }
        assertEquals("32,47", type("32,47"))
        assertEquals("0,5", type(",5"))
        assertEquals("32,47", type("32,479"))   // 2 décimales max
        assertEquals("123", type("1234"))       // 3 chiffres max
        assertEquals("5", type("05"))           // pas de zéro en tête
        assertEquals("3,2", type("3,,2"))       // une seule virgule
        assertEquals(32.47, ReceiptEntry.parse("32,47")!!, 0.001)
        assertNull(ReceiptEntry.parse(""))
    }

    // ── Conso entre deux pleins ──

    private fun fill(
        day: Int,
        before: Int,
        after: Int,
        liters: Float,
        odo: Double?,
        ticket: Boolean = true,
    ) = RefuelEvent(
        ts = day * 86_400_000L,
        fuelPctBefore = before,
        fuelPctAfter = after,
        litersAdded = liters,
        odoKm = odo,
        userEntered = ticket,
        pricePerLiter = if (ticket) 2.0 else null,
    )

    private fun trip(day: Int, km: Double, cons: Float) = Trip(
        startTs = day * 86_400_000L + 3_600_000L,
        endTs = day * 86_400_000L + 7_200_000L,
        startOdoKm = 1000.0,
        endOdoKm = 1000.0 + km,
        avgConsL100 = cons,
    )

    @Test
    fun pleinsAuMemeNiveauMesuresAuTicket() {
        val tanks = FuelCalibration.tankConsumptions(
            listOf(fill(0, 20, 100, 33f, 1000.0), fill(10, 30, 100, 30f, 1500.0)),
            capacityLiters = 41f,
        )
        assertEquals(1, tanks.size)
        assertTrue(tanks[0].exact)
        assertEquals(6.0, tanks[0].l100, 0.001)
    }

    @Test
    fun appointMesureALaJaugePasAuTicket() {
        // 10 L d'appoint après 400 km : l'ancien calcul affichait 2,5 l/100.
        val tanks = FuelCalibration.tankConsumptions(
            listOf(fill(0, 20, 100, 33f, 1000.0), fill(10, 40, 65, 10f, 1400.0)),
            capacityLiters = 40f,
        )
        assertFalse(tanks[0].exact)
        assertEquals(6.0, tanks[0].l100, 0.001)
    }

    @Test
    fun mesuresInexploitablesEcartees() {
        val cap = 41f
        // odomètre inconnu
        assertTrue(FuelCalibration.tankConsumptions(listOf(fill(0, 20, 100, 33f, null), fill(1, 30, 100, 30f, 1500.0)), cap).isEmpty())
        // trop peu de km
        assertTrue(FuelCalibration.tankConsumptions(listOf(fill(0, 20, 100, 33f, 1000.0), fill(1, 90, 100, 4f, 1010.0)), cap).isEmpty())
        // jauge inconnue sur un plein manuel
        assertTrue(FuelCalibration.tankConsumptions(listOf(fill(0, 0, 0, 33f, 1000.0), fill(1, 0, 0, 30f, 1500.0)), cap).isEmpty())
        // valeur aberrante
        assertTrue(FuelCalibration.tankConsumptions(listOf(fill(0, 20, 100, 33f, 1000.0), fill(1, 30, 100, 40f, 1040.0)), cap).isEmpty())
    }

    @Test
    fun correctionDeConsoAPartirDeTroisMesures() {
        // Réel 6 l/100 (30 L sur 500 km), voiture 5 l/100 : correction 1,2.
        val fills = (0..3).map { fill(it * 10, 30, 100, 30f, 1000.0 + it * 500) }
        val trips = (0..2).map { trip(it * 10, 500.0, 5f) }
        val check = FuelCalibration.consumptionCheck(fills, trips, 41f)
        assertEquals(3, check.fills)
        assertEquals(1.2, check.factor!!, 0.001)
        assertEquals(6.0, check.realL100!!, 0.001)
        assertEquals(5.0, check.shownL100!!, 0.001)

        assertNull(FuelCalibration.consumptionCheck(fills.take(3), trips, 41f).factor)
    }

    @Test
    fun correctionIgnoreLesPeriodesSansTrajetEnregistre() {
        val fills = (0..3).map { fill(it * 10, 30, 100, 30f, 1000.0 + it * 500) }
        // 100 km enregistrés sur 500 : l'app ne tournait pas, comparaison impossible.
        val trips = (0..2).map { trip(it * 10, 100.0, 5f) }
        assertEquals(0, FuelCalibration.consumptionCheck(fills, trips, 41f).fills)
    }

    // ── Coût d'un trajet ──

    @Test
    fun coutDuTrajetAuPrixDuDernierTicketConnu() {
        val t = trip(5, 100.0, 6f)     // 6 L
        val old = fill(0, 20, 100, 33f, 1000.0).copy(pricePerLiter = 1.5)
        val after = fill(9, 20, 100, 33f, 2000.0).copy(pricePerLiter = 3.0)
        val estimate = fill(4, 20, 100, 33f, 1500.0, ticket = false).copy(pricePerLiter = 9.0)
        assertEquals(9.0, FuelCalibration.tripCost(t, listOf(old, after, estimate), 2.0, 1.0)!!, 0.001)
        // Aucun ticket antérieur : prix réglé. Correction de conso appliquée.
        assertEquals(14.4, FuelCalibration.tripCost(t, listOf(after), 2.0, 1.2)!!, 0.001)
    }

    @Test
    fun coutInconnuSansConsoOuDistance() {
        assertNull(FuelCalibration.tripCost(trip(5, 100.0, 6f).copy(avgConsL100 = null), emptyList(), 2.0, 1.0))
        assertNull(FuelCalibration.tripCost(trip(5, 0.0, 6f), emptyList(), 2.0, 1.0))
    }

    // ── Plein manuel ──

    @Test
    fun pleinManuelNeCompteNiPourLaCapaciteNiDeuxFois() {
        // Sa hausse de jauge est déduite des litres : calcul circulaire.
        val manual = (0..2).map { fill(it, 40, 80, 50f, null).copy(manual = true) }
        assertNull(FuelCalibration.estimateCapacity(manual).liters)
        assertEquals(0, FuelCalibration.estimateCapacity(manual).fills)

        assertEquals(60, FuelCalibration.manualLevelBefore(80, 8.0, 40f))
        assertEquals(0, FuelCalibration.manualLevelBefore(10, 8.0, 40f))
        assertEquals(0, FuelCalibration.manualLevelBefore(0, 8.0, 40f))

        val last = fill(0, 60, 80, 8f, 1000.0).copy(manual = true)
        val merged = FuelCalibration.mergeWithManual(last, 55, 75, last.ts + 3_600_000L)!!
        assertEquals(55, merged.fuelPctBefore)
        assertEquals(75, merged.fuelPctAfter)
        assertEquals(8f, merged.litersAdded)
        assertFalse(merged.manual)

        assertNull(FuelCalibration.mergeWithManual(last, 55, 75, last.ts + 3 * 3_600_000L))
        assertNull(FuelCalibration.mergeWithManual(last.copy(manual = false), 55, 75, last.ts))
        assertNull(FuelCalibration.mergeWithManual(null, 55, 75, 0L))
    }
}
