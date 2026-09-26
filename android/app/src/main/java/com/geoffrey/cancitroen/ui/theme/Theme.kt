package com.geoffrey.cancitroen.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.geoffrey.cancitroen.App
import com.geoffrey.cancitroen.settings.AppSettings

/**
 * Variante claire de l'accent — utilisée pour les gradients de sweep, les
 * hovers et les composants secondaires. Default = [AccentSoft] (cyan clair)
 * pour les Previews Compose qui n'ont pas de CANCitroenTheme parent.
 */
val LocalAccentSoft = compositionLocalOf { AccentSoft }

/**
 * Mode nuit global — set par HomeScreen. Quand true :
 *  - Les icônes accessoires (status bar, page indicator, shortcuts) baissent
 *    leur alpha vers 0.3 pour ne pas distraire la conduite nocturne
 *  - Le sweep des secondes de l'horloge est désactivé
 *  - Les shortcuts forcent leur mode "idle" (estompé) en permanence
 *
 * Les éléments vitaux (vitesse, RPM, alertes rouge/orange) restent en
 * plein contraste.
 */
val LocalNightMode = compositionLocalOf { false }

/**
 * Gradient de fond app dynamique — calculé depuis la teinte de l'accent.
 * Fallback statique pour les Previews.
 */
val LocalAppBackgroundGradient = compositionLocalOf { AppBackgroundGradient }

/**
 * Construit un dark theme dont les panels/borders sont teintés très légèrement
 * vers l'accent — pour que toute l'app baigne dans la couleur du preset, pas
 * juste les boutons. À 5-15% de saturation, le résultat reste sombre et lisible
 * même de nuit, mais le bleu "Tesla par défaut" disparaît quand on choisit
 * rouge/vert/ambre.
 */
private fun buildTintedDarkScheme(preset: AccentPreset): androidx.compose.material3.ColorScheme {
    val hue = preset.hue
    val background        = Color.hsl(hue, 0.40f, 0.04f)   // fond app
    val surface           = Color.hsl(hue, 0.20f, 0.10f)   // cards / sheets
    val surfaceVariant    = Color.hsl(hue, 0.18f, 0.07f)   // panels secondaires
    val outline           = Color.hsl(hue, 0.15f, 0.14f)   // bordures
    // ⚠ Material3 fournit des defaults *bluish-lavender* aux containers non
    // spécifiés (primaryContainer, secondaryContainer, tertiaryContainer).
    // FilledTonalButton et autres composants Material3 les utilisent → sans
    // override on voit du violet pâle qui n'a rien à voir avec le preset.
    // On définit donc TOUS les containers depuis la hue accent.
    val primaryContainer  = Color.hsl(hue, 0.35f, 0.18f)   // bouton tonal accent
    val secondaryContainer = Color.hsl(hue, 0.20f, 0.16f)  // bouton tonal secondaire
    val tertiary          = Color.hsl(hue, 0.30f, 0.50f)
    val tertiaryContainer = Color.hsl(hue, 0.25f, 0.20f)
    // Texte : presque blanc avec une teinte très légère de l'accent. Avant
    // la couleur Text était hardcodée `#E0E6ED` (légèrement bleutée) → tous
    // les textes restaient bleus même en preset rouge/ambre/vert.
    val onText            = Color.hsl(hue, 0.15f, 0.92f)   // texte principal
    val onTextDim         = Color.hsl(hue, 0.10f, 0.55f)   // texte secondaire (MaterialTheme.colorScheme.onSurfaceVariant)

    return darkColorScheme(
        primary             = preset.accent,
        onPrimary           = preset.onAccent,
        primaryContainer    = primaryContainer,
        onPrimaryContainer  = preset.accent,
        secondary           = Ok,
        onSecondary         = Color(0xFF07260E),
        secondaryContainer  = secondaryContainer,
        onSecondaryContainer = onText,
        tertiary            = tertiary,
        onTertiary          = Color.Black,
        tertiaryContainer   = tertiaryContainer,
        onTertiaryContainer = onText,
        background          = background,
        onBackground        = onText,
        surface             = surface,
        onSurface           = onText,
        surfaceVariant      = surfaceVariant,
        onSurfaceVariant    = onTextDim,
        error               = Err,
        onError             = Color.White,
        outline             = outline,
        outlineVariant      = outline,
    )
}

/**
 * Construit le gradient de fond app : du plus clair (haut) au plus sombre
 * (bas), tous teintés vers l'accent. Reproduit le look Tesla/MBUX d'avant
 * mais avec la teinte choisie.
 */
private fun buildAppBackground(preset: AccentPreset): Brush {
    val hue = preset.hue
    return Brush.verticalGradient(
        colors = listOf(
            Color.hsl(hue, 0.30f, 0.08f),  // top, plus clair
            Color.hsl(hue, 0.40f, 0.04f),  // milieu (= background)
            Color.hsl(hue, 0.50f, 0.02f),  // bas, presque noir
        ),
    )
}

@Composable
fun CANCitroenTheme(content: @Composable () -> Unit) {
    // On lit le preset depuis DataStore. Pas dispo sur Compose Previews (App
    // pas init) donc on fallback gracefully sur CYAN.
    // Seul l'accès à App est protégé : un try/catch autour d'un appel
    // @Composable est interdit par le compilateur Compose.
    val settingsFlow = remember { runCatching { App.get().settings.flow }.getOrNull() }
    val preset = if (settingsFlow != null) {
        val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = AppSettings())
        settings.theme.accent
    } else AccentPreset.CYAN

    val scheme = remember(preset) { buildTintedDarkScheme(preset) }
    val bgGradient = remember(preset) { buildAppBackground(preset) }

    // Toujours le thème sombre — c'est une voiture de nuit comme de jour ;
    // un fond clair éblouirait au volant.
    CompositionLocalProvider(
        LocalAccentSoft provides preset.accentSoft,
        LocalAppBackgroundGradient provides bgGradient,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography  = Typography,
            content     = content,
        )
    }
}
