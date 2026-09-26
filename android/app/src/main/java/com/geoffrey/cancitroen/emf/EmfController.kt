package com.geoffrey.cancitroen.emf

import com.geoffrey.cancitroen.decode.CanIds
import com.geoffrey.cancitroen.usb.CanableSerial
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Contrôleur du mode EMF (émulation RD4 menu).
 *
 * Reproduit le comportement de PSAWifiDisplayControl :
 *  - boucle permanente émettant 0x3E5 = 00*6 toutes les 65 ms (release continu)
 *  - sur appel [pressButton] : injection d'UNE trame avec le bit du bouton
 *  - le tick suivant remet à 00 → auto-release ~65 ms après
 *
 * Le mode doit être activé explicitement via [setActive] (pour ne pas spammer le
 * bus en permanence quand on n'utilise pas l'EMF).
 */
class EmfController(
    private val canable: CanableSerial,
    private val scope: CoroutineScope,
) {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    private val _lastAction = MutableStateFlow<EmfButton?>(null)
    val lastAction: StateFlow<EmfButton?> = _lastAction

    private var releaseJob: Job? = null

    fun setActive(value: Boolean) {
        if (value && !_active.value) {
            _active.value = true
            releaseJob = scope.launch(Dispatchers.IO) {
                while (isActive && _active.value) {
                    canable.send(CanIds.ID_EMF_BUTTONS, EmfButton.RELEASE)
                    delay(RELEASE_TICK_MS)
                }
            }
        } else if (!value && _active.value) {
            _active.value = false
            releaseJob?.cancel()
            releaseJob = null
            // Final release pour propreté — toujours sur IO, jamais sur le
            // thread appelant (peut être main si toggle depuis Compose).
            scope.launch(Dispatchers.IO) {
                canable.send(CanIds.ID_EMF_BUTTONS, EmfButton.RELEASE)
            }
        }
    }

    /**
     * Émet 1 pulse pour le bouton donné. Le mode doit être actif.
     * `canable.send()` fait un `port.write(timeout=200)` USB → on l'envoie sur
     * IO pour ne pas bloquer le main thread si l'utilisateur tape vite.
     */
    fun pressButton(button: EmfButton): Boolean {
        if (!_active.value) return false
        _lastAction.value = button
        scope.launch(Dispatchers.IO) {
            canable.send(CanIds.ID_EMF_BUTTONS, button.payload)
        }
        return true
    }

    companion object {
        /** Période entre 2 trames release — valeur reverse-engineered de PSAWifiDisplayControl. */
        private const val RELEASE_TICK_MS = 65L
    }
}
