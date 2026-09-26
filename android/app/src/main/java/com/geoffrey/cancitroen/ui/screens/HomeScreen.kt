package com.geoffrey.cancitroen.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.geoffrey.cancitroen.ui.theme.LocalNightMode
import com.geoffrey.cancitroen.decode.VehicleState
import com.geoffrey.cancitroen.system.AppShortcuts
import com.geoffrey.cancitroen.system.GpsTracker
import com.geoffrey.cancitroen.system.AppShortcuts.GridSlot
import com.geoffrey.cancitroen.ui.screens.home.AndroidAutoHero
import com.geoffrey.cancitroen.ui.screens.home.MainGaugesRow
import com.geoffrey.cancitroen.ui.screens.home.ShortcutsGrid
import com.geoffrey.cancitroen.ui.screens.home.StatusBarValues
import com.geoffrey.cancitroen.ui.screens.home.TopStatusBar

/**
 * Page d'accueil — orchestre :
 *   - TopStatusBar (pills d'état)
 *   - MainGaugesRow (vitesse + horloge + RPM)
 *   - AndroidAutoHero (si AA installé)
 *   - ShortcutsGrid (apps favorites)
 *
 * Calcule les dimensions adaptatives selon le ratio d'écran (téléphone vs tablette)
 * via BoxWithConstraints, puis délègue le rendu aux sous-composants.
 */
@Composable
fun HomeScreen(
    state: () -> VehicleState,
    primaryShortcut: AppShortcuts.ResolvedShortcut?,
    gridSlots: List<GridSlot>,
    gpsState: GpsTracker.SatelliteState?,
    gpsSpeedKmh: Float?,
    nightMode: Boolean,
    isIdle: Boolean,
    fuelDisplayLiters: Boolean,
    oilDisplayLevel: Boolean,
    speedShowGps: Boolean,
    rangeShowConsumption: Boolean,
    onLaunchShortcut: (String) -> Unit,
    onPickEmptySlot: (slot: Int) -> Unit,
    onLongPressFilledSlot: (slot: Int) -> Unit,
    onToggleNightMode: () -> Unit,
    onToggleFuelDisplay: () -> Unit,
    onToggleOilDisplay: () -> Unit,
    onToggleSpeedSource: () -> Unit,
    onToggleRangeDisplay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Provide le mode nuit en CompositionLocal : chaque composant enfant qui
    // veut s'atténuer en mode nuit lit LocalNightMode.current sans qu'on
    // doive plumber un boolean dans toute la hiérarchie.
    CompositionLocalProvider(LocalNightMode provides nightMode) {
        HomeScreenImpl(
            state, primaryShortcut, gridSlots, gpsState, gpsSpeedKmh, nightMode, isIdle,
            fuelDisplayLiters, oilDisplayLevel, speedShowGps, rangeShowConsumption,
            onLaunchShortcut, onPickEmptySlot, onLongPressFilledSlot,
            onToggleNightMode, onToggleFuelDisplay, onToggleOilDisplay,
            onToggleSpeedSource, onToggleRangeDisplay, modifier,
        )
    }
}

@Composable
private fun HomeScreenImpl(
    state: () -> VehicleState,
    primaryShortcut: AppShortcuts.ResolvedShortcut?,
    gridSlots: List<GridSlot>,
    gpsState: GpsTracker.SatelliteState?,
    gpsSpeedKmh: Float?,
    nightMode: Boolean,
    isIdle: Boolean,
    fuelDisplayLiters: Boolean,
    oilDisplayLevel: Boolean,
    speedShowGps: Boolean,
    rangeShowConsumption: Boolean,
    onLaunchShortcut: (String) -> Unit,
    onPickEmptySlot: (slot: Int) -> Unit,
    onLongPressFilledSlot: (slot: Int) -> Unit,
    onToggleNightMode: () -> Unit,
    onToggleFuelDisplay: () -> Unit,
    onToggleOilDisplay: () -> Unit,
    onToggleSpeedSource: () -> Unit,
    onToggleRangeDisplay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Lectures dérivées hors du BoxWithConstraints : son contenu (sous-
    // composition) n'est plus relancé à chaque échantillon d'état.
    val statusValues by remember(state) { derivedStateOf { StatusBarValues.of(state()) } }
    val speed = remember(state) { derivedStateOf { state().speed?.toFloat() ?: 0f } }
    val rpm = remember(state) { derivedStateOf { state().rpm?.toFloat() ?: 0f } }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val totalH = maxHeight
        val totalW = maxWidth

        val tileSize = (totalH * 0.18f).coerceIn(108.dp, 168.dp)
        val heroHeight = (totalH * 0.13f).coerceIn(86.dp, 130.dp)
        val heroSpace = if (primaryShortcut != null) heroHeight + 14.dp else 0.dp
        val reservedSpace = 40.dp + tileSize + heroSpace + 92.dp
        val maxMainH = (totalH - reservedSpace).coerceAtLeast(180.dp)
        val mainRowHeight = (totalH * 0.55f).coerceIn(180.dp, maxMainH)

        // Largeur slot d'une jauge dans Row(weight 1+1.5+1) pour rester ronde
        val rowUsableW = totalW - 40.dp - 28.dp
        val gaugeSlotW = rowUsableW / 3.5f
        val gaugeDiameter = minOf(mainRowHeight - 30.dp, gaugeSlotW - 16.dp)
            .coerceAtLeast(120.dp)

        // Sur écran court (téléphone landscape) : labels masqués pour tassement
        val compactShortcuts = totalH < 500.dp

        Column(
            modifier = Modifier.fillMaxSize().padding(
                start = 20.dp, end = 20.dp, top = 20.dp,
                bottom = 36.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TopStatusBar(
                state = statusValues,
                gps = gpsState,
                nightMode = nightMode,
                fuelDisplayLiters = fuelDisplayLiters,
                oilDisplayLevel = oilDisplayLevel,
                rangeShowConsumption = rangeShowConsumption,
                onToggleNightMode = onToggleNightMode,
                onToggleFuelDisplay = onToggleFuelDisplay,
                onToggleOilDisplay = onToggleOilDisplay,
                onToggleRangeDisplay = onToggleRangeDisplay,
            )

            Spacer(Modifier.weight(1f))

            MainGaugesRow(
                speedKmh = { speed.value },
                rpm = { rpm.value },
                gpsSpeedKmh = gpsSpeedKmh,
                speedShowGps = speedShowGps,
                onToggleSpeedSource = onToggleSpeedSource,
                rowHeight = mainRowHeight,
                gaugeDiameter = gaugeDiameter,
            )

            Spacer(Modifier.weight(1f))

            if (primaryShortcut != null) {
                AndroidAutoHero(
                    shortcut = primaryShortcut,
                    onClick = { onLaunchShortcut(primaryShortcut.packageName) },
                    modifier = Modifier.fillMaxWidth().height(heroHeight),
                )
            }

            ShortcutsGrid(
                slots = gridSlots,
                tileSize = tileSize,
                showLabels = !compactShortcuts,
                isIdle = isIdle,
                onLaunch = onLaunchShortcut,
                onPickEmpty = onPickEmptySlot,
                onLongPressFilled = onLongPressFilledSlot,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
