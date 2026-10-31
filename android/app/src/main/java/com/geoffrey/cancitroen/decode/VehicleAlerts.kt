package com.geoffrey.cancitroen.decode

/** Alertes à voir depuis n'importe quelle page de l'app, par gravité décroissante. */
enum class VehicleAlert(val message: String) {
    OIL_PRESSURE("PRESSION D'HUILE : arrêt dès que possible"),
    COOLANT_TEMP("TEMPÉRATURE D'EAU ÉLEVÉE : arrêt dès que possible"),
    COOLANT_LEVEL("NIVEAU DE LIQUIDE DE REFROIDISSEMENT : à vérifier"),
    OIL_LEVEL("NIVEAU D'HUILE : à vérifier"),
}

/** Au-delà de la plage normale d'un moteur essence, ventilateur compris. */
const val COOLANT_ALERT_C = 118
/** Moteur à l'arrêt, le voyant de pression d'huile est allumé : ce n'est pas une panne. */
private const val ENGINE_RUNNING_RPM = 600.0

fun activeAlerts(s: VehicleState): List<VehicleAlert> {
    if (!s.contact) return emptyList()
    return buildList {
        if (s.warn.oilPressureAlert && (s.rpm ?: 0.0) >= ENGINE_RUNNING_RPM) add(VehicleAlert.OIL_PRESSURE)
        if ((s.tCoolant ?: 0) >= COOLANT_ALERT_C) add(VehicleAlert.COOLANT_TEMP)
        if (s.warn.coolantLevelAlert) add(VehicleAlert.COOLANT_LEVEL)
        if (s.warn.oilLevelAlert) add(VehicleAlert.OIL_LEVEL)
    }
}
