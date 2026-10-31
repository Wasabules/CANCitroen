package com.geoffrey.cancitroen.companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class TransferTest {
    private fun answer(text: String) = TransferProtocol.parseAnswer(text.toByteArray(Charsets.ISO_8859_1).inputStream())

    /** Vecteur HMAC-SHA256 connu, le même que dans `TransferSecurityTest` de l'app. */
    @Test fun proofMatchesKnownVector() = assertEquals(
        "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
        TransferProtocol.pairingProof("key", "The quick brown fox jumps over the lazy dog".toByteArray()),
    )

    @Test fun readsStatusHeadersAndBody() {
        val parsed = answer("HTTP/1.0 200 OK\r\nContent-Length: 3\r\nX-Count: 12\r\nConnection: close\r\n\r\nabc")
        assertEquals(200, parsed.status)
        assertEquals("12", parsed.headers["x-count"])
        assertArrayEquals("abc".toByteArray(), parsed.body)
    }

    @Test fun refusesAnswerCutMidBody() {
        assertThrows(IOException::class.java) { answer("HTTP/1.0 200 OK\r\nContent-Length: 10\r\n\r\nabc") }
        assertThrows(IOException::class.java) { answer("HTTP/1.0 200 OK\r\nContent-Le") }
    }

    @Test fun normalizesTypedCode() = assertEquals("ABCD2345", TransferProtocol.normalizeCode(" abcd-2345 "))

    @Test fun resumesFromTheFurthestConfirmation() {
        assertEquals(500, TransferProtocol.resumeAfter(local = 500, ack = 300, last = 900))
        assertEquals(300, TransferProtocol.resumeAfter(local = 0, ack = 300, last = 900))
    }

    @Test fun followsTheAtotoWhenItsBaseWasRecreated() =
        assertEquals(0, TransferProtocol.resumeAfter(local = 500, ack = 0, last = 12))

    @Test fun splitsBatchByDayInOrder() {
        val days = TransferProtocol.splitByDay(
            "id;date;x\n1;2026-10-31 23:59:58;a\n2;2026-11-01 00:00:03;b\n3;2026-11-01 00:00:08;c\n",
        )
        assertEquals(listOf("2026-10-31", "2026-11-01"), days.keys.toList())
        assertEquals(listOf("2;2026-11-01 00:00:03;b", "3;2026-11-01 00:00:08;c"), days["2026-11-01"])
    }

    @Test fun refusesUnreadableLine() {
        assertThrows(IOException::class.java) { TransferProtocol.splitByDay("id;date\n1;../../etc\n") }
    }
}
