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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.geoffrey.cancitroen.history.UnifiedDataPoint
import com.geoffrey.cancitroen.ui.components.GlassCard
import com.geoffrey.cancitroen.ui.components.MiniBarChart
import com.geoffrey.cancitroen.ui.theme.Dim

@Composable
internal fun RpmHistogramSection(points: List<UnifiedDataPoint>) {
    val bars = remember(points) {
        val rpms = points.mapNotNull { it.rpm }.filter { it > 0 }
        if (rpms.isEmpty()) return@remember null
        val buckets = listOf(0..1500, 1500..2500, 2500..3500, 3500..4500, 4500..7000)
        val labels = listOf("<1.5k", "1.5-2.5k", "2.5-3.5k", "3.5-4.5k", ">4.5k")
        val counts = buckets.map { range ->
            rpms.count { (it.toInt() in range.first until range.last) }.toDouble()
        }
        val total = counts.sum().takeIf { it > 0 } ?: return@remember null
        labels.zip(counts.map { (it / total) * 100.0 })
    } ?: return

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Text("⚙️ Distribution du régime moteur",
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium)
            Text("Temps passé dans chaque tranche RPM (% des samples)",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            MiniBarChart(
                bars = bars,
                color = MaterialTheme.colorScheme.primary,
                height = 160.dp,
                yAxisFormatter = { "${it.toInt()}%" },
                tooltipFormatter = { l, v -> "$l  ·  ${"%.1f".format(v)} %" },
            )
        }
    }
}
