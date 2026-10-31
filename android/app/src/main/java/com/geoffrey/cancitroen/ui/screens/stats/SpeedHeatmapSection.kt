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
import com.geoffrey.cancitroen.ui.components.SectionTitle
import com.geoffrey.cancitroen.ui.theme.AppIcons

@Composable
internal fun SpeedHeatmapSection(data: SpeedGrid) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            SectionTitle("Vitesse moyenne par heure de la semaine", AppIcons.CalendarMonth)
            Text("Plus la cellule est claire, plus tu roulais vite à cette heure-là",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            com.geoffrey.cancitroen.ui.components.HeatmapGrid(
                grid = data.grid,
                rowLabels = listOf("Lun", "Mar", "Mer", "Jeu", "Ven", "Sam", "Dim"),
                colLabelsEvery = 4,
                maxValue = data.maxValue,
                tooltipFormatter = { d, h, v ->
                    val day = listOf("Lun","Mar","Mer","Jeu","Ven","Sam","Dim")[d]
                    "$day ${"%02d".format(h)}h  ·  ${"%.0f".format(v)} km/h"
                },
            )
        }
    }
}
