package com.geoffrey.cancitroen.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geoffrey.cancitroen.App
import com.geoffrey.cancitroen.system.AppShortcuts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * VM de l'écran d'accueil. Centralise :
 *  - les toggles d'affichage (carburant, huile, vitesse source, range/conso)
 *  - le mode jour/nuit local (non persisté)
 *  - la résolution dynamique de la liste de raccourcis (avec overrides)
 *
 * Le HomeScreen ne reçoit que la state finale et des callbacks → composable
 * léger et plus testable.
 */
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = App.get().settings

    // ── State exposée ──
    val settingsFlow: StateFlow<com.geoffrey.cancitroen.settings.AppSettings> =
        settings.flow.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            com.geoffrey.cancitroen.settings.AppSettings(),
        )

    /** Primary (Android Auto) à part, et les 6 slots (filled ou empty). */
    val gridSlotsFlow: StateFlow<Pair<AppShortcuts.ResolvedShortcut?, List<AppShortcuts.GridSlot>>> = settingsFlow
        // Seulement quand les raccourcis changent (pas à chaque toggle carburant…),
        // et hors main thread : résolution PackageManager + icônes → bitmaps.
        .map { it.shortcutOverrides }
        .distinctUntilChanged()
        .map { AppShortcuts.resolveSlots(getApplication(), it) }
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            null to emptyList(),
        )

    private val _nightMode = MutableStateFlow(false)
    val nightMode: StateFlow<Boolean> = _nightMode

    // ── Callbacks ──
    fun toggleNightMode() { _nightMode.value = !_nightMode.value }

    fun toggleFuelDisplay() = viewModelScope.launch {
        settings.setFuelDisplayLiters(!settingsFlow.value.display.fuelDisplayLiters)
    }
    fun toggleOilDisplay() = viewModelScope.launch {
        settings.setOilDisplayLevel(!settingsFlow.value.display.oilDisplayLevel)
    }
    fun toggleSpeedSource() = viewModelScope.launch {
        settings.setSpeedShowGps(!settingsFlow.value.display.speedShowGps)
    }
    fun toggleRangeDisplay() = viewModelScope.launch {
        settings.setRangeShowConsumption(!settingsFlow.value.display.rangeShowConsumption)
    }
    fun setShortcutOverride(slot: Int, packageName: String?) = viewModelScope.launch {
        settings.setShortcutOverride(slot, packageName)
    }
    fun launchShortcut(packageName: String) {
        AppShortcuts.launch(getApplication(), packageName)
    }
}
