package com.geoffrey.cancitroen.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthesizer audio temps réel pour simuler un bruit de moteur.
 *
 *  Deux modes :
 *   1. **Sample-based** (par défaut si les WAV sont présents dans assets/) :
 *      3 samples par profil (idle/mid/high) chargés au démarrage, lus en boucle
 *      avec pitch shift par interpolation, crossfade selon RPM.
 *   2. **Synthesizer procédural** (fallback) : oscillateur sinus/sawtooth +
 *      harmoniques + filtre passe-bas. Sonne moins bien mais marche sans assets.
 *
 *  Le mode est choisi automatiquement à init : si les WAV sont introuvables, on
 *  bascule en synthèse procédurale et on log un avertissement.
 */
class EngineSoundSynth(private val context: Context? = null) {
    companion object {
        private const val TAG = "EngineSoundSynth"
        private const val SAMPLE_RATE = 44100
        private const val BUFFER_FRAMES = 1024
        /** Silence continu au-delà duquel l'AudioTrack est mise en pause. */
        private const val SILENCE_PAUSE_MS = 1_000L

        /** Chemins assets pour les samples 4 cyl essence (CC0). */
        private const val SAMPLE_4CYL_IDLE = "engines/4cyl_idle.wav"
        private const val SAMPLE_4CYL_MID  = "engines/4cyl_mid.wav"
        private const val SAMPLE_4CYL_HIGH = "engines/4cyl_high.wav"
    }

    private var track: AudioTrack? = null
    private var job: Job? = null

    @Volatile private var profile: EngineProfile = EngineProfiles.FOUR_CYL_SPORT
    @Volatile private var targetRpm: Float = 800f
    @Volatile private var manualRpm: Float? = null
    @Volatile private var smoothRpm: Float = 800f
    @Volatile private var volume: Float = 0.7f
    @Volatile private var running: Boolean = false

    // ── Mode samples ──
    // @Volatile : lus par le thread audio (fillSamples), écrits par audioScope.
    @Volatile private var sampleIdle: LoopedSamplePlayer? = null
    @Volatile private var sampleMid: LoopedSamplePlayer? = null
    @Volatile private var sampleHigh: LoopedSamplePlayer? = null
    @Volatile private var samplesLoadAttempted: Boolean = false
    private val loadMutex = Mutex()
    private val samplesAvailable: Boolean
        get() = sampleIdle != null && sampleMid != null && sampleHigh != null

    // ── Mode procédural (fallback) ──
    private var lowpassY = 0f
    private var phase = 0.0
    private var phaseSecond = 0.0

    // ── Filtres post-traitement (mode samples) ──
    /** High-pass 1-pôle pour virer les rumbles < 50 Hz. */
    private var hpPrev = 0f
    private var hpInPrev = 0f

    /**
     * Charge les WAV + estime leur fréquence fondamentale (autocorr) en
     * arrière-plan. À appeler une fois depuis App.onCreate sur audioScope —
     * pas dans le init du synth (l'autocorr est synchrone ~150 ms par sample
     * et bloquait le boot, donc le watchdog SYU Atoto).
     *
     * Idempotent : appels multiples ne re-chargent pas. Tant que le chargement
     * n'a pas terminé, le synth tourne en fallback procédural.
     */
    fun loadSamplesAsync(scope: CoroutineScope) {
        if (context == null) return
        scope.launch(Dispatchers.IO) {
            loadMutex.withLock {
                if (samplesLoadAttempted) return@withLock
                samplesLoadAttempted = true
                sampleIdle = loadAndAnalyze(context, SAMPLE_4CYL_IDLE, "idle")
                sampleMid = loadAndAnalyze(context, SAMPLE_4CYL_MID, "mid")
                sampleHigh = loadAndAnalyze(context, SAMPLE_4CYL_HIGH, "high")
                if (samplesAvailable) {
                    Log.i(TAG, "✓ Mode SAMPLES (3 WAV chargés, freqs auto-détectées)")
                } else {
                    Log.w(TAG, "✗ Samples manquants → mode procédural (fallback). " +
                        "Place les WAV dans app/src/main/assets/engines/ pour activer.")
                }
            }
        }
    }

    /**
     * Charge un WAV et estime sa fréquence fondamentale par autocorrélation,
     * de sorte que le pitch shifting parte d'une valeur réelle (pas hardcodée).
     */
    private fun loadAndAnalyze(context: Context, path: String, label: String): LoopedSamplePlayer? {
        val loaded = WavLoader.loadFromAssets(context, path) ?: return null
        val freq = PitchEstimator.estimateFundamentalHz(loaded.pcm, loaded.sampleRate)
        Log.i(TAG, "Sample $label : ${loaded.pcm.size} frames @ ${loaded.sampleRate} Hz → " +
            "fondamental détecté ${"%.1f".format(freq)} Hz (~${"%.0f".format(freq * 60 / 2)} RPM 4 cyl)")
        return LoopedSamplePlayer(loaded, freq)
    }

