package com.geoffrey.cancitroen.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs

/**
 * Joue [EngineSoundCore] sur une piste audio. Thread dédié en priorité audio :
 * sur l'Atoto, une coroutine partagée se fait préempter et la piste se vide, ce
 * qui s'entend comme un grésillement.
 */
class EngineSoundSynth {
    companion object {
        private const val TAG = "EngineSoundSynth"
        private const val BLOCK_FRAMES = 512
        private const val MIN_BUFFER_FRAMES = 6144
        /** Silence continu au-delà duquel l'AudioTrack est mise en pause. */
        private const val SILENCE_PAUSE_MS = 1_000L
        private const val FADE_OUT_MS = 40
        private const val UNDERRUN_LOG_MS = 30_000L
    }

    @Volatile private var profile: EngineProfile = EngineProfiles.FOUR_CYL_SPORT
    @Volatile private var targetRpm: Float = 0f
    @Volatile private var manualRpm: Float? = null
    @Volatile private var volume: Float = 0.7f
    @Volatile private var running: Boolean = false
    private var thread: Thread? = null

    fun setProfile(p: EngineProfile) { profile = p }
    fun setRpm(rpm: Float) { targetRpm = rpm.coerceIn(0f, 12000f) }
    fun setVolume(v: Float) { volume = v.coerceIn(0f, 1f) }
    fun setManualRpm(rpm: Float?) { manualRpm = rpm?.coerceIn(0f, 12000f) }
    val isManualOverrideActive: Boolean get() = manualRpm != null

    fun ensureRunning() { if (!running) start() }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        thread = Thread(::playLoop, "EngineSound").also { it.start() }
    }

    /** Non bloquant : le thread termine par un fondu puis libère la piste. */
    @Synchronized
    fun stop() {
        running = false
        thread = null
    }

    private fun playLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        // Le taux natif évite le rééchantillonneur du système.
        val rate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
        val minBytes = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBytes, MIN_BUFFER_FRAMES * Float.SIZE_BYTES))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Piste audio indisponible", e)
            running = false
            return
        }

        val core = EngineSoundCore(rate)
        val block = FloatArray(BLOCK_FRAMES)
        var silentSince = 0L
        var paused = false
        var underruns = 0
        var peak = 0f
        var underrunCheck = SystemClock.uptimeMillis()
        try {
            track.play()
            Log.i(TAG, "Son moteur démarré à $rate Hz, tampon ${track.bufferSizeInFrames} trames")
            while (running) {
                core.profile = profile
                core.targetRpm = manualRpm ?: profile.fromCarRpm(targetRpm)
                core.volume = volume

                if (core.isSilent) {
                    val now = SystemClock.uptimeMillis()
                    if (silentSince == 0L) silentSince = now
                    if (!paused && now - silentSince > SILENCE_PAUSE_MS) {
                        track.pause()
                        track.flush()
                        paused = true
                    }
                    if (paused) {
                        SystemClock.sleep(100L)
                        continue
                    }
                } else {
                    silentSince = 0L
                    if (paused) {
                        track.play()
                        paused = false
                    }
                }
                core.render(block)
                track.write(block, 0, block.size, AudioTrack.WRITE_BLOCKING)
                for (sample in block) peak = maxOf(peak, abs(sample))

                val now = SystemClock.uptimeMillis()
                if (now - underrunCheck > UNDERRUN_LOG_MS) {
                    underrunCheck = now
                    Log.i(TAG, "Niveau généré : pic ${"%.3f".format(peak)}, régime ${core.targetRpm.toInt()} tr/min")
                    peak = 0f
                    val total = track.underrunCount
                    if (total != underruns) Log.w(TAG, "Piste vidée ${total - underruns} fois en 30 s : grésillement probable")
                    underruns = total
                }
            }
            if (!paused) {
                core.volume = 0f
                val fadeBlocks = FADE_OUT_MS * rate / 1000 / BLOCK_FRAMES + 1
                repeat(fadeBlocks) {
                    core.render(block)
                    track.write(block, 0, block.size, AudioTrack.WRITE_BLOCKING)
                }
            }
            track.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Piste audio interrompue", e)
        } finally {
            track.release()
        }
    }
}
