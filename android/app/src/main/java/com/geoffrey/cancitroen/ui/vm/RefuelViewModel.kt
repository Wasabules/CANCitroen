package com.geoffrey.cancitroen.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geoffrey.cancitroen.App
import com.geoffrey.cancitroen.history.FuelCalibration
import com.geoffrey.cancitroen.history.entities.RefuelEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Plein à afficher ; [manual] : ouvert depuis Stats, pas détecté à l'instant. */
data class RefuelPrompt(val event: RefuelEvent, val manual: Boolean)

class RefuelViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = App.get().historyDao
    private val settings = App.get().settings

    private val editing = MutableStateFlow<RefuelEvent?>(null)
    /** « Plus tard » : plus de question automatique, le plein reste à saisir depuis Stats. */
    private val snoozed = MutableStateFlow(emptySet<Long>())

    val prompt: StateFlow<RefuelPrompt?> =
        combine(editing, dao.pendingRefuel(), snoozed) { edited, pending, later ->
            when {
                edited != null -> RefuelPrompt(edited, manual = true)
                pending != null && pending.id !in later -> RefuelPrompt(pending, manual = false)
                else -> null
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun edit(event: RefuelEvent) { editing.value = event }

    /** Plein non détecté (appoint) : ouvert à la saisie, enregistré seulement à la validation. */
    fun addManual() {
        viewModelScope.launch {
            editing.value = RefuelEvent(
                ts = System.currentTimeMillis(),
                fuelPctBefore = 0,
                fuelPctAfter = dao.lastSampleFuelPct() ?: dao.lastAggregateFuelPct() ?: 0,
                litersAdded = 0f,
                odoKm = dao.lastSampleOdoKm() ?: dao.lastAggregateOdoKm(),
                manual = true,
            )
        }
    }

    fun later(prompt: RefuelPrompt) {
        editing.value = null
        if (prompt.event.awaitingInput) snoozed.value = snoozed.value + prompt.event.id
    }

    fun save(event: RefuelEvent, liters: Double, amount: Double) {
        editing.value = null
        viewModelScope.launch {
            var receipt = FuelCalibration.applyReceipt(event, liters, amount)
            if (event.manual) {
                val capacity = settings.flow.first().fuel.tankCapacityLiters
                receipt = receipt.copy(
                    fuelPctBefore = FuelCalibration.manualLevelBefore(event.fuelPctAfter, liters, capacity),
                )
            }
            if (event.id == 0L) dao.insertRefuel(receipt) else dao.updateRefuel(receipt)
            // Corriger un ancien ticket ne doit pas ramener le prix en arrière.
            if (dao.lastRefuel()?.ts == event.ts) settings.setFuelPrice(amount / liters)
        }
    }

    fun delete(event: RefuelEvent) {
        editing.value = null
        viewModelScope.launch { dao.deleteRefuel(event.id) }
    }
}
