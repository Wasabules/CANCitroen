package com.geoffrey.cancitroen.companion

/**
 * Le rythme d'une session de suivi, sans Android : testé en JVM.
 *
 * Une session commence quand le téléphone a une raison de croire qu'il est dans la voiture
 * (Bluetooth, Android Auto, écran ouvert), cherche l'Atoto quelques minutes, puis l'interroge
 * tant qu'il répond. Elle se termine quand il ne répond plus depuis [LOST_MS] : contact coupé.
 */
class SessionPlan(private val startedMs: Long) {
    enum class Phase { SEARCHING, LINKED, LOST, GAVE_UP }

    var lastContactMs: Long? = null
        private set
    var lastPublishOkMs: Long? = null
        private set
    private var publishFailures = 0
    private var nextPublishMs = 0L
    private var lastSyncMs: Long? = null

    fun onContact(now: Long) {
        lastContactMs = now
    }

    fun phase(now: Long): Phase {
        val contact = lastContactMs ?: return if (now - startedMs >= SEARCH_MS) Phase.GAVE_UP else Phase.SEARCHING
        return if (now - contact >= LOST_MS) Phase.LOST else Phase.LINKED
    }

    /** Attente avant la prochaine interrogation : l'Atoto se cherche plus souvent qu'il ne s'interroge. */
    fun pauseMs(now: Long): Long = if (phase(now) == Phase.SEARCHING) SEARCH_POLL_MS else POLL_MS

    /** Après un échec, on espace : un serveur injoignable sur la route ne doit pas tout ralentir. */
    fun mayPublish(now: Long): Boolean = now >= nextPublishMs

    /** Rien de neuf depuis longtemps : on renvoie quand même, Home Assistant voit que la liaison vit. */
    fun heartbeatDue(now: Long): Boolean = lastPublishOkMs.let { it == null || now - it >= HEARTBEAT_MS }

    fun onPublished(now: Long, ok: Boolean) {
        if (ok) {
            lastPublishOkMs = now
            publishFailures = 0
            nextPublishMs = 0
        } else {
            publishFailures++
            val backoff = PUBLISH_RETRY_MS shl (publishFailures - 1).coerceAtMost(4)
            nextPublishMs = now + backoff.coerceAtMost(MAX_PUBLISH_RETRY_MS)
        }
    }

    /** Tour complet du transfert (trajets, pleins, agrégats) ; le léger, lui, suit chaque interrogation. */
    fun syncDue(now: Long): Boolean = lastSyncMs.let { it == null || now - it >= SYNC_MS }

    fun onSyncStarted(now: Long) {
        lastSyncMs = now
    }

    companion object {
        const val SEARCH_MS = 5L * 60_000L
        const val SEARCH_POLL_MS = 10_000L
        const val POLL_MS = 30_000L
        const val LOST_MS = 90_000L
        const val HEARTBEAT_MS = 5L * 60_000L
        const val SYNC_MS = 10L * 60_000L
        const val PUBLISH_RETRY_MS = 30_000L
        const val MAX_PUBLISH_RETRY_MS = 5L * 60_000L
    }
}
