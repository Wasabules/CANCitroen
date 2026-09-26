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
import androidx.compose.material3.MaterialTheme
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
import com.geoffrey.cancitroen.ui.components.GlassCard
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
    val profile = EngineProfiles.byId(state.profileId)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "🔊 Simulateur de bruit moteur",
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            "Génère un son moteur synthétique modulé par les RPM en temps réel. " +
                "Sortie via les haut-parleurs de l'Atoto, mixée avec ta musique.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )

        // ── Toggle ON/OFF géant ──
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (state.enabled) "● ACTIF" else "OFF",
                        color = if (state.enabled) Ok else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        "Tap pour ${if (state.enabled) "désactiver" else "activer"}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = state.enabled,
                    onCheckedChange = vm::setEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Ok,
                        checkedTrackColor = Ok.copy(alpha = 0.4f),
                    ),
                )
            }
        }

        // ── Volume ──
        var dragVolume by remember { mutableStateOf<Float?>(null) }
        val shownVolume = dragVolume ?: state.volume
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
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

        // ── Sélection du profil moteur ──
        Text(
            "Choisir une motorisation",
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleMedium,
        )
        EngineProfiles.ALL.forEach { p ->
            ProfileCard(
                profile = p,
                selected = p.id == state.profileId && state.memeEngineId == null,
                onSelect = {
                    vm.setProfile(p.id)
                    vm.setMemeEngine(null)
                },
            )
        }

        // ── Mode meme (déclenchement par cycle de combustion) ──
        if (vm.availableMemes.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "🤡 Mode meme — son par cycle de combustion",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Au lieu d'un son moteur, joue un meme à chaque explosion. " +
                    "Cap à 30 trig/sec pour rester audible.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            vm.availableMemes.forEach { m ->
                MemeEngineCard(
                    meme = m,
                    selected = state.memeEngineId == m.id,
                    onSelect = { vm.setMemeEngine(m.id) },
                )
            }
        }

        // ── Détail du profil sélectionné ──
        ProfileDetailCard(profile)

        // ── Bouton démo ──
        DemoTestCard(
            running = demoRunning,
            currentRpm = demoRpm,
            profile = profile,
            onStart = vm::runDemo,
            onStop = vm::stopDemo,
        )

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun DemoTestCard(
    running: Boolean,
    currentRpm: Float,
    profile: EngineProfile,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Text("🎧 Tester le son",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium)
            Text(
                "Balaye les RPM ${profile.idleRpm.toInt()} → ${profile.redlineRpm.toInt()} → ${profile.idleRpm.toInt()} sur 12 s. " +
                    "Override le RPM véhicule pendant la démo.",
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
                        "Démo en cours · ${currentRpm.toInt()} RPM",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEF5350),
                        contentColor = Color.White,
                    ),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) { Text("⏹ Arrêter la démo") }
            } else {
                Button(
                    onClick = onStart,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = profile.accent,
                        contentColor = Color.Black,
                    ),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Text("▶ Lancer la démo (12 s)",
                        fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(
    profile: EngineProfile,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val border = if (selected) profile.accent else Color.Transparent
    val bg = if (selected) profile.accent.copy(alpha = 0.12f) else Color.Transparent
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, border, RoundedCornerShape(20.dp)),
        onClick = onSelect,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp).background(bg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(profile.accent.copy(alpha = 0.20f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(profile.emoji, fontSize = 26.sp)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(profile.name, color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium)
                Text(profile.description, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
            if (selected) {
                Box(
                    modifier = Modifier.size(20.dp).clip(CircleShape).background(profile.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✓", color = Color.Black, fontWeight = FontWeight.Bold,
                        fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun MemeEngineCard(
    meme: MemeEngine,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val accent = Color(0xFFFFB74D)
    val border = if (selected) accent else Color.Transparent
    val bg = if (selected) accent.copy(alpha = 0.12f) else Color.Transparent
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, border, RoundedCornerShape(20.dp)),
        onClick = onSelect,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp).background(bg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.20f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(meme.emoji, fontSize = 26.sp)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(meme.label, color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium)
                Text(meme.assetPath, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
            if (selected) {
                Box(
                    modifier = Modifier.size(20.dp).clip(CircleShape).background(accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✓", color = Color.Black, fontWeight = FontWeight.Bold,
                        fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun ProfileDetailCard(profile: EngineProfile) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Text("ℹ️ Caractéristiques",
                color = profile.accent,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            DetailRow("Cylindres", "${profile.cylinders}")
            DetailRow("Plage RPM", "${profile.idleRpm.toInt()} – ${profile.redlineRpm.toInt()}")
            DetailRow("Forme d'onde",
                "${(profile.sawtoothMix * 100).toInt()}% sawtooth · " +
                    "${((1 - profile.sawtoothMix) * 100).toInt()}% sinus")
            DetailRow("Bruit", "${(profile.noiseLevel * 100).toInt()} %")
            DetailRow("Coupure passe-bas",
                "${profile.lowpassIdleHz.toInt()} → ${profile.lowpassRedlineHz.toInt()} Hz")
            if (profile.crossplane) {
                DetailRow("Cross-plane", "Oui (irrégulier)")
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(value, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
    }
}
