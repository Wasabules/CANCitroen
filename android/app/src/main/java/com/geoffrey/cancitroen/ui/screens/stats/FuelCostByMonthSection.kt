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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.geoffrey.cancitroen.history.entities.RefuelEvent
import com.geoffrey.cancitroen.ui.components.GlassCard
import com.geoffrey.cancitroen.ui.components.MiniBarChart
import com.geoffrey.cancitroen.ui.theme.Dim

@Composable
internal fun FuelCostByMonthSection(refuels: List<RefuelEvent>) {
    val withCost = refuels.filter { (it.totalCost ?: 0.0) > 0 }
    if (withCost.isEmpty()) return

    val sdfMonth = remember { java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.FRANCE) }
    val sdfLabel = remember { java.text.SimpleDateFormat("MMM", java.util.Locale.FRANCE) }

    // Préremplit 12 derniers mois pour avoir les barres même à 0
    val cal = java.util.Calendar.getInstance()
    val now = System.currentTimeMillis()
    val monthsKeys = mutableListOf<String>()
    val monthsLabels = mutableListOf<String>()
    cal.timeInMillis = now
    cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
    val now12 = cal.timeInMillis - 11L * 31 * 24 * 3600 * 1000
    for (i in 11 downTo 0) {
        cal.timeInMillis = now
        cal.add(java.util.Calendar.MONTH, -i)
        monthsKeys += sdfMonth.format(cal.time)
        monthsLabels += sdfLabel.format(cal.time).replaceFirstChar { it.uppercase() }
    }
    val totals = LinkedHashMap<String, Double>().apply {
        monthsKeys.forEach { put(it, 0.0) }
    }
    for (r in withCost) {
        if (r.ts < now12) continue
        val key = sdfMonth.format(java.util.Date(r.ts))
        if (totals.containsKey(key)) totals[key] = (totals[key] ?: 0.0) + (r.totalCost ?: 0.0)
    }
    val bars = monthsKeys.zip(monthsLabels).map { (k, l) -> l to (totals[k] ?: 0.0) }
    val total12m = totals.values.sum()

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("💶 Dépense carburant — 12 mois",
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium)
                Text("Total : ${"%.0f".format(total12m)} €",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
            MiniBarChart(
                bars = bars,
                color = Color(0xFF66BB6A),
                height = 160.dp,
                yAxisFormatter = { "${it.toInt()} €" },
                tooltipFormatter = { l, v -> "$l  ·  ${"%.0f".format(v)} €" },
            )
        }
    }
}
