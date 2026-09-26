package com.geoffrey.cancitroen.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geoffrey.cancitroen.system.AppShortcuts
import com.geoffrey.cancitroen.ui.theme.GlassBg
import com.geoffrey.cancitroen.ui.theme.GlassBorder
import com.geoffrey.cancitroen.ui.theme.LocalNightMode

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShortcutsGrid(
    slots: List<AppShortcuts.GridSlot>,
    tileSize: Dp,
    showLabels: Boolean,
    isIdle: Boolean,
    onLaunch: (String) -> Unit,
    onPickEmpty: (slotIndex: Int) -> Unit,
    onLongPressFilled: (slotIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val nightMode = LocalNightMode.current
    // En mode nuit la grille reste en permanence dans l'état "estompé" (même
    // alpha/scale que l'idle), pour qu'aucune icône d'app ne tape les yeux du
    // conducteur la nuit. Le facteur 0.40 est plus bas qu'idle pour bien
    // marquer la distinction.
    val targetAlpha = when {
        nightMode -> 0.35f
        isIdle    -> 0.55f
        else      -> 1f
    }
    val gridAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(700),
        label = "gridAlpha",
    )
    val gridScale by animateFloatAsState(
        targetValue = if (isIdle || nightMode) 0.92f else 1f,
        animationSpec = tween(700),
        label = "gridScale",
    )

    // On sépare les Filled (toujours rendus dans le même ordre) des Empty
    // qui ne servent qu'à savoir "où" un nouveau raccourci doit aller.
    // Les "+" gauche/droit sont des composants distincts qui fade-in via
    // AnimatedVisibility — pas d'interleaving, donc pas de réordonnancement
    // brutal des Filled quand on bascule idle ↔ actif.
    val filledSlots = slots.filterIsInstance<AppShortcuts.GridSlot.Filled>()
    val emptySlots = slots.filterIsInstance<AppShortcuts.GridSlot.Empty>()
    val firstEmptyIdx = emptySlots.firstOrNull()?.index
    val lastEmptyIdx = emptySlots.lastOrNull()?.index
    val showLeftEdge = !isIdle && firstEmptyIdx != null
    // Évite d'afficher deux "+" pointant vers le même slot.
    val showRightEdge = !isIdle && lastEmptyIdx != null && lastEmptyIdx != firstEmptyIdx

    if (filledSlots.isEmpty() && isIdle) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                "Aucune app — appuie sur + pour en ajouter",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    FlowRow(
        modifier = modifier.graphicsLayer {
            alpha = gridAlpha
            scaleX = gridScale
            scaleY = gridScale
        },
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // "+" gauche : entre en glissant depuis la gauche, sort de même.
        AnimatedVisibility(
            visible = showLeftEdge,
            enter = slideInHorizontally(tween(350)) { -it } +
                fadeIn(tween(350)) +
                scaleIn(tween(350), initialScale = 0.6f),
            exit = slideOutHorizontally(tween(250)) { -it } +
                fadeOut(tween(250)) +
                scaleOut(tween(250), targetScale = 0.6f),
        ) {
            firstEmptyIdx?.let { idx ->
                EmptyTile(
                    showLabel = showLabels,
                    onClick = { onPickEmpty(idx) },
                    modifier = Modifier.size(tileSize),
                )
            }
        }

        filledSlots.forEach { slot ->
            ShortcutTile(
                shortcut = slot.shortcut,
                showLabel = showLabels,
                onClick = { onLaunch(slot.shortcut.packageName) },
                onLongPress = { onLongPressFilled(slot.index) },
                modifier = Modifier.size(tileSize),
            )
        }

        // "+" droit : entre en glissant depuis la droite.
        AnimatedVisibility(
            visible = showRightEdge,
            enter = slideInHorizontally(tween(350)) { it } +
                fadeIn(tween(350)) +
                scaleIn(tween(350), initialScale = 0.6f),
            exit = slideOutHorizontally(tween(250)) { it } +
                fadeOut(tween(250)) +
                scaleOut(tween(250), targetScale = 0.6f),
        ) {
            lastEmptyIdx?.let { idx ->
                EmptyTile(
                    showLabel = showLabels,
                    onClick = { onPickEmpty(idx) },
                    modifier = Modifier.size(tileSize),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortcutTile(
    shortcut: AppShortcuts.ResolvedShortcut,
    showLabel: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(GlassBg)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (shortcut.icon != null) {
                Image(
                    bitmap = shortcut.icon,
                    contentDescription = shortcut.label,
                    modifier = Modifier.size(if (showLabel) 52.dp else 64.dp)
                        .clip(RoundedCornerShape(13.dp)),
                )
            } else {
                Box(
                    modifier = Modifier.size(if (showLabel) 52.dp else 64.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(GlassBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(shortcut.label.take(2),
                        color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                }
            }
            if (showLabel) {
                Spacer(Modifier.height(8.dp))
                Text(
                    shortcut.label,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun EmptyTile(
    showLabel: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(GlassBg.copy(alpha = 0.4f))
            .border(2.dp, GlassBorder.copy(alpha = 0.5f), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("+", color = MaterialTheme.colorScheme.primary, fontSize = 42.sp, fontWeight = FontWeight.Light)
            if (showLabel) {
                Text("Ajouter", color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
