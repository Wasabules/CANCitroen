package com.geoffrey.cancitroen.history

import android.util.Log
import com.geoffrey.cancitroen.App
import com.geoffrey.cancitroen.decode.VehicleState
import com.geoffrey.cancitroen.history.dao.HistoryDao
import com.geoffrey.cancitroen.history.entities.RefuelEvent
import com.geoffrey.cancitroen.history.entities.Trip
import com.geoffrey.cancitroen.history.entities.VehicleSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Enregistre l'historique du véhicule dans la base Room :
 *  - 1 sample / 5 s pendant que le contact est ON
 *  - Détecte les pleins (saut fuelPct ≥ 20% en moins de 10 min)
 *  - Détecte les trajets (start = contact ON et déjà roulé > 5 km/h ;
 *    end = speed=0 prolongé > 60 s ou contact OFF)
 *  - La rétention est gérée par [RollupAggregator] : samples bruts gardés
 *    6 h puis agrégés (1 min → 7 j, 30 min → 30 j, 1 h → 90 j)
 *
 *  Intervalles & seuils calibrés pour usage réel ; ajustables ici.
 */
class VehicleHistoryRecorder(
    private val dao: HistoryDao,
    private val state: StateFlow<VehicleState>,
) {
    companion object {
        private const val TAG = "HistoryRecorder"
        private const val SAMPLE_INTERVAL_MS = 5_000L
        private const val REFUEL_MIN_DELTA_PCT = 20
        private const val REFUEL_WINDOW_MS = 10L * 60L * 1000L  // 10 min
        private const val TRIP_START_SPEED_KMH = 5f
        private const val TRIP_STOP_GRACE_MS = 60_000L  // 60 s à 0 km/h → fin trajet
    }

    private var job: Job? = null

    // État interne pour la détection des pleins
    private var lastFuelPct: Int? = null
    private var lastFuelPctTs: Long = 0

    // État interne pour la détection des trajets
    private var activeTripId: Long? = null
    private var tripMaxSpeed: Float = 0f
    private var tripMaxOdoKm: Double? = null
    private var tripSamplesCount: Int = 0
    private var tripSpeedSum: Float = 0f
    private var tripConsCount: Int = 0
    private var tripConsSum: Float = 0f
    private var stopMomentMs: Long = 0   // dernier instant où speed est passé sous TRIP_START_SPEED

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return

        // Boucle principale : sample périodique + détections
        job = scope.launch {
            // Reprend un trajet ouvert s'il y en a un (cas service tué/relancé)
            // ET reconstruit les compteurs in-memory via SQL aggregate (avant
            // on chargeait toute la liste des samples en RAM — pour un trip
            // 4h = 2880 rows, douloureux).
            dao.activeTrip()?.let { trip ->
                activeTripId = trip.id
                val agg = dao.tripResumeCounters(trip.id)
                tripMaxSpeed = (agg?.speedMax ?: 0.0).toFloat()
                tripSpeedSum = (agg?.speedSum ?: 0.0).toFloat()
                tripSamplesCount = agg?.speedCount ?: 0
                tripConsSum = (agg?.consSum ?: 0.0).toFloat()
                tripConsCount = agg?.consCount ?: 0
                tripMaxOdoKm = trip.startOdoKm
                Log.i(TAG, "↺ Reprise trajet #${trip.id} (${tripSamplesCount} samples agrégés en SQL)")
            }

            while (isActive) {
                // Une erreur SQLite ponctuelle (disque plein, verrou…) ne doit
                // ni tuer le process (launcher) ni arrêter l'enregistrement.
                try { tick() } catch (e: CancellationException) { throw e }
                catch (e: Exception) { Log.e(TAG, "Échantillon non enregistré", e) }
                delay(SAMPLE_INTERVAL_MS)
            }
        }

        // Note : la purge des samples raw est désormais gérée par RollupAggregator,
        // qui les agrège en buckets avant de les supprimer.
    }

    fun stop() {
        job?.cancel(); job = null
    }

    private suspend fun tick() {
        val s = state.value
        if (!s.contact) {
            // Contact OFF : ferme un éventuel trajet en cours et ne sample plus
            closeActiveTripIfAny()
            return
        }
        val now = System.currentTimeMillis()

        // ── Trip detection ──
        val speed = s.speed?.toFloat() ?: 0f
        if (activeTripId == null && speed >= TRIP_START_SPEED_KMH) {
            // Démarre un trajet
            val trip = Trip(
                startTs = now,
                startOdoKm = s.odo,
                startFuelPct = s.fuelPct,
            )
            activeTripId = dao.insertTrip(trip)
            tripMaxSpeed = speed
            tripMaxOdoKm = s.odo
            tripSamplesCount = 0
            tripSpeedSum = 0f
            tripConsCount = 0
            tripConsSum = 0f
            stopMomentMs = 0
            Log.i(TAG, "▶ Trajet démarré #$activeTripId à ${s.odo} km")
        }
        if (activeTripId != null) {
            tripMaxSpeed = maxOf(tripMaxSpeed, speed)
            // Track maxOdo : si le capteur drop le ts d'odo (null sentinel),
            // on garde la dernière valeur connue → la distance finale est juste.
            s.odo?.let { tripMaxOdoKm = maxOf(tripMaxOdoKm ?: it, it) }
            tripSpeedSum += speed
            tripSamplesCount++
            s.fuelInst?.let {
                tripConsSum += it.toFloat()
                tripConsCount++
            }
            if (speed < TRIP_START_SPEED_KMH) {
                if (stopMomentMs == 0L) stopMomentMs = now
                else if (now - stopMomentMs > TRIP_STOP_GRACE_MS) {
                    closeActiveTripIfAny()
                }
            } else {
                stopMomentMs = 0
            }
        }

        // ── Sample ──
        val sample = VehicleSample(
            ts = now,
            tripId = activeTripId,
            speedKmh = s.speed?.toFloat(),
            rpm = s.rpm?.toFloat(),
            fuelPct = s.fuelPct,
            fuelLiters = s.fuelLitersEst?.toFloat(),
            rangeKm = s.rangeKm,
            tCoolant = s.tCoolant,
            tOil = s.tOil,
            tExt = s.tExt,
            oilLevelPct = s.oilLevelPct,
            odoKm = s.odo,
            fuelInstL100 = s.fuelInst?.toFloat(),
        )
        dao.insertSample(sample)

        // ── Refuel detection ──
        // Subtilité : on ne met PAS à jour `lastFuelPct` quand le niveau MONTE
        // (delta > 0), sinon un plein progressif (60→80→95% en 10 min) verrait
        // chaque pas écraser la référence — au final delta calculé = 80→95=15%
        // < seuil → plein raté. On garde le min observé sur la fenêtre, qui
        // représente bien "niveau avant plein".
        val pct = s.fuelPct
        if (pct != null) {
            val prev = lastFuelPct
            if (prev != null && (now - lastFuelPctTs) <= REFUEL_WINDOW_MS) {
                val delta = pct - prev
                if (delta >= REFUEL_MIN_DELTA_PCT && speed < 1f) {
                    // Lit les settings carburant (prix + capacité) au moment du plein
                    val fuelSettings = App.get().settings.flow.first().fuel
                    val liters = delta / 100f * fuelSettings.tankCapacityLiters
                    val price = fuelSettings.pricePerLiter
                    val cost = liters.toDouble() * price
                    dao.insertRefuel(
                        RefuelEvent(
                            ts = now,
                            fuelPctBefore = prev,
                            fuelPctAfter = pct,
                            litersAdded = liters,
                            odoKm = s.odo,
                            pricePerLiter = price,
                            totalCost = cost,
                        )
                    )
                    Log.i(TAG, "⛽ Plein : $prev% → $pct% (~${"%.1f".format(liters)} L · ${"%.2f".format(cost)} €)")
                    // Reset après détection : on repart d'un palier propre.
                    lastFuelPct = pct
                    lastFuelPctTs = now
                }
            }
            // Ne mettre à jour que sur conso normale (delta ≤ 0) ou si pas
            // encore initialisé / hors fenêtre — sinon on glisse à chaque
            // sample d'un plein en cours.
            val isFirst = prev == null
            val outOfWindow = (now - lastFuelPctTs) > REFUEL_WINDOW_MS
            val isConsuming = prev != null && pct <= prev
            if (isFirst || outOfWindow || isConsuming) {
                lastFuelPct = pct
                lastFuelPctTs = now
            }
        }
    }

    private suspend fun closeActiveTripIfAny() {
        val id = activeTripId ?: return
        val now = System.currentTimeMillis()
        val s = state.value
        val trip = dao.tripById(id) ?: return
        val avgSpeed = if (tripSamplesCount > 0) tripSpeedSum / tripSamplesCount else null
        val avgCons = if (tripConsCount > 0) tripConsSum / tripConsCount else null
        val durationMs = now - trip.startTs
        // Utilise le maxOdoKm tracké in-memory : si l'odo courant est null
        // (sentinel BSI au moment précis de l'arrêt), on garde la dernière
        // valeur valide vue pendant le trajet → distance correcte.
        val endOdo = s.odo ?: tripMaxOdoKm
        val dist = endOdo?.let { o -> trip.startOdoKm?.let { o - it } } ?: 0.0
        // Micro-trajet (< 30 s OU < 0.1 km) → suppression réelle, samples
        // associés conservés mais détachés (FK ON DELETE SET NULL)
        if (durationMs < 30_000 || dist < 0.1) {
            dao.deleteTrip(id)
            Log.i(TAG, "Trajet #$id supprimé (micro-trajet : ${durationMs}ms / ${dist}km)")
        } else {
            dao.updateTrip(
                trip.copy(
                    endTs = now,
                    endOdoKm = endOdo,
                    endFuelPct = s.fuelPct,
                    maxSpeedKmh = tripMaxSpeed.takeIf { it > 0 },
                    avgSpeedKmh = avgSpeed,
                    avgConsL100 = avgCons,
                )
            )
            Log.i(TAG, "■ Trajet #$id clos : ${dist}km en ${durationMs / 1000}s")
        }
        activeTripId = null
        tripMaxOdoKm = null
    }
}
