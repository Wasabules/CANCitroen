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
import com.geoffrey.cancitroen.history.entities.Trip
import com.geoffrey.cancitroen.ui.components.GlassCard
import com.geoffrey.cancitroen.ui.components.MiniBarChart
import com.geoffrey.cancitroen.ui.components.SectionTitle
import com.geoffrey.cancitroen.ui.theme.AppIcons

@Composable
internal fun ConsumptionPerTripSection(trips: List<Trip>) {
    val sdf = remember { java.text.SimpleDateFormat("d/M", java.util.Locale.FRANCE) }
    val bars = remember(trips) {
        trips.filter { it.avgConsL100 != null }.take(20).reversed().map { t ->
            sdf.format(java.util.Date(t.startTs)) to t.avgConsL100!!.toDouble()
        }
    }
    if (bars.isEmpty()) return

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            SectionTitle("Conso moyenne par trajet", AppIcons.LocalGasStation)
            Spacer(Modifier.height(12.dp))
            MiniBarChart(
                bars = bars,
                color = MaterialTheme.colorScheme.primary,
                height = 160.dp,
                yAxisFormatter = { "%.1f".format(it) },
                tooltipFormatter = { l, v -> "$l  ·  ${"%.2f".format(v)} l/100 km" },
            )
        }
    }
}
