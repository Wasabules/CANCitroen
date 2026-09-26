package com.geoffrey.cancitroen.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geoffrey.cancitroen.ui.theme.GlassBorder
import com.geoffrey.cancitroen.ui.theme.LocalNightMode
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Horloge premium circulaire — cadran minimaliste + heure numérique au centre.
 *
 *  - Anneau extérieur : track + arc des secondes qui se remplit chaque minute
 *  - 12 marqueurs d'heures + 60 mini-ticks minutes (subtils)
 *  - Heure numérique HH:MM + date parfaitement centrées
 *
 *  Le composable s'adapte à la taille fournie (aspectRatio 1:1).
 *  Coût : seul l'arc des secondes est redessiné (30 fps), dans son propre
 *  calque ; ni recomposition ni redessin du cadran à chaque frame.
 */
@Composable
fun ClockGauge(
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = GlassBorder,
) {
    val nightMode = LocalNightMode.current
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // L'arc des secondes avance de 6°/s : 30 fps suffisent pour qu'il glisse
    // sans à-coups. `nowMs` n'est lu que dans la phase de dessin de l'arc (et
    // via derivedStateOf pour le texte) → aucun tick ne recompose l'écran.
    // En mode nuit : 1 Hz, pas d'arc, zéro mouvement.
    LaunchedEffect(nightMode) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(if (nightMode) 1000L - nowMs % 1000L else FRAME_30FPS_MS)
        }
    }

    // Recompose une fois par minute seulement.
    val minuteMs by remember { derivedStateOf { nowMs / 60_000L * 60_000L } }
    val timeText = remember(minuteMs) {
        val cal = Calendar.getInstance().apply { timeInMillis = minuteMs }
        "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    }
    val dateText = remember(minuteMs) {
        SimpleDateFormat("EEE d MMMM", Locale.FRANCE).format(Date(minuteMs))
            .replaceFirstChar { it.uppercase() }
    }

    BoxWithConstraints(
        modifier = modifier.aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        val sideDp = min(maxWidth.value, maxHeight.value)
        // Heure imposante : 24% du diamètre. Date discrète : 5%.
        val timeFontSize = (sideDp * 0.24f).sp
        val dateFontSize = (sideDp * 0.05f).coerceAtLeast(11f).sp
        // Capture du onSurface hors du Canvas drawScope (non @Composable).
        val tickColor = MaterialTheme.colorScheme.onSurface

        // ── Cadran statique : anneau + 12 ticks d'heures + 60 mini-ticks ──
        // Ne lit aucun état qui bouge → dessiné une fois, jamais réenregistré.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val g = ClockGeometry(size)
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = g.rect.topLeft,
                size = g.rect.size,
                style = Stroke(width = g.strokeW, cap = StrokeCap.Round),
            )
            val rTickOuter = g.r - g.strokeW * 1.6f
            for (i in 0 until 60) {
                val isHour = i % 5 == 0
                val isQuarter = i % 15 == 0
                val a = (i * 6f - 90f).toRad()
                val rIn = rTickOuter - when {
                    isQuarter -> g.strokeW * 1.0f
                    isHour    -> g.strokeW * 0.75f
                    else      -> g.strokeW * 0.30f
                }
                val sx = g.cx + rTickOuter * cos(a).toFloat()
                val sy = g.cy + rTickOuter * sin(a).toFloat()
                val ex = g.cx + rIn * cos(a).toFloat()
                val ey = g.cy + rIn * sin(a).toFloat()
                drawLine(
                    color = if (isHour) tickColor.copy(alpha = 0.85f) else trackColor.copy(alpha = 0.55f),
                    start = Offset(sx, sy),
                    end = Offset(ex, ey),
                    strokeWidth = if (isQuarter) 4.5f else if (isHour) 3f else 1.2f,
                    cap = StrokeCap.Round,
                )
            }
        }

        // ── Arc des secondes : se remplit progressivement chaque minute ──
        // Calque à part (graphicsLayer) : seul lui est redessiné 30×/s.
        // En mode nuit on saute l'arc complètement → zéro mouvement permanent
        // qui distrairait le conducteur la nuit.
        if (!nightMode) {
            Spacer(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer()
                    .drawWithCache {
                        val g = ClockGeometry(size)
                        val brush = Brush.sweepGradient(
                            listOf(
                                accentColor.copy(alpha = 0.4f),
                                accentColor,
                                accentColor.copy(alpha = 0.4f),
                            )
                        )
                        val stroke = Stroke(width = g.strokeW, cap = StrokeCap.Round)
                        onDrawBehind {
                            val secSweep = (nowMs % 60_000L) / 60_000f * 360f
                            if (secSweep > 0.5f) {
                                drawArc(
                                    brush = brush,
                                    startAngle = -90f,
                                    sweepAngle = secSweep,
                                    useCenter = false,
                                    topLeft = g.rect.topLeft,
                                    size = g.rect.size,
                                    style = stroke,
                                )
                            }
                        }
                    },
            )
        }

        // ── Heure numérique + date parfaitement centrées dans le cercle ──
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                timeText,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = timeFontSize,
                fontWeight = FontWeight.Light,
                style = MaterialTheme.typography.displayMedium.copy(letterSpacing = (-1.5).sp),
            )
            Text(
                dateText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = dateFontSize,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

private const val FRAME_30FPS_MS = 33L

/** Géométrie commune au cadran et à l'arc (mêmes rayons, même épaisseur). */
private class ClockGeometry(size: Size) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val r = min(cx, cy)
    val strokeW = r * 0.06f
    private val pad = strokeW / 2f + 4f
    val rect = androidx.compose.ui.geometry.Rect(
        offset = Offset(pad, pad),
        size = Size(size.width - pad * 2, size.height - pad * 2),
    )
}

private fun Float.toRad(): Double = this.toDouble() * PI / 180.0
