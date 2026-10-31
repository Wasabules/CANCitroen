package com.geoffrey.cancitroen.companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecoveryTest {
    private val header = "id;date;ts_ms;x"
    private fun line(id: Int) = "$id;2026-10-31 10:00:00;$id;a"

    @Test fun premierLotAvecEnTete() = assertEquals(
        "$header\n${line(1)}\n",
        TransferProtocol.textToAppend(null, header, listOf(line(1))),
    )

    @Test fun lotRecuDeuxFoisSansDoublon() {
        val existing = "$header\n${line(1)}\n${line(2)}\n"
        assertEquals("${line(3)}\n", TransferProtocol.textToAppend(existing, header, listOf(line(1), line(2), line(3))))
    }

    @Test fun jamaisColleAUneEcritureCoupee() {
        val cut = "$header\n${line(1)}\n2;2026-10"
        assertEquals("\n${line(2)}\n", TransferProtocol.textToAppend(cut, header, listOf(line(2))))
    }

    @Test fun derniereLigneSansFinDeLigneNeCompteJamais() {
        // Toutes les colonnes y sont, mais la dernière valeur peut être tronquée.
        val cut = "$header\n${line(1)}\n${line(2)}"
        assertEquals(listOf(line(1)), TransferProtocol.validLines(cut))
        assertEquals("\n${line(2)}\n", TransferProtocol.textToAppend(cut, header, listOf(line(2))))
    }

    @Test fun reparationTrieDedoublonneEtRetireLesLignesAbimees() {
        val damaged = "$header\n${line(3)}\n2;2026-10\n${line(1)}\n${line(3)}\n${line(2)}\n"
        assertEquals("$header\n${line(1)}\n${line(2)}\n${line(3)}\n", TransferProtocol.repairedDay(damaged))
    }

    @Test fun fichierSainNonReecrit() = assertNull(TransferProtocol.repairedDay("$header\n${line(1)}\n${line(2)}\n"))

    @Test fun plages() {
        assertEquals(listOf(12L..500L, 520L..520L), TransferProtocol.parseRanges("12-500,520-520"))
        assertEquals(emptyList<LongRange>(), TransferProtocol.parseRanges(""))
    }

    private fun missing(held: String, present: LongArray, upTo: Long) =
        TransferProtocol.missingRanges(TransferProtocol.parseRanges(held), present, upTo)

    @Test fun rienNeManque() = assertEquals(emptyList<LongRange>(), missing("1-3", longArrayOf(1, 2, 3), 3))

    @Test fun seulsLesManquantsSontDemandes() =
        assertEquals(listOf(2L..3L, 6L..6L), missing("1-6", longArrayOf(1, 4, 5), 6))

    @Test fun plusieursPlagesEtTrouAuDebut() =
        assertEquals(listOf(10L..11L, 20L..21L), missing("10-12,20-22", longArrayOf(12, 22), 100))

    @Test fun neDemandePasAuDelaDuDernierRecu() =
        assertEquals(listOf(1L..5L), missing("1-100", longArrayOf(), 5))

    @Test fun ignoreCeQueLAtotoNAPlus() =
        assertEquals(listOf(5L..5L), missing("5-6", longArrayOf(1, 2, 6), 10))

    @Test fun lectureDesPeriodes() {
        val buckets = TransferProtocol.parseBuckets("debut;ts_ms;duree_s;echantillons;v\n2026-10-31 10:00:00;60000;60;12;;\n")
        assertEquals(60000L, buckets[0].startTs)
        assertEquals(60000L, buckets[0].durationMs)
        assertEquals(12, buckets[0].count)
    }

    private fun bucket(minute: Int, count: Int) = TransferProtocol.Bucket(minute * 60_000L, 60_000L, count)

    @Test fun periodeCompleteNonSignalee() {
        val times = LongArray(12) { 60_000L + it * 5_000 }
        assertEquals(0, TransferProtocol.detailGaps(listOf(bucket(1, 12)), times, 0).size)
    }

    @Test fun periodesManquantesFusionnees() {
        val times = LongArray(12) { it * 5_000L }
        val gaps = TransferProtocol.detailGaps(listOf(bucket(0, 12), bucket(1, 12), bucket(2, 12), bucket(5, 3)), times, 0)
        assertEquals(2, gaps.size)
        assertEquals(60_000L, gaps[0].startTs)
        assertEquals(180_000L, gaps[0].endTs)
        assertEquals(24, gaps[0].expected)
        assertEquals(0, gaps[0].present)
        assertEquals(300_000L, gaps[1].startTs)
    }

    @Test fun avantLePremierEchantillonRienNeManque() {
        assertEquals(0, TransferProtocol.detailGaps(listOf(bucket(0, 12)), longArrayOf(), 60_000).size)
    }

    @Test fun lotPartielDetecte() {
        val gaps = TransferProtocol.detailGaps(listOf(bucket(0, 12)), longArrayOf(0, 5_000), 0)
        assertEquals(2, gaps[0].present)
        assertArrayEquals(intArrayOf(12), intArrayOf(gaps[0].expected))
    }
}
