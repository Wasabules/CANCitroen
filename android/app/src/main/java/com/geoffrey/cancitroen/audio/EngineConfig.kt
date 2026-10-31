package com.geoffrey.cancitroen.audio

import kotlin.math.pow

import kotlin.math.PI

enum class EngineLayout(val label: String) {
    INLINE("En ligne"),
    V("En V"),
    CROSS_PLANE("V cross-plane"),
    BOXER("Boxer"),
}

/**
 * Ce que le silencieux fait subir à l'onde : [chambers] la disperse (donc l'étouffe
 * et la fait durer), [cutoffHz] retire le haut du spectre, [reflection] renvoie une
 * part de l'onde vers le moteur (le caractère creux d'un tube libre).
 */
enum class ExhaustType(
    val label: String,
    val chambers: Int,
    val cutoffHz: Float,
    val reflection: Float,
    val pipeM: Float,
    val level: Float,
    /** Détonations dans le tube : force en sortie, fréquence, brillance, durée. */
    val popOpening: Float,
    val popRate: Float,
    val popCutoffHz: Float,
    val popDecayS: Float,
    /** Ce que ça donne à l'oreille, pour le configurateur. */
    val popCharacter: String,
) {
    STOCK("D'origine", 3, 2200f, 0.22f, 2.6f, 0.8f, 0.45f, 0.45f, 700f, 0.05f, "Rares et sourdes, retenues par le silencieux."),
    SPORT("Sport", 2, 4800f, 0.34f, 2.2f, 1f, 0.85f, 1f, 1500f, 0.04f, "Franches, avec du corps."),
    STRAIGHT("Ligne directe", 0, 11000f, 0.48f, 1.8f, 1.25f, 1.3f, 1.5f, 2400f, 0.045f, "Violentes et sèches, rien ne les retient."),
}

/** Détonations par seconde juste après un lever de pied, avec un échappement sport. */
enum class PopLevel(val label: String, val popsPerSecond: Float) {
    OFF("Aucun", 0f),
    LIGHT("Léger", 1.5f),
    STRONG("Fort", 4f),
}

enum class TurboRelease(val label: String) {
    DUMP_VALVE("Dump valve"),
    FLUTTER("Flutter"),
}

enum class Aspiration(val label: String) {
    NATURAL("Atmosphérique"),
    TURBO("Turbo"),
}

enum class EngineFuel(val label: String) {
    PETROL("Essence"),
    DIESEL("Diesel"),
}

