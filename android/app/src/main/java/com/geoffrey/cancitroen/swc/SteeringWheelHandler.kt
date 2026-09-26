package com.geoffrey.cancitroen.swc

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.geoffrey.cancitroen.decode.VehicleState
import com.geoffrey.cancitroen.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Détecte les transitions d'appui sur le comodo (CAN ID 0x21F) et délègue
 * l'exécution au [SwcDispatcher] selon le mapping utilisateur.
 *
 * Avant : envoyait systématiquement la commande sur 3-5 canaux en parallèle,
 * ce qui causait des doubles sauts de piste sur Android Auto.
 * Maintenant : un seul canal par bouton, choisi par l'utilisateur.
 *
 * Les états 0x21F sont traités dans l'ordre sur un thread dédié : un dispatch
 * peut bloquer (Binder, première sonde `su` jusqu'à 2 s en ROOT_SHELL) et ne
 * doit pas figer le collecteur CAN (UI, RPM de l'audio).
 */
class SteeringWheelHandler(
    context: Context,
    private val settings: StateFlow<AppSettings>,
    scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "SteeringWheelHandler"
        private const val DEBOUNCE_MS = 200L

        /** Dernier bouton détecté (pour le mode test dans Réglages). */
        private val _lastDetected = MutableStateFlow<SwcButton?>(null)
        val lastDetected: StateFlow<SwcButton?> = _lastDetected.asStateFlow()

        /** Dernier raw vu (pour debug : si pas dans les 6 SwcButton, raw != 0 mais lastDetected = null). */
        private val _lastRaw = MutableStateFlow(0)
        val lastRaw: StateFlow<Int> = _lastRaw.asStateFlow()
    }

    private val dispatcher = SwcDispatcher(context)

    private var lastFiredRaw: Int = 0
    private var lastFiredAt: Long = 0L

    private val pending = Channel<VehicleState>(Channel.UNLIMITED)

    init {
        @OptIn(ExperimentalCoroutinesApi::class)
        scope.launch(Dispatchers.IO.limitedParallelism(1)) {
            // Sonde root faite d'avance si un bouton est mappé en ROOT_SHELL :
            // sinon le premier appui la paie.
            val mapping = settings.value.swcMapping
            if (SwcButton.entries.any { mapping.forButton(it).endpoint == SwcEndpoint.ROOT_SHELL }) {
                RootKeyInjector.warmUp()
            }
            for (state in pending) process(state)
        }
    }

    /** Appelé par le collecteur CAN à chaque trame 0x21F : non bloquant. */
    fun onWheelStateChanged(state: VehicleState) {
        pending.trySend(state)
    }

    private fun process(state: VehicleState) {
        val raw = state.wheelButtonRaw
        if (raw != _lastRaw.value) _lastRaw.value = raw

        // Molette de scroll : indépendante des boutons, peut survenir sans
        // appui. À traiter AVANT le early-return sur lastFiredRaw, sinon les
        // frames où seul le scroll change passent à la trappe.
        //
        // Court-circuit si un bouton VOL+/VOL− est déjà en cours : sinon une
        // pression VOL+ qui passe aussi par la molette ferait double cran.
        val delta = state.wheelScrollDelta
        val volButtonPressed = (raw and 0x0C) != 0
        if (delta != 0 && !volButtonPressed) handleScroll(delta)

        if (raw == lastFiredRaw) return
        if (lastFiredRaw == 0 && raw != 0) {
            val now = SystemClock.uptimeMillis()
            if (now - lastFiredAt < DEBOUNCE_MS) {
                lastFiredRaw = raw
                return
            }
            lastFiredAt = now
            val button = detectButton(raw)
            _lastDetected.value = button
            if (button == null) {
                Log.w(TAG, "Raw 0x%02X non mappé à un SwcButton".format(raw))
            } else {
                val mapping = settings.value.swcMapping.forButton(button)
                Log.d(TAG, "Bouton $button (raw=0x%02X) → ${mapping.action} via ${mapping.endpoint}".format(raw))
                dispatcher.dispatch(mapping.action, mapping.endpoint)
            }
        }
        lastFiredRaw = raw
    }

    /**
     * Émet `|delta|` events VOLUME_UP/DOWN selon le signe. Réutilise l'endpoint
     * configuré pour le bouton VOL_UP/VOL_DOWN (pour que l'utilisateur n'ait
     * qu'un seul endroit à régler le canal volume). Si l'utilisateur a mis
     * l'action correspondante à NONE, on respecte ça → scroll désactivé aussi.
     *
     * Clamp à 5 pour éviter qu'un parser glitch qui envoie un wrap signé
     * massif ne fasse exploser le volume.
     */
    private fun handleScroll(delta: Int) {
        val button = if (delta > 0) SwcButton.VOL_UP else SwcButton.VOL_DOWN
        val mapping = settings.value.swcMapping.forButton(button)
        if (mapping.action == SwcAction.NONE) return
        val action = if (delta > 0) SwcAction.VOLUME_UP else SwcAction.VOLUME_DOWN
        val steps = kotlin.math.abs(delta).coerceAtMost(5)
        Log.d(TAG, "Scroll Δ=$delta → $action × $steps via ${mapping.endpoint}")
        repeat(steps) { dispatcher.dispatch(action, mapping.endpoint) }
    }
}
