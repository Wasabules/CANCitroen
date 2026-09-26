package com.geoffrey.cancitroen.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.geoffrey.cancitroen.ui.theme.Dim
import com.geoffrey.cancitroen.ui.theme.GlassBg
import com.geoffrey.cancitroen.ui.theme.GlassBorder
import com.geoffrey.cancitroen.ui.theme.Ok
import com.geoffrey.cancitroen.ui.theme.Text as TextColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Plage temporelle (from..to) (epoch ms).
 *  - "Live" : la fin est proche de maintenant (à <60 s près)
 *  - durationMs : taille de la plage
 *  - shift : translate de N fois la plage
 */
data class TimeRange(val from: Long, val to: Long) {
    val durationMs: Long get() = to - from
    fun isLive(now: Long = System.currentTimeMillis()) = (now - to) <= 60_000

    fun shift(deltaMs: Long): TimeRange = TimeRange(from + deltaMs, to + deltaMs)
    fun resizeKeepingEnd(newDuration: Long): TimeRange =
        TimeRange(to - newDuration, to)
    fun resizeLive(newDuration: Long, now: Long = System.currentTimeMillis()): TimeRange =
        TimeRange(now - newDuration, now)
}

/** Présets disponibles dans la barre. */
enum class TimeRangePreset(val label: String, val durationMs: Long) {
    H1("1 h", 1L * 3600 * 1000),
    H6("6 h", 6L * 3600 * 1000),
    H24("24 h", 24L * 3600 * 1000),
    D7("7 j", 7L * 24 * 3600 * 1000),
    D30("30 j", 30L * 24 * 3600 * 1000),
}

/**
 * Barre de range temporelle façon Zabbix :
 *  ┌──────────────────────────────────────────────┐
 *  │  ◄  [du 2 mai au 9 mai · ● LIVE]  ►   [Now]  │
 *  ├──────────────────────────────────────────────┤
 *  │  [1h] [6h] [24h] [7j] [30j]                  │
 *  └──────────────────────────────────────────────┘
 */
@Composable
fun TimeRangeBar(
    range: TimeRange,
    onRangeChange: (TimeRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(14.dp))
        .background(GlassBg.copy(alpha = 0.45f))
        .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Ligne 1 : navigation + display
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ArrowBtn("◄", onClick = { onRangeChange(range.shift(-range.durationMs)) })
            // Display range
            Box(modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (range.isLive()) {
                            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Ok))
                            Text("LIVE",
                                color = Ok, fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.labelMedium)
                            Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(formatRange(range), color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium)
                    }
                    Text(formatDuration(range.durationMs),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            ArrowBtn("►", onClick = {
                val now = System.currentTimeMillis()
                val shifted = range.shift(range.durationMs)
                // Empêche de "naviguer dans le futur" : si on dépasse now, on ramène à live
                if (shifted.to > now) onRangeChange(range.resizeLive(range.durationMs, now))
                else onRangeChange(shifted)
            })
            if (!range.isLive()) {
                LiveBtn(onClick = { onRangeChange(range.resizeLive(range.durationMs)) })
            }
        }
        // Ligne 2 : présets
        Row(modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TimeRangePreset.values().forEach { p ->
                val active = range.durationMs == p.durationMs
                Box(
                    modifier = Modifier.weight(1f).height(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.Transparent)
                        .clickable {
                            // Garde la fin courante (live ou pas), redimensionne à la nouvelle durée
                            onRangeChange(range.resizeKeepingEnd(p.durationMs))
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(p.label,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ArrowBtn(label: String, onClick: () -> Unit) {
    Box(modifier = Modifier
        .size(width = 36.dp, height = 30.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(GlassBg.copy(alpha = 0.6f))
        .clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun LiveBtn(onClick: () -> Unit) {
    Box(modifier = Modifier
        .height(30.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(Ok.copy(alpha = 0.18f))
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center) {
        Text("Live", color = Ok, fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatRange(r: TimeRange): String {
    val now = System.currentTimeMillis()
    val sdfTime = SimpleDateFormat("HH:mm", Locale.FRANCE)
    val sdfDate = SimpleDateFormat("d MMM", Locale.FRANCE)
    val sdfFull = SimpleDateFormat("d MMM HH:mm", Locale.FRANCE)
    val isLive = r.isLive(now)
    val sameDay = isSameDay(r.from, r.to)

    return when {
        // Range live <= 24h : "8h12 — maintenant"
        isLive && r.durationMs <= 24L * 3600_000 ->
            "${sdfTime.format(Date(r.from))} — maintenant"
        // Range live > 24h : "il y a 7 j"
        isLive ->
            "depuis ${sdfDate.format(Date(r.from))}"
        // Range historique courte (même jour) : "Lun 9 mai 8:00 — 14:00"
        sameDay ->
            "${sdfDate.format(Date(r.from))}  ${sdfTime.format(Date(r.from))} — ${sdfTime.format(Date(r.to))}"
        // Sinon
        else ->
            "${sdfFull.format(Date(r.from))} — ${sdfFull.format(Date(r.to))}"
    }
}

private fun isSameDay(a: Long, b: Long): Boolean {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = a; val da = cal.get(java.util.Calendar.DAY_OF_YEAR); val ya = cal.get(java.util.Calendar.YEAR)
    cal.timeInMillis = b; val db = cal.get(java.util.Calendar.DAY_OF_YEAR); val yb = cal.get(java.util.Calendar.YEAR)
    return da == db && ya == yb
}

private fun formatDuration(ms: Long): String {
    val h = ms / 3_600_000
    val d = ms / (24L * 3_600_000)
    return when {
        d >= 1 -> "$d j"
        h >= 1 -> "$h h"
        else -> "${ms / 60_000} min"
    }
}
