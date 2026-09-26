package com.geoffrey.cancitroen.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geoffrey.cancitroen.ui.theme.GlassBorder
import kotlin.math.max
import kotlin.math.min

/**
 * Valeur lissée vers [target] par un ressort : chaque nouvelle cible (10 Hz)
 * reprend l'animation en cours sans casser la vitesse de l'aiguille. À lire
 * en phase de dessin (`{ anim.value }`) pour ne pas recomposer à chaque frame.
 */
@Composable
fun rememberSmoothedValue(target: Float): Animatable<Float, *> {
    val anim = remember { Animatable(target) }
    LaunchedEffect(target) {
        anim.animateTo(
            target,
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),
        )
    }
    return anim
}

/**
 * Jauge circulaire 270° (pleine de 7 h à 5 h) avec valeur au centre.
 *  - Track ténu en GlassBorder
 *  - Arc rempli avec gradient MaterialTheme.colorScheme.primary → Accent50% (transition lisible)
 *  - Valeur, unité et libellé empilés au centre
 *
 * [value] et [secondaryValue] sont des lambdas lues en phase de dessin : l'arc
 * suit l'animation sans recomposer. Le cadran (track + graduations) est dans
 * un calque à part, jamais redessiné quand seule la valeur bouge.
 */
@Composable
fun CircularGauge(
    value: () -> Float,
    valueText: String,
    label: String,
    unit: String,
    minValue: Float = 0f,
    maxValue: Float = 100f,
    modifier: Modifier = Modifier,
    diameter: Dp = 220.dp,
    trackColor: Color = GlassBorder,
    progressBrush: Brush = defaultGaugeBrush(MaterialTheme.colorScheme.primary),
    /** Valeur secondaire (ex: vitesse GPS) rendue comme un point sur l'arc. Null pour rien. */
    secondaryValue: (() -> Float)? = null,
    secondaryColor: Color = Color(0xFFFFB74D), // orange doux pour distinguer
    /** Sous-titre optionnel sous la valeur (ex: "GPS 92"). */
    subtitle: String? = null,
) {
    fun pctOf(v: Float) = ((v - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)

    Box(
        modifier = modifier.size(diameter),
        contentAlignment = Alignment.Center,
    ) {
        // ── Cadran statique : track + graduations ──
        Canvas(modifier = Modifier.fillMaxSize()) {
            val g = GaugeGeometry(size)
            drawArc(
                color = trackColor,
                startAngle = START_ANGLE,
                sweepAngle = SWEEP_FULL,
                useCenter = false,
                topLeft = g.rect.topLeft,
                size = g.rect.size,
                style = Stroke(width = g.strokeW, cap = StrokeCap.Round),
            )
            val ticks = 9
            val r1 = min(g.rect.width, g.rect.height) / 2 - g.strokeW
            val r2 = r1 - g.strokeW * 0.55f
            for (i in 0..ticks) {
                val a = (START_ANGLE + SWEEP_FULL * (i.toFloat() / ticks)) * (Math.PI / 180f)
                drawLine(
                    color = trackColor.copy(alpha = 0.6f),
                    start = Offset((g.cx + r1 * Math.cos(a)).toFloat(), (g.cy + r1 * Math.sin(a)).toFloat()),
                    end = Offset((g.cx + r2 * Math.cos(a)).toFloat(), (g.cy + r2 * Math.sin(a)).toFloat()),
                    strokeWidth = 2f,
                )
            }
        }

        // ── Arc de valeur + marqueur secondaire : calque dynamique ──
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer()
                .drawWithCache {
                    val g = GaugeGeometry(size)
                    val stroke = Stroke(width = g.strokeW, cap = StrokeCap.Round)
                    val rArc = min(g.rect.width, g.rect.height) / 2 - g.strokeW / 2
                    onDrawBehind {
                        drawArc(
                            brush = progressBrush,
                            startAngle = START_ANGLE,
                            sweepAngle = max(2f, SWEEP_FULL * pctOf(value())),
                            useCenter = false,
                            topLeft = g.rect.topLeft,
                            size = g.rect.size,
                            style = stroke,
                        )
                        if (secondaryValue != null) {
                            val a = (START_ANGLE + SWEEP_FULL * pctOf(secondaryValue())) * (Math.PI / 180f)
                            val center = Offset(
                                (g.cx + rArc * Math.cos(a)).toFloat(),
                                (g.cy + rArc * Math.sin(a)).toFloat(),
                            )
                            drawCircle(color = secondaryColor, radius = g.strokeW * 0.45f, center = center)
                            drawCircle(
                                color = Color.Black.copy(alpha = 0.4f),
                                radius = g.strokeW * 0.45f,
                                center = center,
                                style = Stroke(width = 2f),
                            )
                        }
                    }
                },
        )

        // Valeur + unité + libellé au centre
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                valueText,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = (diameter.value * 0.18f).sp,
                fontWeight = FontWeight.Light,
                style = MaterialTheme.typography.displayMedium.copy(letterSpacing = (-1).sp),
            )
            Text(
                unit,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                label.uppercase(),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = secondaryColor.copy(alpha = 0.9f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/** Gradient d'arc par défaut, mémorisé par couleur (pas de nouveau Brush à chaque recomposition). */
@Composable
fun defaultGaugeBrush(color: Color): Brush = remember(color) {
    Brush.sweepGradient(listOf(color.copy(alpha = 0.6f), color, color.copy(alpha = 0.6f)))
}

private const val SWEEP_FULL = 270f
private const val START_ANGLE = 135f

private class GaugeGeometry(size: Size) {
    val strokeW = size.minDimension * 0.08f
    private val pad = strokeW / 2 + 4f
    val rect = Rect(offset = Offset(pad, pad), size = Size(size.width - pad * 2, size.height - pad * 2))
    val cx = rect.center.x
    val cy = rect.center.y
}
