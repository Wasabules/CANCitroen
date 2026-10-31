package com.geoffrey.cancitroen.ui.screens.stats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.geoffrey.cancitroen.ui.components.GlassCard
import com.geoffrey.cancitroen.ui.components.MiniBarChart
import com.geoffrey.cancitroen.ui.components.SectionTitle
import com.geoffrey.cancitroen.ui.theme.AppIcons

@Composable
internal fun RpmHistogramSection(bars: List<Pair<String, Double>>) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            SectionTitle("Répartition du régime moteur", AppIcons.BarChart)
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
