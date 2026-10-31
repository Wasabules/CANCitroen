package com.geoffrey.cancitroen.sim

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Une petite citadine et son conducteur : pédale, embrayage, cinq rapports.
 * Régime et vitesse découlent de ce que le conducteur fait, ils ne sont jamais
 * imposés, d'où de vraies montées en régime et de vrais passages de vitesses.
 */
class DrivingModel {
    private class Leg(val fromSec: Double, val targetKmh: Double, val eagerness: Double)

    /** Boucle de [LOOP_SEC] secondes : ville, feu, voie rapide, dépassement, arrêt. */
    private val legs = listOf(
        Leg(0.0, 0.0, 0.0),
        Leg(5.0, 50.0, 0.55),
        Leg(20.0, 50.0, 0.4),
        Leg(26.0, 0.0, 0.0),
        Leg(36.0, 112.0, 1.0),
        Leg(62.0, 90.0, 0.35),
        Leg(76.0, 128.0, 1.0),
        Leg(92.0, 0.0, 0.0),
    )

    var speedKmh = 0.0
        private set
    var rpm = IDLE_RPM
        private set
    /** 0 = point mort. */
    var gear = 0
        private set
    var throttle = 0.0
        private set
    var cycleSec = 0.0
        private set

    private var brake = 0.0
    private var clutch = 0.0
    private var shiftLeft = 0.0
    private var pendingGear = 0
    private var clock = 0.0
    private var sinceShift = 0.0

    fun step(dt: Double) {
        clock += dt
        cycleSec = clock % LOOP_SEC
        val leg = legs.last { it.fromSec <= cycleSec }
        val speed = speedKmh / 3.6

        drive(leg, speed, dt)
        selectGear(leg, dt)
        moveCar(speed, dt)
        turnEngine(dt)
    }

    private fun drive(leg: Leg, speed: Double, dt: Double) {
        val error = leg.targetKmh - speedKmh
        val wantedThrottle = when {
            shiftLeft > 0.0 -> 0.0
            leg.targetKmh == 0.0 -> 0.0
            else -> {
                val hold = resistance(speed) * WHEEL_RADIUS / driveRatio() / max(torqueAt(rpm), 1.0)
                (hold + error * THROTTLE_PER_KMH).coerceIn(0.0, max(leg.eagerness, hold))
            }
        }
        val pedalRate = if (wantedThrottle > throttle) PEDAL_UP_PER_S else PEDAL_DOWN_PER_S
        throttle = approach(throttle, wantedThrottle, pedalRate * dt)

        val wantedBrake = if (error < -BRAKE_MARGIN_KMH) min(-error * 0.03, 0.42) else 0.0
        val comfort = if (leg.targetKmh == 0.0 && speedKmh < 12.0) 0.3 else wantedBrake
        brake = approach(brake, comfort, BRAKE_RATE_PER_S * dt)
    }

    private fun selectGear(leg: Leg, dt: Double) {
        sinceShift += dt
        if (shiftLeft > 0.0) {
            shiftLeft -= dt
            clutch = approach(clutch, 0.0, CLUTCH_OUT_PER_S * dt)
            if (shiftLeft <= 0.0) gear = pendingGear
            return
        }
        when {
            // Démarrage : l'embrayage patine, le moteur prend des tours.
            gear == 0 && leg.targetKmh > 0.0 -> {
                gear = 1
                clutch = 0.0
            }
            gear > 0 && leg.targetKmh == 0.0 && speedKmh < NEUTRAL_BELOW_KMH -> gear = 0
            // Pied levé, on ne monte pas un rapport : on se prépare à ralentir.
            gear in 1 until RATIOS.size && throttle > 0.05 && rpm > upshiftRpm() && settled() -> beginShift(gear + 1)
            gear > 1 && rpm < DOWNSHIFT_RPM && clutch > 0.95 -> beginShift(gear - 1)
            // Rétrogradage franc pour dépasser, s'il ne fait pas sur-régimer.
            gear > 1 && throttle > 0.85 && rpm < 2600.0 && rpmInGear(gear - 1) < 4800.0 && settled() -> beginShift(gear - 1)
        }
        // Au démarrage l'embrayage patine tant que la voiture n'a pas assez de vitesse.
        val launching = gear == 1 && speedKmh < LAUNCH_END_KMH
        val engage = if (launching) CLUTCH_LAUNCH_PER_S else CLUTCH_IN_PER_S
        val fullClutch = if (launching) LAUNCH_CLUTCH else 1.0
        clutch = if (gear == 0) approach(clutch, 0.0, CLUTCH_OUT_PER_S * dt) else approach(clutch, fullClutch, engage * dt)
    }

