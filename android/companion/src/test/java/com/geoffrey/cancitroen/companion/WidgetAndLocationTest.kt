package com.geoffrey.cancitroen.companion

import com.geoffrey.cancitroen.shared.SummaryFields as F
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetAndLocationTest {
    private val summary = JSONObject()
        .put(F.FUEL_PCT, 64).put(F.RANGE, 416).put(F.TRIP_KM, 12.34).put(F.TRIP_CONS, 6.2)
        .put(F.TRIP_ACTIVE, true).put(F.CURRENT_KM, 3.0)

    @Test fun widgetVoitureConnecteeEnTrajet() {
        val lines = WidgetText.of(summary, CarSessionService.Status(running = true, phase = SessionPlan.Phase.LINKED), 0)
        assertEquals("connectée", lines.status)
        assertEquals("64 %", lines.fuel)
        assertEquals("416 km d'autonomie", lines.range)
        assertEquals("Trajet en cours · 3,0 km", lines.trip)
    }

    @Test fun widgetHorsDeLaVoiture() {
        val lines = WidgetText.of(summary, CarSessionService.Status(), 0)
        assertEquals("", lines.status)
        // Sans liaison, le trajet « en cours » n'est plus sûr : on montre le dernier terminé.
        assertEquals("Dernier trajet 12,3 km · 6,2 L/100", lines.trip)
        assertEquals("–", WidgetText.of(null, CarSessionService.Status(), 0).fuel)
    }

    @Test fun positionPourHomeAssistant() {
        val located = JSONObject(summary.toString()).put(F.LAT, 48.8566123).put(F.LON, 2.3522219).put(F.GPS_ACCURACY, 7.6).put(F.GPS_BEARING, 181.4)
        val data = HaPayloads.location(located)!!.getJSONObject("data")
        assertEquals(48.856612, data.getJSONArray("gps").getDouble(0), 0.0)
        assertEquals(7, data.getInt("gps_accuracy"))
        assertEquals(181, data.getInt("course"))
        assertNull(HaPayloads.location(summary))
        // Sans précision connue : 10 m, exigée par Home Assistant.
        assertEquals(10, HaPayloads.location(JSONObject().put(F.LAT, 1.0).put(F.LON, 2.0))!!.getJSONObject("data").getInt("gps_accuracy"))
    }

    @Test fun evenementPourHomeAssistant() {
        val event = HaPayloads.fireEvent("cancitroen_plein", JSONObject().put("litres", 30.5))
        assertEquals("fire_event", event.getString("type"))
        assertEquals("cancitroen_plein", event.getJSONObject("data").getString("event_type"))
        assertTrue(event.getJSONObject("data").getJSONObject("event_data").has("litres"))
    }
}
