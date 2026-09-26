package com.geoffrey.cancitroen.history

import com.geoffrey.cancitroen.history.dao.HistoryDao
import com.geoffrey.cancitroen.history.entities.AggregatedSample
import com.geoffrey.cancitroen.history.entities.VehicleSample
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

/** Regroupement des couches de rétention (raw + agrégats) pour les vues Stats. */
class RebucketTest {

    // rebucket() n'appelle pas le DAO : un proxy vide suffit.
    private val repo = HistoryRepository(
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(HistoryDao::class.java)) { _, _, _ ->
            error("DAO non utilisé par rebucket")
        } as HistoryDao
    )

    private val min = RollupAggregator.ONE_MIN
    private val t0 = 1_790_000_000_000L - Math.floorMod(1_790_000_000_000L, min)

    @Test
    fun agregatsAnciensEtRawRecentsSontTousDansLaVue() {
        // Minute t0 : déjà agrégée (12 samples) ; minute t0+1 : encore en raw.
        val agg = listOf(AggregatedSample(ts = t0, bucketMs = min, count = 12, speedAvg = 50f, fuelPct = 60))
        val raw = listOf(
            VehicleSample(ts = t0 + min + 5_000, speedKmh = 80f, fuelPct = 59),
            VehicleSample(ts = t0 + min + 10_000, speedKmh = 90f, fuelPct = 58),
        )
        val pts = repo.rebucket(raw, agg, min)
        assertEquals(listOf(t0, t0 + min), pts.map { it.ts })
        assertEquals(50f, pts[0].speedKmh!!, 1e-4f)
        assertEquals(85f, pts[1].speedKmh!!, 1e-4f)
        assertEquals(58, pts[1].fuelPct)   // dernière valeur du bucket
    }

    @Test
    fun moyennePondereeParNombreDEchantillons() {
        // Vue 30 min : un agrégat 1 min de 12 samples à 100 km/h + 1 raw à 40 km/h.
        val thirty = RollupAggregator.THIRTY_MIN
        val base = t0 - Math.floorMod(t0, thirty)
        val agg = listOf(AggregatedSample(ts = base, bucketMs = min, count = 12, speedAvg = 100f, speedMax = 110f))
        val raw = listOf(VehicleSample(ts = base + 5 * min, speedKmh = 40f))
        val p = repo.rebucket(raw, agg, thirty).single()
        assertEquals((12 * 100f + 40f) / 13f, p.speedKmh!!, 1e-3f)
        assertEquals(base, p.ts)
    }

    @Test
    fun ordreChronologiqueQuelQueSoitLOrdreDEntree() {
        val raw = listOf(
            VehicleSample(ts = t0 + 3 * min, speedKmh = 3f),
            VehicleSample(ts = t0 + min, speedKmh = 1f),
        )
        val agg = listOf(AggregatedSample(ts = t0 + 2 * min, bucketMs = min, count = 1, speedAvg = 2f))
        assertEquals(listOf(1f, 2f, 3f), repo.rebucket(raw, agg, min).map { it.speedKmh })
    }
}
