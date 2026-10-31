package com.geoffrey.cancitroen.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsMarkerTest {
    @Test fun doesNotBlinkAroundOneThreshold() {
        val marker = GpsMarker()
        assertFalse(marker.update(2.5f))
        assertTrue(marker.update(3f))
        assertTrue(marker.update(1.5f))
        assertTrue(marker.update(2.5f))
        assertFalse(marker.update(0.5f))
        assertFalse(marker.update(2.5f))
    }

    @Test fun keepsPositionWithoutFix() {
        val marker = GpsMarker()
        assertTrue(marker.update(90f))
        assertFalse(marker.update(null))
        assertEquals(90f, marker.position)
        assertTrue(marker.update(88f))
        assertEquals(88f, marker.position)
    }

    @Test fun hiddenUntilFirstFix() {
        val marker = GpsMarker()
        assertFalse(marker.update(null))
        assertEquals(0f, marker.position)
    }
}
