package com.geoffrey.cancitroen.companion

import com.geoffrey.cancitroen.companion.SessionPlan.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPlanTest {
    private val t0 = 1_000_000L

    @Test fun chercheVitePuisAbandonne() {
        val plan = SessionPlan(t0)
        assertEquals(Phase.SEARCHING, plan.phase(t0))
        assertEquals(SessionPlan.SEARCH_POLL_MS, plan.pauseMs(t0))
        assertEquals(Phase.SEARCHING, plan.phase(t0 + SessionPlan.SEARCH_MS - 1))
        assertEquals(Phase.GAVE_UP, plan.phase(t0 + SessionPlan.SEARCH_MS))
    }

    @Test fun lieTantQueLAtotoRepond() {
        val plan = SessionPlan(t0)
        plan.onContact(t0 + 20_000)
        assertEquals(Phase.LINKED, plan.phase(t0 + 20_000))
        assertEquals(SessionPlan.POLL_MS, plan.pauseMs(t0 + 20_000))
        // Bien au-delà de la recherche : tant qu'il répond, la session continue.
        plan.onContact(t0 + 3_600_000)
        assertEquals(Phase.LINKED, plan.phase(t0 + 3_600_000 + SessionPlan.LOST_MS - 1))
        assertEquals(Phase.LOST, plan.phase(t0 + 3_600_000 + SessionPlan.LOST_MS))
    }

    @Test fun deuxInterrogationsRateesNeSuffisentPasAFinir() {
        val plan = SessionPlan(t0)
        plan.onContact(t0)
        assertEquals(Phase.LINKED, plan.phase(t0 + 2 * SessionPlan.POLL_MS))
    }

    @Test fun premierEnvoiPuisBattementDeCoeur() {
        val plan = SessionPlan(t0)
        assertTrue(plan.heartbeatDue(t0))
        plan.onPublished(t0, ok = true)
        assertFalse(plan.heartbeatDue(t0 + SessionPlan.HEARTBEAT_MS - 1))
        assertTrue(plan.heartbeatDue(t0 + SessionPlan.HEARTBEAT_MS))
    }

    @Test fun echecsEspacesPuisPlafonnes() {
        val plan = SessionPlan(t0)
        assertTrue(plan.mayPublish(t0))
        plan.onPublished(t0, ok = false)
        assertFalse(plan.mayPublish(t0 + SessionPlan.PUBLISH_RETRY_MS - 1))
        assertTrue(plan.mayPublish(t0 + SessionPlan.PUBLISH_RETRY_MS))
        plan.onPublished(t0, ok = false)
        assertFalse(plan.mayPublish(t0 + 2 * SessionPlan.PUBLISH_RETRY_MS - 1))
        repeat(10) { plan.onPublished(t0, ok = false) }
        assertTrue(plan.mayPublish(t0 + SessionPlan.MAX_PUBLISH_RETRY_MS))
        // Un succès remet tout à zéro.
        plan.onPublished(t0, ok = true)
        assertTrue(plan.mayPublish(t0))
    }

    @Test fun transfertAuDebutPuisPeriodique() {
        val plan = SessionPlan(t0)
        assertTrue(plan.syncDue(t0))
        plan.onSyncStarted(t0)
        assertFalse(plan.syncDue(t0 + SessionPlan.SYNC_MS - 1))
        assertTrue(plan.syncDue(t0 + SessionPlan.SYNC_MS))
    }
}