    fun setProfile(p: EngineProfile) { profile = p }
    fun setRpm(rpm: Float) { targetRpm = rpm.coerceIn(0f, 12000f) }
    fun setVolume(v: Float) { volume = v.coerceIn(0f, 1f) }
    fun setManualRpm(rpm: Float?) { manualRpm = rpm?.coerceIn(0f, 12000f) }
    val isManualOverrideActive: Boolean get() = manualRpm != null

    fun ensureRunning(scope: CoroutineScope) { if (!running) start(scope) }

    fun start(scope: CoroutineScope) {
        if (running) return
        running = true

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufBytes = maxOf(minBuf, BUFFER_FRAMES * 2 * 4)
        val localTrack = AudioTrack(
            audioAttrs, format, bufBytes,
            AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE,
        ).also { it.play() }
        track = localTrack

        Log.i(TAG, "▶ Engine synth started (${if (samplesAvailable) "SAMPLES" else "PROCEDURAL"})")

        // On capture `localTrack` ici : si stop() set `track = null` et
        // appelle Thread { release() } pendant qu'on écrit, le track champ
        // pourrait pointer sur une AudioTrack en cours de release →
        // IllegalStateException JNI. Le local référence le track jusqu'à la
        // sortie de boucle, où il sera collecté quand running passe false.
        job = scope.launch(Dispatchers.Default) {
            val buffer = ShortArray(BUFFER_FRAMES)
            val mixBuffer = FloatArray(BUFFER_FRAMES)
            // Moteur arrêté (ou volume nul) depuis SILENCE_PAUSE_MS → AudioTrack
            // en pause au lieu d'écrire du silence en continu : la sortie audio
            // peut se mettre en veille. Reprise dès que le moteur tourne.
            var silentSince = 0L
            var paused = false
            while (isActive && running) {
                if (isSilent()) {
                    val now = SystemClock.uptimeMillis()
                    if (silentSince == 0L) silentSince = now
                    if (!paused && now - silentSince > SILENCE_PAUSE_MS) {
                        try { localTrack.pause(); localTrack.flush() } catch (_: Exception) { break }
                        paused = true
                    }
                    if (paused) { delay(100L); continue }
                } else {
                    silentSince = 0L
                    if (paused) {
                        try { localTrack.play() } catch (_: Exception) { break }
                        paused = false
                    }
                }
                fillBuffer(buffer, mixBuffer)
                try { localTrack.write(buffer, 0, buffer.size) } catch (_: Exception) { break }
            }
        }
    }

    /**
     * Stop non-bloquant : marque running=false (la boucle sort au prochain
     * write), annule le job, libère l'AudioTrack en async. Pas de cancelAndJoin
     * pour éviter ANR si on est appelé depuis le main thread.
     */
    fun stop() {
        running = false
        job?.cancel()
        job = null
        // Release async pour ne pas bloquer si write est en cours
        val toRelease = track
        track = null
        Thread {
            try {
                toRelease?.stop()
                toRelease?.release()
            } catch (_: Exception) {}
        }.start()
    }

    /** Même seuil que fillSamples/fillProcedural, qui écrivent alors des zéros. */
    private fun isSilent(): Boolean {
        val target = manualRpm ?: targetRpm
        return volume == 0f || (target < 100f && smoothRpm < 100f)
    }

    private fun fillBuffer(out: ShortArray, mix: FloatArray) {
        if (samplesAvailable) fillSamples(out, mix) else fillProcedural(out)
    }

