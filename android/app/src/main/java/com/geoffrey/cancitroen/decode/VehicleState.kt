package com.geoffrey.cancitroen.decode

/** Snapshot complet de l'état véhicule, immuable. Diffusé via StateFlow. */
data class VehicleState(
    // ── Moteur / dynamique ──
    val rpm: Double? = null,
    val speed: Double? = null,
    val tCoolant: Int? = null,
    val tOil: Int? = null,
    val tExt: Int? = null,
    val odo: Double? = null,
    val reverseGear: Boolean = false,
    val wiperActive: Boolean = false,
    val wiperAuto: Boolean = false,
    val gearCmb: String? = null,

    // ── Carburant / huile ──
    val fuelPct: Int? = null,
    val fuelLitersEst: Double? = null,
    val oilLevelPct: Int? = null,
    val oilLevelLitersEst: Double? = null,
    val fuelInst: Double? = null,        // l/100 (null = "non calculé")
    val rangeKm: Int? = null,            // autonomie km
    val fuelLowWarning: Boolean = false,

    // ── BSI / contact ──
    val contact: Boolean = false,
    val keyPosition: KeyPosition = KeyPosition.STOP,
    val ignitionMode: IgnitionMode = IgnitionMode.STANDBY,
    val economyMode: Boolean = false,
    val nightMode: Boolean = false,
    val dashboardBrightness: Int? = null,
    val blackPanel: Boolean = false,
    val datetimeBsi: String? = null,

    // ── Ouvrants ──
    val doors: Doors = Doors(),

    // ── Feux ──
    val lights: Lights = Lights(),
    val warningsOn: Boolean = false,

    // ── Témoins / alertes ──
    val warn: Warnings = Warnings(),

    // ── Volant ──
    val wheelButton: String? = null,
    val wheelButtonRaw: Int = 0,
    val wheelScroll: Int? = null,
    val wheelScrollDelta: Int = 0,

    // ── Trip computer ──
    val tripAvgSpeed: Int? = null,
    val tripDist: Double? = null,
    val tripAvgCons: Double? = null,
    val tripDistTotal: Int? = null,
    val emfPage: EmfPage = EmfPage.NONE,

    // ── Maintenance ──
    val maintDue: Boolean = false,
    val maintKmRemaining: Int? = null,
    val maintDaysRemaining: Int? = null,

    val handbrake: Boolean = false,
)

enum class KeyPosition { STOP, CONTACT, STARTER, FREE }
enum class IgnitionMode { STANDBY, NORMAL, STANDBY_SOON, WAKE_UP, COM_OFF, UNKNOWN }
enum class EmfPage { NONE, GENERAL, TRIP1, TRIP2, NOT_MGD, UNKNOWN }

data class Doors(
    val avg: Boolean = false,
    val avd: Boolean = false,
    val arg: Boolean = false,
    val ard: Boolean = false,
    val coffre: Boolean = false,
    val capot: Boolean = false,
    val vitreAr: Boolean = false,
    val trappeCarb: Boolean = false,
) {
    val anyOpen: Boolean
        get() = avg || avd || arg || ard || coffre || capot || vitreAr
}

data class Lights(
    val drl: Boolean = false,
    val clignoG: Boolean = false,
    val clignoD: Boolean = false,
    val antibrouillardAv: Boolean = false,
    val antibrouillardAr: Boolean = false,
    val feuxRoute: Boolean = false,
    val feuxCroisement: Boolean = false,
    val feuxPosition: Boolean = false,
)

data class Warnings(
    val fuelLow: Boolean = false,
    val driverBelt: Boolean = false,
    val passengerBelt: Boolean = false,
    val passengerAirbagOff: Boolean = false,
    val absActive: Boolean = false,
    val espInProgress: Boolean = false,
    val espInactivated: Boolean = false,
    val serviceExclamation: Boolean = false,
    val oilPressureAlert: Boolean = false,
    val oilLevelAlert: Boolean = false,
    val coolantLevelAlert: Boolean = false,
    val coolantTempMax: Boolean = false,
    val oilTempMax: Boolean = false,
    val brakeFluidAlert: Boolean = false,
    val tyrePressureLow: Boolean = false,
    val tyrePunctured: Boolean = false,
    val fapClogged: Boolean = false,
    val maxRpm1: Boolean = false,
    val maxRpm2: Boolean = false,
    val warningActive: Boolean = false,
    val dieselPreheat: Boolean = false,
    val doorOpenAbove10: Boolean = false,
    val doorOpenBelow10: Boolean = false,
)
