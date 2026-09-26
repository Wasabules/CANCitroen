package com.geoffrey.cancitroen.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.geoffrey.cancitroen.decode.VehicleState
import com.geoffrey.cancitroen.system.GpsTracker
import com.geoffrey.cancitroen.ui.theme.Dim
import com.geoffrey.cancitroen.ui.theme.Err
import com.geoffrey.cancitroen.ui.theme.GlassBg
import com.geoffrey.cancitroen.ui.theme.LocalNightMode
import com.geoffrey.cancitroen.ui.theme.Ok
import com.geoffrey.cancitroen.ui.theme.Text as TextColor
import com.geoffrey.cancitroen.ui.theme.Warn
import com.geoffrey.cancitroen.ui.theme.AppIcons

/**
 * Barre du haut du HomeScreen.
 * Gauche : 3 pills d'état véhicule (carburant, huile, odomètre).
 * Droite : 3 pills d'instrumentation (autonomie/conso, GPS, jour/nuit).
 */
/**
 * Les seuls champs affichés par la barre (odomètre au km, comme à l'écran) :
 * dérivé via derivedStateOf, la barre ne recompose que si l'un d'eux change,
 * pas à chaque échantillon RPM/vitesse.
 */
data class StatusBarValues(
    val fuelPct: Int?,
    val fuelLitersEst: Double?,
    val oilLevelPct: Int?,
    val tOil: Int?,
    val odoKm: Long?,
    val fuelInst: Double?,
    val rangeKm: Int?,
) {
    companion object {
        fun of(s: VehicleState) = StatusBarValues(
            fuelPct = s.fuelPct,
            fuelLitersEst = s.fuelLitersEst,
            oilLevelPct = s.oilLevelPct,
            tOil = s.tOil,
            odoKm = s.odo?.toLong(),
            fuelInst = s.fuelInst,
            rangeKm = s.rangeKm,
        )
    }
}

