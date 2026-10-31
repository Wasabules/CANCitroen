package com.geoffrey.cancitroen.audio

import com.geoffrey.cancitroen.sim.DrivingModel
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Écrit des WAV pour écouter le moteur sans émulateur ni voiture :
 * `ENGINE_SOUND_RENDER_DIR=/chemin ./gradlew :app:testDebugUnitTest --tests '*EngineSoundRender' --rerun-tasks`
 */
class EngineSoundRender {
    private val rate = 44100

    @Test fun renderLaps() {
        val dir = System.getenv("ENGINE_SOUND_RENDER_DIR")
        assumeTrue(!dir.isNullOrEmpty())
        File(dir!!).mkdirs()
        val highRevs = EngineConfig(4, EngineLayout.INLINE, 2000, 86, 12000, ExhaustType.SPORT, revMultiplier = 2.6f)
            .toProfile("4CYL_X2_6", "Multiplicateur", "Voiture à 4000 tr/min, moteur à 9000")
        val popEngine = EngineConfig(4, EngineLayout.INLINE, 2000, 86, 7200, pops = PopLevel.STRONG)
        writeWav(File(dir, "coups_de_gaz_POP_ORIGINE.wav"), blips(popEngine.copy(exhaust = ExhaustType.STOCK)))
        writeWav(File(dir, "coups_de_gaz_POP_SPORT.wav"), blips(popEngine.copy(exhaust = ExhaustType.SPORT)))
        writeWav(File(dir, "coups_de_gaz_POP_LIGNE_DIRECTE.wav"), blips(popEngine.copy(exhaust = ExhaustType.STRAIGHT)))
        writeWav(File(dir, "coups_de_gaz_TURBO_DUMP.wav"), blips(EngineConfig(4, EngineLayout.INLINE, 2000, 82, 6800, ExhaustType.SPORT, Aspiration.TURBO)))
        writeWav(File(dir, "coups_de_gaz_TURBO_FLUTTER.wav"), blips(EngineConfig(4, EngineLayout.INLINE, 2000, 82, 6800, ExhaustType.SPORT, Aspiration.TURBO, turboRelease = TurboRelease.FLUTTER)))
        writeWav(File(dir, "coups_de_gaz_V8_POP_FORT.wav"), blips(EngineConfig(8, EngineLayout.CROSS_PLANE, 5000, 96, 6000, ExhaustType.STRAIGHT, pops = PopLevel.STRONG)))
        for (profile in EngineProfiles.ALL + highRevs) {
            writeWav(File(dir, "tour_${profile.id}.wav"), lap(profile, seconds = 60))
            if (profile === EngineProfiles.FOUR_CYL_SPORT) writeTrace(File(dir, "tour.csv"))
            for (rpm in intArrayOf(profile.idleRpm.toInt(), 2500, 5000)) {
                writeWav(File(dir, "stable_${profile.id}_$rpm.wav"), steady(profile, rpm.toFloat()))
            }
        }
    }

    /** Comme le bouton de l'app : 3 montées franches suivies d'un lever de pied. */
    private fun blips(config: EngineConfig): FloatArray {
        val profile = config.toProfile("TEST", "test", "test")
        val core = EngineSoundCore(rate)
        core.profile = profile
        core.volume = 0.7f
        val out = FloatArray(rate * 14)
        val tick = rate / 20
        val scratch = FloatArray(tick)
        var done = 0
        var step = 0
        val top = profile.idleRpm + (profile.redlineRpm - profile.idleRpm) * 0.85f
        while (done < out.size) {
            val inBlip = (step - 20).mod(80)
            core.targetRpm = when {
                step < 20 -> profile.idleRpm
                inBlip < 16 -> profile.idleRpm + (top - profile.idleRpm) * (inBlip / 16f)
                inBlip < 24 -> top
                inBlip < 54 -> (1f - (inBlip - 24) / 30f).let { profile.idleRpm + (top - profile.idleRpm) * it * it }
                else -> profile.idleRpm
            }
            val n = minOf(tick, out.size - done)
            core.render(scratch, n)
            scratch.copyInto(out, done, 0, n)
            done += n
            step++
        }
        return out
    }

    private fun steady(profile: EngineProfile, rpm: Float): FloatArray {
        val core = EngineSoundCore(rate)
        core.profile = profile
        core.volume = 0.7f
        core.targetRpm = rpm
        return FloatArray(rate * 4).also { core.render(it) }
    }

    /** Le vrai modèle de conduite pilote le son, comme le simulateur dans l'app. */
    private fun lap(profile: EngineProfile, seconds: Int): FloatArray {
        val core = EngineSoundCore(rate)
        core.profile = profile
        core.volume = 0.7f
        val model = DrivingModel()
        val out = FloatArray(rate * seconds)
        val tick = rate / 20
        var done = 0
        val scratch = FloatArray(tick)
        while (done < out.size) {
            model.step(0.05)
            core.targetRpm = profile.fromCarRpm(model.rpm.toFloat())
            val n = minOf(tick, out.size - done)
            core.render(scratch, n)
            scratch.copyInto(out, done, 0, n)
            done += n
        }
        return out
    }

    private fun writeTrace(file: File) {
        val model = DrivingModel()
        file.printWriter().use { w ->
            w.println("t,rpm,kmh,gear,throttle")
            repeat((DrivingModel.LOOP_SEC / 0.05).toInt()) {
                model.step(0.05)
                w.println("${it * 0.05},${model.rpm},${model.speedKmh},${model.gear},${model.throttle}")
            }
        }
    }

    private fun writeWav(file: File, pcm: FloatArray) {
        val bytes = ByteBuffer.allocate(44 + pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVEfmt ".toByteArray())
        bytes.putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        bytes.put("data".toByteArray()).putInt(pcm.size * 2)
        for (s in pcm) bytes.putShort((s * 32767f).toInt().coerceIn(-32768, 32767).toShort())
        file.writeBytes(bytes.array())
    }
}
