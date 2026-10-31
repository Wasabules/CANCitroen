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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Producteur de [VehicleState] réalistes pour la démo / les screenshots / les
 * tests UI. [DrivingModel] conduit la voiture ; le reste suit : carburant qui
 * descend, t° moteur qui monte, odo qui s'incrémente, clignotants ponctuels…
 */
class VehicleSimulator(
    private val state: MutableStateFlow<VehicleState>,
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        val model = DrivingModel()
        val t0 = System.currentTimeMillis()
        var last = t0
        var fuelPctF = 76f
        var odoKm = 154_320.0
        var tCool = 18f
        var tOil = 16f

        job = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val dt = ((now - last) / 1000.0).coerceIn(0.001, MAX_STEP_SEC)
                last = now
                model.step(dt)
                val tSec = (now - t0) / 1000.0
                val cycle = model.cycleSec
                val speed = model.speedKmh
                val rpm = model.rpm

                // T° moteur : monte vite au début, plateau ~92°C.
                tCool = min(92f, tCool + dt.toFloat() / 3f)
                tOil = min(98f, tOil + dt.toFloat() / 4f)

                // T° extérieure : varie sur ~24h synthétique avec un cycle long
                // (10°C la nuit → 28°C en plein été ; pour la démo on simule entre 12 et 22)
                val tExt = (17 + 5 * sin(tSec / 600.0)).toInt()

                // Carburant : descend lentement (~8 % par minute simulé à 90 km/h)
                fuelPctF = max(0f, fuelPctF - (speed.toFloat() / 90f) * 0.08f * dt.toFloat())

                // Odomètre
                odoKm += speed / 3600.0 * dt

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
                    tCoolant = tCool.toInt(),
                    tOil = tOil.toInt(),
                    gearCmb = if (model.gear == 0) "N" else model.gear.toString(),
                    tExt = tExt,
                    odo = odoKm,
                    fuelPct = fuelPctF.toInt(),
                    fuelLitersEst = (fuelPctF / 100f * 41f).toDouble(),  // réservoir 41L
                    oilLevelPct = 78,                                     // niveau huile stable
                    oilLevelLitersEst = 3.5,
                    fuelInst = if (speed > 5) (1.5 + 70.0 * model.throttle * rpm / 6000.0 / (speed / 50.0)).coerceAtMost(30.0) else null,
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
                delay(TICK_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        // Rétablit un état "vide" cohérent
        state.value = VehicleState()
        App.get().gpsTracker.clearSimulatedSpeed()
    }

    private companion object {
        /** Au rythme du régime sur le vrai bus (0x0B6). */
        const val TICK_MS = 50L
        const val MAX_STEP_SEC = 0.2
    }
}
