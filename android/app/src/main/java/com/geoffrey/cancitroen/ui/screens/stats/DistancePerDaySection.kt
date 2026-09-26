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
import java.util.Calendar

@Composable
internal fun DistancePerDaySection(trips: List<Trip>) {
    val cal = remember { Calendar.getInstance() }
    val now = System.currentTimeMillis()
    val perDay: Map<String, Double> = remember(trips) {
        val sdfKey = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.FRANCE)
        val sdfLabel = java.text.SimpleDateFormat("d/M", java.util.Locale.FRANCE)
        val map = LinkedHashMap<String, Double>()
        // Préremplit 14 derniers jours pour avoir des barres même à 0
        for (i in 13 downTo 0) {
            cal.timeInMillis = now - i * 24L * 3600 * 1000
            val key = sdfKey.format(cal.time)
            map[key] = 0.0
        }
        for (t in trips) {
            val d = t.distanceKm ?: continue
            val key = sdfKey.format(java.util.Date(t.startTs))
            if (map.containsKey(key)) map[key] = (map[key] ?: 0.0) + d
        }
        // Convertir keys yyyy-MM-dd → d/M pour affichage
        val out = LinkedHashMap<String, Double>()
        for ((k, v) in map) {
            val date = sdfKey.parse(k) ?: continue
            out[sdfLabel.format(date)] = v
        }
        out
    }
    val bars = perDay.toList()

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Text("🛣️ Kilométrage — 14 derniers jours",
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            MiniBarChart(
                bars = bars,
                color = MaterialTheme.colorScheme.primary,
                height = 160.dp,
                yAxisFormatter = { "%.0f".format(it) },
                tooltipFormatter = { l, v -> "$l  ·  ${"%.1f".format(v)} km" },
            )
        }
    }
}
