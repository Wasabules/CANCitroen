package com.geoffrey.cancitroen.companion

import androidx.car.app.versioning.CarAppApiLevels
import androidx.car.app.HandshakeInfo
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.PaneTemplate
import androidx.car.app.testing.TestCarContext
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Les écrans Android Auto construits comme l'hôte les demande : les constructeurs de modèles
 * refusent ce qu'Android Auto refuserait (lignes, actions, images). Avec les fichiers déjà
 * reçus sur l'appareil s'il y en a, sinon vides.
 */
@RunWith(AndroidJUnit4::class)
class CarScreensTest {
    private lateinit var car: TestCarContext

    /**
     * Le contexte voiture et les écrans vivent sur le thread principal, comme dans Android Auto.
     * Chaque test passe deux fois : hôte le plus ancien (API 1, sans image) et le plus récent.
     */
    private fun onMain(block: () -> Unit) {
        for (level in listOf(CarAppApiLevels.LEVEL_1, CarAppApiLevels.getLatest())) {
            var failure: Throwable? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                try {
                    car = TestCarContext.createCarContext(ApplicationProvider.getApplicationContext())
                    car.updateHandshakeInfo(HandshakeInfo("com.google.android.projection.gearhead", level))
                    block()
                } catch (e: Throwable) {
                    failure = e
                }
            }
            failure?.let { throw AssertionError("hôte API $level", it) }
        }
    }

    @Test fun accueilAvantEtApresChargement() = onMain {
        val screen = VehicleCarScreen(car)
        assertTrue((screen.onGetTemplate() as PaneTemplate).pane.isLoading || VehicleHistory.summary(car) != null)
        screen.load()
        val pane = (screen.onGetTemplate() as PaneTemplate).pane
        assertFalse(pane.isLoading)
        assertTrue(pane.rows.size in 1..RefreshingScreen.PANE_ROWS)
        assertEquals(2, pane.actions.size)
        if (car.carAppApiLevel >= 4) assertNotNull(pane.image) else assertEquals(null, pane.image)
    }

    @Test fun trajetsPuisDetail() = onMain {
        val list = TripsCarScreen(car)
        list.load()
        val items = (list.onGetTemplate() as ListTemplate).singleList!!.items
        assertTrue(items.size <= RefreshingScreen.LIST_ROWS)
        val now = System.currentTimeMillis()
        val trip = VehicleHistory.load(car, now - 30L * 86_400_000L, now).trips.lastOrNull()
            ?: TripRow(now - 600_000, now, 5.2, 31.0, 70.0, 6.4, 60, 58)
        val detail = TripCarScreen(car, trip)
        detail.load()
        val pane = (detail.onGetTemplate() as PaneTemplate).pane
        assertEquals(RefreshingScreen.PANE_ROWS, pane.rows.size)
    }

    @Test fun suivi() = onMain {
        val pane = (StatusCarScreen(car).onGetTemplate() as PaneTemplate).pane
        assertEquals(4, pane.rows.size)
        assertEquals(2, pane.actions.size)
    }

    @Test fun chaqueGraphiqueSeDessine() = onMain {
        val now = System.currentTimeMillis()
        val view = VehicleHistory.load(car, now - 86_400_000L, now)
        for (kind in ChartKind.entries) {
            val bitmap = ChartPainter.bitmap(kind, view.series, view.from, view.to, 720, 440, 2f)
            assertEquals(720, bitmap.width)
        }
        // Sans aucune donnée : un message, pas d'exception.
        ChartPainter.bitmap(ChartKind.TEMPERATURES, emptyMap(), now - 1000, now, 360, 220, 1f)
    }

    /** Le widget tel que le lanceur l'affiche : sa mise en page doit s'appliquer sans erreur. */
    @Test fun widgetSApplique() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        var failure: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                val view = CarWidget.render(context).apply(context, android.widget.FrameLayout(context))
                val fuel = view.findViewById<android.widget.TextView>(R.id.widget_carburant).text.toString()
                assertTrue(fuel, fuel == "–" || fuel.endsWith("%"))
            } catch (e: Throwable) {
                failure = e
            }
        }
        failure?.let { throw it }
    }
}
