package com.geoffrey.cancitroen.system

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Compte les satellites GPS visibles + utilisés en fix.
 *
 * Si la permission [Manifest.permission.ACCESS_FINE_LOCATION] n'est pas accordée,
 * expose `null`. Sinon expose ([SatelliteState]) en live via [state].
 */
class GpsTracker(private val context: Context) {

    companion object { private const val TAG = "GpsTracker" }

    data class SatelliteState(
        val visible: Int = 0,
        val usedInFix: Int = 0,
        val hasFix: Boolean = false,
    )

    private val _state = MutableStateFlow<SatelliteState?>(null)
    val state: StateFlow<SatelliteState?> = _state

    /** Vitesse GPS en km/h, ou null si pas de fix. */
    private val _speedKmh = MutableStateFlow<Float?>(null)
    val speedKmh: StateFlow<Float?> = _speedKmh

    /** Timestamp du dernier callback location reçu, pour détecter la perte de fix. */
    @Volatile private var lastLocationMs: Long = 0L

    /** Watchdog handler partagé — instancié une seule fois, jamais leaké. */
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private var watchdogRunnable: Runnable? = null
    @Volatile private var started: Boolean = false

    private val locationListener = LocationListener { loc ->
        lastLocationMs = System.currentTimeMillis()
        if (loc.hasSpeed()) {
            _speedKmh.value = (loc.speed * 3.6f).coerceAtLeast(0f)
        }
    }

    private val locManager: LocationManager?
        get() = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val callback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val total = status.satelliteCount
            var used = 0
            for (i in 0 until total) {
                if (status.usedInFix(i)) used++
            }
            _state.value = SatelliteState(
                visible = total,
                usedInFix = used,
                hasFix = used >= 4,
            )
        }
    }

    /** Permet au simulateur d'injecter une vitesse GPS factice. */
    fun setSimulatedSpeed(kmh: Float?) {
        _speedKmh.value = kmh
    }

    @SuppressLint("MissingPermission")
    fun start() {
        // Idempotent : si déjà démarré on no-op (sinon chaque onResume() empile
        // un nouveau locationListener + watchdog Runnable — leak garanti).
        if (started) return

        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.w(TAG, "ACCESS_FINE_LOCATION non accordée — pas de tracking GPS")
            _state.value = null
            _speedKmh.value = null
            return
        }
        val lm = locManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lm.registerGnssStatusCallback(context.mainExecutor, callback)
            } else {
                @Suppress("DEPRECATION")
                lm.registerGnssStatusCallback(callback, watchdogHandler)
            }
            // Demande des updates de location pour capter la vitesse
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                /* minTimeMs */ 500L,
                /* minDistanceM */ 0f,
                locationListener,
                Looper.getMainLooper(),
            )
            // Watchdog : si plus aucun callback location depuis 5s, le fix est
            // probablement perdu (tunnel, pont) → on remet la vitesse à null
            // pour ne pas figer la dernière valeur affichée. La Runnable est
            // stockée pour être removable dans stop().
            val tick = object : Runnable {
                override fun run() {
                    val since = System.currentTimeMillis() - lastLocationMs
                    if (lastLocationMs > 0 && since > 5000) {
                        _speedKmh.value = null
                    }
                    watchdogHandler.postDelayed(this, 1000)
                }
            }
            watchdogRunnable = tick
            watchdogHandler.postDelayed(tick, 1000)
            started = true
            Log.i(TAG, "GPS tracking démarré (sat + vitesse + watchdog)")
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission GPS refusée", e)
            _state.value = null
            _speedKmh.value = null
        } catch (e: Exception) {
            Log.e(TAG, "Erreur start tracker", e)
        }
    }

    fun stop() {
        if (!started) return
        try { locManager?.unregisterGnssStatusCallback(callback) } catch (_: Exception) {}
        try { locManager?.removeUpdates(locationListener) } catch (_: Exception) {}
        watchdogRunnable?.let { watchdogHandler.removeCallbacks(it) }
        watchdogRunnable = null
        started = false
    }
}