    /** Remplit le buffer en mode samples : crossfade idle/mid/high selon RPM. */
    private fun fillSamples(out: ShortArray, mix: FloatArray) {
        val p = profile

        // Smooth RPM
        val rpmAlpha = 0.05f
        val effectiveTarget = manualRpm ?: targetRpm
        val rpm = run {
            for (i in out.indices) smoothRpm += (effectiveTarget - smoothRpm) * rpmAlpha
            smoothRpm
        }

        // Moteur à l'arrêt → buffer à zéro (pas de bourdon résiduel)
        if (rpm < 100f) {
            for (i in out.indices) out[i] = 0
            return
        }
        val rpmClamped = rpm.coerceAtLeast(50f)

        // Crossfade basé sur la fréquence de combustion : on choisit le sample
        // dont la baseFreq est la plus proche de la cible. Évite les pitch shifts
        // extrêmes (chipmunk effect) qui foiraient avec un crossfade par RPM.
        val baseFreq = (rpmClamped / 60f) * (p.cylinders / 2f)
        val freqs = listOfNotNull(
            sampleIdle?.baseCombustionHz,
            sampleMid?.baseCombustionHz,
            sampleHigh?.baseCombustionHz,
        ).sorted()
        val (gIdle, gMid, gHigh) = freqGains(baseFreq, freqs)

        val rpmFrac = ((rpmClamped - p.idleRpm) / (p.redlineRpm - p.idleRpm)).coerceIn(0f, 1f)
        val rpmVol = 0.4f + 0.6f * rpmFrac
        val masterGain = volume * rpmVol

        // Reset mix buffer
        for (i in mix.indices) mix[i] = 0f

        sampleIdle?.read(baseFreq, SAMPLE_RATE, mix, gIdle * masterGain)
        sampleMid?.read(baseFreq, SAMPLE_RATE, mix, gMid * masterGain)
        sampleHigh?.read(baseFreq, SAMPLE_RATE, mix, gHigh * masterGain)

        // ── Post-traitement : HP filter + soft saturation ──
        // High-pass à ~50 Hz : élimine rumbles & DC
        val hpAlpha = 0.99f   // ~50 Hz à 44.1 kHz : alpha = 1/(1 + 2π × fc / sr) ≈ 0.99
        for (i in mix.indices) {
            val x = mix[i]
            val y = hpAlpha * (hpPrev + x - hpInPrev)
            hpInPrev = x
            hpPrev = y
            // Soft saturation (tanh approximé) pour donner du grain
            val saturated = saturate(y * 1.4f)
            out[i] = (saturated * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /**
     * Crossfade smoothstep entre 3 samples (idx 0/1/2) selon la fréquence cible
     * et les fréquences de référence (auto-détectées). Le sample le plus proche
     * domine, transition lisse aux frontières.
     */
    private fun freqGains(target: Float, freqs: List<Float>): Triple<Float, Float, Float> {
        if (freqs.size < 3) {
            // Fallback : 1 seul sample dispo
            return Triple(1f, 0f, 0f)
        }
        val (fLo, fMid, fHi) = Triple(freqs[0], freqs[1], freqs[2])
        return when {
            target <= fLo -> Triple(1f, 0f, 0f)
            target <= fMid -> {
                val t = smoothstep((target - fLo) / (fMid - fLo))
                Triple(1f - t, t, 0f)
            }
            target <= fHi -> {
                val t = smoothstep((target - fMid) / (fHi - fMid))
                Triple(0f, 1f - t, t)
            }
            else -> Triple(0f, 0f, 1f)
        }
    }

    /** Easing 0→1 plus naturel que linéaire. */
    private fun smoothstep(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3 - 2 * x)
    }

    /** Saturation type tanh approximé : doux à amplitude basse, écrête en douceur. */
    private fun saturate(x: Float): Float {
        val a = x.coerceIn(-2f, 2f)
        return a / (1f + Math.abs(a) * 0.4f)
    }

    /** Ancien synth procédural (fallback). Conservé pour ne pas casser si pas de WAV. */
    private fun fillProcedural(out: ShortArray) {
        val p = profile
        val sr = SAMPLE_RATE.toDouble()
        val rpmAlpha = 0.05f
        // Mute si moteur arrêté
        val effTargetCheck = manualRpm ?: targetRpm
        if (effTargetCheck < 100f && smoothRpm < 100f) {
            for (i in out.indices) out[i] = 0
            return
        }
        for (i in out.indices) {
            val effectiveTarget = manualRpm ?: targetRpm
            smoothRpm += (effectiveTarget - smoothRpm) * rpmAlpha
            val rpm = smoothRpm.coerceAtLeast(50f)
            val baseFreq = (rpm / 60f) * (p.cylinders / 2f)
            val rpmFrac = ((rpm - p.idleRpm) / (p.redlineRpm - p.idleRpm)).coerceIn(0f, 1f)
            val cutoff = p.lowpassIdleHz + (p.lowpassRedlineHz - p.lowpassIdleHz) * rpmFrac
            val rpmVolume = 0.35f + 0.65f * rpmFrac

            val twoPi = 2.0 * PI
            phase += twoPi * baseFreq / sr
            if (phase > twoPi) phase -= twoPi
            val sinV = sin(phase).toFloat()
            val sawV = (((phase / twoPi) * 2.0 - 1.0)).toFloat()
            var sample = sinV * (1f - p.sawtoothMix) + sawV * p.sawtoothMix
            var harmTotal = sample * p.harmonicWeights[0]
            var weightSum = p.harmonicWeights[0]
            for (k in 1 until p.harmonicWeights.size) {
                val w = p.harmonicWeights[k]
                if (w == 0f) continue
                val hPhase = phase * (k + 1)
                val hSin = sin(hPhase).toFloat()
                val hSaw = (((hPhase / twoPi) % 1.0) * 2.0 - 1.0).toFloat()
                val hSample = hSin * (1f - p.sawtoothMix) + hSaw * p.sawtoothMix
                harmTotal += hSample * w
                weightSum += w
            }
            sample = harmTotal / weightSum
            if (p.crossplane) {
                phaseSecond += twoPi * baseFreq * 1.007 / sr
                if (phaseSecond > twoPi) phaseSecond -= twoPi
                val s2 = sin(phaseSecond).toFloat()
                sample = (sample + s2 * 0.45f) / 1.45f
            }
            if (p.noiseLevel > 0f) {
                val n = Random.nextFloat() * 2f - 1f
                sample += n * p.noiseLevel * (0.5f + rpmFrac * 0.5f)
            }
            val alpha = (1.0 - exp(-twoPi * cutoff / sr)).toFloat()
            lowpassY += alpha * (sample - lowpassY)
            sample = lowpassY
            sample *= volume * rpmVolume
            sample = softClip(sample)
            out[i] = (sample * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun softClip(x: Float): Float {
        val a = x.coerceIn(-1.5f, 1.5f)
        return a - (a * a * a) / 3f
    }
}
