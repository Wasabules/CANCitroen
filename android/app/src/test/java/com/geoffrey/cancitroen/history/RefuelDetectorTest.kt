package com.geoffrey.cancitroen.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RefuelDetectorTest {

    private val tick = 5_000L

    /** Rejoue des niveaux à une mesure toutes les 5 s ; renvoie les pleins vus. */
    private fun run(d: RefuelDetector, vararg levels: Int?): List<RefuelDetector.Refuel> =
        levels.mapIndexedNotNull { i, pct -> d.onLevel(pct, i * tick) }

    private fun hold(level: Int, durationMs: Long) =
        Array<Int?>((durationMs / tick).toInt() + 1) { level }

    @Test
    fun pleinFranc() {
        val pleins = run(RefuelDetector(30), *hold(85, RefuelDetector.STABLE_MS))
        assertEquals(listOf(RefuelDetector.Refuel(30, 85)), pleins)
    }

    @Test
    fun pleinFaitPendantQueLAppNeTournaitPas() {
        // Niveau d'avant lu en base, rien d'autre à l'écran que la nouvelle valeur.
        val d = RefuelDetector(40)
        assertNull(d.onLevel(90, 0))
        assertEquals(RefuelDetector.Refuel(40, 90), d.onLevel(90, 3 * 3600_000L))
    }

    @Test
    fun jaugeQuiMonteParPaliersNeDonneQuUnSeulPlein() {
        val pleins = run(RefuelDetector(30), 45, 60, 75, 90, *hold(90, RefuelDetector.STABLE_MS))
        assertEquals(listOf(RefuelDetector.Refuel(30, 90)), pleins)
    }

    @Test
    fun hausseQuiRetombeNEstPasUnPlein() {
        assertEquals(emptyList<RefuelDetector.Refuel>(), run(RefuelDetector(50), 65, 65, 50, 50))
    }

    @Test
    fun petiteHausseIgnoree() {
        val pleins = run(RefuelDetector(50), *hold(50 + RefuelDetector.MIN_RISE_PCT - 1, 300_000L))
        assertEquals(emptyList<RefuelDetector.Refuel>(), pleins)
    }

    @Test
    fun valeursDeReveilIgnorees() {
        // 0 puis 0xFF au réveil de la BSI, puis le vrai niveau : pas de plein.
        val pleins = run(RefuelDetector(), null, 0, 255, *hold(60, 300_000L))
        assertEquals(emptyList<RefuelDetector.Refuel>(), pleins)
    }

    @Test
    fun valeurBasseIsoleeNeFaitPasDeFauxPlein() {
        val pleins = run(RefuelDetector(60), 5, *hold(60, 300_000L))
        assertEquals(emptyList<RefuelDetector.Refuel>(), pleins)
    }

    @Test
    fun laConsommationFaitDescendreLaReference() {
        val d = RefuelDetector(60)
        run(d, 50, 50, 40, 40)
        val pleins = (0..20).mapNotNull { d.onLevel(70, 100_000L + it * tick) }
        assertEquals(listOf(RefuelDetector.Refuel(40, 70)), pleins)
    }
}
