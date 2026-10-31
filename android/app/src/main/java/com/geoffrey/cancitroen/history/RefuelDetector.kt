package com.geoffrey.cancitroen.history

/**
 * Détecte un plein à partir du niveau de carburant (en %). Fonction pure de la
 * suite (niveau, instant) : la voiture ne fabrique pas d'essence, donc toute
 * hausse franche et durable est un plein, quel que soit le temps écoulé.
 *
 *  - [baseline] : dernier niveau connu avant le plein. Vient de la base au
 *    démarrage : un plein fait contact coupé est vu même si l'app a été tuée
 *    pendant la veille.
 *  - La hausse doit tenir [STABLE_MS] sans nouveau maximum : une jauge qui
 *    monte par paliers (60 → 80 → 95 %) ne donne qu'un plein, avec le bon
 *    niveau final. Une hausse qui retombe (ballottement, pente) est ignorée.
 *  - Une baisse n'est retenue qu'après deux mesures de suite : une valeur
 *    aberrante isolée ne crée pas de faux plein au retour à la normale.
 */
class RefuelDetector(private var baseline: Int? = null) {

    data class Refuel(val before: Int, val after: Int)

    private var peak: Int? = null
    private var lastRiseMs = 0L
    private var lowSeen = false

    /** À appeler à chaque échantillon ; renvoie le plein quand il est confirmé. */
    fun onLevel(pct: Int?, nowMs: Long): Refuel? {
        // 0 et 0xFF : BSI au réveil, pas une mesure.
        if (pct == null || pct !in 1..100) return null
        val base = baseline ?: run {
            baseline = pct
            return null
        }
        if (pct - base >= MIN_RISE_PCT) {
            lowSeen = false
            val top = peak
            if (top == null || pct > top) {
                peak = pct
                lastRiseMs = nowMs
                return null
            }
            if (nowMs - lastRiseMs < STABLE_MS) return null
            peak = null
            baseline = pct
            return Refuel(before = base, after = pct)
        }
        peak = null
        if (pct < base) {
            if (lowSeen) baseline = pct
            lowSeen = !lowSeen
        } else {
            lowSeen = false
        }
        return null
    }

    companion object {
        /** ≈ 5 L sur 41 L : assez bas pour un petit plein, au-dessus du ballottement. */
        const val MIN_RISE_PCT = 12
        const val STABLE_MS = 60_000L
    }
}
