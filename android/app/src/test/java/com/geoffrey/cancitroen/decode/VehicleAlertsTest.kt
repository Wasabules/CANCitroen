package com.geoffrey.cancitroen.decode

import org.junit.Assert.assertEquals
import org.junit.Test

class VehicleAlertsTest {

    private val running = VehicleState(contact = true, rpm = 900.0, tCoolant = 90)

    @Test
    fun aucuneAlerteEnTempsNormal() {
        assertEquals(emptyList<VehicleAlert>(), activeAlerts(running))
    }

    @Test
    fun pressionHuileSeulementMoteurTournant() {
        val warn = Warnings(oilPressureAlert = true)
        assertEquals(listOf(VehicleAlert.OIL_PRESSURE), activeAlerts(running.copy(warn = warn)))
        // Contact mis, moteur arrêté : le voyant est normalement allumé.
        assertEquals(emptyList<VehicleAlert>(), activeAlerts(running.copy(warn = warn, rpm = 0.0)))
        assertEquals(emptyList<VehicleAlert>(), activeAlerts(running.copy(warn = warn, rpm = null)))
    }

    @Test
    fun temperatureEauAuSeuil() {
        assertEquals(emptyList<VehicleAlert>(), activeAlerts(running.copy(tCoolant = COOLANT_ALERT_C - 1)))
        assertEquals(listOf(VehicleAlert.COOLANT_TEMP), activeAlerts(running.copy(tCoolant = COOLANT_ALERT_C)))
    }

    @Test
    fun rienContactCoupeEtOrdreDeGravite() {
        val all = Warnings(oilPressureAlert = true, oilLevelAlert = true, coolantLevelAlert = true)
        assertEquals(emptyList<VehicleAlert>(), activeAlerts(running.copy(contact = false, warn = all)))
        assertEquals(
            listOf(VehicleAlert.OIL_PRESSURE, VehicleAlert.COOLANT_LEVEL, VehicleAlert.OIL_LEVEL),
            activeAlerts(running.copy(warn = all)),
        )
    }
}
