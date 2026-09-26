package com.geoffrey.cancitroen.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geoffrey.cancitroen.App
import com.geoffrey.cancitroen.settings.AppSettings
import com.geoffrey.cancitroen.swc.SwcAction
import com.geoffrey.cancitroen.swc.SwcButton
import com.geoffrey.cancitroen.swc.SwcEndpoint
import com.geoffrey.cancitroen.ui.theme.AccentPreset
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = App.get().settings

    val settingsFlow: StateFlow<AppSettings> = settings.flow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings(),
    )

    fun setEmfAutostart(v: Boolean) = viewModelScope.launch { settings.setEmfAutostart(v) }
    fun setLaunchAsHome(v: Boolean) = viewModelScope.launch { settings.setLaunchAsHome(v) }
    fun setForceHomeRoot(v: Boolean) = viewModelScope.launch { settings.setForceHomeRoot(v) }
    fun setAggressiveDisableLaunchers(v: Boolean) =
        viewModelScope.launch { settings.setAggressiveDisableLaunchers(v) }
    fun setSimulatorEnabled(v: Boolean) = viewModelScope.launch { settings.setSimulatorEnabled(v) }
    fun setFuelPrice(v: Double) = viewModelScope.launch { settings.setFuelPrice(v) }
    fun setTankCapacity(v: Float) = viewModelScope.launch { settings.setTankCapacity(v) }
    fun setTExtOffset(v: Int) = viewModelScope.launch { settings.setTExtOffset(v) }
    fun setSwcMapping(button: SwcButton, action: SwcAction, endpoint: SwcEndpoint) =
        viewModelScope.launch { settings.setSwcMapping(button, action, endpoint) }
    fun resetSwcMapping() = viewModelScope.launch { settings.resetSwcMapping() }
    fun setAccentPreset(p: AccentPreset) = viewModelScope.launch { settings.setAccentPreset(p) }
}
