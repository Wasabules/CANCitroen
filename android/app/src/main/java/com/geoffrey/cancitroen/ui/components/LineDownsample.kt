package com.geoffrey.cancitroen.ui.components

/**
 * Down-sample une série de points en bucketisant l'axe X.
 *  - Si la série a moins que [targetCount] points : retournée telle quelle
 *  - Sinon : on découpe l'intervalle (xmin..xmax) en [targetCount] buckets et on
 *    calcule la moyenne des y de chaque bucket. Retourne au plus [targetCount]
 *    points (les buckets vides sont omis).
 *
 *  Utilisé pour limiter la densité des line charts indépendamment de la
 *  granularité réelle des données stockées.
 */
fun aggregateMean(
    points: List<Pair<Double, Double>>,
    targetCount: Int = 200,
): List<Pair<Double, Double>> {
    if (points.size <= targetCount) return points
    val xMin = points.first().first
    val xMax = points.last().first
    val span = xMax - xMin
    if (span <= 0.0) return points

    val bucketSize = span / targetCount
    val sums = DoubleArray(targetCount)
    val counts = IntArray(targetCount)
    for ((x, y) in points) {
        val idx = ((x - xMin) / bucketSize).toInt().coerceIn(0, targetCount - 1)
        sums[idx] += y
        counts[idx]++
    }
    val out = ArrayList<Pair<Double, Double>>(targetCount)
    for (i in 0 until targetCount) {
        if (counts[i] == 0) continue
        val xCenter = xMin + (i + 0.5) * bucketSize
        val mean = sums[i] / counts[i]
        out += xCenter to mean
    }
    return out
}