    private fun settled() = clutch > 0.95 && sinceShift > MIN_SHIFT_GAP_SEC

    private fun beginShift(next: Int) {
        pendingGear = next
        shiftLeft = SHIFT_SEC
        sinceShift = 0.0
    }

    private fun upshiftRpm() = 2300.0 + 3400.0 * throttle * throttle

    private fun moveCar(speed: Double, dt: Double) {
        val drive = if (gear == 0) {
            0.0
        } else if (throttle > 0.02) {
            throttle * torqueAt(rpm) * driveRatio() * DRIVELINE_EFFICIENCY * clutch / WHEEL_RADIUS
        } else {
            -ENGINE_BRAKE_NM * (rpm / 3000.0) * driveRatio() * clutch / WHEEL_RADIUS
        }
        val stopping = brake * 9.81 * MASS_KG
        val force = drive - resistance(speed) - if (speed > 0.0) stopping else 0.0
        val next = (speed + force / MASS_KG * dt).coerceAtLeast(0.0)
        speedKmh = next * 3.6
    }

    private fun turnEngine(dt: Double) {
        val idle = IDLE_RPM + 9.0 * sin(clock * 2.1) + 4.0 * sin(clock * 5.3)
        val free = idle + throttle * (REDLINE_RPM - idle) * 0.85
        val tied = max(rpmInGear(gear), idle)
        var wanted = if (gear == 0) free else free + (tied - free) * clutch
        if (gear == 1 && speedKmh < LAUNCH_END_KMH && throttle > 0.02) {
            wanted = max(wanted, LAUNCH_RPM + 1200.0 * throttle)
        }
        val rate = if (wanted > rpm) RPM_UP_PER_S else RPM_DOWN_PER_S
        val glide = (wanted - rpm) * min(1.0, dt / RPM_TAU_S)
        rpm = (rpm + glide.coerceIn(-rate * dt, rate * dt)).coerceIn(idle.coerceAtMost(rpm), REDLINE_RPM)
    }

    private fun rpmInGear(g: Int): Double {
        if (g == 0) return 0.0
        return speedKmh / 3.6 * RATIOS[g - 1] * FINAL_DRIVE * 60.0 / (2.0 * PI * WHEEL_RADIUS)
    }

    private fun driveRatio() = if (gear == 0) RATIOS[0] * FINAL_DRIVE else RATIOS[gear - 1] * FINAL_DRIVE

    /** Courbe de couple d'un 1.4 atmosphérique. */
    private fun torqueAt(r: Double): Double {
        val x = ((r - 3200.0) / 3500.0).coerceIn(-1.0, 1.0)
        return 118.0 * (1.0 - 0.32 * x * x)
    }

    private fun resistance(speed: Double) =
        0.5 * 1.2 * 0.68 * speed * speed + if (speed > 0.1) 0.012 * MASS_KG * 9.81 else 0.0

    private fun approach(from: Double, to: Double, maxStep: Double): Double {
        val delta = to - from
        return if (abs(delta) <= maxStep) to else from + maxStep * if (delta > 0) 1 else -1
    }

    companion object {
        const val LOOP_SEC = 120.0
        const val IDLE_RPM = 850.0
        const val REDLINE_RPM = 6200.0
        private val RATIOS = doubleArrayOf(3.42, 1.81, 1.28, 0.98, 0.77)
        private const val FINAL_DRIVE = 3.94
        private const val WHEEL_RADIUS = 0.285
        private const val MASS_KG = 1100.0
        private const val DRIVELINE_EFFICIENCY = 0.9
        private const val ENGINE_BRAKE_NM = 16.0
        private const val THROTTLE_PER_KMH = 0.09
        private const val PEDAL_UP_PER_S = 1.6
        private const val PEDAL_DOWN_PER_S = 3.5
        private const val BRAKE_MARGIN_KMH = 4.0
        private const val BRAKE_RATE_PER_S = 1.2
        private const val SHIFT_SEC = 0.42
        private const val CLUTCH_OUT_PER_S = 9.0
        private const val CLUTCH_IN_PER_S = 3.0
        private const val CLUTCH_LAUNCH_PER_S = 0.75
        private const val LAUNCH_END_KMH = 14.0
        private const val LAUNCH_CLUTCH = 0.85
        private const val LAUNCH_RPM = 1300.0
        private const val MIN_SHIFT_GAP_SEC = 2.5
        private const val DOWNSHIFT_RPM = 1450.0
        private const val NEUTRAL_BELOW_KMH = 8.0
        private const val RPM_TAU_S = 0.09
        private const val RPM_UP_PER_S = 3800.0
        private const val RPM_DOWN_PER_S = 2600.0
    }
}
