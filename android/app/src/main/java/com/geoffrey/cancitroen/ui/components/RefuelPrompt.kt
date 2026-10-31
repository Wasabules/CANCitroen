package com.geoffrey.cancitroen.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.geoffrey.cancitroen.history.FuelCalibration
import com.geoffrey.cancitroen.history.ReceiptEntry
import com.geoffrey.cancitroen.history.entities.RefuelEvent
import com.geoffrey.cancitroen.ui.vm.RefuelPrompt
import com.geoffrey.cancitroen.ui.vm.RefuelViewModel
import kotlinx.coroutines.delay
import java.util.Locale

/** Au-delà, la voiture roule : rien ne doit couvrir l'écran. */
private const val MOVING_KMH = 3.0
/** Arrêt minimal avant de poser la question (pas à chaque ralentissement). */
private const val STOPPED_BEFORE_PROMPT_MS = 10_000L

/**
 * Affiche la saisie du ticket de plein, **jamais en roulant** : elle disparaît
 * dès que la voiture repart et revient au prochain arrêt, saisie conservée.
 * Surcouche dans l'arbre Compose : ni fenêtre ni clavier système, qui feraient
 * réapparaître les barres sur ce ROM et sortiraient du mode immersif.
 */
@Composable
fun RefuelPromptHost(speedKmh: () -> Double?, vm: RefuelViewModel = viewModel()) {
    val prompt by vm.prompt.collectAsStateWithLifecycle()
    val current = prompt ?: return
    val moving by remember { derivedStateOf { (speedKmh() ?: 0.0) >= MOVING_KMH } }
    var ready by remember(current.event.id, current.event.ts) { mutableStateOf(current.manual) }
    LaunchedEffect(moving, current) {
        if (moving) {
            ready = false
        } else if (!ready) {
            delay(if (current.manual) 0L else STOPPED_BEFORE_PROMPT_MS)
            ready = true
        }
    }

    // Hors de la condition : la saisie survit à un départ et revient à l'arrêt.
    val id = current.event.id to current.event.ts
    val editing = current.event.userEntered
    var liters by rememberSaveable(id.first, id.second) {
        mutableStateOf(if (editing) "%.2f".format(Locale.FRANCE, current.event.litersAdded) else "")
    }
    var amount by rememberSaveable(id.first, id.second) {
        mutableStateOf(if (editing) "%.2f".format(Locale.FRANCE, current.event.totalCost ?: 0.0) else "")
    }
    if (moving || !ready) return

    BackHandler { vm.later(current) }
    RefuelPromptOverlay(
        prompt = current,
        liters = liters,
        amount = amount,
        onLitersChange = { liters = it },
        onAmountChange = { amount = it },
        onSave = { l, a -> vm.save(current.event, l, a) },
        onLater = { vm.later(current) },
        onDelete = { vm.delete(current.event) },
    )
}

@Composable
private fun RefuelPromptOverlay(
    prompt: RefuelPrompt,
    liters: String,
    amount: String,
    onLitersChange: (String) -> Unit,
    onAmountChange: (String) -> Unit,
    onSave: (Double, Double) -> Unit,
    onLater: () -> Unit,
    onDelete: () -> Unit,
) {
    val event = prompt.event
    var litersFocused by rememberSaveable(event.id) { mutableStateOf(true) }
    val price = FuelCalibration.pricePerLiter(ReceiptEntry.parse(liters), ReceiptEntry.parse(amount))
    val canSave = price != null

    // Capture tous les touchers : rien ne passe au pager en dessous.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .padding(horizontal = 40.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Text(
                    when {
                        event.id == 0L -> "Ajouter un plein"
                        prompt.manual -> "Ticket du plein"
                        else -> "Plein détecté"
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    gaugeSummary(event),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ReceiptField("Quantité", liters, "L", litersFocused) { litersFocused = true }
                    ReceiptField("Montant", amount, "€", !litersFocused) { litersFocused = false }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    when {
                        price != null -> "Soit %.3f €/L".format(Locale.FRANCE, price)
                        liters.isNotEmpty() && amount.isNotEmpty() -> "Valeurs incohérentes : vérifie le ticket"
                        else -> "Les deux valeurs sont sur le ticket ou la pompe"
                    },
                    color = if (price == null && liters.isNotEmpty() && amount.isNotEmpty())
                        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.weight(1f))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        enabled = canSave,
                        onClick = { onSave(ReceiptEntry.parse(liters)!!, ReceiptEntry.parse(amount)!!) },
                    ) { Text("Enregistrer") }
                    TextButton(onClick = onLater) { Text(if (prompt.manual) "Fermer" else "Plus tard") }
                    Spacer(Modifier.weight(1f))
                    if (event.id != 0L) {
                        DeleteButton(if (event.awaitingInput) "Pas un plein" else "Supprimer", onDelete)
                    }
                }
            }
            Keypad(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onKey = { key ->
                    if (litersFocused) onLitersChange(ReceiptEntry.append(liters, key))
                    else onAmountChange(ReceiptEntry.append(amount, key))
                },
                onBackspace = {
                    if (litersFocused) onLitersChange(liters.dropLast(1))
                    else onAmountChange(amount.dropLast(1))
                },
            )
        }
    }
}

private fun gaugeSummary(event: RefuelEvent): String {
    if (event.fuelPctAfter <= 0) return "Jauge inconnue"
    if (event.id == 0L) return "Jauge actuelle : ${event.fuelPctAfter} %"
    val gauge = "Jauge : ${event.fuelPctBefore} % → ${event.fuelPctAfter} %"
    return if (event.userEntered) gauge
    else gauge + " (environ %.0f L)".format(Locale.FRANCE, event.litersAdded)
}

@Composable
private fun RowScope.ReceiptField(
    label: String,
    value: String,
    unit: String,
    focused: Boolean,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier
            .weight(1f)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) accent else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Text(
            "${value.ifEmpty { "—" }} $unit",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.headlineSmall,
        )
    }
}

/** Deux appuis : un geste malheureux ne doit pas effacer un plein. */
@Composable
private fun DeleteButton(label: String, onDelete: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    TextButton(onClick = { if (armed) onDelete() else armed = true }) {
        Text(
            if (armed) "Confirmer ?" else label,
            color = if (armed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Keypad(modifier: Modifier, onKey: (Char) -> Unit, onBackspace: () -> Unit) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in listOf("123", "456", "789", ",0⌫")) {
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (key in row) {
                    FilledTonalButton(
                        onClick = { if (key == '⌫') onBackspace() else onKey(key) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) {
                        Text(key.toString(), style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        }
    }
}
