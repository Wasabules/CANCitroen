package com.geoffrey.cancitroen.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.geoffrey.cancitroen.history.FuelCalibration
import com.geoffrey.cancitroen.history.entities.Trip
import com.geoffrey.cancitroen.ui.components.PageTitle
import com.geoffrey.cancitroen.ui.components.TimeRangeBar
import com.geoffrey.cancitroen.ui.components.TripDetailSheet
import com.geoffrey.cancitroen.ui.vm.RefuelViewModel
import com.geoffrey.cancitroen.ui.vm.StatsViewModel
import com.geoffrey.cancitroen.ui.screens.stats.ActiveTripCard
import com.geoffrey.cancitroen.ui.screens.stats.ConsumptionPerTankSection
import com.geoffrey.cancitroen.ui.screens.stats.ConsumptionPerTripSection
import com.geoffrey.cancitroen.ui.screens.stats.DailyAverageSection
import com.geoffrey.cancitroen.ui.screens.stats.DistancePerDaySection
import com.geoffrey.cancitroen.ui.screens.stats.EmptyState
import com.geoffrey.cancitroen.ui.screens.stats.EngineTempSection
import com.geoffrey.cancitroen.ui.screens.stats.FuelCostByMonthSection
import com.geoffrey.cancitroen.ui.screens.stats.FuelHistorySection
import com.geoffrey.cancitroen.ui.screens.stats.RpmHistogramSection
import com.geoffrey.cancitroen.ui.screens.stats.SpeedHeatmapSection
import com.geoffrey.cancitroen.ui.screens.stats.TripsHistorySection
import kotlinx.coroutines.delay as kxDelay

/** < 60 s : au-delà, `TimeRange.isLive()` n'affiche plus le direct. */
private const val LIVE_REFRESH_MS = 30_000L

@Composable
fun StatsScreen(
    modifier: Modifier = Modifier,
    vm: StatsViewModel = viewModel(),
) {
    val range by vm.range.collectAsStateWithLifecycle()
    val charts by vm.charts.collectAsStateWithLifecycle()
    val allRefuels by vm.allRefuels.collectAsStateWithLifecycle()
    val allTrips by vm.allTrips.collectAsStateWithLifecycle()
    val detailedTrip by vm.detailedTrip.collectAsStateWithLifecycle()
    val fuel by vm.fuelSettings.collectAsStateWithLifecycle()
    val refuelVm: RefuelViewModel = viewModel()

    // Les graphiques se rafraîchissent avec la fenêtre, pas à chaque insert en
    // base (toutes les 5 s) : à leur échelle, ces 5 s ne se voient pas.
    LaunchedEffect(Unit) {
        while (true) {
            vm.tickLiveRange()
            kxDelay(LIVE_REFRESH_MS)
        }
    }

    // En direct, un plein qu'on vient d'ajouter est plus récent que la fin de la fenêtre.
    val refuels = remember(allRefuels, range) {
        allRefuels.filter { it.ts >= range.from && (it.ts <= range.to || range.isLive()) }
    }
    val activeTrip = remember(allTrips) { allTrips.firstOrNull { it.endTs == null } }
    val finishedTrips = remember(allTrips) { allTrips.filter { it.endTs != null } }
    val pastTrips = remember(finishedTrips, range) {
        finishedTrips.filter { it.startTs >= range.from && it.startTs <= range.to }
    }
    val costOf = remember(allRefuels, fuel) {
        { t: Trip -> FuelCalibration.tripCost(t, allRefuels, fuel.pricePerLiter, fuel.consumptionFactor) }
    }

    // Liste paresseuse : à l'ouverture de la page, seules les sections visibles
    // sont composées, pas la dizaine de graphiques.
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 60.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "title") {
            PageTitle("Statistiques")
        }

        // ── Barre de range globale (présets + nav + live) ──
        item(key = "range") {
            TimeRangeBar(
                range = range,
                onRangeChange = vm::setRange,
            )
        }

        activeTrip?.let { trip ->
            item(key = "active") { ActiveTripCard(trip) }
        }

        item(key = "fuel") {
            FuelHistorySection(
                pts = charts.fuel,
                refuels = refuels,
                range = range,
                onRefuelClick = refuelVm::edit,
                onAddRefuel = refuelVm::addManual,
            )
        }

        if (charts.temps.hasEnough) {
            item(key = "temps") { EngineTempSection(charts.temps, range = range) }
        }

        // Sections daily uniquement si fenêtre > 24 h
        charts.daily?.let { daily ->
            item(key = "daily") { DailyAverageSection(daily) }
        }

        if (pastTrips.isNotEmpty()) {
            item(key = "consTrip") { ConsumptionPerTripSection(pastTrips) }
        }
        charts.rpmBars?.let { bars ->
            item(key = "rpm") { RpmHistogramSection(bars) }
        }
        charts.speedGrid?.let { grid ->
            item(key = "heatmap") { SpeedHeatmapSection(grid) }
        }
        if (pastTrips.isNotEmpty()) {
            item(key = "trips") { TripsHistorySection(pastTrips, costOf, onTripClick = vm::showTripDetail) }
        }

        // Sur leur propre durée (14 jours, 12 mois, derniers pleins), pas celle de la barre.
        if (finishedTrips.isNotEmpty()) {
            item(key = "distance") { DistancePerDaySection(finishedTrips) }
        }
        item(key = "consTank") { ConsumptionPerTankSection(allRefuels, finishedTrips, fuel.tankCapacityLiters) }
        item(key = "cost") { FuelCostByMonthSection(allRefuels) }

        if (!charts.hasPoints && refuels.isEmpty() && allTrips.isEmpty()) {
            item(key = "empty") { EmptyState() }
        }
    }

    // Bottom sheet détail
    detailedTrip?.let { t ->
        TripDetailSheet(trip = t, cost = costOf(t), onDismiss = vm::dismissTripDetail)
    }
}
