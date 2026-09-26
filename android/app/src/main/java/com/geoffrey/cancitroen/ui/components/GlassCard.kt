package com.geoffrey.cancitroen.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.geoffrey.cancitroen.ui.theme.GlassBg
import com.geoffrey.cancitroen.ui.theme.GlassBorder
import com.geoffrey.cancitroen.ui.theme.GlassCardGradient

/**
 * Card "glass" minimaliste façon dashboard voiture moderne.
 *  - Fond translucide (15% blanc) sur le gradient sombre
 *  - Border thin 1dp avec opacity 15%
 *  - Surimpression d'un gradient subtil interne pour effet "lit par le haut"
 *  - Coins arrondis 20dp par défaut
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    cornerRadius: Dp = 20.dp,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val clickMod = if (onClick != null)
        Modifier.clickable(onClick = onClick)
    else Modifier

    Surface(
        shape = shape,
        color = GlassBg,
        border = BorderStroke(1.dp, GlassBorder),
        modifier = modifier
            .clip(shape)
            .then(clickMod),
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .background(GlassCardGradient)
                .padding(contentPadding),
            content = content,
        )
    }
}
