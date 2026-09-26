package com.geoffrey.cancitroen.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.geoffrey.cancitroen.decode.VehicleState
import com.geoffrey.cancitroen.system.GpsTracker
import com.geoffrey.cancitroen.ui.vm.HomeViewModel

/**
 * Wrapper qui injecte [HomeViewModel] et expose un appel HomeScreen "plat".
 * MainActivity n'a plus à connaître les détails des toggles.
 */
@Composable
fun HomeRoute(
    /** Provider : seuls les composants qui affichent une valeur la lisent. */
    state: () -> VehicleState,
    gpsState: GpsTracker.SatelliteState?,
    gpsSpeedKmh: Float?,
    isIdle: Boolean,
    onPickShortcut: (slot: Int) -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val settings by vm.settingsFlow.collectAsStateWithLifecycle()
    val gridState by vm.gridSlotsFlow.collectAsStateWithLifecycle()
    val (primary, slots) = gridState
    val nightMode by vm.nightMode.collectAsStateWithLifecycle()

    HomeScreen(
        state = state,
        primaryShortcut = primary,
        gridSlots = slots,
        gpsState = gpsState,
        gpsSpeedKmh = gpsSpeedKmh,
        nightMode = nightMode,
        isIdle = isIdle,
        fuelDisplayLiters = settings.display.fuelDisplayLiters,
        oilDisplayLevel = settings.display.oilDisplayLevel,
        speedShowGps = settings.display.speedShowGps,
        rangeShowConsumption = settings.display.rangeShowConsumption,
        onLaunchShortcut = vm::launchShortcut,
        onPickEmptySlot = onPickShortcut,
        onLongPressFilledSlot = onPickShortcut,
        onToggleNightMode = vm::toggleNightMode,
        onToggleFuelDisplay = vm::toggleFuelDisplay,
        onToggleOilDisplay = vm::toggleOilDisplay,
        onToggleSpeedSource = vm::toggleSpeedSource,
        onToggleRangeDisplay = vm::toggleRangeDisplay,
        modifier = Modifier.fillMaxSize(),
    )
}
