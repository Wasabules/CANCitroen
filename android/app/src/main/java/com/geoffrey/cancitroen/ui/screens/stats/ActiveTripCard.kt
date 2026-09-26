package com.geoffrey.cancitroen.ui.screens.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geoffrey.cancitroen.App
import com.geoffrey.cancitroen.history.entities.Trip
import com.geoffrey.cancitroen.ui.components.GlassCard
import com.geoffrey.cancitroen.ui.components.MiniLineChart
import com.geoffrey.cancitroen.ui.theme.Dim
import com.geoffrey.cancitroen.ui.theme.Text as TextColor

@Composable
internal fun ActiveTripCard(trip: Trip) {
    val dao = remember { App.get().historyDao }
    val now = System.currentTimeMillis()
    // Chart : 5 dernières minutes uniquement, pour ne pas materialiser
    // 4h × 720 samples/h = 2880 rows à chaque INSERT (toutes les 5s).
    // Cutoff coulisse toutes les 30s pour garder la fenêtre fraîche sans
    // re-créer le Flow Room à chaque recomposition.
    var cutoff by remember(trip.id) {
        mutableLongStateOf(System.currentTimeMillis() - 5 * 60 * 1000L)
    }
    LaunchedEffect(trip.id) {
        while (true) {
            delay(30_000L)
            cutoff = System.currentTimeMillis() - 5 * 60 * 1000L
        }
    }
    // Flows mémorisés : collectAsStateWithLifecycle redémarre la requête dès
    // que l'instance du Flow change, donc à chaque recomposition sans remember.
    val recent by remember(trip.id, cutoff) { dao.samplesForTripSince(trip.id, cutoff) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    // Aggregates : calculés côté SQL (Room AVG/MAX) — un row, pas 2880.
    val aggregates by remember(trip.id) { dao.tripLiveAggregates(trip.id) }
        .collectAsStateWithLifecycle(initialValue = null)
    val durationS = (now - trip.startTs) / 1000
    val distKm = aggregates?.maxOdoKm?.let { it - (trip.startOdoKm ?: it) } ?: 0.0
    val avgSpeed = aggregates?.avgSpeedKmh
    val avgCons = aggregates?.avgConsL100

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF66BB6A))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Trajet en cours",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatChip("Durée", formatDuration(durationS))
                StatChip("Distance", "%.1f km".format(distKm))
                StatChip("Vitesse moy", avgSpeed?.let { "%.0f km/h".format(it) } ?: "—")
                StatChip("Conso moy", avgCons?.let { "%.1f l/100".format(it) } ?: "—")
            }

            // Mini-courbe vitesse sur 5 dernières minutes (samples déjà bornés)
            if (recent.size >= 4) {
                Spacer(Modifier.height(16.dp))
                Text("Vitesse — 5 dernières min",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                MiniLineChart(
                    points = recent.map {
                        ((it.ts - recent.first().ts) / 1000.0) to ((it.speedKmh ?: 0f).toDouble())
                    },
                    color = MaterialTheme.colorScheme.primary,
                    height = 100.dp,
                    yMin = 0.0,
                )
            }
        }
    }
}
