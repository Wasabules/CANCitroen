package com.geoffrey.cancitroen.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.geoffrey.cancitroen.audio.Aspiration
import com.geoffrey.cancitroen.audio.EngineConfig
import com.geoffrey.cancitroen.audio.EngineFuel
import com.geoffrey.cancitroen.audio.EngineLayout
import com.geoffrey.cancitroen.audio.EngineProfile
import com.geoffrey.cancitroen.audio.ExhaustType
import com.geoffrey.cancitroen.audio.PopLevel
import com.geoffrey.cancitroen.audio.TurboRelease
import com.geoffrey.cancitroen.ui.components.SectionCard
import com.geoffrey.cancitroen.ui.theme.AppIcons
import kotlin.math.roundToInt

/** Chaque réglage est appliqué au relâchement : pas de coupure du son à chaque pixel. */
@Composable
internal fun EngineBuilderCard(
    config: EngineConfig,
    isCustom: Boolean,
    onChange: (EngineConfig) -> Unit,
) {
    SectionCard(if (isCustom) "Mon moteur" else "Configurer le moteur", icon = AppIcons.Build) {
        Text(
            summary(config),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "Modifier un réglage part du moteur affiché et crée « Mon moteur ».",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Choices("Cylindres", EngineConfig.CYLINDER_CHOICES, config.cylinders, { "$it" }) {
            onChange(config.copy(cylinders = it))
        }
        Choices("Architecture", EngineLayout.entries, config.layout, { it.label }) {
            onChange(config.copy(layout = it))
        }
        Choices("Échappement", ExhaustType.entries, config.exhaust, { it.label }) {
            onChange(config.copy(exhaust = it))
        }
        Choices("Alimentation", Aspiration.entries, config.aspiration, { it.label }) {
            onChange(config.copy(aspiration = it))
        }
        Choices("Carburant", EngineFuel.entries, config.fuel, { it.label }) {
            onChange(config.copy(fuel = it))
        }
        if (config.fuel == EngineFuel.DIESEL) {
            Text(
                "Pas de pop & bang sur un diesel : il ne reste pas de carburant imbrûlé à enflammer.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 10.dp),
            )
        } else {
            Choices("Pop & bang au lever de pied", PopLevel.entries, config.pops, { it.label }) {
                onChange(config.copy(pops = it))
            }
            if (config.pops != PopLevel.OFF) {
                Text(
                    config.exhaust.popCharacter,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (config.aspiration == Aspiration.TURBO) {
            Choices("Décharge du turbo", TurboRelease.entries, config.turboRelease, { it.label }) {
                onChange(config.copy(turboRelease = it))
            }
        }
        Setting("Cylindrée", config.displacementCc, EngineConfig.DISPLACEMENT_RANGE, 50, { "$it cm³" }) {
            onChange(config.copy(displacementCc = it))
        }
        Setting("Alésage (taille des cylindres)", config.boreMm, EngineConfig.BORE_RANGE, 1, { "$it mm" }) {
            onChange(config.copy(boreMm = it))
        }
        Setting("Régime maximal", config.redlineRpm, EngineConfig.REDLINE_RANGE, 100, { "$it tr/min" }) {
            onChange(config.copy(redlineRpm = it))
        }
        val profile = remember(config) { config.toProfile("APERCU", "", "") }
        Setting(
            "Multiplicateur de régime",
            (profile.revMultiplier * 100f).roundToInt(),
            (EngineConfig.MULTIPLIER_MIN * 100).toInt()..(EngineConfig.MULTIPLIER_MAX * 100).toInt(),
            5,
            { "×%.2f".format(it / 100f) },
        ) { onChange(config.copy(revMultiplier = it / 100f)) }
        Text(
            "Voiture à 2000 tr/min : moteur à ${previewRpm(profile, 2000f)}. " +
                "Voiture à 4000 tr/min : moteur à ${previewRpm(profile, 4000f)}.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun previewRpm(profile: EngineProfile, carRpm: Float) =
    "${(profile.fromCarRpm(carRpm) / 10f).roundToInt() * 10} tr/min"

private fun summary(c: EngineConfig): String =
    "${c.cylinders} cyl. · %.1f L · ${c.cylinderCc.roundToInt()} cm³ par cylindre · course ${c.strokeMm.roundToInt()} mm".format(c.displacementCc / 1000f)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(modifier = Modifier.padding(top = 10.dp)) {
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { if (option != selected) onSelect(option) },
                    label = { Text(label(option)) },
                    colors = FilterChipDefaults.filterChipColors(
                        labelColor = MaterialTheme.colorScheme.onSurface,
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            }
        }
    }
}

@Composable
private fun Setting(
    title: String,
    value: Int,
    range: IntRange,
    step: Int,
    format: (Int) -> String,
    onCommit: (Int) -> Unit,
) {
    var dragging by remember { mutableStateOf<Int?>(null) }
    val shown = dragging ?: value
    Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text(format(shown), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyLarge)
        }
        Slider(
            value = shown.toFloat(),
            onValueChange = { dragging = (it / step).roundToInt() * step },
            onValueChangeFinished = {
                dragging?.let(onCommit)
                dragging = null
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            ),
        )
    }
}
