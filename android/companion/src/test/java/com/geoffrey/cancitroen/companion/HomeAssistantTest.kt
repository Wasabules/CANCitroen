package com.geoffrey.cancitroen.companion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAssistantTest {
    private val full = JSONObject("""{"fuelPct":42,"fuelLiters":17.26,"odoKm":154320.04,"tripActive":false,
        "tripStartTs":0,"tripEndTs":600000,"tripKm":12.34,"tripCons":6.72,"refuelTs":1000,"refuelLiters":30.5,
        "refuelCost":55.123,"refuelPrice":1.8071,"refuelEstimated":true,"monthKm":400,"monthTrips":3,
        "monthCost":45.5,"monthLiters":25,"lastSeenTs":0}""")

    private fun state(id: String, summary: JSONObject = full): Any? {
        val updates = HaPayloads.updateStates(HaSnapshot(summary, projecting = true)).getJSONArray("data")
        for (i in 0 until updates.length()) {
            val item = updates.getJSONObject(i)
            if (item.getString("unique_id") == HaPayloads.UNIQUE_PREFIX + id) return item.get("state")
        }
        error("capteur inconnu : $id")
    }

    @Test fun valeursArrondies() {
        assertEquals(42L, state("jauge"))
        assertEquals(17.3, state("carburant"))
        assertEquals(154320.0, state("odometre"))
        assertEquals(1.807, state("prix_litre"))
        assertEquals(true, state("android_auto"))
    }

    @Test fun valeurInconnueEstNulle() {
        assertEquals(JSONObject.NULL, state("jauge", JSONObject("{}")))
        assertEquals(JSONObject.NULL, state("dernier_plein", JSONObject("{}")))
    }

    @Test fun inscriptionDecritLeCapteur() {
        val sensor = HaPayloads.sensors.first { it.id == "depense_mois" }
        val data = HaPayloads.registerSensor(sensor, HaSnapshot(full, false)).getJSONObject("data")
        assertEquals("cancitroen_depense_mois", data.getString("unique_id"))
        assertEquals("sensor", data.getString("type"))
        assertEquals("monetary", data.getString("device_class"))
        assertEquals("total", data.getString("state_class"))
        assertEquals("EUR", data.getString("unit_of_measurement"))
    }

    @Test fun attributsDuTrajetEtDuPlein() {
        val data = HaPayloads.updateStates(HaSnapshot(full, false)).getJSONArray("data")
        val all = (0 until data.length()).map { data.getJSONObject(it) }
        val trip = all.first { it.getString("unique_id").endsWith("dernier_trajet") }.getJSONObject("attributes")
        assertEquals(10L, trip.getLong("duree_min"))
        val refuel = all.first { it.getString("unique_id").endsWith("dernier_plein") }.getJSONObject("attributes")
        assertEquals(55.12, refuel.getDouble("montant_eur"), 0.0)
        assertTrue(refuel.getBoolean("estimation"))
    }

    @Test fun identifiantsUniques() =
        assertEquals(HaPayloads.sensors.size, HaPayloads.sensors.map { it.id }.toSet().size)

    @Test fun capteursARecreerSelonLaReponse() {
        val answer = JSONObject("""{"cancitroen_jauge":{"success":false,"error":{"code":"not_registered"}},
            "cancitroen_carburant":{"success":true}}""")
        assertEquals(listOf("jauge"), HaPayloads.notRegistered(answer).map { it.id })
    }

    @Test fun adresses() {
        assertEquals(HaUrl.Valid("https://ha.example"), HaUrl.parse(" ha.example/ "))
        assertEquals(HaUrl.Valid("https://ha.example:8443"), HaUrl.parse("HTTPS://HA.example:8443"))
        assertEquals(HaUrl.Valid("http://192.168.1.10:8123"), HaUrl.parse("http://192.168.1.10:8123/"))
        assertEquals(HaUrl.Valid("http://homeassistant.local:8123"), HaUrl.parse("http://homeassistant.local:8123"))
        assertTrue(HaUrl.parse("http://ha.example") is HaUrl.Invalid)
        assertTrue("IP publique", HaUrl.parse("http://8.8.8.8:8123") is HaUrl.Invalid)
        assertTrue("faux local", HaUrl.parse("http://192.168.1.10.evil.example") is HaUrl.Invalid)
        assertTrue("identifiants", HaUrl.parse("https://user:pass@ha.example") is HaUrl.Invalid)
        assertTrue("chemin", HaUrl.parse("https://ha.example/api") is HaUrl.Invalid)
        assertTrue(HaUrl.parse("") is HaUrl.Invalid)
    }

    @Test fun adressesLocales() {
        assertTrue(HaUrl.isLocal("172.16.0.1"))
        assertFalse(HaUrl.isLocal("172.32.0.1"))
        assertFalse(HaUrl.isLocal("192.169.0.1"))
        assertFalse(HaUrl.isLocal("300.1.1.1"))
    }
}
