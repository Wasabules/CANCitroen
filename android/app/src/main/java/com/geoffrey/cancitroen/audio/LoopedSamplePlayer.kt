package com.geoffrey.cancitroen.audio

/**
 * Lecteur de sample en boucle avec pitch shift et **interpolation Hermite 4 points**
 * (réduit l'aliasing par rapport à l'interpolation linéaire, surtout aux pitch shifts élevés).
 *
 *  - Lecture circulaire
 *  - Mix accumulatif : write += sample × gain (permet le crossfade)
 */
class LoopedSamplePlayer(
    private val sample: LoadedSample,
    /** Fréquence de combustion à laquelle ce sample a été enregistré (Hz). */
    val baseCombustionHz: Float,
) {
    private var phase = 0.0
    private val sizeD = sample.pcm.size.toDouble()
    private val pcm = sample.pcm

    fun read(
        targetCombustionHz: Float,
        outSampleRate: Int,
        out: FloatArray,
        gain: Float,
    ) {
        if (gain <= 0f || out.isEmpty() || pcm.isEmpty()) return
        // Garde-fou : si la fondamentale détectée est foireuse (PitchEstimator
        // retourne 0 sur sample silencieux ou trop court), le ratio devient
        // NaN/Inf → phase explose → idx undefined → potentiel out-of-bounds.
        if (baseCombustionHz <= 0f || !baseCombustionHz.isFinite()) return
        val ratio = (targetCombustionHz / baseCombustionHz).toDouble() *
            sample.sampleRate / outSampleRate
        if (!ratio.isFinite() || ratio <= 0.0) return
        val size = pcm.size
        for (i in out.indices) {
            // Math.floorMod fait le double-modulo proprement pour phase qui
            // pourrait avoir glissé négatif sur edge cases (phase += -ratio).
            val idx = Math.floorMod(phase.toInt(), size)
            val t = (phase - phase.toInt().toDouble()).toFloat().coerceIn(0f, 1f)
            // Hermite 4-point : utilise (idx-1, idx, idx+1, idx+2)
            val y0 = pcm[Math.floorMod(idx - 1, size)]
            val y1 = pcm[idx]
            val y2 = pcm[Math.floorMod(idx + 1, size)]
            val y3 = pcm[Math.floorMod(idx + 2, size)]
            val c0 = y1
            val c1 = 0.5f * (y2 - y0)
            val c2 = y0 - 2.5f * y1 + 2f * y2 - 0.5f * y3
            val c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2)
            val s = ((c3 * t + c2) * t + c1) * t + c0
            out[i] += s * gain
            phase += ratio
            // Normalisation bornée : si phase a glissé d'un facteur > sizeD,
            // les if isolés ne suffisent pas. while inutile en pratique avec
            // ratio borné, mais robuste.
            while (phase >= sizeD) phase -= sizeD
            while (phase < 0) phase += sizeD
        }
    }
}
