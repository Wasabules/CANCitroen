package com.geoffrey.cancitroen.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlcanParserTest {

    private fun bytes(s: String) = s.toByteArray(Charsets.US_ASCII)

    @Test
    fun trameStandard() {
        val frames = SlcanParser().feed(bytes("t0B681A4022C400000000\r"))
        assertEquals(1, frames.size)
        val f = frames[0]
        assertEquals(0x0B6, f.id)
        assertEquals(false, f.extended)
        assertArrayEquals(byteArrayOf(0x1A, 0x40, 0x22, 0xC4.toByte(), 0, 0, 0, 0), f.data)
    }

    @Test
    fun trameEtendueEtMinuscules() {
        val f = SlcanParser().feed(bytes("T18FEF1002aabb\r")).single()
        assertEquals(0x18FEF100, f.id)
        assertEquals(true, f.extended)
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), f.data)
    }

    @Test
    fun trameDecoupeeEntrePlusieursLectures() {
        val p = SlcanParser()
        assertTrue(p.feed(bytes("t21F3")).isEmpty())
        assertTrue(p.feed(bytes("0800")).isEmpty())
        val f = p.feed(bytes("00\rt2")).single()
        assertEquals(0x21F, f.id)
        assertArrayEquals(byteArrayOf(0x08, 0, 0), f.data)
    }

    @Test
    fun plusieursTramesDansUnChunk() {
        val frames = SlcanParser().feed(bytes("t1A01FF\rt22028800\r\r"))
        assertEquals(listOf(0x1A0, 0x220), frames.map { it.id })
    }

    @Test
    fun remoteEchosEtLignesInvalidesIgnorees() {
        val frames = SlcanParser().feed(bytes("r1A08\rz\rt1A0\rt1A09\rt1AG100\rt1A02AA\r"))
        // r = remote, z = écho TX, DLC absent, DLC 9 > 8, id non hex, données tronquées
        assertTrue(frames.isEmpty())
    }

    @Test
    fun ligneTropLongueNeCorrompPasLaSuivante() {
        val p = SlcanParser()
        val frames = p.feed(bytes("t" + "A".repeat(40) + "\rt1A0100\r"))
        assertEquals(1, frames.size)
        assertEquals(0x1A0, frames[0].id)
    }

    @Test
    fun resetOublieLaLigneEnCours() {
        val p = SlcanParser()
        p.feed(bytes("t1A0"))
        p.reset()
        assertEquals(0x221, p.feed(bytes("t2210\r")).single().id)
    }

    @Test
    fun encodeFrameRelisible() {
        val line = SlcanParser.encodeFrame(0x3E5, byteArrayOf(0x40, 0, 0, 0, 0, 0))
        assertEquals("t3E56400000000000\r", line)
        val f = SlcanParser().feed(bytes(line)).single()
        assertEquals(0x3E5, f.id)
        assertArrayEquals(byteArrayOf(0x40, 0, 0, 0, 0, 0), f.data)
    }
}
