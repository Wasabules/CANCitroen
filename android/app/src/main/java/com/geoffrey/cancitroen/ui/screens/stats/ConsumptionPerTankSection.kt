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
import com.geoffrey.cancitroen.history.FuelCalibration
import com.geoffrey.cancitroen.history.entities.RefuelEvent
import com.geoffrey.cancitroen.history.entities.Trip
import com.geoffrey.cancitroen.ui.components.GlassCard
import com.geoffrey.cancitroen.ui.components.MiniBarChart
import com.geoffrey.cancitroen.ui.components.SectionTitle
import com.geoffrey.cancitroen.ui.theme.AppIcons

/** Dernières mesures affichées. */
private const val MAX_BARS = 12

@Composable
internal fun ConsumptionPerTankSection(
    refuels: List<RefuelEvent>,
    trips: List<Trip>,
    capacityLiters: Float,
) {
    val (bars, check) = remember(refuels, trips, capacityLiters) {
        val sdf = java.text.SimpleDateFormat("d/M", java.util.Locale.FRANCE)
        FuelCalibration.tankConsumptions(refuels, capacityLiters)
            .takeLast(MAX_BARS)
            .map { sdf.format(java.util.Date(it.ts)) to it.l100 } to
            FuelCalibration.consumptionCheck(refuels, trips, capacityLiters)
    }
    if (bars.isEmpty()) return

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            SectionTitle("Conso réelle entre deux pleins", AppIcons.LocalGasStation)
            Text("D'après le ticket si les deux pleins finissent au même niveau, sinon d'après la jauge",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            if (check.realL100 != null && check.shownL100 != null) {
                Text("Réelle %.1f l/100 · indiquée par la voiture %.1f l/100 (%d mesures)"
                    .format(check.realL100, check.shownL100, check.fills),
                    color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
            MiniBarChart(
                bars = bars,
                color = Color(0xFFFFB74D),
                height = 160.dp,
                yAxisFormatter = { "%.1f".format(it) },
                tooltipFormatter = { l, v -> "$l  ·  ${"%.2f".format(v)} l/100 km" },
            )
        }
    }
}
