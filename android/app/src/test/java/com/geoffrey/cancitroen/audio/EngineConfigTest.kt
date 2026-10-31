package com.geoffrey.cancitroen.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class EngineConfigTest {
    private val base = EngineConfig()

    private fun profile(c: EngineConfig) = c.toProfile("TEST", "test", "test")

    private fun rms(x: FloatArray) = sqrt(x.sumOf { (it * it).toDouble() } / x.size)

    /** Part du son au-dessus de [hz], mesurée après un filtre passe-haut rudimentaire. */
    private fun brightness(c: EngineConfig): Double {
        val core = EngineSoundCore(44100)
        core.profile = profile(c)
        core.volume = 0.7f
        core.targetRpm = 3000f
        val x = FloatArray(44100 * 2).also { core.render(it) }.copyOfRange(22050, 88200)
        val diff = FloatArray(x.size - 1) { x[it + 1] - x[it] }
        return rms(diff) / rms(x)
    }

    @Test fun strokeFollowsDisplacementAndBore() {
        val c = EngineConfig(cylinders = 4, displacementCc = 2000, boreMm = 84)
        assertEquals(90.2f, c.strokeMm, 0.1f)
    }

    @Test fun biggerCylindersSoundDeeper() {
        assertTrue(profile(base.copy(displacementCc = 6000)).strokeM > profile(base.copy(displacementCc = 1000)).strokeM)
        assertTrue(brightness(base.copy(displacementCc = 6000)) < brightness(base.copy(displacementCc = 1000)))
    }

    @Test fun freerExhaustIsBrighter() {
        assertTrue(brightness(base.copy(exhaust = ExhaustType.STRAIGHT)) > brightness(base))
    }

    @Test fun cylinderCountSetsTheFiringRate() {
        for (n in EngineConfig.CYLINDER_CHOICES) {
            val p = profile(base.copy(cylinders = n))
            assertEquals(n, p.firing.size)
            assertEquals(n, p.firingGain.size)
        }
    }

    /** Toute combinaison proposée à l'écran doit produire un son valide. */
    @Test fun everyCombinationPlaysCleanly() {
        for (n in EngineConfig.CYLINDER_CHOICES) {
            for (layout in EngineLayout.entries) {
                for (exhaust in ExhaustType.entries) {
                    for (aspiration in Aspiration.entries) {
                        for (fuel in EngineFuel.entries) {
                            val c = EngineConfig(n, layout, 600 + n * 400, 60 + n * 4, 6000, exhaust, aspiration, fuel)
                            val p = profile(c)
                            for (i in 1 until n) assertTrue("$c : allumages non triés", p.firing[i] > p.firing[i - 1])
                            assertTrue(p.firing.last() < 1f)
                            val core = EngineSoundCore(44100)
                            core.profile = p
                            core.volume = 1f
                            core.targetRpm = 1500f
                            val out = FloatArray(8192)
                            repeat(3) { step ->
                                core.targetRpm = 1500f + step * 2000f
                                core.render(out)
                            }
                            assertTrue("$c : invalide", out.none { it.isNaN() })
                            assertTrue("$c : pic ${out.maxOf { abs(it) }}", out.maxOf { abs(it) } < 0.99f)
                            assertTrue("$c : muet", rms(out) > 0.01)
                        }
                    }
                }
            }
        }
    }

    @Test fun turboOnlyWhistlesUnderLoad() {
        fun whistleBand(load: Boolean): Double {
            val core = EngineSoundCore(44100)
            core.profile = profile(base.copy(aspiration = Aspiration.TURBO))
            core.volume = 0.7f
            core.targetRpm = 2000f
            val idle = FloatArray(44100)
            core.render(idle)
            val out = FloatArray(44100 * 2)
            if (load) {
                var done = 0
                val tick = FloatArray(441)
                while (done < out.size) {
                    core.targetRpm = 2000f + done * 0.06f
                    core.render(tick)
                    tick.copyInto(out, done, 0, minOf(441, out.size - done))
                    done += 441
                }
            } else {
                core.render(out)
            }
            val diff = FloatArray(out.size - 1) { out[it + 1] - out[it] }
            return rms(diff)
        }
        assertTrue(whistleBand(load = true) > whistleBand(load = false))
    }

    @Test fun autoMultiplierCoversTheEngineRange() {
        val diesel = EngineProfiles.DIESEL_4
        assertEquals(diesel.idleRpm, diesel.fromCarRpm(850f), 0.01f)
        assertTrue(diesel.fromCarRpm(6200f) in (diesel.redlineRpm * 0.93f)..diesel.redlineRpm)
        assertEquals(0f, diesel.fromCarRpm(0f), 0f)
    }

    /** L'exemple demandé : une voiture à 4000 tr/min qui sonne comme un moteur à 9000. */
    @Test fun multiplierTakes4000To9000() {
        val p = profile(EngineConfig(redlineRpm = 12000, revMultiplier = 2.6f))
        assertEquals(9000f, p.fromCarRpm(4000f), 200f)
    }

    /** Aucun accroc : ni saut, ni changement brutal de pente, ni dépassement du maximum. */
    @Test fun revCurveHasNoKink() {
        for (multiplier in floatArrayOf(0.5f, 1f, 2.6f, 4f)) {
            val p = profile(EngineConfig(redlineRpm = 9000, revMultiplier = multiplier))
            val curve = (850..7000 step 5).map { p.fromCarRpm(it.toFloat()) }
            val slopes = curve.zipWithNext { a, b -> b - a }
            assertTrue("×$multiplier recule", slopes.all { it >= 0f })
            assertTrue("×$multiplier dépasse", curve.max() <= 9000f)
            val worst = slopes.zipWithNext { a, b -> abs(b - a) }.max()
            assertTrue("×$multiplier : pente qui casse de $worst", worst < 0.05f + multiplier * 5f * 0.02f)
        }
    }

    /** Le régime arrive par marches à 25 Hz : le son ne doit pas les faire entendre. */
    @Test fun steppedRevsStaySmoothWithAHighMultiplier() {
        val p = profile(EngineConfig(redlineRpm = 12000, revMultiplier = 3f))
        val core = EngineSoundCore(44100)
        core.profile = p
        core.volume = 0.7f
        val out = FloatArray(44100 * 4)
        val tick = 44100 / 25
        var done = 0
        val scratch = FloatArray(tick)
        while (done < out.size) {
            val car = 1000f + 3000f * done / out.size
            core.targetRpm = p.fromCarRpm((car / 8f).toInt() * 8f)
            val n = minOf(tick, out.size - done)
            core.render(scratch, n)
            scratch.copyInto(out, done, 0, n)
            done += n
        }
        val jumps = out.toList().zipWithNext { a, b -> abs(b - a) }.sorted()
        assertTrue("saut anormal", jumps.last() < jumps[(jumps.size * 0.99).toInt()] * 6f)
    }

    /** Monte, tient, puis lève le pied d'un coup. Renvoie (pied sur l'accélérateur, lever de pied). */
    private fun blip(c: EngineConfig): Pair<FloatArray, FloatArray> {
        val p = profile(c)
        val core = EngineSoundCore(44100)
        core.profile = p
        core.volume = 0.7f
        val tick = FloatArray(2205)
        fun play(seconds: Float, rpm: (Float) -> Float): FloatArray {
            val out = FloatArray((seconds * 44100).toInt())
            var done = 0
            while (done < out.size) {
                core.targetRpm = rpm(done.toFloat() / out.size)
                val n = minOf(2205, out.size - done)
                core.render(tick, n)
                tick.copyInto(out, done, 0, n)
                done += n
            }
            return out
        }
        play(1f) { p.idleRpm }
        val top = p.idleRpm + (p.redlineRpm - p.idleRpm) * 0.85f
        val up = play(1.5f) { p.idleRpm + (top - p.idleRpm) * it }
        val lift = play(1.5f) { t -> p.idleRpm + (top - p.idleRpm) * (1f - t) * (1f - t) }
        return up to lift
    }

    @Test fun popsOnlyHappenOnLiftOff() {
        val quiet = EngineConfig(pops = PopLevel.OFF, exhaust = ExhaustType.STRAIGHT)
        val loud = quiet.copy(pops = PopLevel.STRONG)
        val (upLoud, liftLoud) = blip(loud)
        val (upQuiet, liftQuiet) = blip(quiet)
        // Une fois le moteur redescendu, seules les détonations dépassent.
        val after = { x: FloatArray -> x.copyOfRange(x.size / 4, x.size * 3 / 4) }
        assertTrue("détonations absentes : ${peak(after(liftLoud))} / ${peak(after(liftQuiet))}", peak(after(liftLoud)) > peak(after(liftQuiet)) * 1.5f)
        assertEquals("sous charge, le réglage ne doit rien changer", rms(upQuiet), rms(upLoud), rms(upQuiet) * 0.05)
    }

    /** Autour de la plus forte détonation : (crête, brillance) sur 40 ms. */
    private fun loudestPop(c: EngineConfig): Pair<Float, Double> {
        val lift = blip(c).second
        val at = lift.indices.maxByOrNull { abs(lift[it]) }!!
        val from = (at - 882).coerceAtLeast(0)
        val window = lift.copyOfRange(from, (from + 1764).coerceAtMost(lift.size))
        val slope = FloatArray(window.size - 1) { window[it + 1] - window[it] }
        return window.maxOf { abs(it) } to rms(slope) / rms(window)
    }

    /** Le défaut « caillou » : un claquement sec, sans corps, donc presque tout dans les aigus. */
    @Test fun popsHaveBodyInsteadOfClicking() {
        for (exhaust in ExhaustType.entries) {
            val (_, brightness) = loudestPop(EngineConfig(exhaust = exhaust, pops = PopLevel.STRONG))
            assertTrue("$exhaust : brillance $brightness", brightness < 0.35)
        }
    }

    @Test fun freerExhaustPopsHarder() {
        val engine = EngineConfig(pops = PopLevel.STRONG)
        val stock = loudestPop(engine.copy(exhaust = ExhaustType.STOCK))
        val straight = loudestPop(engine.copy(exhaust = ExhaustType.STRAIGHT))
        assertTrue("crêtes ${stock.first} / ${straight.first}", straight.first > stock.first)
    }

    @Test fun exhaustAndEngineShapeThePop() {
        val big = profile(EngineConfig(8, EngineLayout.V, 6000, 100, 6000))
        val small = profile(EngineConfig(3, EngineLayout.INLINE, 1000, 72, 6000))
        assertTrue(big.popBodyHz < small.popBodyHz)
        assertTrue(big.popForce > small.popForce)
        assertTrue(profile(base.copy(aspiration = Aspiration.TURBO)).popForce < profile(base).popForce)
        assertEquals(PopLevel.OFF, profile(base.copy(fuel = EngineFuel.DIESEL, pops = PopLevel.STRONG)).pops)
    }

    @Test fun noPopsAtIdle() {
        val core = EngineSoundCore(44100)
        core.profile = profile(EngineConfig(pops = PopLevel.STRONG, exhaust = ExhaustType.STRAIGHT))
        core.volume = 0.7f
        core.targetRpm = core.profile.idleRpm
        val out = FloatArray(44100 * 4).also { core.render(it) }.copyOfRange(44100, 44100 * 4)
        val reference = EngineSoundCore(44100).also {
            it.profile = profile(EngineConfig(pops = PopLevel.OFF, exhaust = ExhaustType.STRAIGHT))
            it.volume = 0.7f
            it.targetRpm = it.profile.idleRpm
        }
        val silent = FloatArray(44100 * 4).also { reference.render(it) }.copyOfRange(44100, 44100 * 4)
        assertArrayEquals(silent, out, 0f)
    }

    @Test fun turboReleasesPressureOnLiftOff() {
        val atmo = base.copy(exhaust = ExhaustType.SPORT)
        val dump = atmo.copy(aspiration = Aspiration.TURBO)
        val flutter = dump.copy(turboRelease = TurboRelease.FLUTTER)
        val lift = { c: EngineConfig -> blip(c).second.let { it.copyOfRange(0, it.size / 2) } }
        val hiss = { x: FloatArray -> val d = FloatArray(x.size - 1) { x[it + 1] - x[it] }; rms(d) }
        assertTrue(hiss(lift(dump)) > hiss(lift(atmo)) * 1.2)
        assertTrue(hiss(lift(flutter)) > hiss(lift(atmo)) * 1.1)
    }

    @Test fun eventsNeverClip() {
        for (c in listOf(
            EngineConfig(exhaust = ExhaustType.STRAIGHT, pops = PopLevel.STRONG),
            EngineConfig(8, EngineLayout.CROSS_PLANE, 5000, 96, 6000, ExhaustType.STRAIGHT, Aspiration.TURBO, pops = PopLevel.STRONG),
            EngineConfig(aspiration = Aspiration.TURBO, turboRelease = TurboRelease.FLUTTER, pops = PopLevel.STRONG),
        )) {
            val (up, lift) = blip(c)
            assertTrue("$c : ${peak(up)} / ${peak(lift)}", peak(up) < 0.99f && peak(lift) < 0.99f)
            assertTrue("$c : invalide", lift.none { it.isNaN() })
        }
    }

    private fun peak(x: FloatArray) = x.maxOf { abs(it) }

    @Test fun sameConfigKeepsTheSameProfile() {
        val a = EngineProfiles.resolve(EngineProfiles.CUSTOM_ID, base)
        assertSame(a, EngineProfiles.resolve(EngineProfiles.CUSTOM_ID, base.copy()))
        assertNotEquals(a, EngineProfiles.resolve(EngineProfiles.CUSTOM_ID, base.copy(boreMm = 70)))
    }
}
