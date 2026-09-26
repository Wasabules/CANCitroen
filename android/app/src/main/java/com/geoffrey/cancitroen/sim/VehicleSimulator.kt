package com.geoffrey.cancitroen.sim

import com.geoffrey.cancitroen.App
import com.geoffrey.cancitroen.decode.Doors
import com.geoffrey.cancitroen.decode.IgnitionMode
import com.geoffrey.cancitroen.decode.KeyPosition
import com.geoffrey.cancitroen.decode.Lights
import com.geoffrey.cancitroen.decode.VehicleState
import com.geoffrey.cancitroen.decode.Warnings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Producteur de [VehicleState] réalistes pour la démo / les screenshots / les
 * tests UI. Boucle de cycle ~120 s :
 *   démarrage → accélération → vitesse stable → décélération → stop
 *   avec carburant qui descend, t° moteur qui monte, odo qui s'incrémente,
 *   clignotants ponctuels, etc.
 */
class VehicleSimulator(
    private val state: MutableStateFlow<VehicleState>,
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        val t0 = System.currentTimeMillis()
        var fuelPctF = 76f
        var odoKm = 154_320.0
        var tCool = 18
        var tOil = 16
        // Smoothing 1er ordre : on définit une CIBLE par phase, et on tend vers
        // elle avec une constante de temps τ. Élimine les "step" entre phases.
        var smoothSpeed = 0.0
        var smoothRpm = 880.0
        val tickDt = 0.5      // 500 ms (= delay)
        val tauSec = 3.0      // constante de temps : transitions ~3 s

        job = scope.launch {
            while (isActive) {
                val tSec = (System.currentTimeMillis() - t0) / 1000.0
                val cycle = (tSec % 120.0)

                // ── Cibles selon phase (cycle 120 s) ──
                val (speedTarget, rpmTarget) = when {
                    cycle < 5  -> 0.0 to 880.0                  // ralenti
                    cycle < 25 -> 50.0 to 3000.0                // pleine accél (target stable)
                    cycle < 75 -> {                             // croisière oscillante
                        val s = 90.0 + 5 * sin(cycle * 0.4)
                        val r = 2400.0 + 200 * sin(cycle * 0.4)
                        s to r
                    }
                    else -> 0.0 to 880.0                        // décél + arrêt
                }
                // Smoothing 1er ordre : transitions douces, plus de marche
                val alpha = 1.0 - exp(-tickDt / tauSec)
                smoothSpeed += (speedTarget - smoothSpeed) * alpha
                smoothRpm   += (rpmTarget   - smoothRpm)   * alpha
                val speed = smoothSpeed
                val rpm = smoothRpm

                // T° moteur : monte vite au début, plateau ~92°C — pas
                // exactement les mêmes seuils qu'avant (basés sur un compteur
                // d'itérations) mais visuellement équivalent à la démo.
                val tickIdx = (tSec / tickDt).toLong()
                tCool = min(92, tCool + if (tickIdx % 6 == 0L && tCool < 92) 1 else 0)
                tOil  = min(98, tOil  + if (tickIdx % 8 == 0L && tOil  < 98) 1 else 0)

                // T° extérieure : varie sur ~24h synthétique avec un cycle long
                // (10°C la nuit → 28°C en plein été ; pour la démo on simule entre 12 et 22)
                val tExt = (17 + 5 * sin(tSec / 600.0)).toInt()

                // Carburant : descend lentement (~8 % par minute simulé à 90 km/h)
                fuelPctF = max(0f, fuelPctF - (speed.toFloat() / 90f) * 0.04f)

                // Odomètre
                odoKm += speed / 3600.0 * 0.5  // tick 500 ms

                // Vitesse GPS simulée : compteur sur-évalue typiquement 3 à 5 km/h
                // → on injecte une valeur GPS légèrement plus basse
                val gpsKmh = if (speed > 1) (speed - 3 - 2 * sin(cycle * 0.7)).toFloat()
                             else null
                App.get().gpsTracker.setSimulatedSpeed(gpsKmh?.coerceAtLeast(0f))

                // Clignotants ponctuels
                val cligG = (cycle in 30.0..32.0)
                val cligD = (cycle in 60.0..62.0)

                // Phares : croisement la nuit (~> 80 s du cycle)
                val croisement = cycle > 80
                val drl = !croisement && cycle > 5

                state.value = state.value.copy(
                    speed = speed,
                    rpm = rpm,
                    tCoolant = tCool,
                    tOil = tOil,
                    tExt = tExt,
                    odo = odoKm,
                    fuelPct = fuelPctF.toInt(),
                    fuelLitersEst = (fuelPctF / 100f * 41f).toDouble(),  // réservoir 41L
                    oilLevelPct = 78,                                     // niveau huile stable
                    oilLevelLitersEst = 3.5,
                    fuelInst = if (speed > 5) 5.5 + 2 * cos(cycle * 0.2) else null,
                    rangeKm = if (speed > 5) (fuelPctF * 6).toInt() else null,
                    contact = true,
                    keyPosition = KeyPosition.CONTACT,
                    ignitionMode = IgnitionMode.NORMAL,
                    economyMode = false,
                    nightMode = croisement,
                    blackPanel = false,
                    lights = Lights(
                        drl = drl,
                        clignoG = cligG,
                        clignoD = cligD,
                        feuxCroisement = croisement,
                        feuxPosition = croisement,
                    ),
                    handbrake = speed < 0.5 && cycle < 5,
                    doors = Doors(),
                    warn = Warnings(
                        fuelLow = fuelPctF < 15,
                    ),
                    tripAvgSpeed = 78,
                    tripDist = 142.5,
                    tripAvgCons = 6.2,
                    tripDistTotal = odoKm.toInt(),
                    maintDue = false,
                    maintKmRemaining = 8_500,
                    maintDaysRemaining = 142,
                )
                delay(500L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        // Rétablit un état "vide" cohérent
        state.value = VehicleState()
        App.get().gpsTracker.setSimulatedSpeed(null)
    }
}
