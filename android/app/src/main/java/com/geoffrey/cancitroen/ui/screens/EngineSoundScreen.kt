package com.geoffrey.cancitroen.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.geoffrey.cancitroen.audio.EngineProfile
import com.geoffrey.cancitroen.audio.EngineProfiles
import com.geoffrey.cancitroen.audio.MemeEngine
import com.geoffrey.cancitroen.ui.components.CardCorner
import com.geoffrey.cancitroen.ui.components.DropdownPicker
import com.geoffrey.cancitroen.ui.components.GlassCard
import com.geoffrey.cancitroen.ui.components.PageTitle
import com.geoffrey.cancitroen.ui.components.SectionTitle
import com.geoffrey.cancitroen.ui.theme.AppIcons
import com.geoffrey.cancitroen.ui.theme.Dim
import com.geoffrey.cancitroen.ui.theme.Ok
import com.geoffrey.cancitroen.ui.theme.Text as TextColor
import com.geoffrey.cancitroen.ui.vm.EngineSoundViewModel

@Composable
fun EngineSoundScreen(
    modifier: Modifier = Modifier,
    vm: EngineSoundViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val demoRunning by vm.demoRunning.collectAsStateWithLifecycle()
    val demoRpm by vm.demoRpm.collectAsStateWithLifecycle()
    val profile = EngineProfiles.resolve(state.profileId, state.custom)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PageTitle("Son moteur")
        Text(
            "Génère un son moteur synthétique suivant le régime en temps réel. " +
                "Sortie via les haut-parleurs de l'Atoto, mixée avec ta musique.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )

        // ── Toggle ON/OFF géant ──
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Activer le son",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        if (state.enabled) "Actif" else "Désactivé",
                        color = if (state.enabled) Ok else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = state.enabled,
                    onCheckedChange = vm::setEnabled,
                )
            }
        }

        // ── Volume ──
        var dragVolume by remember { mutableStateOf<Float?>(null) }
        val shownVolume = dragVolume ?: state.volume
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Volume", color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium)
                    Text("${(shownVolume * 100).toInt()} %",
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
                Slider(
                    value = shownVolume,
                    // Glissé : état local + aperçu audio ; une seule écriture
                    // DataStore au relâchement (au lieu d'une par pixel).
                    onValueChange = { dragVolume = it; vm.previewVolume(it) },
                    onValueChangeFinished = {
                        dragVolume?.let(vm::setVolume)
                        dragVolume = null
                    },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    ),
                )
            }
        }

        // ── Choix du moteur ──
        val choices = remember(state.custom) {
            EngineProfiles.ALL + EngineProfiles.resolve(EngineProfiles.CUSTOM_ID, state.custom)
        }
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            DropdownPicker(
                label = "Moteur",
                currentLabel = profile.name,
                sublabel = profile.description,
                options = choices,
                optionLabel = { it.name },
                optionSublabel = { it.description },
                isSelected = { it.id == state.profileId },
                onSelect = {
                    vm.setProfile(it.id)
                    vm.setMemeEngine(null)
                },
            )
        }

        // ── Bouton démo ──
        DemoTestCard(
            running = demoRunning,
            currentRpm = demoRpm,
            profile = profile,
            onStart = vm::runDemo,
            onBlips = vm::runRevBlips,
            onStop = vm::stopDemo,
        )

        // ── Configurateur : part du moteur choisi, le modifier crée « Mon moteur » ──
        EngineBuilderCard(
            config = profile.config ?: state.custom,
            isCustom = state.profileId == EngineProfiles.CUSTOM_ID,
            onChange = vm::setCustomEngine,
        )

        // ── Mode meme (déclenchement par cycle de combustion) ──
        if (vm.availableMemes.isNotEmpty()) {
            val memes = remember { listOf<MemeEngine?>(null) + vm.availableMemes }
            val currentMeme = vm.availableMemes.firstOrNull { it.id == state.memeEngineId }
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                DropdownPicker(
                    label = "Mode meme : un son à chaque explosion, 30 par seconde au plus",
                    currentLabel = currentMeme?.label ?: "Désactivé",
                    options = memes,
                    optionLabel = { it?.label ?: "Désactivé" },
                    isSelected = { it?.id == state.memeEngineId },
                    onSelect = { vm.setMemeEngine(it?.id) },
                )
            }
        }

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun DemoTestCard(
    running: Boolean,
    currentRpm: Float,
    profile: EngineProfile,
    onStart: () -> Unit,
    onBlips: () -> Unit,
    onStop: () -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            SectionTitle("Tester le son", AppIcons.Headphones)
            Text(
                "Balaye le régime de ${profile.idleRpm.toInt()} → ${profile.redlineRpm.toInt()} → ${profile.idleRpm.toInt()} sur 12 s. " +
                    "Remplace le régime réel pendant la démo.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))

            if (running) {
                // Indicateur de progression
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier.size(10.dp).clip(CircleShape).background(Ok),
                    )
                    Text(
                        "Démo en cours · ${currentRpm.toInt()} tr/min",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onStop,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Icon(AppIcons.Stop, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Arrêter la démo")
                }
            } else {
                Button(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Icon(AppIcons.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Lancer la démo (12 s)", fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onBlips,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Text("Coups de gaz (12 s)", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
