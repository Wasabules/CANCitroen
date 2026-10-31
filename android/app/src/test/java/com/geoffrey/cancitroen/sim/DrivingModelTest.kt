package com.geoffrey.cancitroen.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DrivingModelTest {
    private val dt = 0.05

    private class Sample(val sec: Double, val rpm: Double, val speed: Double, val gear: Int)

    private fun lap(): List<Sample> {
        val model = DrivingModel()
        return List((DrivingModel.LOOP_SEC / dt).toInt()) {
            model.step(dt)
            Sample(it * dt, model.rpm, model.speedKmh, model.gear)
        }
    }

    @Test fun rpmAndSpeedAreContinuous() {
        val samples = lap()
        val worstRpm = samples.zipWithNext { a, b -> abs(b.rpm - a.rpm) }.max()
        val worstSpeed = samples.zipWithNext { a, b -> abs(b.speed - a.speed) }.max()
        assertTrue("saut de régime de $worstRpm tr/min", worstRpm <= 200.0)
        assertTrue("saut de vitesse de $worstSpeed km/h", worstSpeed <= 1.0)
    }

    @Test fun staysWithinEngineLimits() {
        for (s in lap()) {
            assertTrue("régime ${s.rpm} à ${s.sec} s", s.rpm in 800.0..DrivingModel.REDLINE_RPM)
        }
    }

    @Test fun climbsThroughAllGearsThenStops() {
        val samples = lap()
        assertEquals(5, samples.maxOf { it.gear })
        assertTrue(samples.maxOf { it.speed } > 110.0)
        assertEquals(0.0, samples.last().speed, 0.01)
        assertEquals(0, samples.last().gear)
    }

    @Test fun launchDoesNotBogDown() {
        val samples = lap().filter { it.sec in 6.0..10.0 && it.gear == 1 }
        assertTrue(samples.isNotEmpty())
        assertTrue("régime ${samples.minOf { it.rpm }} au démarrage", samples.minOf { it.rpm } > 1300.0)
    }

    @Test fun gearsDoNotHunt() {
        val samples = lap()
        val shifts = samples.zipWithNext().filter { (a, b) -> a.gear != b.gear && a.gear > 0 && b.gear > 0 }
        for ((first, second) in shifts.zipWithNext()) {
            val reversed = (first.second.gear - first.first.gear) * (second.second.gear - second.first.gear) < 0
            assertTrue(
                "hésitation à ${second.first.sec} s",
                !reversed || second.first.sec - first.first.sec >= 2.5,
            )
        }
    }

    @Test fun liftingOffDoesNotUpshift() {
        val samples = lap().filter { it.sec in 26.0..30.0 }
        for ((a, b) in samples.zipWithNext()) assertTrue("montée de rapport au freinage", b.gear <= a.gear)
    }

    @Test fun upshiftDropsTheRevsAndTheyClimbAgain() {
        val samples = lap()
        val shift = samples.indices.first { it > 0 && samples[it].gear == 2 && samples[it - 1].gear == 1 }
        val atShift = samples[shift].rpm
        val after = samples.subList(shift, shift + 20)
        assertTrue("pas de chute au passage de la 2e", after.minOf { it.rpm } < atShift)
        val fullThrottle = samples.filter { it.sec in 36.0..55.0 }
        assertTrue("pas de montée à fond", fullThrottle.maxOf { it.rpm } > 5000.0)
    }
}
