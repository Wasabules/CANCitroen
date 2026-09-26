package com.geoffrey.cancitroen.audio

/**
 * Estime la fréquence fondamentale d'un échantillon PCM via autocorrélation.
 *
 *  - On normalise les échantillons (mean removal) pour ignorer la composante DC
 *  - On cherche le décalage τ (en samples) entre les bornes raisonnables (selon
 *    la fréquence min/max attendue) qui maximise la corrélation
 *  - On affine via interpolation parabolique entre 3 points autour du max
 *
 *  Pour un moteur thermique, on attend la fréquence de combustion entre 15 et
 *  300 Hz environ. Pour la stabilité on accepte un peu plus large.
 */
object PitchEstimator {

    fun estimateFundamentalHz(
        pcm: FloatArray,
        sampleRate: Int,
        minHz: Float = 15f,
        maxHz: Float = 400f,
    ): Float {
        if (pcm.size < 1024) return 50f  // sample trop court → valeur arbitraire
        val n = minOf(pcm.size, 16384)   // limite à 16k samples pour rester rapide

        // Mean removal
        var sum = 0f
        for (i in 0 until n) sum += pcm[i]
        val mean = sum / n

        val minPeriod = (sampleRate / maxHz).toInt().coerceAtLeast(2)
        val maxPeriod = (sampleRate / minHz).toInt().coerceAtMost(n - 2)

        var bestPeriod = minPeriod
        var bestCorr = Float.NEGATIVE_INFINITY
        // Compute autocorrelation for each candidate period
        // Step de 1 sample → précision max, mais O(N×P) → on limite à 8k samples
        val limit = minOf(n - maxPeriod, 8192)
        for (period in minPeriod..maxPeriod) {
            var corr = 0f
            var energyA = 0f
            var energyB = 0f
            for (i in 0 until limit) {
                val a = pcm[i] - mean
                val b = pcm[i + period] - mean
                corr += a * b
                energyA += a * a
                energyB += b * b
            }
            // Normalisation : coefficient de Pearson (insensible à l'amplitude)
            val norm = Math.sqrt((energyA * energyB).toDouble()).toFloat()
            val nCorr = if (norm > 0f) corr / norm else 0f
            if (nCorr > bestCorr) {
                bestCorr = nCorr
                bestPeriod = period
            }
        }

        // Affinage parabolique : ajuste sur ±1 sample autour du best
        val refinedPeriod = if (bestPeriod > minPeriod && bestPeriod < maxPeriod) {
            // Recalcule corr aux periods adjacents
            val cMinus = autocorr(pcm, mean, bestPeriod - 1, limit)
            val cZero = autocorr(pcm, mean, bestPeriod, limit)
            val cPlus = autocorr(pcm, mean, bestPeriod + 1, limit)
            // Interpolation parabolique : delta = (cMinus - cPlus) / (2 × (cMinus - 2×cZero + cPlus))
            val denom = 2f * (cMinus - 2f * cZero + cPlus)
            val delta = if (denom != 0f) (cMinus - cPlus) / denom else 0f
            bestPeriod + delta
        } else bestPeriod.toFloat()

        return sampleRate.toFloat() / refinedPeriod
    }

    private fun autocorr(pcm: FloatArray, mean: Float, period: Int, limit: Int): Float {
        var corr = 0f
        for (i in 0 until limit) {
            corr += (pcm[i] - mean) * (pcm[i + period] - mean)
        }
        return corr
    }
}
