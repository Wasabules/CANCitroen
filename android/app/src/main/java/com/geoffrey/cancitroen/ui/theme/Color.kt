package com.geoffrey.cancitroen.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// ── Palette deep / minimaliste type Tesla / Mercedes MBUX ──
val Background  = Color(0xFF050810)   // bleu-noir profond
val BgGradTop   = Color(0xFF0B121C)
val BgGradBot   = Color(0xFF02050A)

val Panel       = Color(0xFF131A23)
val Panel2      = Color(0xFF0E131B)
val Border      = Color(0xFF1E2832)

val Warn        = Color(0xFFFFB74D)
val Err         = Color(0xFFEF5350)
val Ok          = Color(0xFF66BB6A)
val Text        = Color(0xFFE0E6ED)
val Dim         = Color(0xFF6B7785)

// Accent par défaut (Cyan) — conservé pour les rares helpers non-@Composable.
// Dans le code Compose préférer MaterialTheme.colorScheme.primary +
// LocalAccentSoft.current qui reflètent le choix utilisateur en live.
val Accent      = Color(0xFF4DD0E1)
val AccentSoft  = Color(0xFF80DEEA)

// ── Glass (verre) ──
val GlassBg          = Color(0x14FFFFFF)   // 8% blanc
val GlassBgStrong    = Color(0x22FFFFFF)   // 13% blanc, pour boutons hover
val GlassBorder      = Color(0x26FFFFFF)   // 15% blanc
val GlassBorderSoft  = Color(0x14FFFFFF)
val GlassShadow      = Color(0x40000000)   // 25% noir, drop-shadow

/** Gradient de fond app — bleu-noir vertical Tesla-like. */
val AppBackgroundGradient: Brush
    get() = Brush.verticalGradient(
        colors = listOf(BgGradTop, Background, BgGradBot),
    )

/** Gradient subtil interne pour cards glass (haut → bas, légèrement décroissant). */
val GlassCardGradient: Brush
    get() = Brush.verticalGradient(
        colors = listOf(
            Color(0x1AFFFFFF),  // 10% blanc en haut
            Color(0x08FFFFFF),  // 3% blanc en bas
        ),
    )

/**
 * Préréglages d'accent — choisis pour s'aligner aux couleurs courantes des
 * lumières d'ambiance voiture. Chaque preset définit :
 *  - [accent]      : teinte principale (boutons, arcs gauges, highlights)
 *  - [accentSoft]  : variante claire pour gradients (sweep gauges) et hovers
 *  - [onAccent]    : couleur lisible posée SUR l'accent (texte de bouton)
 *
 * L'utilisateur change le preset depuis Réglages → Thème. Tout le reste de
 * l'UI hérite de ce choix via MaterialTheme.colorScheme.primary et le
 * CompositionLocal LocalAccentSoft.
 */
enum class AccentPreset(
    val label: String,
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color,
    /**
     * Hue (0..360°) utilisée pour teinter backgrounds/panels. Hardcodée
     * pour éviter d'extraire dynamiquement via Color.hsl (qui demande
     * un cast int et n'est pas direct côté Compose). Pour WHITE on met
     * 215° (bleu froid) car le blanc n'a pas de hue propre — ça garde
     * un fond légèrement bleu-noir Tesla-like.
     */
    val hue: Float,
) {
    CYAN  ("Cyan",   Color(0xFF4DD0E1), Color(0xFF80DEEA), Color(0xFF002B30), 188f),
    BLUE  ("Bleu",   Color(0xFF5B8DEF), Color(0xFF8AAFFF), Color(0xFF001A4D), 220f),
    AMBER ("Ambre",  Color(0xFFFFB74D), Color(0xFFFFD180), Color(0xFF2A1A00),  35f),
    RED   ("Rouge",  Color(0xFFEF5350), Color(0xFFFF8A80), Color(0xFFFFFFFF),   1f),
    GREEN ("Vert",   Color(0xFF66BB6A), Color(0xFFA5D6A7), Color(0xFF07260E), 122f),
    VIOLET("Violet", Color(0xFFB388FF), Color(0xFFD1B3FF), Color(0xFF1A0040), 262f),
    WHITE ("Blanc",  Color(0xFFE0E6ED), Color(0xFFF5F5F7), Color(0xFF0A0E14), 215f);
}
