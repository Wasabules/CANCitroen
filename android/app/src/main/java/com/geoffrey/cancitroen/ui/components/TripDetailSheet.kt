package com.geoffrey.cancitroen.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.geoffrey.cancitroen.App
import com.geoffrey.cancitroen.history.RollupAggregator
import com.geoffrey.cancitroen.history.dao.HistoryDao
import com.geoffrey.cancitroen.history.entities.Trip
import com.geoffrey.cancitroen.ui.theme.Dim
import com.geoffrey.cancitroen.ui.theme.Text as TextColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bottom sheet plein-écran montrant le détail d'un trajet :
 *  - Récap (date, durée, distance, vitesse moy/max, conso moy)
 *  - Multi-curve chart : vitesse + RPM/100 + conso instantanée + T° eau
 *
 *  Le RPM est divisé par 100 dans la même série pour rester sur l'échelle 0-150
 *  qu'on partage avec la vitesse — sinon les ordres de grandeur écraseraient les
 *  autres courbes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailSheet(
    trip: Trip,
    onDismiss: () -> Unit,
) {
    // Chargé une fois, hors du main thread (null = en cours).
    val curves by produceState<TripCurves?>(initialValue = null, trip.id) {
        value = withContext(Dispatchers.IO) { loadTripCurves(App.get().historyDao, trip) }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val sdf = remember {
        SimpleDateFormat("EEEE d MMMM HH:mm", Locale.FRANCE)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "🚗 Détail trajet",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                sdf.format(Date(trip.startTs)).replaceFirstChar { it.uppercase() },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )

            // Stats récap
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatRow("Distance", trip.distanceKm?.let { "%.1f km".format(it) } ?: "—")
                StatRow("Durée", trip.durationMs?.let { formatDur(it / 1000) } ?: "—")
                StatRow("Vitesse moy", trip.avgSpeedKmh?.let { "%.0f km/h".format(it) } ?: "—")
                StatRow("Vitesse max", trip.maxSpeedKmh?.let { "%.0f km/h".format(it) } ?: "—")
                StatRow("Conso moy", trip.avgConsL100?.let { "%.1f l/100".format(it) } ?: "—")
                StatRow("Carburant",
                    if (trip.startFuelPct != null && trip.endFuelPct != null)
                        "${trip.startFuelPct}% → ${trip.endFuelPct}%"
                    else "—")
            }

            val c = curves ?: return@Column
            if (c.speed.size < 4) {
                Spacer(Modifier.height(20.dp))
                Text("Pas assez d'échantillons pour tracer une courbe" +
                        " (le détail minute par minute est conservé 7 jours).",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            val speed = c.speed
            val rpmDiv100 = c.rpmDiv100
            val cons = c.cons
            val coolant = c.coolant

            Spacer(Modifier.height(8.dp))
            Text("Vitesse + RPM (÷100)",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            MultiLineChart(
                series = listOf(
                    ChartSeries("Vitesse", MaterialTheme.colorScheme.primary, speed),
                    ChartSeries("RPM÷100", Color(0xFFFFB74D), rpmDiv100),
                ),
                height = 200.dp,
                yMin = 0.0,
                xAxisFormatter = { "%.0f min".format(it) },
                yAxisFormatter = { "%.0f".format(it) },
            )

            if (cons.size >= 2) {
                Spacer(Modifier.height(12.dp))
                Text("Consommation instantanée (l/100)",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                MiniLineChart(
                    points = cons,
                    color = Color(0xFFEF5350),
                    height = 160.dp,
                    yMin = 0.0,
                    xAxisFormatter = { "%.0f min".format(it) },
                    yAxisFormatter = { "%.0f".format(it) },
                    tooltipFormatter = { (x, y) ->
                        "${"%.0f".format(x)} min  ·  ${"%.1f".format(y)} l/100"
                    },
                )
            }

            if (coolant.size >= 2) {
                Spacer(Modifier.height(12.dp))
                Text("T° eau",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                MiniLineChart(
                    points = coolant,
                    color = MaterialTheme.colorScheme.primary,
                    height = 140.dp,
                    yMin = 0.0,
                    yMax = 130.0,
                    xAxisFormatter = { "%.0f min".format(it) },
                    yAxisFormatter = { "${it.toInt()}°" },
                )
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

/** Séries (minutes depuis le départ, valeur) prêtes à tracer. */
private class TripCurves(
    val speed: List<Pair<Double, Double>>,
    val rpmDiv100: List<Pair<Double, Double>>,
    val cons: List<Pair<Double, Double>>,
    val coolant: List<Pair<Double, Double>>,
)

/**
 * Samples bruts du trajet s'ils existent encore (gardés 6 h par
 * [RollupAggregator]), sinon agrégats 1 min sur la plage du trajet (7 j).
 */
private suspend fun loadTripCurves(dao: HistoryDao, trip: Trip): TripCurves {
    val raw = dao.samplesForTripOnce(trip.id)
    if (raw.size >= 4) {
        val t0 = raw.first().ts
        fun m(ts: Long) = (ts - t0) / 60_000.0
        return TripCurves(
            speed = raw.mapNotNull { s -> s.speedKmh?.let { m(s.ts) to it.toDouble() } },
            rpmDiv100 = raw.mapNotNull { s -> s.rpm?.let { m(s.ts) to it / 100.0 } },
            cons = raw.mapNotNull { s -> s.fuelInstL100?.let { m(s.ts) to it.toDouble() } },
            coolant = raw.mapNotNull { s -> s.tCoolant?.let { m(s.ts) to it.toDouble() } },
        )
    }
    val end = trip.endTs ?: System.currentTimeMillis()
    val from = trip.startTs - Math.floorMod(trip.startTs, RollupAggregator.ONE_MIN)
    val agg = dao.aggregatesInRangeOnce(RollupAggregator.ONE_MIN, from, end + 1)
    // Bucket = début de minute : peut précéder le départ de quelques secondes.
    fun m(ts: Long) = maxOf(0.0, (ts - trip.startTs) / 60_000.0)
    return TripCurves(
        speed = agg.mapNotNull { a -> a.speedAvg?.let { m(a.ts) to it.toDouble() } },
        rpmDiv100 = agg.mapNotNull { a -> a.rpmAvg?.let { m(a.ts) to it / 100.0 } },
        cons = agg.mapNotNull { a -> a.fuelInstAvg?.let { m(a.ts) to it.toDouble() } },
        coolant = agg.mapNotNull { a -> a.tCoolantAvg?.let { m(a.ts) to it.toDouble() } },
    )
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(value, color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium)
    }
}

private fun formatDur(s: Long): String {
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return when {
        h > 0 -> "%dh%02d".format(h, m)
        m > 0 -> "%dmin %ds".format(m, sec)
        else -> "%ds".format(sec)
    }
}
