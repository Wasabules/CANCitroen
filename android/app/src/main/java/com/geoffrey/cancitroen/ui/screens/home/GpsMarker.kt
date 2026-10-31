package com.geoffrey.cancitroen.ui.screens.home

/**
 * Point GPS de la jauge de vitesse. À l'arrêt, la vitesse GPS oscille autour de
 * quelques km/h : deux seuils évitent que le point clignote. Sans fix, il garde
 * sa dernière position pour ne pas repartir de zéro au retour du signal.
 */
internal class GpsMarker {
    private var visible = false

    var position = 0f
        private set

    /** Renvoie true si le point doit être affiché. */
    fun update(speedKmh: Float?): Boolean {
        if (speedKmh != null) position = speedKmh
        visible = when {
            speedKmh == null -> false
            visible -> speedKmh >= HIDE_BELOW_KMH
            else -> speedKmh >= SHOW_FROM_KMH
        }
        return visible
    }

    private companion object {
        const val SHOW_FROM_KMH = 3f
        const val HIDE_BELOW_KMH = 1f
    }
}
