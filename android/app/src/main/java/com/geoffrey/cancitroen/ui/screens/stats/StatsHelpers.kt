package com.geoffrey.cancitroen.ui.screens.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geoffrey.cancitroen.ui.components.EmptyHint
import com.geoffrey.cancitroen.ui.components.GlassCard
import com.geoffrey.cancitroen.ui.theme.AppIcons
import com.geoffrey.cancitroen.ui.theme.Dim
import com.geoffrey.cancitroen.ui.theme.Text as TextColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun EmptyState() {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        EmptyHint(AppIcons.ShowChart, "Pas encore de données", "L'app commence à enregistrer ton véhicule dès que le contact est mis. Active le simulateur dans Réglages pour générer des données de démo.")
    }
}

@Composable
internal fun StatChip(label: String, value: String) {
    Column {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        Text(value, color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium)
    }
}

internal fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> "%dh%02d".format(h, m)
        m > 0 -> "%dmin %ds".format(m, s)
        else -> "%ds".format(s)
    }
}

internal fun formatDate(ts: Long): String =
    SimpleDateFormat("EEE d MMM HH:mm", Locale.FRANCE).format(Date(ts))
