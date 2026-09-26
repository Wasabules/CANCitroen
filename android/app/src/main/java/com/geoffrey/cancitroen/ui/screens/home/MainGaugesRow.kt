package com.geoffrey.cancitroen.ui.screens.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.geoffrey.cancitroen.ui.components.CircularGauge
import com.geoffrey.cancitroen.ui.components.ClockGauge
import com.geoffrey.cancitroen.ui.components.defaultGaugeBrush
import com.geoffrey.cancitroen.ui.components.rememberSmoothedValue
import kotlin.math.roundToInt

private val GpsOrange = Color(0xFFFFB74D)

/**
 * Rangée centrale du HomeScreen : speed gauge | horloge | rpm gauge.
 * Hauteur et diamètre des jauges sont calculés en amont (HomeScreen) pour
 * s'adapter au ratio d'écran (téléphone ↔ tablette).
 */
@Composable
fun MainGaugesRow(
    speedKmh: () -> Float,
    rpm: () -> Float,
    gpsSpeedKmh: Float?,
    speedShowGps: Boolean,
    onToggleSpeedSource: () -> Unit,
    rowHeight: Dp,
    gaugeDiameter: Dp,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(rowHeight),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Providers lus dans chaque jauge : seule la jauge concernée recompose
        // (10 Hz en roulant), pas la rangée ni l'horloge.
        SpeedGauge(speedKmh, gpsSpeedKmh, gaugeDiameter, speedShowGps,
            onToggleSpeedSource, modifier = Modifier.weight(1f).fillMaxHeight())
        Box(
            modifier = Modifier.weight(1.5f).fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) { ClockGauge() }
        RpmGauge(rpm, gaugeDiameter,
            modifier = Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun SpeedGauge(
    canSpeed: () -> Float,
    gpsSpeed: Float?,
    diameter: Dp,
    showGpsAsPrimary: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gpsAvailable = gpsSpeed != null

    // Lissage par ressort entre deux échantillons (10 Hz) : l'arc glisse, et
    // le nombre affiché ne recompose que quand l'entier change.
    val canAnim = rememberSmoothedValue(canSpeed())
    val gpsAnim = rememberSmoothedValue(gpsSpeed ?: 0f)
    val canShown by remember { derivedStateOf { canAnim.value.roundToInt() } }
    val gpsShown by remember { derivedStateOf { gpsAnim.value.roundToInt() } }

    val primaryColor = MaterialTheme.colorScheme.primary
    val accentBrush = defaultGaugeBrush(primaryColor)
    val orangeBrush = defaultGaugeBrush(GpsOrange)

    Box(
        modifier = modifier.clickable(enabled = gpsAvailable, onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        if (showGpsAsPrimary && gpsAvailable) {
            CircularGauge(
                value = { gpsAnim.value },
                valueText = gpsShown.toString(),
                label = "Vitesse GPS",
                unit = "km/h",
                minValue = 0f,
                maxValue = 180f,
                diameter = diameter,
                progressBrush = orangeBrush,
                secondaryValue = { canAnim.value },
                secondaryColor = primaryColor,
                subtitle = "CAN $canShown",
            )
        } else {
            val showSecondary = gpsSpeed != null && gpsSpeed >= 2f
            CircularGauge(
                value = { canAnim.value },
                valueText = canShown.toString(),
                label = "Vitesse",
                unit = "km/h",
                minValue = 0f,
                maxValue = 180f,
                diameter = diameter,
                progressBrush = accentBrush,
                secondaryValue = if (showSecondary) ({ gpsAnim.value }) else null,
                secondaryColor = GpsOrange,
                subtitle = if (showSecondary) "GPS $gpsShown" else null,
            )
        }
    }
}

@Composable
private fun RpmGauge(rpm: () -> Float, diameter: Dp, modifier: Modifier = Modifier) {
    val anim = rememberSmoothedValue(rpm())
    // Affiché par paliers de 10 tr/min : le texte ne recompose qu'au changement de palier.
    val rounded by remember { derivedStateOf { (anim.value / 10f).toInt() * 10 } }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        CircularGauge(
            value = { anim.value },
            valueText = rounded.toString(),
            label = "Régime",
            unit = "tr/min",
            minValue = 0f,
            maxValue = 7000f,
            diameter = diameter,
        )
    }
}
