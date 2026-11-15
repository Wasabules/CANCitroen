package com.geoffrey.cancitroen.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.car.app.connection.CarConnection
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Le suivi d'un trajet, au premier plan : de l'arrivée du téléphone dans la voiture jusqu'à ce
 * que l'Atoto ne réponde plus. Il interroge l'Atoto ([SessionPlan.POLL_MS]), publie dans Home
 * Assistant à chaque changement et régulièrement sinon, et transfère l'historique en route.
 *
 * Une tâche périodique ne suffit pas : Android la repousse de plusieurs heures pour une app
 * qu'on n'ouvre pas, d'où un état figé dans Home Assistant. Home Assistant et le transfert
 * tournent à part : un serveur lent ne doit pas faire croire que l'Atoto s'est tu.
 */
class CarSessionService : Service() {

    /** Ce que l'écran du téléphone et celui d'Android Auto affichent. */
    data class Status(
        val running: Boolean = false,
        val phase: SessionPlan.Phase? = null,
        val reason: String = "",
        val startedMs: Long = 0,
        val lastContactMs: Long? = null,
        val route: String? = null,
    )

    private class Start(val reason: String, val device: String?)

    private val main = Handler(Looper.getMainLooper())
    private val wakeups = Semaphore(0)
    private val publishing = AtomicBoolean(false)
    private val syncing = AtomicBoolean(false)
    private lateinit var workers: ExecutorService
    private var session: Thread? = null
    private var pendingStart: Start? = null
    @Volatile private var stopRequested = false
    @Volatile private var linked = false
    @Volatile private var carProjecting = false
    private var carType: LiveData<Int>? = null
    private val carObserver = Observer<Int> { carProjecting = it == CarConnection.CONNECTION_TYPE_PROJECTION }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Un pour Home Assistant, un pour le transfert : chacun à son rythme.
        workers = Executors.newFixedThreadPool(2)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Suivi de la voiture", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Pendant un trajet : liaison avec l'Atoto et envois à Home Assistant."
                setShowBadge(false)
            },
        )
        carType = CarConnection(this).type.also { it.observeForever(carObserver) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            requestStop("Suivi arrêté depuis la notification.")
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification("Recherche de l'Atoto…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: RuntimeException) {
            // Refus d'Android (arrière-plan, type de service) : rien ne doit planter.
            CompanionLog.add(this, "Suivi refusé au premier plan : ${e.javaClass.simpleName}")
            if (session?.isAlive != true) stopSelf()
            return START_NOT_STICKY
        }
        // Relancé par Android après un arrêt forcé : l'intention est perdue, la session reprend.
        val start = Start(intent?.getStringExtra(EXTRA_REASON) ?: "relance par Android", intent?.getStringExtra(EXTRA_DEVICE))
        when {
            session?.isAlive != true -> begin(start)
            // Fin de session en cours : on repartira juste derrière.
            stopRequested -> pendingStart = start
            else -> wake()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopRequested = true
        wake()
        carType?.removeObserver(carObserver)
        workers.shutdown()
        instance = null
        super.onDestroy()
    }

    private fun begin(start: Start) {
        stopRequested = false
        session = thread(name = "Suivi") { runSession(start) }
    }

    /** Sur le thread principal, comme les démarrages : pas de course entre fin et relance. */
    private fun ended() {
        val next = pendingStart
        pendingStart = null
        if (next != null) {
            begin(next)
        } else {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun runSession(start: Start) {
        val wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CANCitroen:suivi")
            .apply { setReferenceCounted(false) }
        val startedMs = System.currentTimeMillis()
        val plan = SessionPlan(startedMs)
        var route: AtotoRoute? = null
        linked = false
        status = Status(running = true, phase = SessionPlan.Phase.SEARCHING, reason = start.reason, startedMs = startedMs)
        CompanionLog.add(this, "Suivi démarré : ${start.reason}.")
        try {
            while (!stopRequested) {
                // Renouvelé à chaque tour : un blocage imprévu ne garde pas le téléphone éveillé.
                wake.acquire(WAKE_MS)
                val now = System.currentTimeMillis()
                when (val poll = TelemetrySync.poll(this, route)) {
                    is TelemetrySync.Poll.Answered -> {
                        synchronized(plan) { plan.onContact(now) }
                        if (route?.label != poll.route.label) CompanionLog.add(this, "Atoto joint (${poll.route.label}).")
                        route = poll.route
                        if (!linked) {
                            linked = true
                            learnCar(start.device)
                        }
                        val changed = HomeAssistant.lastSummary(this) != poll.summary
                        HomeAssistant.saveSummary(this, poll.summary)
                        HomeAssistant.saveLink(this, true, now)
                        if (changed) CarWidget.refresh(this)
                    }
                    is TelemetrySync.Poll.Refused -> {
                        CompanionLog.add(this, "Suivi arrêté : ${poll.reason}.")
                        break
                    }
                    TelemetrySync.Poll.Absent -> Unit
                }
                val phase = synchronized(plan) { plan.phase(now) }
                status = status.copy(phase = phase, lastContactMs = plan.lastContactMs, route = route?.label)
                when (phase) {
                    SessionPlan.Phase.GAVE_UP -> {
                        CompanionLog.add(this, "Atoto introuvable en ${SessionPlan.SEARCH_MS / 60_000} min : suivi arrêté.")
                        break
                    }
                    SessionPlan.Phase.LOST -> {
                        CompanionLog.add(this, "L'Atoto ne répond plus depuis ${SessionPlan.LOST_MS / 1000} s : fin du trajet.")
                        break
                    }
                    SessionPlan.Phase.LINKED -> {
                        publishSoon(plan, now)
                        syncSoon(plan, now)
                    }
                    SessionPlan.Phase.SEARCHING -> Unit
                }
                refreshNotification()
                sleep(synchronized(plan) { plan.pauseMs(now) })
            }
        } finally {
            if (linked) finish(plan)
            linked = false
            if (wake.isHeld) wake.release()
            status = status.copy(running = false, phase = null)
            CompanionLog.add(this, "Suivi terminé.")
            main.post(::ended)
        }
    }

    private fun projecting() = carProjecting || linked

    private fun publishSoon(plan: SessionPlan, now: Long) {
        if (!HomeAssistant.isConnected(this)) return
        val heartbeat = synchronized(plan) {
            if (!plan.mayPublish(now)) return
            plan.heartbeatDue(now)
        }
        if (!publishing.compareAndSet(false, true)) return
        workers.execute {
            try {
                val result = HomeAssistant.publish(this, projecting(), force = heartbeat)
                if (result.sent) synchronized(plan) { plan.onPublished(System.currentTimeMillis(), result.ok) }
            } finally {
                publishing.set(false)
            }
        }
    }

    /**
     * À chaque interrogation, les échantillons neufs seulement : les courbes suivent le trajet.
     * Tour complet (trajets, pleins, agrégats) au premier contact puis toutes les 10 min.
     */
    private fun syncSoon(plan: SessionPlan, now: Long) {
        if (!TransferPrefs(this).auto || !syncing.compareAndSet(false, true)) return
        val full = synchronized(plan) { plan.syncDue(now).also { if (it) plan.onSyncStarted(now) } }
        workers.execute {
            try {
                val outcome = TelemetrySync.run(this, snapshots = full) { stopRequested }
                // Le léger toutes les 30 s ne remplit le journal que s'il échoue.
                if (full || !outcome.ok) CompanionLog.add(this, "Transfert${if (full) "" else " léger"} : ${outcome.message}")
            } finally {
                syncing.set(false)
            }
        }
    }

    /** Le contact est coupé : Home Assistant doit savoir que la voiture n'est plus jointe. */
    private fun finish(plan: SessionPlan) {
        HomeAssistant.saveLink(this, false, plan.lastContactMs)
        HomeAssistant.noteSessionEnd(this, plan.lastContactMs ?: System.currentTimeMillis())
        CarWidget.refresh(this)
        if (!HomeAssistant.isConnected(this)) return
        repeat(FINAL_ATTEMPTS) { attempt ->
            if (attempt > 0) Thread.sleep(FINAL_RETRY_MS)
            if (publishing.compareAndSet(false, true)) {
                val result = try {
                    HomeAssistant.publish(this, carProjecting)
                } finally {
                    publishing.set(false)
                }
                if (result.ok) return
            }
        }
        CompanionLog.add(this, "Fin du trajet pas encore reçue par Home Assistant : la vérification périodique la renverra.")
    }

    /** Le Bluetooth connecté juste avant le premier contact est celui de la voiture. */
    private fun learnCar(device: String?) {
        val prefs = TransferPrefs(this)
        if (prefs.carBluetooth.isNotEmpty()) return
        val recent = device?.takeIf { it.isNotEmpty() }
            ?: prefs.lastBluetooth.takeIf { it.isNotEmpty() && System.currentTimeMillis() - prefs.lastBluetoothMs < LEARN_WINDOW_MS }
            ?: return
        prefs.carBluetooth = recent
        CompanionLog.add(this, "Bluetooth de la voiture reconnu (${maskedAddress(recent)}) : les autres appareils ne lancent plus le suivi.")
    }

    private fun requestStop(reason: String) {
        stopRequested = true
        CompanionLog.add(this, reason)
        wake()
    }

    private fun sleep(ms: Long) {
        if (!stopRequested) wakeups.tryAcquire(ms, TimeUnit.MILLISECONDS)
        wakeups.drainPermits()
    }

    private fun wake() = wakeups.release()

    private fun refreshNotification() {
        val text = when (status.phase) {
            SessionPlan.Phase.LINKED -> {
                val sent = HomeAssistant.lastSentMs(this)
                when {
                    !HomeAssistant.isConnected(this) -> "Voiture connectée"
                    sent > 0 -> "Voiture connectée · Home Assistant à ${clock(sent)}"
                    else -> "Voiture connectée · Home Assistant en attente"
                }
            }
            else -> "Recherche de l'Atoto…"
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CarSessionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_suivi)
            .setContentTitle("Suivi de la voiture")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_suivi), "Arrêter", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "suivi"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_REASON = "raison"
        private const val EXTRA_DEVICE = "appareil"
        private const val ACTION_STOP = "com.geoffrey.cancitroen.companion.ARRETER_SUIVI"
        private const val WAKE_MS = 2L * 60_000L
        private const val FINAL_ATTEMPTS = 3
        private const val FINAL_RETRY_MS = 20_000L
        private const val LEARN_WINDOW_MS = 10L * 60_000L

        @Volatile
        var status = Status()
            private set

        @Volatile
        private var instance: CarSessionService? = null

        /**
         * Renvoie false si Android refuse. Depuis l'arrière-plan (Bluetooth, tâche), il faut que
         * l'optimisation de la batterie soit désactivée pour le compagnon.
         */
        fun start(context: Context, reason: String, device: String? = null): Boolean {
            if (!TransferPrefs(context).paired) return false
            val intent = Intent(context, CarSessionService::class.java)
                .putExtra(EXTRA_REASON, reason)
                .putExtra(EXTRA_DEVICE, device)
            return try {
                context.startForegroundService(intent)
                true
            } catch (e: IllegalStateException) {
                CompanionLog.add(context, "Suivi ($reason) refusé par Android : désactive l'optimisation de la batterie pour le compagnon.")
                false
            } catch (e: SecurityException) {
                CompanionLog.add(context, "Suivi ($reason) refusé par Android : ${e.message}")
                false
            }
        }

        fun stop() {
            instance?.requestStop("Suivi arrêté à la main.")
        }

        /** Un indice que la voiture vient de partir : vérifier tout de suite plutôt qu'à la prochaine échéance. */
        fun poke() {
            instance?.wake()
        }

        fun maskedAddress(address: String) = "…" + address.takeLast(5)

        fun clock(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(Date(ms))
    }
}
