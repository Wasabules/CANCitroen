package com.geoffrey.cancitroen.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SharedTest {
    @Test fun preuveStable() {
        // Valeur figée : un changement casserait l'association entre versions des deux apps.
        val proof = LinkProtocol.pairingProof("ABCD2345", ByteArray(32) { it.toByte() })
        assertEquals(64, proof.length)
        assertEquals(proof, LinkProtocol.pairingProof("ABCD2345", ByteArray(32) { it.toByte() }))
        assertNotEquals(proof, LinkProtocol.pairingProof("ABCD2346", ByteArray(32) { it.toByte() }))
        assertEquals(PROOF, proof)
    }

    @Test fun numeroEtDateEnTete() {
        // Le compagnon lit ces deux colonnes à leur place, sans en-tête (TransferProtocol).
        val columns = TelemetryFormat.Samples.HEADER.split(';')
        assertEquals(TelemetryFormat.Samples.ID, columns[0])
        assertEquals(TelemetryFormat.Samples.DATE, columns[1])
    }

    @Test fun enTetesSansDoublon() {
        for (header in listOf(
            TelemetryFormat.Samples.HEADER, TelemetryFormat.Aggregates.HEADER,
            TelemetryFormat.Trips.HEADER, TelemetryFormat.Refuels.HEADER,
        )) {
            val columns = header.split(';')
            assertEquals(header, columns.size, columns.toSet().size)
        }
    }

    companion object {
        private const val PROOF = "dafe4c218f94519f07b8b59d54d0881b7ec39f146346a2db0049142306b0c9a6"
    }
}
