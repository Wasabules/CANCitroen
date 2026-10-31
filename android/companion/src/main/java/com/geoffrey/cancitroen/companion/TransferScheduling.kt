package com.geoffrey.cancitroen.companion

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Transfert automatique, seulement pendant Android Auto. Une tâche périodique, qui survit
 * au redémarrage du téléphone, regarde si la projection est active ; la diffusion
 * d'Android Auto la devance quand elle arrive jusqu'à l'app.
 *
 * Pas de réveil sur l'arrivée du Wi-Fi : un `registerNetworkCallback` à PendingIntent est
 * retiré par le système 5 s après son premier envoi (observé sur Android 16).
 */
object TransferScheduler {
    private const val JOB_NOW = 1
    private const val JOB_PERIODIC = 2
    private const val PERIOD_MS = 15L * 60_000L
    private const val MIN_ATTEMPT_GAP_MS = 60_000L
    private const val CAR_STATE_TIMEOUT_S = 3L

    /** Reprogrammer une tâche en cours l'arrêterait : on ne touche pas à celle qui existe. */
    fun refresh(context: Context) {
        val prefs = TransferPrefs(context)
        val jobs = context.getSystemService(JobScheduler::class.java)
        // Home Assistant attend aussi la tâche : un état reçu sur la route part une fois à la maison.
        if (!(prefs.auto && prefs.paired) && !HomeAssistant.isConnected(context)) {
            jobs.cancelAll()
            return
        }
        if (jobs.getPendingJob(JOB_PERIODIC) == null) {
            jobs.schedule(
                baseJob(context, JOB_PERIODIC)
                    .setPeriodic(PERIOD_MS)
                    .setPersisted(true)
                    .build(),
            )
        }
    }

    fun onCarConnectionChanged(context: Context) {
        val prefs = TransferPrefs(context)
        val now = System.currentTimeMillis()
        val wanted = (prefs.auto && prefs.paired) || HomeAssistant.isConnected(context)
        if (!wanted || now - prefs.lastAttemptMs < MIN_ATTEMPT_GAP_MS) return
        prefs.lastAttemptMs = now
        val job = baseJob(context, JOB_NOW)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) job.setExpedited(true)
        context.getSystemService(JobScheduler::class.java).schedule(job.build())
    }

    /** Sans contrainte de réseau, le système peut couper le réseau en arrière-plan. Le Wi-Fi d'Android Auto n'a pas Internet. */
    private fun baseJob(context: Context, id: Int): JobInfo.Builder {
        val anyNetwork = NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        return JobInfo.Builder(id, ComponentName(context, TransferJobService::class.java))
            .setRequiredNetwork(anyNetwork)
    }

    /** Lit l'état une fois : à appeler hors du thread principal. CarConnection exige, lui, le principal. */
    fun isProjecting(context: Context): Boolean {
        val main = Handler(Looper.getMainLooper())
        val answered = CountDownLatch(1)
        var projecting = false
        val observer = Observer<Int> {
            projecting = it == CarConnection.CONNECTION_TYPE_PROJECTION
            answered.countDown()
        }
        var type: LiveData<Int>? = null
        main.post {
            type = CarConnection(context.applicationContext).type.also { it.observeForever(observer) }
        }
        answered.await(CAR_STATE_TIMEOUT_S, TimeUnit.SECONDS)
        main.post { type?.removeObserver(observer) }
        return projecting
    }
}

class TransferJobService : JobService() {
    @Volatile private var stopped = false

    override fun onStartJob(params: JobParameters): Boolean {
        stopped = false
        thread(name = "Transfert") {
            val projecting = TransferScheduler.isProjecting(this)
            val at = SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE).format(Date())
            TransferPrefs(this).lastCheck = "$at, Android Auto ${if (projecting) "connecté" else "non connecté"}"
            val prefs = TransferPrefs(this)
            if (projecting && prefs.auto && prefs.paired) TelemetrySync.run(this) { stopped }
            HomeAssistant.publish(this, projecting)
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped = true
        return false
    }
}

/** N'importe quelle app peut l'envoyer : elle ne fait que déclencher la vérification d'Android Auto. */
class TransferReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == CarConnection.ACTION_CAR_CONNECTION_UPDATED) {
            TransferScheduler.onCarConnectionChanged(context)
        }
    }
}
