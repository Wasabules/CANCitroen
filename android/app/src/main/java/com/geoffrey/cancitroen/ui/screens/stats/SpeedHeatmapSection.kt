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
import com.geoffrey.cancitroen.ui.theme.Dim
import java.util.Calendar

@Composable
internal fun SpeedHeatmapSection(points: List<UnifiedDataPoint>) {
    if (points.isEmpty()) return
    // Grille calculée (et donc pointerInput(grid) stable) une fois par jeu de points.
    val (grid, maxVal) = remember(points) { speedGrid(points) } ?: return


    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Text("🗓️ Vitesse moy par heure de la semaine",
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium)
            Text("Plus la cellule est claire, plus tu roulais vite à cette heure-là",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            com.geoffrey.cancitroen.ui.components.HeatmapGrid(
                grid = grid,
                rowLabels = listOf("Lun", "Mar", "Mer", "Jeu", "Ven", "Sam", "Dim"),
                colLabelsEvery = 4,
                maxValue = maxVal,
                tooltipFormatter = { d, h, v ->
                    val day = listOf("Lun","Mar","Mer","Jeu","Ven","Sam","Dim")[d]
                    "$day ${"%02d".format(h)}h  ·  ${"%.0f".format(v)} km/h"
                },
            )
        }
    }
}

/** Vitesse moyenne par (jour de semaine, heure) ; null si aucune donnée en mouvement. */
private fun speedGrid(points: List<UnifiedDataPoint>): Pair<Array<DoubleArray>, Double>? {
    val sums = Array(7) { DoubleArray(24) }
    val counts = Array(7) { IntArray(24) }
    val cal = Calendar.getInstance()
    for (s in points) {
        val sp = s.speedKmh ?: continue
        if (sp < 1f) continue   // ignore le stationnaire
        cal.timeInMillis = s.ts
        // Compose Calendar.DAY_OF_WEEK : Sunday=1..Saturday=7. On remappe Lundi=0..Dimanche=6.
        val rawDow = cal.get(Calendar.DAY_OF_WEEK)
        val dow = (rawDow + 5) % 7
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        sums[dow][hour] += sp
        counts[dow][hour]++
    }
    val grid = Array(7) { d ->
        DoubleArray(24) { h ->
            if (counts[d][h] > 0) sums[d][h] / counts[d][h] else 0.0
        }
    }
    val maxVal = grid.maxOf { row -> row.max() }.takeIf { it > 0 } ?: return null
    return grid to maxVal
}
