package com.geoffrey.cancitroen.ui.screens.stats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.geoffrey.cancitroen.history.entities.Trip
import com.geoffrey.cancitroen.ui.components.GlassCard
import com.geoffrey.cancitroen.ui.theme.Dim
import com.geoffrey.cancitroen.ui.theme.Text as TextColor

@Composable
internal fun TripsHistorySection(trips: List<Trip>, onTripClick: (Trip) -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Text(
                "🚗 Trajets récents",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            trips.take(15).forEach { t ->
                TripRow(t, onClick = { onTripClick(t) })
            }
        }
    }
}

@Composable
private fun TripRow(t: Trip, onClick: () -> Unit) {
    val dist = t.distanceKm
    val dur = t.durationMs?.div(1000)
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(formatDate(t.startTs), color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium)
            Text(
                listOfNotNull(
                    dist?.let { "%.1f km".format(it) },
                    dur?.let { formatDuration(it) },
                    t.avgSpeedKmh?.let { "%.0f km/h moy".format(it) },
                    t.avgConsL100?.let { "%.1f l/100".format(it) },
                ).joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            t.maxSpeedKmh?.let { "max %.0f".format(it) } ?: "—",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
