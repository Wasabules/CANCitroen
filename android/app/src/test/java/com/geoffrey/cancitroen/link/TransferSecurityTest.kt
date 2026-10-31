package com.geoffrey.cancitroen.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class TransferSecurityTest {
    /** Vecteur HMAC-SHA256 connu, le même que dans `TransferTest` du compagnon. */
    @Test fun proofMatchesKnownVector() {
        assertEquals(
            "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
            TransferSecurity.proof("key", "The quick brown fox jumps over the lazy dog".toByteArray()),
        )
    }

    @Test fun proofDependsOnCertificateAndCode() {
        val base = TransferSecurity.proof("ABCD2345", byteArrayOf(1, 2))
        assertNotEquals(base, TransferSecurity.proof("ABCD2345", byteArrayOf(1, 3)))
        assertNotEquals(base, TransferSecurity.proof("ABCD2346", byteArrayOf(1, 2)))
    }

    @Test fun tokensAreLongAndDifferent() {
        val token = TransferSecurity.newToken()
        assertEquals(64, token.length)
        assertNotEquals(token, TransferSecurity.newToken())
    }

    @Test fun onlyOwnAccessPointIsServed() {
        assertTrue(TransferSecurity.isOwnAccessPoint(InetAddress.getByName("192.168.43.1")))
        assertTrue(TransferSecurity.isOwnAccessPoint(InetAddress.getByName("127.0.0.1")))
        assertFalse("client d'un autre Wi-Fi", TransferSecurity.isOwnAccessPoint(InetAddress.getByName("192.168.1.37")))
        assertFalse("adresse publique", TransferSecurity.isOwnAccessPoint(InetAddress.getByName("8.8.8.1")))
    }
}
