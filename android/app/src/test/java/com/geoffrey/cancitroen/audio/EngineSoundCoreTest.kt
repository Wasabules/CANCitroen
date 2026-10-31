package com.geoffrey.cancitroen.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class EngineSoundCoreTest {
    private val rate = 44100

    /** Régime tenu pendant [seconds], rendu d'un bloc après démarrage du moteur. */
    private fun steady(profile: EngineProfile, rpm: Float, seconds: Float = 3f, volume: Float = 0.7f): FloatArray {
        val core = EngineSoundCore(rate)
        core.profile = profile
        core.volume = volume
        core.targetRpm = rpm
        val out = FloatArray((seconds * rate).toInt())
        core.render(out)
        return out.copyOfRange(rate / 2, out.size)
    }

    /** Montée puis descente, avec un régime actualisé à 25 Hz et arrondi comme sur le bus. */
    private fun sweep(profile: EngineProfile, block: Int = 1024, volume: Float = 1f): FloatArray {
        val core = EngineSoundCore(rate)
        core.profile = profile
        core.volume = volume
        val out = FloatArray(rate * 10)
        val scratch = FloatArray(block)
        var done = 0
        val perUpdate = rate / 25
        while (done < out.size) {
            if (done % perUpdate == 0) {
                val t = done.toFloat() / out.size
                val shape = if (t < 0.5f) t * 2f else (1f - t) * 2f
                val raw = profile.idleRpm + (profile.redlineRpm - profile.idleRpm) * shape
                core.targetRpm = (raw / 8f).toInt() * 8f
            }
            val n = minOf(perUpdate - done % perUpdate, block, out.size - done)
            core.render(scratch, n)
            scratch.copyInto(out, done, 0, n)
            done += n
        }
        return out
    }

    private fun rms(x: FloatArray) = sqrt(x.sumOf { (it * it).toDouble() } / x.size)

    private fun powerAt(x: FloatArray, hz: Double): Double {
        var re = 0.0
        var im = 0.0
        for (i in x.indices) {
            val a = 2.0 * PI * hz * i / rate
            val w = 0.5 - 0.5 * cos(2.0 * PI * i / x.size)
            re += x[i] * w * cos(a)
            im += x[i] * w * sin(a)
        }
        return re * re + im * im
    }

    /** Énergie au-dessus de [hz], par différences répétées, en dB sous le signal. */
    private fun highBandDb(x: FloatArray, hz: Double): Double {
        val total = x.sumOf { (it * it).toDouble() }
        // Bande de fréquences testée : des raies réparties, assez pour détecter un repliement.
        val band = (0 until 24).sumOf { powerAt(x.copyOf(8192), hz + it * 400.0) }
        val full = (0 until 240).sumOf { powerAt(x.copyOf(8192), 40.0 + it * 90.0) }
        return if (total == 0.0) -200.0 else 10 * log10(band / full)
    }

    @Test fun outputDoesNotDependOnBlockSize() {
        val a = sweep(EngineProfiles.FOUR_CYL_SPORT, block = 64)
        val b = sweep(EngineProfiles.FOUR_CYL_SPORT, block = 1024)
        assertArrayEquals(a, b, 0f)
    }

    @Test fun neverClipsAtFullVolume() {
        for (p in EngineProfiles.ALL) {
            val peak = sweep(p).maxOf { abs(it) }
            assertTrue("${p.id} : pic $peak", peak < 0.99f)
        }
    }

    @Test fun noClickWhileRevsMove() {
        for (preset in EngineProfiles.ALL) {
            // Détonations et décharge du turbo sont voulues : on cherche ici les clics parasites.
            val p = preset.config?.copy(pops = PopLevel.OFF, aspiration = Aspiration.NATURAL)?.toProfile(preset.id, preset.name, preset.description) ?: preset
            // Un clic est un saut hors de proportion avec le signal qui l'entoure.
            val jumps = sweep(p).toList().zipWithNext { a, b -> abs(b - a) }.sorted()
            val worst = jumps.last()
            val usual = jumps[(jumps.size * 0.99).toInt()]
            assertTrue("${p.id} : saut de $worst pour $usual d'habitude", worst < usual * 6f)
            assertTrue("${p.id} : saut de $worst entre deux échantillons", worst < 0.55f)
        }
    }

    @Test fun profilesPlayAtComparableLevels() {
        val levels = EngineProfiles.ALL.associate { it.id to rms(steady(it, 3000f)) }
        for ((id, level) in levels) {
            assertTrue("$id : niveau $level", level in 0.06..0.45)
        }
    }

    @Test fun limiterStaysOutOfTheWayAtNormalVolume() {
        for (p in EngineProfiles.ALL) {
            val x = steady(p, 3000f, volume = 0.6f)
            assertTrue("${p.id} écrêté", x.maxOf { abs(it) } < 0.9f)
        }
    }

    @Test fun firingRateIsAudible() {
        for (p in listOf(EngineProfiles.FOUR_CYL_SPORT, EngineProfiles.V6_GT, EngineProfiles.DIESEL_4)) {
            val rpm = 3000f
            val firingHz = rpm / 120.0 * p.firing.size
            val x = steady(p, rpm)
            val on = powerAt(x, firingHz)
            val off = powerAt(x, firingHz * 1.5)
            assertTrue("${p.id} : ${10 * log10(on / off)} dB", on > off * 4)
        }
    }

    /** Sans explosions il ne reste que le fondamental, une sinusoïde sans vie. */
    @Test fun keepsFiringOverTime() {
        for (p in EngineProfiles.ALL.filter { !it.electric }) {
            val rpm = 3000f
            val firingHz = rpm / 120.0 * p.firing.size
            val x = steady(p, rpm, seconds = 12f)
            val last = x.copyOfRange(x.size - rate, x.size)
            val harmonics = (3..6).sumOf { powerAt(last, firingHz * it) }
            val db = 10 * log10(harmonics / powerAt(last, firingHz))
            assertTrue("${p.id} : harmoniques à $db dB", db > -30.0)
        }
    }

    /** Autocorrélation maximale sur les périodes de 15 à 400 Hz, signal vu au-dessus de ~500 Hz. */
    private fun highBandPeriodicity(x: FloatArray): Double {
        val d = FloatArray(x.size) { if (it < 2) 0f else x[it] - 2f * x[it - 1] + x[it - 2] }
        val n = minOf(d.size, rate) - rate / 15
        val energy = (0 until n).sumOf { (d[it] * d[it]).toDouble() }
        var best = 0.0
        for (lag in rate / 400..rate / 15) {
            var sum = 0.0
            for (i in 0 until n) sum += d[i] * d[i + lag]
            best = max(best, sum / energy)
        }
        return best
    }

    /**
     * Le défaut « moustique » : des raies trop pures en haut du spectre. De vraies
     * voitures mesurées en charge donnent 0,25 à 0,35 sur ce critère (voir l'architecture) ;
     * l'ancien modèle à raies donnait 0,5 à 0,7.
     */
    @Test fun doesNotWhineLikeAMosquito() {
        for (p in EngineProfiles.ALL.filter { !it.electric }) {
            val x = steady(p, p.idleRpm + (p.redlineRpm - p.idleRpm) * 0.5f, seconds = 2.5f)
            val periodicity = highBandPeriodicity(x)
            assertTrue("${p.id} : périodicité $periodicity", periodicity < 0.45)
        }
    }

    /** Deux explosions de suite ne sont jamais identiques. */
    @Test fun noTwoCyclesAreIdentical() {
        val p = EngineProfiles.CITROEN_C2
        val x = steady(p, 2000f, seconds = 3f)
        val cycle = rate * 120 / 2000
        val a = x.copyOfRange(rate, rate + cycle)
        val b = x.copyOfRange(rate + cycle, rate + 2 * cycle)
        val difference = FloatArray(cycle) { a[it] - b[it] }
        assertTrue("trop identiques : ${rms(difference) / rms(a)}", rms(difference) > rms(a) * 0.2)
    }

    /** Le défaut « étouffé » : il faut de l'énergie jusque dans les aigus, comme en réel. */
    @Test fun keepsItsCrispInTheHighs() {
        for (p in EngineProfiles.ALL.filter { !it.electric }) {
            val x = steady(p, p.idleRpm + (p.redlineRpm - p.idleRpm) * 0.6f)
            val d = FloatArray(x.size) { if (it < 3) 0f else x[it] - 3f * x[it - 1] + 3f * x[it - 2] - x[it - 3] }
            // Pente de +18 dB/octave : rapport aigus/ensemble sans FFT.
            val db = 20 * log10(rms(d) / rms(x))
            assertTrue("${p.id} : aigus à $db dB", db > -28.0)
        }
    }

    @Test fun irregularEnginesHaveSubHarmonics() {
        val rpm = 2000f
        val irregular = steady(EngineProfiles.V8_MUSCLE, rpm)
        val cycleHz = rpm / 120.0
        // Entre les multiples de la fréquence d'allumage : le « glouglou » du cross-plane.
        val between = (2..7).maxOf { powerAt(irregular, cycleHz * it) } / powerAt(irregular, cycleHz * 8)
        assertTrue("V8 trop régulier : $between", between > 0.01)
    }

    @Test fun noEnergyFoldsIntoTheTopOfTheSpectrum() {
        for (p in EngineProfiles.ALL) {
            val x = steady(p, p.redlineRpm)
            val db = highBandDb(x, 14000.0)
            assertTrue("${p.id} : $db dB au-dessus de 14 kHz", db < -25.0)
        }
    }

    @Test fun engineOffIsSilentAfterAFade() {
        val core = EngineSoundCore(rate)
        core.volume = 0.8f
        core.targetRpm = 3000f
        val running = FloatArray(rate)
        core.render(running)
        core.targetRpm = 0f
        val fading = FloatArray(rate)
        core.render(fading)
        assertTrue(core.isSilent)
        val worst = fading.toList().zipWithNext { a, b -> abs(b - a) }.max()
        assertTrue("clic à la coupure : $worst", worst < 0.2f)
        assertEquals(0f, fading.takeLast(100).maxOf { abs(it) }, 1e-3f)
    }

    /** Contact mis moteur arrêté, ou calage puis redémarrage : le son doit reprendre. */
    @Test fun restartsAfterTheEngineWasOff() {
        val core = EngineSoundCore(48000)
        core.volume = 0.7f
        val idle = FloatArray(48000 * 2)
        core.targetRpm = 0f
        core.render(idle)
        assertTrue(core.isSilent)
        assertEquals(0f, idle.maxOf { abs(it) }, 0f)
        core.targetRpm = 2000f
        assertTrue("toujours muet pour la piste", !core.isSilent)
        val running = FloatArray(48000)
        core.render(running)
        assertTrue("reste muet : ${rms(running)}", rms(running) > 0.05)
    }

    @Test fun switchingProfileDoesNotClick() {
        val core = EngineSoundCore(rate)
        core.volume = 0.8f
        core.targetRpm = 3000f
        val first = FloatArray(rate)
        core.render(first)
        core.profile = EngineProfiles.V8_MUSCLE
        val switching = FloatArray(rate)
        core.render(switching)
        val worst = switching.toList().zipWithNext { a, b -> abs(b - a) }.max()
        assertTrue("clic au changement : $worst", worst < 0.2f)
        assertTrue("reste muet", switching.takeLast(rate / 4).maxOf { abs(it) } > 0.01f)
    }

    /** Le taux natif de la sortie audio, utilisé sur l'appareil. */
    @Test fun playsAtTheNativeRate() {
        for (p in EngineProfiles.ALL) {
            val core = EngineSoundCore(48000)
            core.profile = p
            core.volume = 0.7f
            core.targetRpm = 3000f
            val out = FloatArray(48000 * 3)
            core.render(out)
            val played = out.copyOfRange(24000, out.size)
            assertTrue("${p.id} : valeur invalide", played.none { it.isNaN() })
            assertTrue("${p.id} : pic ${played.maxOf { abs(it) }}", played.maxOf { abs(it) } < 0.99f)
            assertTrue("${p.id} : niveau ${rms(played)}", rms(played) > 0.05)
        }
    }

    @Test fun cheapEnoughForTheHeadUnit() {
        val core = EngineSoundCore(rate)
        core.targetRpm = 4000f
        val out = FloatArray(rate * 10)
        val started = System.nanoTime()
        core.render(out)
        val seconds = (System.nanoTime() - started) / 1e9
        assertTrue("$seconds s pour 10 s de son", seconds < 2.0)
        println("Coût : ${"%.1f".format(max(seconds, 0.0) / 10.0 * 100)} % d'un cœur")
    }
}
