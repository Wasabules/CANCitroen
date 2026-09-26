package com.geoffrey.cancitroen.decode

import com.geoffrey.cancitroen.usb.CanFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Décode les trames de référence partagées avec bridge.py
 * (fixtures/can_decode_cases.tsv) et compare aux valeurs attendues.
 * Même fichier que scripts/test_decoders.py : les deux décodeurs restent alignés.
 */
class CanDecoderParityTest {

    private data class Case(
        val line: Int,
        val id: Int,
        val data: ByteArray,
        val field: String,
        val expected: String,
        val source: String,
    )

    private fun loadCases(): List<Case> {
        val dir = System.getProperty("fixturesDir") ?: error("fixturesDir non défini (voir testOptions)")
        return File(dir, "can_decode_cases.tsv").readLines().mapIndexedNotNull { i, line ->
            if (line.isBlank() || line.startsWith("#")) return@mapIndexedNotNull null
            val c = line.split('\t')
            if (c[2] == "-") return@mapIndexedNotNull null
            Case(
                line = i + 1,
                id = c[0].toInt(16),
                data = c[1].split(' ').map { it.toInt(16).toByte() }.toByteArray(),
                field = c[2],
                expected = c[4],
                source = c[5],
            )
        }
    }

    /** Lit `a.b.c` via les getters Java du data class (pas de kotlin-reflect). */
    private fun read(obj: Any?, path: String): Any? =
        path.split('.').fold(obj) { cur, name ->
            cur!!.javaClass.getMethod("get" + name.replaceFirstChar { it.uppercase() }).invoke(cur)
        }

    @Test
    fun fixtures() {
        val cases = loadCases()
        assertTrue("aucune trame de référence chargée", cases.isNotEmpty())
        val failures = cases.mapNotNull { c ->
            val state = CanDecoder.decode(VehicleState(), CanFrame(c.id, c.data))
            val actual = read(state, c.field)
            val ok = when (c.expected) {
                "null" -> actual == null
                "true", "false" -> actual == c.expected.toBoolean()
                else -> c.expected.toDoubleOrNull()?.let { exp ->
                    actual is Number && kotlin.math.abs(actual.toDouble() - exp) < 1e-6
                } ?: (actual?.toString() == c.expected)
            }
            if (ok) null
            else "ligne ${c.line} 0x%03X %s : attendu %s, obtenu %s (%s)"
                .format(c.id, c.field, c.expected, actual, c.source)
        }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }

    @Test
    fun trameInconnueNeModifiePasLEtat() {
        val s = VehicleState(rpm = 800.0)
        assertTrue(CanDecoder.decode(s, CanFrame(0x7FF, byteArrayOf(1, 2, 3))) === s)
    }

    @Test
    fun trameTropCourteIgnoree() {
        val s = VehicleState()
        assertTrue(CanDecoder.decode(s, CanFrame(0x0F6, byteArrayOf(0x08, 0x91.toByte()))) === s)
        assertNotNull(CanDecoder.decode(s, CanFrame(0x0B6, ByteArray(4))).rpm)
    }
}
