package com.geoffrey.cancitroen.audio

import kotlin.math.max
import kotlin.math.tanh

/** Le moteur vu par [EngineSoundCore] : sa géométrie, d'où découle le son. */
class EngineProfile(
    val id: String,
    val name: String,
    val description: String,

    /** Nombre de cylindres (virtuel pour un moteur électrique). */
    val cylinders: Int,
    val idleRpm: Float,
    val redlineRpm: Float,
    /** Tours moteur simulés par tour de la voiture, au-dessus du ralenti. */
    val revMultiplier: Float = fullRangeMultiplier(idleRpm, redlineRpm),

    /** Instant de chaque explosion dans le cycle 4 temps, dans [0, 1[ et trié. */
    val firing: FloatArray = FloatArray(0),
    /** Force de chaque explosion : un écart entre cylindres crée les sous-harmoniques. */
    val firingGain: FloatArray = FloatArray(0),
    /** Allumage ou collecteur irréguliers (V8 cross-plane, boxer). */
    val irregular: Boolean = false,

    /** Dimensions d'un cylindre : elles fixent la durée de la bouffée d'échappement. */
    val boreM: Float = 0.08f,
    val strokeM: Float = 0.08f,
    /** Longueur de chaque collecteur (m), différente selon l'architecture. */
    val headerM: FloatArray = FloatArray(0),
    val exhaust: ExhaustType = ExhaustType.STOCK,
    val diesel: Boolean = false,
    /** Moteur électrique : coupure du filtre de sortie, au repos puis à plein régime. */
    val lowpassIdleHz: Float = 1800f,
    val lowpassRedlineHz: Float = 6500f,
    /** Sifflement du turbo et décharge au lever de pied. */
    val turbo: Boolean = false,
    val pops: PopLevel = PopLevel.OFF,
    val popForce: Float = 1f,
    val popBodyHz: Float = 110f,
    val turboRelease: TurboRelease = TurboRelease.DUMP_VALVE,
    /** Pas d'explosions : sifflement de l'onduleur et du moteur. */
    val electric: Boolean = false,
    /** La configuration d'origine, pour repartir de ce profil dans le configurateur. */
    val config: EngineConfig? = null,
) {
    /**
     * Régime simulé pour un régime réel de la voiture. Au ralenti, les deux
     * ralentis coïncident ; ensuite le multiplicateur s'applique. La courbe se
     * couche à l'approche du régime maximal, sans jamais butter : pas de cassure.
     */
    fun fromCarRpm(carRpm: Float): Float {
        if (carRpm < MIN_CAR_RPM) return 0f
        if (carRpm < CAR_IDLE_RPM) return idleRpm * carRpm / CAR_IDLE_RPM
        val above = (carRpm - CAR_IDLE_RPM) * revMultiplier
        val room = max(redlineRpm - idleRpm, 1f)
        val knee = room * SOFT_CEILING_FROM
        val rest = room - knee
        val climbed = if (above <= knee) above else knee + rest * tanh((above - knee) / rest)
        return idleRpm + climbed
    }

    companion object {
        const val CAR_IDLE_RPM = 850f
        const val CAR_REDLINE_RPM = 6200f
        private const val MIN_CAR_RPM = 100f
        private const val SOFT_CEILING_FROM = 0.8f

        fun fullRangeMultiplier(idle: Float, redline: Float) =
            (redline - idle) / (CAR_REDLINE_RPM - CAR_IDLE_RPM)
    }
}

object EngineProfiles {
    const val CUSTOM_ID = "CUSTOM"

