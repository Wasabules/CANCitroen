package com.geoffrey.cancitroen.ui.screens.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import com.geoffrey.cancitroen.system.AppShortcuts
import com.geoffrey.cancitroen.ui.components.GlassCard
import com.geoffrey.cancitroen.ui.theme.LocalNightMode

@Composable
fun AndroidAutoHero(
    shortcut: AppShortcuts.ResolvedShortcut,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Mode nuit : la card "Lancer la projection" est atténuée comme le reste
    // des accessoires (icône + texte + ▶ tous moins visibles).
    val nightAlpha by animateFloatAsState(
        targetValue = if (LocalNightMode.current) 0.40f else 1f,
        animationSpec = tween(400),
        label = "aaHeroAlpha",
    )
    GlassCard(
        modifier = modifier.graphicsLayer { alpha = nightAlpha },
        onClick = onClick,
        cornerRadius = 24.dp,
        contentPadding = 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (shortcut.icon != null) {
                    Image(
                        bitmap = shortcut.icon,
                        contentDescription = shortcut.label,
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)),
                    )
                } else {
                    Box(
                        modifier = Modifier.size(56.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center,
                    ) { Text("AA", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.width(20.dp))
                Column {
                    Text(
                        shortcut.label.uppercase(),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 2.sp,
                    )
                    Text(
                        "Lancer la projection",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Box(
                modifier = Modifier.size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text("▶", color = MaterialTheme.colorScheme.onPrimary, fontSize = 20.sp)
            }
        }
    }
}
