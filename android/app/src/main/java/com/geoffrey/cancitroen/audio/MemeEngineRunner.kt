package com.geoffrey.cancitroen.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * "Moteur meme" : un son court (fart, buzzer, apple pay…) déclenché à chaque
 * cycle de combustion virtuel.
 *
 *  - Fréquence des triggers = RPM/60 × cyl/2 (= fréquence d'explosion réelle)
 *  - Cappée à 30 Hz (au-delà = SoundPool sature et le son devient inintelligible)
 *  - Volume + pitch (rate SoundPool 0.5..2.0) modulés par les RPM
 *  - SoundPool max 8 streams simultanés → polyphonie correcte sur les transitions
 *
 *  Indépendant de [EngineSoundSynth] : quand le mode meme est actif, le synth
 *  normal est arrêté et c'est ce runner qui produit le son.
 */
class MemeEngineRunner(private val context: Context) {
    companion object {
        private const val TAG = "MemeEngineRunner"
        /** On ne joue qu'une explosion sur N pour rester lisible.
         *  À 1000 RPM (4 cyl) on aurait 33 Hz natif → ici 6.7 Hz lisible. */
        private const val TRIGGER_DIVIDER = 5f
        private const val MAX_TRIGGER_HZ = 12f
        private const val MIN_INTERVAL_NS = 1_000_000_000L / 60
    }

    private var soundPool: SoundPool? = null
    private var soundId: Int = -1
    @Volatile private var loaded: Boolean = false
    private var loadedPath: String? = null
    private var job: Job? = null

    @Volatile private var rpm: Float = 0f
    @Volatile private var manualRpm: Float? = null
    @Volatile private var cylinders: Int = 4
    @Volatile private var idleRpm: Float = 800f
    @Volatile private var redlineRpm: Float = 6500f
    @Volatile private var volume: Float = 0.7f
    @Volatile private var running: Boolean = false

    /** Charge un meme depuis les assets. Recharge si chemin différent du courant. */
    fun load(memeAssetPath: String) {
        if (loadedPath == memeAssetPath && loaded) return
        unload()
        try {
            val sp = SoundPool.Builder()
                .setMaxStreams(8)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .build()
            // IMPORTANT : SoundPool.load(afd, prio) lit le FD de manière async.
            // L'afd doit rester ouvert jusqu'à ce que le listener fire (sinon
            // status != 0 sur certaines ROMs Atoto). On capte l'afd dans la
            // closure et on le ferme là.
            val afd = context.assets.openFd(memeAssetPath)
            sp.setOnLoadCompleteListener { _, _, status ->
                loaded = (status == 0)
                if (status != 0) Log.w(TAG, "Load fail status=$status pour $memeAssetPath")
                else Log.i(TAG, "✓ Sample chargé : $memeAssetPath")
                try { afd.close() } catch (_: Exception) {}
            }
            soundId = sp.load(afd, 1)
            soundPool = sp
            loadedPath = memeAssetPath
        } catch (e: Exception) {
            Log.e(TAG, "Erreur load $memeAssetPath", e)
            unload()
        }
    }

    fun setRpm(rpm: Float) { this.rpm = rpm.coerceIn(0f, 12000f) }
    /** Override pour la démo. null = retour à la source live. */
    fun setManualRpm(rpm: Float?) { manualRpm = rpm?.coerceIn(0f, 12000f) }
    fun setProfile(cyl: Int, idle: Float, redline: Float) {
        cylinders = cyl.coerceAtLeast(1)
        idleRpm = idle
        redlineRpm = redline
    }
    fun setVolume(v: Float) { volume = v.coerceIn(0f, 1f) }

    fun start(scope: CoroutineScope) {
        if (running) return
        running = true
        job = scope.launch(Dispatchers.Default) {
            var nextTriggerNs = System.nanoTime()
            while (isActive && running) {
                val curRpm = manualRpm ?: rpm
                // Moteur à l'arrêt (contact OFF, sim OFF) → silence
                if (curRpm < 100f) {
                    delay(100L)
                    nextTriggerNs = System.nanoTime()  // reset pour pas spammer au redémarrage
                    continue
                }
                val now = System.nanoTime()
                val combustionHz = (curRpm / 60f) * (cylinders / 2f)
                // Diviseur : on ne joue qu'une explosion sur N
                val triggerHz = (combustionHz / TRIGGER_DIVIDER).coerceAtMost(MAX_TRIGGER_HZ)
                val intervalNs = maxOf(MIN_INTERVAL_NS,
                    (1_000_000_000L / triggerHz).toLong())

                if (now >= nextTriggerNs && loaded && soundId >= 0) {
                    // Volume monte avec les RPM (idle calme, redline fort)
                    val rpmFrac = ((curRpm - idleRpm) / (redlineRpm - idleRpm))
                        .coerceIn(0f, 1f)
                    val vol = volume * (0.4f + 0.6f * rpmFrac)
                    // Léger pitch up à haute RPM (effet "accélération")
                    val rate = (1.0f + rpmFrac * 0.5f).coerceIn(0.5f, 2.0f)
                    try {
                        soundPool?.play(soundId, vol, vol, /*priority*/ 1,
                            /*loop*/ 0, rate)
                    } catch (_: Exception) {}
                    nextTriggerNs = now + intervalNs
                }
                // Dort jusqu'au prochain déclenchement (au plus 20 ms, pour
                // suivre les variations de RPM) au lieu de boucler à 500 Hz.
                val waitMs = ((nextTriggerNs - System.nanoTime()) / 1_000_000L).coerceIn(1L, 20L)
                delay(waitMs)
            }
        }
    }

    fun stop() {
        running = false
        job?.cancel(); job = null
    }

    fun unload() {
        stop()
        try { soundPool?.release() } catch (_: Exception) {}
        soundPool = null
        soundId = -1
        loaded = false
        loadedPath = null
    }
}
