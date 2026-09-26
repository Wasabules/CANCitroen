package com.geoffrey.cancitroen.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.geoffrey.cancitroen.ui.components.TimeRangeBar
import com.geoffrey.cancitroen.ui.components.TripDetailSheet
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

@Composable
fun StatsScreen(
    modifier: Modifier = Modifier,
    vm: StatsViewModel = viewModel(),
) {
    val range by vm.range.collectAsStateWithLifecycle()
    val points by vm.points.collectAsStateWithLifecycle()
    val allRefuels by vm.allRefuels.collectAsStateWithLifecycle()
    val allTrips by vm.allTrips.collectAsStateWithLifecycle()
    val detailedTrip by vm.detailedTrip.collectAsStateWithLifecycle()

    // En mode live, fait avancer la fenêtre chaque minute : les nouveaux
    // points arrivent déjà via le Flow Room (un insert / 5 s), le décalage du
    // bord gauche n'a pas besoin de relancer la requête plus souvent.
    LaunchedEffect(range.isLive()) {
        while (range.isLive()) {
            kxDelay(60_000L)
            vm.tickLiveRange()
        }
    }

    val refuels = remember(allRefuels, range) { allRefuels.filter { it.ts in range.from..range.to } }
    val activeTrip = remember(allTrips) { allTrips.firstOrNull { it.endTs == null } }
    val pastTrips = remember(allTrips, range) {
        allTrips.filter { it.endTs != null && it.startTs >= range.from && it.startTs <= range.to }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "📊 Statistiques",
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.headlineSmall,
        )

        // ── Barre de range globale (présets + nav + live) ──
        TimeRangeBar(
            range = range,
            onRangeChange = vm::setRange,
        )

        if (activeTrip != null) {
            ActiveTripCard(activeTrip)
        }

        FuelHistorySection(points = points, refuels = refuels, range = range)

        EngineTempSection(points = points, range = range)

        // Sections daily uniquement si fenêtre > 24 h
        if (range.durationMs > 24L * 3600 * 1000) {
            DailyAverageSection(points = points)
        }

        if (pastTrips.isNotEmpty()) {
            ConsumptionPerTripSection(pastTrips)
            DistancePerDaySection(pastTrips)
            ConsumptionPerTankSection(refuels)
            FuelCostByMonthSection(allRefuels)
            RpmHistogramSection(points)
            SpeedHeatmapSection(points)
            TripsHistorySection(pastTrips, onTripClick = vm::showTripDetail)
        }

        // Bottom sheet détail
        detailedTrip?.let { t ->
            TripDetailSheet(trip = t, onDismiss = vm::dismissTripDetail)
        }

        if (points.isEmpty() && refuels.isEmpty() && allTrips.isEmpty()) {
            EmptyState()
        }

        Spacer(Modifier.height(40.dp))
    }
}
