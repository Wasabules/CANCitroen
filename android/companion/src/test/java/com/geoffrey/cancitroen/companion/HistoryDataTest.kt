package com.geoffrey.cancitroen.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class HistoryDataTest {
    private val samples = """
        id;date;ts_ms;trajet;vitesse_kmh;regime_tr_min;jauge_pct;litres;autonomie_km;t_eau;t_huile;t_ext;niveau_huile_pct;odometre_km;conso_inst_l100
        1;x;1000;1;50,5;2000;60;24,60;400;80;85;12;78;154320,0;6,1
        2;x;6000;1;;2100;60;24,60;400;81;;12;78;154320,1;
        3;x;99000;1;70,0;2500;59;24,19;395;90;95;13;78;154321,0;5,0
    """.trimIndent()

    private val aggregates = """
        debut;ts_ms;duree_s;echantillons;vitesse_moy_kmh;vitesse_max_kmh;regime_moy;regime_max;jauge_pct;litres;t_eau_moy;t_eau_max;t_huile_moy;t_huile_max;t_ext_moy;t_ext_max;niveau_huile_pct;conso_moy_l100;odometre_km
        x;0;60;12;40,0;60,0;1800;2500;61;25;70;80;75;85;11;12;78;6,5;154319,0
        x;200000;60;12;30,0;50,0;1500;2000;58;24;88;90;92;95;14;15;78;7,0;154322,0
    """.trimIndent()

    private fun builders() = Metric.entries.associateWith { SeriesBuilder() }

    @Test fun echantillonsLusAvecVirguleEtTrous() {
        val into = builders()
        HistoryCsv.collect(samples, 0, 100_000, into)
        val speed = into.getValue(Metric.SPEED).build()
        assertEquals(listOf(1000L, 99000L), speed.ts.toList())
        assertEquals(listOf(50.5, 70.0), speed.values.toList())
        assertEquals(3, into.getValue(Metric.COOLANT).build().size)
        // Valeur vide : pas de point, pas de zéro inventé.
        assertEquals(2, into.getValue(Metric.OIL).build().size)
    }

    @Test fun agregatsSeulementHorsDuDetail() {
        val into = builders()
        val times = SeriesBuilder()
        HistoryCsv.collect(samples, 0, 300_000, into, times = times)
        HistoryCsv.collect(aggregates, 0, 300_000, into, aggregates = true, detail = times.build().ts)
        val speed = into.getValue(Metric.SPEED).build()
        // L'agrégat de 0 à 60 s recouvre des échantillons : écarté. Celui de 200 s est gardé.
        assertEquals(listOf(1000L, 99000L, 200000L), speed.ts.toList())
        assertEquals(30.0, speed.values.last(), 0.0)
    }

    @Test fun periodeRespectee() {
        val into = builders()
        HistoryCsv.collect(samples, 5000, 50_000, into)
        assertEquals(listOf(6000L), into.getValue(Metric.RPM).build().ts.toList())
    }

    @Test fun couverture() {
        val sorted = longArrayOf(10, 20, 30)
        assertTrue(HistoryCsv.covers(sorted, 15, 21))
        assertFalse(HistoryCsv.covers(sorted, 21, 30))
        assertFalse(HistoryCsv.covers(sorted, 31, 100))
        assertFalse(HistoryCsv.covers(LongArray(0), 0, 100))
    }

    @Test fun reductionGardeLesTrous() {
        val series = Series(longArrayOf(0, 10, 90, 95), doubleArrayOf(1.0, 3.0, 5.0, 7.0))
        val reduced = series.downsampled(0, 100, 4)
        assertEquals(4, reduced.size)
        assertEquals(2.0, reduced.values[0], 0.0)
        assertTrue(reduced.values[1].isNaN())
        assertTrue(reduced.values[2].isNaN())
        assertEquals(6.0, reduced.values[3], 0.0)
        assertEquals(6.0, reduced.lastValue()!!, 0.0)
    }

    @Test fun valeurProcheDuCurseur() {
        val series = Series(longArrayOf(0, 100, 200), doubleArrayOf(1.0, Double.NaN, 3.0))
        assertEquals(3.0, series.valueNear(160, 50)!!, 0.0)
        assertNull(series.valueNear(100, 20))
    }

    @Test fun trajetsEtPleins() {
        val trips = HistoryCsv.trips(
            "debut;fin;distance_km;vitesse_moy_kmh;vitesse_max_kmh;conso_moy_l100;jauge_debut;jauge_fin\n" +
                "2026-11-15 08:40;;3,2;30;50;;55;\n" +
                "2026-11-15 08:12;2026-11-15 08:30;12,3;42;90;6,2;60;56\n",
        )
        assertEquals(2, trips.size)
        val first = trips.first()
        assertEquals(at("2026-11-15 08:12"), first.startTs)
        assertEquals(at("2026-11-15 08:30"), first.endTs)
        assertEquals(12.3, first.km!!, 1e-9)
        assertEquals(60, first.fuelStart)
        assertNull(trips.last().endTs)
        val refuels = HistoryCsv.refuels(
            "date;jauge_avant;jauge_apres;litres;montant_eur;prix_litre_eur;odometre_km;origine\n" +
                "2026-11-12 18:00;20;95;30,50;55,12;1,807;154000,0;ticket\n",
        )
        assertEquals(1.807, refuels.single().price!!, 1e-9)
        assertEquals("ticket", refuels.single().origin)
    }

    @Test fun conservation() {
        val now = at("2026-11-15 12:00")
        assertEquals("2026-10-17", Retention.DAYS_30.firstKeptDay(now))
        assertNull(Retention.ALL.firstKeptDay(now))
        assertEquals(0L, Retention.ALL.startMs(now))
        val names = listOf(
            "echantillons-2026-10-16.csv", "echantillons-2026-10-16 (1).csv",
            "echantillons-2026-10-17.csv", "echantillons-2026-11-15.csv",
        )
        assertEquals(names.take(2), Retention.expired(names, "2026-10-17"))
    }

    private fun at(text: String) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.FRANCE).parse(text)!!.time
}
