package com.geoffrey.cancitroen.ui.screens.stats

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.geoffrey.cancitroen.history.UnifiedDataPoint
import com.geoffrey.cancitroen.history.entities.RefuelEvent
import com.geoffrey.cancitroen.ui.components.GlassCard
import com.geoffrey.cancitroen.ui.components.MiniLineChart
import com.geoffrey.cancitroen.ui.components.TimeRange
import com.geoffrey.cancitroen.ui.components.aggregateMean
import com.geoffrey.cancitroen.ui.components.formatTooltipTs
import com.geoffrey.cancitroen.ui.components.formatTsForWindow
import com.geoffrey.cancitroen.ui.theme.Dim
import com.geoffrey.cancitroen.ui.theme.Text as TextColor

@Composable
internal fun FuelHistorySection(
    points: List<UnifiedDataPoint>,
    refuels: List<RefuelEvent>,
    range: TimeRange,
) {
    // Down-sampling (~200 points max) calculé une fois par jeu de points.
    val pts = remember(points) {
        aggregateMean(
            points.mapNotNull { s -> s.fuelPct?.let { pct -> s.ts.toDouble() to pct.toDouble() } },
            targetCount = 200,
        )
    }
    val markers = remember(refuels) { refuels.map { it.ts.toDouble() } }
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "⛽ Carburant",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "${refuels.size} plein(s)",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (pts.size < 2) {
                Spacer(Modifier.height(12.dp))
                Text("Pas assez de données sur cette période…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            Spacer(Modifier.height(12.dp))

            MiniLineChart(
                points = pts,
                color = MaterialTheme.colorScheme.primary,
                height = 200.dp,
                yMin = 0.0,
                yMax = 100.0,
                markers = markers,
                xAxisFormatter = { formatTsForWindow(it, range.durationMs) },
                yAxisFormatter = { "${it.toInt()}%" },
                tooltipFormatter = { (x, y) ->
                    "${formatTooltipTs(x)}  ·  ${y.toInt()} %"
                },
            )

            if (refuels.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Pleins détectés", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium)
                refuels.take(6).forEach { r ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(formatDate(r.ts),
                            color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${r.fuelPctBefore}% → ${r.fuelPctAfter}%  (~${"%.0f".format(r.litersAdded)} L)",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
