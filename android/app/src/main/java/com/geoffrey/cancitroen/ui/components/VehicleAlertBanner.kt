package com.geoffrey.cancitroen.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.geoffrey.cancitroen.decode.VehicleAlert
import com.geoffrey.cancitroen.decode.VehicleState
import com.geoffrey.cancitroen.decode.activeAlerts
import com.geoffrey.cancitroen.ui.theme.AppIcons
import kotlinx.coroutines.delay

/** Une valeur isolée au réveil de la BSI ne doit pas afficher d'alerte. */
private const val ALERT_CONFIRM_MS = 3_000L

/**
 * Bandeau des alertes graves, sur toutes les pages. Fermable : les signaux n'ont
 * pas tous été vérifiés sur la voiture, une fausse alerte ne doit pas gêner la
 * conduite. Une alerte fermée revient si elle disparaît puis se déclenche à nouveau.
 */
@Composable
fun VehicleAlertBanner(state: () -> VehicleState) {
    val active by remember { derivedStateOf { activeAlerts(state()) } }
    var shown by remember { mutableStateOf(emptyList<VehicleAlert>()) }
    var dismissed by remember { mutableStateOf(emptySet<VehicleAlert>()) }
    LaunchedEffect(active) {
        shown = shown.filter { it in active }
        dismissed = dismissed.intersect(active.toSet())
        if (active.any { it !in shown }) {
            delay(ALERT_CONFIRM_MS)
            shown = active
        }
    }

    val visible = shown.filter { it !in dismissed }
    if (visible.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(Color(0xFFC62828), RoundedCornerShape(12.dp))
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(vertical = 10.dp)) {
            for (alert in visible) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(AppIcons.Warning, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                    Text(alert.message, color = Color.White, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        Icon(
            AppIcons.Close,
            contentDescription = "Fermer",
            tint = Color.White,
            modifier = Modifier
                .clickable { dismissed = dismissed + visible }
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .size(24.dp),
        )
    }
}
