package com.geoffrey.cancitroen.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geoffrey.cancitroen.swc.SteeringWheelHandler
import com.geoffrey.cancitroen.swc.SwcAction
import com.geoffrey.cancitroen.swc.SwcButton
import com.geoffrey.cancitroen.swc.SwcDispatcher
import com.geoffrey.cancitroen.swc.SwcEndpoint
import com.geoffrey.cancitroen.swc.SwcMapping
import com.geoffrey.cancitroen.swc.allowedEndpoints
import com.geoffrey.cancitroen.ui.components.SectionCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SwcMappingSection(
    mapping: SwcMapping,
    onChange: (SwcButton, SwcAction, SwcEndpoint) -> Unit,
    onReset: () -> Unit,
) {
    val lastDetected by SteeringWheelHandler.lastDetected.collectAsStateWithLifecycle()
    val lastRaw by SteeringWheelHandler.lastRaw.collectAsStateWithLifecycle()
    // Dispatcher local pour le bouton "Tester" : on n'a pas besoin de passer
    // par le service CAN, on simule juste un appui ici-et-maintenant.
    val ctx = LocalContext.current
    val testDispatcher = remember(ctx) { SwcDispatcher(ctx.applicationContext) }
    // Feedback "✓ envoyé" éphémère par bouton testé. Le timestamp permet aux
    // recompositions de re-déclencher l'animation de fade si on test le même
    // bouton deux fois.
    var lastTested by remember { mutableStateOf<Pair<SwcButton, Long>?>(null) }
    val scope = rememberCoroutineScope()

    SectionCard("🎛 Mapping des boutons volant") {
        Text(
            "Pour chaque bouton physique du comodo, choisis UNE action et UN canal de diffusion. " +
                "Plusieurs canaux simultanés provoquent des doubles déclenchements (typiquement Android Auto qui saute deux pistes).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        // Indicateur live : permet de vérifier quel bouton la voiture envoie
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("📡 Test live", fontWeight = FontWeight.Bold)
            val label = lastDetected?.displayName ?: if (lastRaw != 0) "raw 0x%02X (inconnu)".format(lastRaw) else "—"
            Text(
                label,
                color = if (lastDetected != null) Color(0xFF66BB6A) else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.weight(1f))
            Text("0x%02X".format(lastRaw), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "Appuie sur un bouton du volant : son nom doit s'afficher ci-dessus.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        SwcButton.values().forEach { button ->
            val entry = mapping.forButton(button)
            val justTested = lastTested?.first == button &&
                (System.currentTimeMillis() - (lastTested?.second ?: 0L)) < 1800L
            ButtonMappingRow(
                button = button,
                action = entry.action,
                endpoint = entry.endpoint,
                justTested = justTested,
                onActionChange = { newAction ->
                    // Si l'endpoint actuel n'est plus valide pour la nouvelle action, on retombe sur AUTO
                    val allowed = allowedEndpoints(newAction)
                    val ep = if (entry.endpoint in allowed) entry.endpoint else SwcEndpoint.AUTO
                    onChange(button, newAction, ep)
                },
                onEndpointChange = { newEndpoint ->
                    onChange(button, entry.action, newEndpoint)
                },
                onTest = {
                    testDispatcher.dispatch(entry.action, entry.endpoint)
                    val ts = System.currentTimeMillis()
                    lastTested = button to ts
                    // Auto-clear le feedback après 1.8s pour relancer une
                    // recomposition et faire disparaître le badge.
                    scope.launch {
                        delay(1800L)
                        if (lastTested?.second == ts) lastTested = null
                    }
                },
            )
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onReset) {
            Text("Réinitialiser aux valeurs par défaut")
        }
    }
}

@Composable
private fun ButtonMappingRow(
    button: SwcButton,
    action: SwcAction,
    endpoint: SwcEndpoint,
    justTested: Boolean,
    onActionChange: (SwcAction) -> Unit,
    onEndpointChange: (SwcEndpoint) -> Unit,
    onTest: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // En-tête : nom du bouton + badge "✓ envoyé" éphémère après un test
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "🔘 ${button.displayName}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (justTested) {
                Text(
                    "✓ envoyé",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF66BB6A),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        DropdownPicker(
            label = "Action",
            currentLabel = action.label,
            options = SwcAction.values().toList(),
            optionLabel = { it.label },
            onSelect = onActionChange,
        )
        if (action != SwcAction.NONE) {
            val allowed = allowedEndpoints(action)
            // Picker canal + bouton "Tester" côte à côte. Le picker occupe le
            // weight() pour rester full-width sur les petits comodos, le bouton
            // reste compact à droite.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    DropdownPicker(
                        label = "Canal",
                        currentLabel = endpoint.label,
                        sublabel = endpoint.description,
                        options = allowed,
                        optionLabel = { it.label },
                        optionSublabel = { it.description },
                        onSelect = onEndpointChange,
                    )
                }
                FilledTonalButton(
                    onClick = onTest,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text("▶ Tester", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun <T> DropdownPicker(
    label: String,
    currentLabel: String,
    sublabel: String? = null,
    options: List<T>,
    optionLabel: (T) -> String,
    optionSublabel: ((T) -> String)? = null,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(currentLabel, style = MaterialTheme.typography.bodyMedium)
                if (sublabel != null) Text(
                    sublabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("▾")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(optionLabel(opt))
                            if (optionSublabel != null) Text(
                                optionSublabel(opt),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(opt)
                    },
                )
            }
        }
    }
}
