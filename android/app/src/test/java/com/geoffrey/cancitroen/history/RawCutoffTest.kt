package com.geoffrey.cancitroen.history

import org.junit.Assert.assertEquals
import org.junit.Test

class RawCutoffTest {
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = 100 * day

    private fun cutoff(paired: Boolean, unsent: Long?) = RollupAggregator.rawCutoff(now, RawHold(paired, unsent))

    @Test fun sansTelephoneSixHeures() = assertEquals(now - 6 * hour, cutoff(false, null))

    @Test fun telephoneAssocieTout7Jours() = assertEquals(now - 7 * day, cutoff(true, null))

    @Test fun attendLePlusAncienNonEnvoye() = assertEquals(now - 12 * day, cutoff(true, now - 12 * day))

    @Test fun nonEnvoyeRecentNeRaccourcitPasLaMarge() = assertEquals(now - 7 * day, cutoff(true, now - hour))

    @Test fun jamaisAuDelaDeTrenteJours() = assertEquals(now - 30 * day, cutoff(true, now - 45 * day))
}
