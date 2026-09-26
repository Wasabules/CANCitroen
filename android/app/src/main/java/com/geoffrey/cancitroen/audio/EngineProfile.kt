package com.geoffrey.cancitroen.audio

import androidx.compose.ui.graphics.Color

/**
 * Profil sonore d'un moteur : nombre de cylindres, signature harmonique,
 * filtre, niveau de bruit. Tout est synthétisé procéduralement à partir de
 * ces paramètres.
 */
data class EngineProfile(
    val id: String,
    val name: String,
    val description: String,
    val emoji: String,
    val accent: Color,

    /** Nombre de cylindres (utilisé pour calculer la fréquence de combustion). */
    val cylinders: Int,
    /** RPM ralenti et redline pour mapper le pitch en pratique. */
    val idleRpm: Float = 800f,
    val redlineRpm: Float = 6500f,
    /** Poids des harmoniques [1, 2, 3, 4, ...] pour modeler le timbre. */
    val harmonicWeights: FloatArray,
    /** Coupure du filtre passe-bas (Hz) au ralenti. Augmente avec les RPM. */
    val lowpassIdleHz: Float,
    val lowpassRedlineHz: Float,
    /** Quantité de bruit blanc filtré ajoutée ([0..1]). */
    val noiseLevel: Float,
    /** Forme : 0=pur sinus, 1=sawtooth (plus agressif). */
    val sawtoothMix: Float,
    /** Décalage entre cylindres (irrégularité, type V8 cross-plane). */
    val crossplane: Boolean = false,
)

object EngineProfiles {

    val FOUR_CYL_SPORT = EngineProfile(
        id = "FOUR_CYL_SPORT",
        name = "4 cylindres sportif",
        description = "Compact essence type GTI / Civic Type-R. Aigu, vif.",
        emoji = "🏎️",
        accent = Color(0xFF4DD0E1),
        cylinders = 4,
        idleRpm = 850f,
        redlineRpm = 7200f,
        harmonicWeights = floatArrayOf(1.0f, 0.55f, 0.30f, 0.15f, 0.08f),
        lowpassIdleHz = 350f,
        lowpassRedlineHz = 3500f,
        noiseLevel = 0.12f,
        sawtoothMix = 0.7f,
    )

    val V6_GT = EngineProfile(
        id = "V6_GT",
        name = "V6 GT",
        description = "Berline grand tourisme. Rond, équilibré.",
        emoji = "🚗",
        accent = Color(0xFF8E97FD),
        cylinders = 6,
        idleRpm = 750f,
        redlineRpm = 6500f,
        harmonicWeights = floatArrayOf(1.0f, 0.45f, 0.25f, 0.18f, 0.10f),
        lowpassIdleHz = 280f,
        lowpassRedlineHz = 2800f,
        noiseLevel = 0.08f,
        sawtoothMix = 0.5f,
    )

    val V8_MUSCLE = EngineProfile(
        id = "V8_MUSCLE",
        name = "V8 muscle car",
        description = "Mustang / Camaro. Gros, grave, rugueux.",
        emoji = "🇺🇸",
        accent = Color(0xFFEF5350),
        cylinders = 8,
        idleRpm = 700f,
        redlineRpm = 6000f,
        harmonicWeights = floatArrayOf(1.0f, 0.7f, 0.5f, 0.35f, 0.22f, 0.15f),
        lowpassIdleHz = 220f,
        lowpassRedlineHz = 2200f,
        noiseLevel = 0.22f,
        sawtoothMix = 0.85f,
        crossplane = true,
    )

    val BOXER_4 = EngineProfile(
        id = "BOXER_4",
        name = "Boxer 4 (Subaru)",
        description = "Signature flat-four. Son tap-tap distinct.",
        emoji = "🌀",
        accent = Color(0xFF66BB6A),
        cylinders = 4,
        idleRpm = 800f,
        redlineRpm = 6800f,
        harmonicWeights = floatArrayOf(1.0f, 0.4f, 0.6f, 0.2f, 0.15f),  // 3e harmonique forte = signature
        lowpassIdleHz = 320f,
        lowpassRedlineHz = 3200f,
        noiseLevel = 0.18f,
        sawtoothMix = 0.65f,
        crossplane = true,
    )

    val DIESEL_4 = EngineProfile(
        id = "DIESEL_4",
        name = "Diesel 4 cyl",
        description = "Tracteur / utilitaire. Grave, claquement.",
        emoji = "🚜",
        accent = Color(0xFF8D6E63),
        cylinders = 4,
        idleRpm = 850f,
        redlineRpm = 4500f,
        harmonicWeights = floatArrayOf(1.0f, 0.6f, 0.4f, 0.3f, 0.25f),
        lowpassIdleHz = 180f,
        lowpassRedlineHz = 1800f,
        noiseLevel = 0.40f,
        sawtoothMix = 0.95f,
    )

    val EV_FUTURISTIC = EngineProfile(
        id = "EV_FUTURISTIC",
        name = "EV futuriste",
        description = "Tesla / Polestar / vaisseau spatial. Aigu, propre.",
        emoji = "⚡",
        accent = Color(0xFFFFB74D),
        cylinders = 2,    // virtuel, donne un fondamental plus haut
        idleRpm = 200f,
        redlineRpm = 12000f,
        harmonicWeights = floatArrayOf(1.0f, 0.0f, 0.15f, 0.0f, 0.05f),  // harmoniques impaires
        lowpassIdleHz = 600f,
        lowpassRedlineHz = 6000f,
        noiseLevel = 0.04f,
        sawtoothMix = 0.0f,    // sinus pur
    )

    val ALL = listOf(
        FOUR_CYL_SPORT, V6_GT, V8_MUSCLE,
        BOXER_4, DIESEL_4, EV_FUTURISTIC,
    )

    fun byId(id: String): EngineProfile = ALL.firstOrNull { it.id == id } ?: FOUR_CYL_SPORT
}
