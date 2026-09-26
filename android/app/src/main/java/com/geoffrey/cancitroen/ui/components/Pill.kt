package com.geoffrey.cancitroen.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.geoffrey.cancitroen.ui.theme.*

enum class PillStyle { Off, On, Warn, Err, Ok }

@Composable
fun Pill(label: String, active: Boolean, style: PillStyle = PillStyle.On) {
    // Couleurs des pills inactives lues depuis MaterialTheme : suivent le
    // preset (surfaceVariant/outline calculés depuis la hue accent). Avant :
    // Panel2/Border hardcodés en bleu-gris.
    val inactiveBg = MaterialTheme.colorScheme.surfaceVariant
    val inactiveBorder = MaterialTheme.colorScheme.outline
    val (bg, fg, border) = when {
        !active -> Triple(inactiveBg, MaterialTheme.colorScheme.onSurfaceVariant, inactiveBorder)
        style == PillStyle.On   -> Triple(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary, MaterialTheme.colorScheme.primary)
        style == PillStyle.Warn -> Triple(Warn,   Color(0xFF3E2200), Warn)
        style == PillStyle.Err  -> Triple(Err,    Color.White,        Err)
        style == PillStyle.Ok   -> Triple(Ok,     Color(0xFF07260E), Ok)
        else -> Triple(inactiveBg, MaterialTheme.colorScheme.onSurfaceVariant, inactiveBorder)
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = bg,
        border = BorderStroke(1.dp, border),
        modifier = Modifier.padding(2.dp),
    ) {
        Text(
            label,
            color = fg,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}