@Composable
fun TopStatusBar(
    state: StatusBarValues,
    gps: GpsTracker.SatelliteState?,
    nightMode: Boolean,
    fuelDisplayLiters: Boolean,
    oilDisplayLevel: Boolean,
    rangeShowConsumption: Boolean,
    onToggleNightMode: () -> Unit,
    onToggleFuelDisplay: () -> Unit,
    onToggleOilDisplay: () -> Unit,
    onToggleRangeDisplay: () -> Unit,
) {
    // On capture l'accent dans une val locale : les lambdas `colorOf` plus
    // bas ne sont PAS @Composable, donc elles ne peuvent pas lire
    // `MaterialTheme.colorScheme.primary` directement. La val locale capte
    // la valeur résolue au moment de la composition, ce qui change quand
    // l'utilisateur switche de preset (re-composition de TopStatusBar).
    val accent = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    // Mode nuit : atténue les pills accessoires (les valeurs vitales gardent
    // leur couleur sémantique, mais leur intensité globale baisse). Le bouton
    // jour/nuit lui-même reste plein contraste (sinon impossible de revenir).
    val nightMode = LocalNightMode.current
    val accessoryAlpha by animateFloatAsState(
        targetValue = if (nightMode) 0.35f else 1f,
        animationSpec = tween(400),
        label = "topStatusAlpha",
    )
    Row(
        // alpha appliqué à TOUTE la barre : le bouton jour/nuit en sortie de
        // mode est aussi dimmé, mais il reste tappable (35% sur un icône
        // pleinement contrasté → suffisant pour voir le pictogramme et taper).
        modifier = Modifier.fillMaxWidth().height(40.dp)
            .graphicsLayer { alpha = accessoryAlpha },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ── Carburant ──
        val fuelPct = state.fuelPct?.toFloat()
        val fuelText = if (fuelDisplayLiters)
            state.fuelLitersEst?.let { "%.1f L".format(it) } ?: "— L"
        else state.fuelPct?.let { "$it %" } ?: "—"
        MetricPill(
            icon = AppIcons.LocalGasStation,
            value = fuelPct,
            valueText = fuelText,
            min = 0f, max = 100f,
            colorOf = { v -> when {
                v < 10f -> Err
                v < 25f -> Warn
                else -> accent
            } },
            onClick = onToggleFuelDisplay,
        )

        // ── Huile (T° ↔ niveau) ──
        val oilValue: Float?; val oilText: String; val oilMax: Float
        val oilColorOf: (Float) -> Color
        if (oilDisplayLevel) {
            oilValue = state.oilLevelPct?.toFloat()
            oilText = state.oilLevelPct?.let { "$it %" } ?: "— %"
            oilMax = 100f
            oilColorOf = { v -> when {
                v < 15f -> Err
                v < 30f -> Warn
                else -> accent
            } }
        } else {
            oilValue = state.tOil?.toFloat()
            oilText = state.tOil?.let { "$it °C" } ?: "— °C"
            oilMax = 130f
            oilColorOf = { v -> when {
                v > 120f -> Err
                v > 105f -> Warn
                v < 60f -> dim
                else -> accent
            } }
        }
        MetricPill(
            icon = AppIcons.OilBarrel,
            value = oilValue,
            valueText = oilText,
            min = 0f, max = oilMax,
            colorOf = oilColorOf,
            onClick = onToggleOilDisplay,
        )

        // ── Odomètre (sans barre) ──
        StatusPill(
            icon = AppIcons.Route,
            text = state.odoKm?.let { formatOdometer(it) } ?: "— km",
        )

        Spacer(Modifier.weight(1f))

        // ── Autonomie ↔ Conso instantanée ──
        if (rangeShowConsumption) {
            val cons = state.fuelInst?.toFloat()
            MetricPill(
                icon = AppIcons.LocalDrink,
                value = cons,
                valueText = cons?.let { "%.1f l/100".format(it) } ?: "— l/100",
                min = 0f, max = 12f,
                colorOf = { v -> when {
                    v < 6f -> Ok
                    v < 8f -> accent
                    v < 10f -> Warn
                    else -> Err
                } },
                onClick = onToggleRangeDisplay,
            )
        } else {
            val km = state.rangeKm?.toFloat()
            MetricPill(
                icon = AppIcons.Timeline,
                value = km,
                valueText = km?.let { "${it.toInt()} km" } ?: "— km",
                min = 0f, max = 800f,
                colorOf = { v -> when {
                    v < 50f -> Err
                    v < 100f -> Warn
                    else -> accent
                } },
                onClick = onToggleRangeDisplay,
            )
        }

        StatusPill(
            icon = AppIcons.GpsFixed,
            text = gps?.let { "${it.usedInFix}/${it.visible}" } ?: "—",
            highlight = gps?.hasFix == true,
        )
        StatusPill(
            icon = if (nightMode) AppIcons.Brightness4 else AppIcons.Brightness7,
            text = if (nightMode) "Nuit" else "Jour",
            onClick = onToggleNightMode,
        )
    }
}

@Composable
private fun StatusPill(
    icon: ImageVector,
    text: String,
    highlight: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val baseMod = Modifier
        .height(38.dp)
        .clip(RoundedCornerShape(19.dp))
        .background(GlassBg)
    val finalMod = if (onClick != null) {
        baseMod.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
        )
    } else baseMod

    Row(
        modifier = finalMod.padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp),
            tint = if (highlight) Ok else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Text(
            text,
            color = if (highlight) Ok else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun MetricPill(
    icon: ImageVector,
    value: Float?,
    valueText: String,
    min: Float,
    max: Float,
    colorOf: (Float) -> Color,
    onClick: (() -> Unit)? = null,
    showBar: Boolean = true,
) {
    val v = value ?: min
    val pct = ((v - min) / (max - min)).coerceIn(0f, 1f)
    val tint = if (value != null) colorOf(v) else MaterialTheme.colorScheme.onSurfaceVariant

    val baseMod = Modifier
        .height(38.dp)
        .clip(RoundedCornerShape(19.dp))
        .background(GlassBg)
    val finalMod = if (onClick != null) baseMod.clickable(onClick = onClick) else baseMod

    Row(
        modifier = finalMod.padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = tint)
        Text(valueText, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
            style = MaterialTheme.typography.bodyMedium)
        if (showBar) {
            Box(
                modifier = Modifier
                    .size(width = 30.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(pct)
                        .background(tint),
                )
            }
        }
    }
}

private fun formatOdometer(km: Long): String {
    val s = km.toString().reversed().chunked(3).joinToString(" ").reversed()
    return "$s km"
}
