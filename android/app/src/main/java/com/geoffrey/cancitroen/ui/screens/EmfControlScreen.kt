package com.geoffrey.cancitroen.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.geoffrey.cancitroen.emf.EmfButton
import com.geoffrey.cancitroen.ui.components.PageTitle
import com.geoffrey.cancitroen.ui.theme.AppIcons
import com.geoffrey.cancitroen.ui.theme.Ok

@Composable
fun EmfControlScreen(
    active: Boolean,
    lastAction: EmfButton?,
    onToggle: () -> Unit,
    onPress: (EmfButton) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── Header + toggle ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                PageTitle("Écran multifonction")
                Text(
                    if (active) "Actif" else "Désactivé",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (active) Ok else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (active) {
                OutlinedButton(
                    onClick = onToggle,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.height(48.dp),
                ) {
                    Icon(AppIcons.Stop, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Désactiver", style = MaterialTheme.typography.titleMedium)
                }
            } else {
                Button(onClick = onToggle, modifier = Modifier.height(48.dp)) {
                    Icon(AppIcons.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Activer", style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        // ── Grille remote, 4 colonnes, 7" landscape ──
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            // Col 1 : ESC, LEFT, AIRCON
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EmfBtn(EmfButton.ESC, active, onPress, Modifier.weight(1f))
                EmfBtn(EmfButton.LEFT, active, onPress, Modifier.weight(1f))
                EmfBtn(EmfButton.AIRCON, active, onPress, Modifier.weight(1f))
            }
            // Col 2 : MENU, UP, DOWN
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EmfBtn(EmfButton.MENU, active, onPress, Modifier.weight(1f))
                EmfBtn(EmfButton.UP, active, onPress, Modifier.weight(1f))
                EmfBtn(EmfButton.DOWN, active, onPress, Modifier.weight(1f))
            }
            // Col 3 : OK, RIGHT, DARK
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EmfBtn(EmfButton.OK, active, onPress, Modifier.weight(1f), highlighted = true)
                EmfBtn(EmfButton.RIGHT, active, onPress, Modifier.weight(1f))
                EmfBtn(EmfButton.DARK, active, onPress, Modifier.weight(1f))
            }
            // Col 4 : MODE, PHONE, TRIP
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EmfBtn(EmfButton.MODE, active, onPress, Modifier.weight(1f))
                EmfBtn(EmfButton.PHONE, active, onPress, Modifier.weight(1f))
                EmfBtn(EmfButton.TRIP, active, onPress, Modifier.weight(1f))
            }
        }

        // ── Last action ──
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                lastAction?.let { "Dernière commande : ${visualOf(it).label}" } ?: "Aucune commande envoyée",
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private class EmfVisual(val label: String, val icon: ImageVector?, val showLabel: Boolean = true)

private fun visualOf(button: EmfButton): EmfVisual = when (button) {
    EmfButton.ESC -> EmfVisual("Retour", AppIcons.Close)
    EmfButton.MENU -> EmfVisual("Menu", AppIcons.Menu)
    EmfButton.OK -> EmfVisual("OK", AppIcons.Check)
    EmfButton.MODE -> EmfVisual("Mode", AppIcons.SwapHoriz)
    EmfButton.LEFT -> EmfVisual("Gauche", AppIcons.ChevronLeft, showLabel = false)
    EmfButton.UP -> EmfVisual("Haut", AppIcons.ExpandLess, showLabel = false)
    EmfButton.RIGHT -> EmfVisual("Droite", AppIcons.ChevronRight, showLabel = false)
    EmfButton.DOWN -> EmfVisual("Bas", AppIcons.ExpandMore, showLabel = false)
    EmfButton.PHONE -> EmfVisual("Téléphone", AppIcons.Phone)
    EmfButton.AIRCON -> EmfVisual("Climatisation", AppIcons.AcUnit)
    EmfButton.DARK -> EmfVisual("Sombre", AppIcons.DarkMode)
    EmfButton.TRIP -> EmfVisual("Trajet", AppIcons.Route)
}

@Composable
private fun EmfBtn(
    button: EmfButton,
    enabled: Boolean,
    onPress: (EmfButton) -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    val visual = visualOf(button)
    Button(
        onClick = { onPress(button) },
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (highlighted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        visual.icon?.let {
            Icon(
                it,
                contentDescription = visual.label,
                modifier = Modifier.size(if (visual.showLabel) 20.dp else 28.dp),
            )
        }
        if (visual.showLabel) {
            Spacer(Modifier.width(8.dp))
            Text(visual.label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
    }
}
