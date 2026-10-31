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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.geoffrey.cancitroen.history.entities.RefuelEvent
import com.geoffrey.cancitroen.ui.components.GlassCard
import com.geoffrey.cancitroen.ui.components.MiniLineChart
import com.geoffrey.cancitroen.ui.components.SectionTitle
import com.geoffrey.cancitroen.ui.components.TimeRange
import com.geoffrey.cancitroen.ui.components.formatTooltipTs
import com.geoffrey.cancitroen.ui.components.formatTsForWindow
import com.geoffrey.cancitroen.ui.theme.AppIcons
import com.geoffrey.cancitroen.ui.theme.Dim
import com.geoffrey.cancitroen.ui.theme.Text as TextColor

@Composable
internal fun FuelHistorySection(
    pts: List<Pair<Double, Double>>,
    refuels: List<RefuelEvent>,
    range: TimeRange,
    onRefuelClick: (RefuelEvent) -> Unit,
    onAddRefuel: () -> Unit,
) {
    val markers = remember(refuels) { refuels.map { it.ts.toDouble() } }
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle("Carburant", AppIcons.LocalGasStation)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${refuels.size} plein(s)",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onAddRefuel) { Text("+ Ajouter") }
                }
            }

            if (pts.size < 2) {
                Spacer(Modifier.height(12.dp))
                Text("Pas assez de données sur cette période…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            } else {
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
            }

            if (refuels.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Pleins — toucher pour saisir ou corriger le ticket",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium)
                refuels.take(6).forEach { r ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { onRefuelClick(r) }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(formatDate(r.ts),
                            color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
                        Text(
                            refuelSummary(r),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

private fun refuelSummary(r: RefuelEvent): String {
    val gauge = if (r.fuelPctAfter > 0) "${r.fuelPctBefore}% → ${r.fuelPctAfter}%" else "Jauge inconnue"
    return when {
        r.userEntered -> "$gauge  ·  ${"%.2f".format(r.litersAdded)} L  ·  ${"%.2f".format(r.totalCost ?: 0.0)} €" +
            "  ·  ${"%.3f".format(r.pricePerLiter ?: 0.0)} €/L"
        r.awaitingInput -> "$gauge  (~${"%.0f".format(r.litersAdded)} L)  ·  ticket à saisir"
        else -> "$gauge  (~${"%.0f".format(r.litersAdded)} L)"
    }
}
