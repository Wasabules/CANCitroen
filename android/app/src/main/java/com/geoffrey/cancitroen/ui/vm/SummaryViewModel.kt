package com.geoffrey.cancitroen.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geoffrey.cancitroen.App
import com.geoffrey.cancitroen.history.entities.RefuelEvent
import com.geoffrey.cancitroen.history.entities.Trip
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class SummaryViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = App.get().historyDao
    private val oneYearAgo = System.currentTimeMillis() - 365L * 24 * 3600 * 1000

    val trips: StateFlow<List<Trip>> = dao.tripsSince(oneYearAgo)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val refuels: StateFlow<List<RefuelEvent>> = dao.refuelsSince(oneYearAgo)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
