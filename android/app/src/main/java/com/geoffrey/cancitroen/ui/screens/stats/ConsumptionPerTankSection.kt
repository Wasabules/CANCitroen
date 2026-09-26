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
import com.geoffrey.cancitroen.history.entities.RefuelEvent
import com.geoffrey.cancitroen.ui.components.GlassCard
import com.geoffrey.cancitroen.ui.components.MiniBarChart
import com.geoffrey.cancitroen.ui.theme.Dim

@Composable
internal fun ConsumptionPerTankSection(refuels: List<RefuelEvent>) {
    if (refuels.size < 2) return
    // refuels est trié desc par ts → on remet en ordre asc
    val sorted = refuels.sortedBy { it.ts }
    // Pour chaque pair (n-1, n) avec odo connus : litres ajoutés au n / (odo[n] - odo[n-1]) * 100
    val sdfLabel = remember { java.text.SimpleDateFormat("d/M", java.util.Locale.FRANCE) }
    val bars = mutableListOf<Pair<String, Double>>()
    for (i in 1 until sorted.size) {
        val a = sorted[i - 1]
        val b = sorted[i]
        val da = a.odoKm
        val db = b.odoKm
        if (da == null || db == null) continue
        val km = db - da
        if (km <= 0) continue
        val l100 = (b.litersAdded.toDouble() / km) * 100.0
        if (l100 in 2.0..25.0) {  // filtre valeurs aberrantes
            bars += sdfLabel.format(java.util.Date(b.ts)) to l100
        }
    }
    if (bars.isEmpty()) return

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Text("⛽ Conso réelle au plein",
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium)
            Text("Litres ajoutés ÷ km parcourus depuis le précédent plein",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
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
