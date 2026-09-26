package com.geoffrey.cancitroen.ui.screens.stats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.geoffrey.cancitroen.history.UnifiedDataPoint
import com.geoffrey.cancitroen.ui.components.GlassCard
import com.geoffrey.cancitroen.ui.components.MiniBarChart
import com.geoffrey.cancitroen.ui.components.aggregateByDay
import com.geoffrey.cancitroen.ui.theme.Dim

@Composable
internal fun DailyAverageSection(points: List<UnifiedDataPoint>) {
    // Calculé une fois par jeu de points, pas à chaque recomposition.
    val byDay = remember(points) {
        val movingSamples = points.filter { (it.speedKmh ?: 0f) > 1f }
        if (movingSamples.size < 5) return@remember null
        aggregateByDay(
            points = movingSamples.mapNotNull { s ->
                s.speedKmh?.let { s.ts.toDouble() to it.toDouble() }
            },
            reducer = { it.average() },
        ) to aggregateByDay(
            points = movingSamples.mapNotNull { s ->
                s.fuelInstL100?.let { s.ts.toDouble() to it.toDouble() }
            },
            reducer = { it.average() },
        )
    } ?: return
    val (speedByDay, consByDay) = byDay

    if (speedByDay.isEmpty() && consByDay.isEmpty()) return

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Text("📈 Moyennes par jour",
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium)
            Text("Calculé sur les samples 'en mouvement' (speed > 1 km/h)",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))

            if (speedByDay.isNotEmpty()) {
                Text("Vitesse moyenne",
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                MiniBarChart(
                    bars = speedByDay,
                    color = MaterialTheme.colorScheme.primary,
                    height = 130.dp,
                    yAxisFormatter = { "${it.toInt()}" },
                    tooltipFormatter = { l, v -> "$l  ·  ${"%.0f".format(v)} km/h moy" },
                )
            }
            if (consByDay.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("Conso instantanée moyenne",
                    color = Color(0xFFFFB74D).copy(alpha = 0.9f),
                    style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                MiniBarChart(
                    bars = consByDay,
                    color = Color(0xFFFFB74D),
                    height = 130.dp,
                    yAxisFormatter = { "%.1f".format(it) },
                    tooltipFormatter = { l, v -> "$l  ·  ${"%.1f".format(v)} l/100 moy" },
                )
            }
        }
    }
}