/** Le moteur tel qu'on le construirait : le son découle de ces choix. */
data class EngineConfig(
    val cylinders: Int = 4,
    val layout: EngineLayout = EngineLayout.INLINE,
    val displacementCc: Int = 2000,
    val boreMm: Int = 84,
    val redlineRpm: Int = 6800,
    val exhaust: ExhaustType = ExhaustType.STOCK,
    val aspiration: Aspiration = Aspiration.NATURAL,
    val fuel: EngineFuel = EngineFuel.PETROL,
    /** Tours moteur simulés par tour de la voiture ; null : la plage de la voiture couvre celle du moteur. */
    val revMultiplier: Float? = null,
    val pops: PopLevel = PopLevel.OFF,
    val turboRelease: TurboRelease = TurboRelease.DUMP_VALVE,
) {
    val cylinderCc: Float get() = displacementCc.toFloat() / cylinders

    /** Déduite de la cylindrée d'un cylindre et de son alésage. */
    val strokeMm: Float get() {
        val boreCm = boreMm / 10f
        return cylinderCc / (PI.toFloat() / 4f * boreCm * boreCm) * 10f
    }

    fun toProfile(id: String, name: String, description: String): EngineProfile {
        val diesel = fuel == EngineFuel.DIESEL
        val idle = (900f - 0.25f * cylinderCc).coerceAtLeast(600f) + if (diesel) 80f else 0f
        return EngineProfile(
            id = id,
            name = name,
            description = description,
            cylinders = cylinders,
            idleRpm = idle,
            redlineRpm = redlineRpm.toFloat(),
            revMultiplier = revMultiplier ?: EngineProfile.fullRangeMultiplier(idle, redlineRpm.toFloat()),
            firing = firingTimes(),
            firingGain = firingGains(),
            irregular = layout == EngineLayout.BOXER || (layout == EngineLayout.CROSS_PLANE && cylinders >= 4),
            boreM = boreMm / 1000f,
            strokeM = strokeMm / 1000f,
            headerM = headerLengths(),
            exhaust = exhaust,
            diesel = diesel,
            turbo = aspiration == Aspiration.TURBO,
            pops = if (diesel) PopLevel.OFF else pops,
            // Grosse cylindrée : plus de carburant imbrûlé. La turbine absorbe une partie de l'onde.
            popForce = (cylinderCc / 500f).coerceIn(0.4f, 3f).pow(0.3f) * if (aspiration == Aspiration.TURBO) 0.72f else 1f,
            // Le tube résonne selon sa longueur ; un gros cylindre pousse plus d'air, plus grave.
            popBodyHz = 520f / (2f * exhaust.pipeM) * (500f / cylinderCc).coerceIn(0.3f, 2.5f).pow(0.2f),
            turboRelease = turboRelease,
            config = this,
        )
    }

    /** Longueurs réalistes : égales en ligne, par rangée en V, très inégales sur un boxer. */
    private fun headerLengths() = FloatArray(cylinders) { i ->
        when (layout) {
            EngineLayout.INLINE -> 0.62f + 0.05f * HEADER_SPREAD[i % HEADER_SPREAD.size]
            EngineLayout.V, EngineLayout.CROSS_PLANE -> (if (i % 2 == 0) 0.55f else 0.72f) + 0.04f * HEADER_SPREAD[i % HEADER_SPREAD.size]
            EngineLayout.BOXER -> if (i % 2 == 0) 0.48f else 1.05f
        }
    }

    private fun firingTimes(): FloatArray {
        val even = FloatArray(cylinders) { it.toFloat() / cylinders }
        return when {
            layout == EngineLayout.CROSS_PLANE && cylinders == 8 -> CROSS_PLANE_V8
            // Collecteur à longueurs inégales : les impulsions arrivent par paires.
            layout == EngineLayout.BOXER && cylinders % 2 == 0 ->
                FloatArray(cylinders) { even[it] + if (it % 2 == 1) 0.14f / cylinders else 0f }
            else -> even
        }
    }

    private fun firingGains() = FloatArray(cylinders) { i ->
        val spread = CYLINDER_SPREAD[i % CYLINDER_SPREAD.size]
        when (layout) {
            EngineLayout.INLINE -> spread
            EngineLayout.V -> spread * if (i % 2 == 0) 1f else 0.96f
            EngineLayout.BOXER -> spread * if (i % 2 == 0) 1f else 0.76f
            // Deux explosions de suite sur une même rangée : le « glouglou ».
            EngineLayout.CROSS_PLANE -> spread * CROSS_PLANE_BANKS[i % CROSS_PLANE_BANKS.size]
        }
    }

    companion object {
        val CYLINDER_CHOICES = listOf(2, 3, 4, 5, 6, 8, 10, 12)
        val DISPLACEMENT_RANGE = 600..7000
        val BORE_RANGE = 60..110
        val REDLINE_RANGE = 3000..12000
        const val MULTIPLIER_MIN = 0.5f
        const val MULTIPLIER_MAX = 4f

        private val CROSS_PLANE_V8 = floatArrayOf(0f, 0.129f, 0.25f, 0.375f, 0.504f, 0.625f, 0.75f, 0.879f)
        private val CROSS_PLANE_BANKS = floatArrayOf(1f, 0.74f, 1f, 1f, 0.74f, 1f, 0.74f, 0.74f)
        private val HEADER_SPREAD = floatArrayOf(0f, 1f, -1f, 0.5f, -0.5f, 0.8f)
        private val CYLINDER_SPREAD = floatArrayOf(1f, 0.94f, 1.05f, 0.97f, 1.02f, 0.95f)
    }
}
