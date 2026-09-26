package com.geoffrey.cancitroen.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.geoffrey.cancitroen.emf.EmfButton
import com.geoffrey.cancitroen.ui.theme.Dim
import com.geoffrey.cancitroen.ui.theme.Err
import com.geoffrey.cancitroen.ui.theme.Ok

private val OnDarkButton = Color(0xFF002B30)

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
                Text(
                    "🖥 Contrôle écran multifonction",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    if (active) "● ACTIF" else "OFF",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (active) Ok else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = onToggle,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (active) Err else Ok,
                    contentColor = if (active) Color.White else OnDarkButton,
                ),
                modifier = Modifier.height(56.dp),
            ) {
                Text(
                    if (active) "⏹ Désactiver" else "▶ Activer",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        // ── Grille remote, 4 colonnes, 7" landscape ──
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            // Col 1 : ESC, LEFT, AIRCON
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EmfBtn("✗ ESC", EmfButton.ESC, active, onPress, color = Err, contentColor = Color.White)
                EmfBtn("◀", EmfButton.LEFT, active, onPress)
                EmfBtn("❄ AIRCON", EmfButton.AIRCON, active, onPress)
            }
            // Col 2 : MENU, UP, DOWN
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EmfBtn("☰ MENU", EmfButton.MENU, active, onPress, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
                EmfBtn("▲", EmfButton.UP, active, onPress)
                EmfBtn("▼", EmfButton.DOWN, active, onPress)
            }
            // Col 3 : OK, RIGHT, DARK
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EmfBtn("✓ OK", EmfButton.OK, active, onPress, color = Ok, contentColor = OnDarkButton)
                EmfBtn("▶", EmfButton.RIGHT, active, onPress)
                EmfBtn("☾ DARK", EmfButton.DARK, active, onPress)
            }
            // Col 4 : MODE, PHONE, TRIP
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EmfBtn("⇋ MODE", EmfButton.MODE, active, onPress)
                EmfBtn("☎ PHONE", EmfButton.PHONE, active, onPress)
                EmfBtn("⊙ TRIP", EmfButton.TRIP, active, onPress)
            }
        }

        // ── Last action ──
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                lastAction?.let { "▶ ${it.name}" } ?: "— en attente —",
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun EmfBtn(
    label: String,
    button: EmfButton,
    enabled: Boolean,
    onPress: (EmfButton) -> Unit,
    color: Color? = null,
    contentColor: Color? = null,
) {
    val containerColor = color ?: MaterialTheme.colorScheme.surfaceVariant
    val txtColor = contentColor ?: MaterialTheme.colorScheme.onSurface
    Button(
        onClick = { onPress(button) },
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = txtColor,
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().height(64.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}
