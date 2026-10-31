package com.geoffrey.cancitroen.history

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Une migration ratée efface l'historique de la voiture : chaque palier est vérifié. */
@RunWith(AndroidJUnit4::class)
class HistoryMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        HistoryDatabase::class.java,
    )

    @Test
    fun v5VersDerniereVersionGardeLesDonnees() {
        helper.createDatabase(DB, 5).use { db ->
            db.execSQL("INSERT INTO refuel_event (ts, fuelPctBefore, fuelPctAfter, litersAdded) VALUES (1000, 30, 90, 24.5)")
            db.execSQL("INSERT INTO trip (startTs, endTs, startOdoKm, endOdoKm) VALUES (1000, 2000, 10.0, 25.0)")
        }

        helper.runMigrationsAndValidate(DB, 7, true).use { db ->
            db.query("SELECT fuelPctAfter, litersAdded, awaitingInput, userEntered, manual FROM refuel_event").use { c ->
                assertEquals(1, c.count)
                c.moveToFirst()
                assertEquals(90, c.getInt(0))
                assertEquals(24.5f, c.getFloat(1))
                // Un ancien plein n'est pas redemandé.
                assertEquals(0, c.getInt(2))
                assertEquals(0, c.getInt(3))
                assertEquals(0, c.getInt(4))
            }
            db.query("SELECT COUNT(*) FROM trip").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
        }
    }

    @Test
    fun v6VersV7GardeLesTickets() {
        helper.createDatabase(DB, 6).use { db ->
            db.execSQL("INSERT INTO refuel_event (ts, fuelPctBefore, fuelPctAfter, litersAdded, totalCost, userEntered) VALUES (1000, 30, 90, 24.5, 45.0, 1)")
        }
        helper.runMigrationsAndValidate(DB, 7, true).use { db ->
            db.query("SELECT totalCost, userEntered, manual FROM refuel_event").use { c ->
                c.moveToFirst()
                assertEquals(45.0, c.getDouble(0), 0.001)
                assertEquals(1, c.getInt(1))
                assertEquals(0, c.getInt(2))
            }
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
