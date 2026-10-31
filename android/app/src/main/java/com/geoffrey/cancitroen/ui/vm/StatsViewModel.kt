package com.geoffrey.cancitroen.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geoffrey.cancitroen.App
import com.geoffrey.cancitroen.history.entities.RefuelEvent
import com.geoffrey.cancitroen.history.entities.Trip
import com.geoffrey.cancitroen.settings.FuelSettings
import com.geoffrey.cancitroen.ui.components.TimeRange
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.geoffrey.cancitroen.ui.screens.stats.StatsCharts
import com.geoffrey.cancitroen.ui.screens.stats.computeStatsCharts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = App.get().historyDao
    private val repo = App.get().historyRepo

    // ── Range temporelle (par défaut 24h live) ──
    private val _range = MutableStateFlow(
        run {
            val now = System.currentTimeMillis()
            TimeRange(now - 24L * 3600 * 1000, now)
        }
    )
    val range: StateFlow<TimeRange> = _range
    /** Explicite : `to` vieillit dès que la page n'est plus affichée. */
    private var followLive = true
    fun setRange(r: TimeRange) {
        followLive = r.isLive()
        _range.value = r
    }

    /** Recale la fenêtre sur l'instant présent quand on suit le direct. */
    fun tickLiveRange() {
        if (followLive) {
            val r = _range.value
            val now = System.currentTimeMillis()
            _range.value = TimeRange(now - r.durationMs, now)
        }
    }

    // ── Detail trajet sélectionné ──
    private val _detailedTrip = MutableStateFlow<Trip?>(null)
    val detailedTrip: StateFlow<Trip?> = _detailedTrip
    fun showTripDetail(trip: Trip) { _detailedTrip.value = trip }
    fun dismissTripDetail() { _detailedTrip.value = null }

    // ── Graphiques de la fenêtre (granularité auto), recalculés quand elle change ──
    internal val charts: StateFlow<StatsCharts> = _range
        .mapLatest { r ->
            val points = repo.pointsForRange(r.from, r.to)
            withContext(Dispatchers.Default) { computeStatsCharts(points, r.durationMs) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsCharts())

    val fuelSettings: StateFlow<FuelSettings> = App.get().settings.flow
        .map { it.fuel }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FuelSettings())

    // ── Pleins ──
    val allRefuels: StateFlow<List<RefuelEvent>> = dao
        .refuelsSince(System.currentTimeMillis() - 365L * 24 * 3600 * 1000)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allTrips: StateFlow<List<Trip>> = dao.recentTrips(200)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
