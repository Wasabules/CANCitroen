package com.geoffrey.cancitroen.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.geoffrey.cancitroen.system.LauncherGuard
import com.geoffrey.cancitroen.system.PermissionsHelper
import com.geoffrey.cancitroen.ui.vm.SettingsViewModel

@Composable
fun SettingsRoute(vm: SettingsViewModel = viewModel()) {
    val settings by vm.settingsFlow.collectAsStateWithLifecycle()
    val guardStatus by LauncherGuard.status.collectAsStateWithLifecycle()
    val ctx: Context = LocalContext.current

    // Rafraîchit le statut launcher (root, guard, home par défaut) à chaque
    // retour au premier plan — sinon après un aller-retour dans les Réglages
    // système ou un toggle, l'UI reste figée sur l'ancien état.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) { LauncherGuard.refresh(ctx) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) LauncherGuard.refresh(ctx)
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    SettingsScreen(
        settings = settings,
        guardStatus = guardStatus,
        onEmfAutostartChange = vm::setEmfAutostart,
        onLaunchAsHomeChange = { v ->
            vm.setLaunchAsHome(v)
            if (v) PermissionsHelper.openHomeSettings(ctx)
        },
        onForceHomeRootChange = vm::setForceHomeRoot,
        onAggressiveDisableChange = vm::setAggressiveDisableLaunchers,
        onReapplyGuard = {
            LauncherGuard.apply(ctx, settings.behavior.aggressiveDisableLaunchers)
        },
        onSimulatorChange = vm::setSimulatorEnabled,
        onFuelPriceChange = vm::setFuelPrice,
        onTankCapacityChange = vm::setTankCapacity,
        onTExtOffsetChange = vm::setTExtOffset,
        onSwcMappingChange = vm::setSwcMapping,
        onSwcMappingReset = vm::resetSwcMapping,
        onAccentPresetChange = vm::setAccentPreset,
        modifier = Modifier.fillMaxSize(),
    )
}
