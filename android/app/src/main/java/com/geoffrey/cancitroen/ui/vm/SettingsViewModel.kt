package com.geoffrey.cancitroen.ui.vm

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geoffrey.cancitroen.App
import com.geoffrey.cancitroen.history.DataExport
import com.geoffrey.cancitroen.history.FuelCalibration
import com.geoffrey.cancitroen.settings.AppSettings
import com.geoffrey.cancitroen.swc.SwcAction
import com.geoffrey.cancitroen.swc.SwcButton
import com.geoffrey.cancitroen.swc.SwcEndpoint
import com.geoffrey.cancitroen.ui.theme.AccentPreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = App.get().settings

    val settingsFlow: StateFlow<AppSettings> = settings.flow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings(),
    )

    /** Capacité du réservoir déduite des tickets de plein. */
    val capacityEstimate: StateFlow<FuelCalibration.CapacityEstimate> = App.get().historyDao.enteredRefuels()
        .map { FuelCalibration.estimateCapacity(it) }
        .stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            FuelCalibration.CapacityEstimate(fills = 0, liters = null),
        )

    /** Conso réelle (tickets) comparée à celle indiquée par la voiture. */
    val consumptionCheck: StateFlow<FuelCalibration.ConsumptionCheck> = combine(
        App.get().historyDao.refuelsSince(0L),
        App.get().historyDao.recentTrips(200),
        settings.flow.map { it.fuel.tankCapacityLiters }.distinctUntilChanged(),
    ) { refuels, trips, capacity -> FuelCalibration.consumptionCheck(refuels, trips, capacity) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FuelCalibration.ConsumptionCheck(fills = 0))

    private val _exportStatus = MutableStateFlow<String?>(null)
    val exportStatus: StateFlow<String?> = _exportStatus

    fun exportData() {
        _exportStatus.value = "Export en cours…"
        viewModelScope.launch {
            _exportStatus.value = try {
                val files = DataExport.exportAll(getApplication())
                "$files fichiers écrits dans Téléchargements/${DataExport.FOLDER}"
            } catch (e: Exception) {
                Log.e("SettingsViewModel", "Export impossible", e)
                "Export impossible : ${e.message}"
            }
        }
    }

    fun setLaunchAsHome(v: Boolean) = viewModelScope.launch { settings.setLaunchAsHome(v) }
    fun setForceHomeRoot(v: Boolean) = viewModelScope.launch { settings.setForceHomeRoot(v) }
    fun setAggressiveDisableLaunchers(v: Boolean) =
        viewModelScope.launch { settings.setAggressiveDisableLaunchers(v) }
    fun setSimulatorEnabled(v: Boolean) = viewModelScope.launch { settings.setSimulatorEnabled(v) }
    fun setSimulatorRecorded(v: Boolean) = viewModelScope.launch { settings.setSimulatorRecorded(v) }
    fun setLinkProbeEnabled(v: Boolean) = viewModelScope.launch { settings.setLinkProbeEnabled(v) }
    fun setTransferEnabled(v: Boolean) = viewModelScope.launch { settings.setTransferEnabled(v) }
    fun forgetTransferPhone() = viewModelScope.launch { settings.forgetTransferPhone() }
    suspend fun pendingSamples(ackedId: Long): Long = App.get().historyDao.countSamplesAfter(ackedId)
    fun setConsumptionFactor(v: Double) = viewModelScope.launch { settings.setConsumptionFactor(v) }
    fun setFuelPrice(v: Double) = viewModelScope.launch { settings.setFuelPrice(v) }
    fun setTankCapacity(v: Float) = viewModelScope.launch { settings.setTankCapacity(v) }
    fun setTExtOffset(v: Int) = viewModelScope.launch { settings.setTExtOffset(v) }
    fun setSwcMapping(button: SwcButton, action: SwcAction, endpoint: SwcEndpoint) =
        viewModelScope.launch { settings.setSwcMapping(button, action, endpoint) }
    fun resetSwcMapping() = viewModelScope.launch { settings.resetSwcMapping() }
    fun setAccentPreset(p: AccentPreset) = viewModelScope.launch { settings.setAccentPreset(p) }
}