    val CITROEN_C2 = EngineConfig(4, EngineLayout.INLINE, 1360, 75, 6200).toProfile(
        "CITROEN_C2", "Citroën C2 1.4", "Ta voiture, avec un son d'origine.",
    )
    val FOUR_CYL_SPORT = EngineConfig(4, EngineLayout.INLINE, 2000, 86, 7200, ExhaustType.SPORT, pops = PopLevel.LIGHT).toProfile(
        "FOUR_CYL_SPORT", "4 cylindres sportif", "Compact essence type GTI / Civic Type-R. Aigu, vif.",
    )
    val THREE_CYL_TURBO = EngineConfig(3, EngineLayout.INLINE, 1200, 75, 6000, ExhaustType.SPORT, Aspiration.TURBO, pops = PopLevel.LIGHT).toProfile(
        "THREE_CYL_TURBO", "3 cylindres turbo", "Petit bloc moderne. Rauque, sifflement de turbo.",
    )
    val V6_GT = EngineConfig(6, EngineLayout.V, 3000, 86, 6500, ExhaustType.SPORT).toProfile(
        "V6_GT", "V6 GT", "Berline grand tourisme. Rond, équilibré.",
    )
    val V8_MUSCLE = EngineConfig(8, EngineLayout.CROSS_PLANE, 5000, 96, 6000, ExhaustType.SPORT, pops = PopLevel.LIGHT).toProfile(
        "V8_MUSCLE", "V8 muscle car", "Mustang / Camaro. Gros, grave, rugueux.",
    )
    val BOXER_4 = EngineConfig(4, EngineLayout.BOXER, 2500, 99, 6800, ExhaustType.SPORT, Aspiration.TURBO, pops = PopLevel.LIGHT, turboRelease = TurboRelease.FLUTTER).toProfile(
        "BOXER_4", "Boxer 4 (Subaru)", "Signature flat-four. Son par paires, distinct.",
    )
    val DIESEL_4 = EngineConfig(4, EngineLayout.INLINE, 2000, 85, 4500, fuel = EngineFuel.DIESEL, aspiration = Aspiration.TURBO).toProfile(
        "DIESEL_4", "Diesel 4 cyl", "Utilitaire. Grave, léger claquement.",
    )
    val GOLF_GTI = EngineConfig(4, EngineLayout.INLINE, 1984, 83, 6500, ExhaustType.SPORT, Aspiration.TURBO, pops = PopLevel.LIGHT).toProfile(
        "GOLF_GTI", "VW Golf GTI", "4 cylindres 2.0 turbo, décharge et pops légers.",
    )
    val CLIO_RS = EngineConfig(4, EngineLayout.INLINE, 1998, 82, 7300, ExhaustType.SPORT, pops = PopLevel.LIGHT).toProfile(
        "CLIO_RS", "Renault Clio RS", "4 cylindres 2.0 atmosphérique qui prend des tours.",
    )
    val AUDI_5 = EngineConfig(5, EngineLayout.INLINE, 2480, 82, 7000, ExhaustType.SPORT, Aspiration.TURBO, pops = PopLevel.LIGHT).toProfile(
        "AUDI_5", "Audi RS3 5 cylindres", "Le chant irrégulier du 5 cylindres turbo.",
    )
    val BMW_M3 = EngineConfig(6, EngineLayout.INLINE, 3246, 87, 8000, ExhaustType.SPORT).toProfile(
        "BMW_M3", "BMW M3 6 en ligne", "Atmosphérique, très rond, monte haut.",
    )
    val PORSCHE_911 = EngineConfig(6, EngineLayout.BOXER, 3800, 102, 7800, ExhaustType.SPORT, pops = PopLevel.LIGHT).toProfile(
        "PORSCHE_911", "Porsche 911 flat-6", "Boxer 6 cylindres, sonorité métallique.",
    )
    val LAMBO_V10 = EngineConfig(10, EngineLayout.V, 5204, 85, 8500, ExhaustType.STRAIGHT, pops = PopLevel.STRONG).toProfile(
        "LAMBO_V10", "Lamborghini V10", "Atmosphérique, ligne libre, détonations franches.",
    )
    val FERRARI_V12 = EngineConfig(12, EngineLayout.V, 6496, 94, 8900, ExhaustType.SPORT).toProfile(
        "FERRARI_V12", "Ferrari V12", "12 cylindres atmosphérique, aigu à haut régime.",
    )
    val EV_FUTURISTIC = EngineProfile(
        id = "EV_FUTURISTIC",
        name = "EV futuriste",
        description = "Tesla / Polestar / vaisseau spatial. Aigu, propre.",
        cylinders = 2,    // virtuel : le mode meme s'en sert pour sa cadence
        idleRpm = 200f,
        redlineRpm = 12000f,
        electric = true,
    )

    val ALL = listOf(
        CITROEN_C2, FOUR_CYL_SPORT, THREE_CYL_TURBO, GOLF_GTI, CLIO_RS, AUDI_5, BMW_M3, V6_GT,
        PORSCHE_911, BOXER_4, V8_MUSCLE, LAMBO_V10, FERRARI_V12, DIESEL_4, EV_FUTURISTIC,
    )

    fun byId(id: String): EngineProfile = ALL.firstOrNull { it.id == id } ?: FOUR_CYL_SPORT

    private var lastCustom: EngineProfile? = null

    /** Même configuration, même objet : le son ne repasse pas par un fondu sans raison. */
    @Synchronized
    fun resolve(id: String, custom: EngineConfig): EngineProfile {
        if (id != CUSTOM_ID) return byId(id)
        lastCustom?.takeIf { it.config == custom }?.let { return it }
        return custom.toProfile(CUSTOM_ID, "Mon moteur", "Votre configuration.").also { lastCustom = it }
    }
}
