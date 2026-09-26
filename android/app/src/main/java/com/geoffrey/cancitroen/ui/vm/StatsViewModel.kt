package com.geoffrey.cancitroen.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geoffrey.cancitroen.App
import com.geoffrey.cancitroen.history.UnifiedDataPoint
import com.geoffrey.cancitroen.history.entities.RefuelEvent
import com.geoffrey.cancitroen.history.entities.Trip
import com.geoffrey.cancitroen.ui.components.TimeRange
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

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
    fun setRange(r: TimeRange) { _range.value = r }

    /** Met à jour `to` à la valeur actuelle quand on est en mode live. */
    fun tickLiveRange() {
        val r = _range.value
        if (r.isLive()) {
            val now = System.currentTimeMillis()
            _range.value = TimeRange(now - r.durationMs, now)
        }
    }

    // ── Detail trajet sélectionné ──
    private val _detailedTrip = MutableStateFlow<Trip?>(null)
    val detailedTrip: StateFlow<Trip?> = _detailedTrip
    fun showTripDetail(trip: Trip) { _detailedTrip.value = trip }
    fun dismissTripDetail() { _detailedTrip.value = null }

    // ── Points filtrés par range (granularité auto) ──
    val points: StateFlow<List<UnifiedDataPoint>> = _range
        .flatMapLatest { r -> repo.pointsForRange(r.from, r.to) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Refuels & trips : on charge largement, on filtre côté UI ──
    val allRefuels: StateFlow<List<RefuelEvent>> = dao
        .refuelsSince(System.currentTimeMillis() - 365L * 24 * 3600 * 1000)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allTrips: StateFlow<List<Trip>> = dao.recentTrips(200)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
