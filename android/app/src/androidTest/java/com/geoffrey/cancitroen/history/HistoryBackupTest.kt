package com.geoffrey.cancitroen.history

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HistoryBackupTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dir = File(context.cacheDir, "backup-test")
    private val dbFile = File(dir, "test.db")

    @Before
    fun create() {
        dir.deleteRecursively()
        dir.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.version = 5
            db.execSQL("CREATE TABLE t (v INTEGER)")
            db.execSQL("INSERT INTO t VALUES (42)")
        }
    }

    @After
    fun clean() {
        dir.deleteRecursively()
    }

    @Test
    fun copieLisibleAvantMigration() {
        val copy = HistoryBackup.backupIfUpgrading(dbFile, 7)
        assertNotNull(copy)
        SQLiteDatabase.openDatabase(copy!!.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(5, db.version)
            db.rawQuery("SELECT v FROM t", null).use { c ->
                c.moveToFirst()
                assertEquals(42, c.getInt(0))
            }
        }
    }

    @Test
    fun pasDeCopieSansMigration() {
        assertNull(HistoryBackup.backupIfUpgrading(dbFile, 5))
        assertNull(HistoryBackup.backupIfUpgrading(File(dir, "absente.db"), 7))
    }

    @Test
    fun neGardeQueLesDernieresCopies() {
        repeat(4) {
            val copy = HistoryBackup.backupIfUpgrading(dbFile, 7)!!
            copy.setLastModified(System.currentTimeMillis() + it * 1000L)
            Thread.sleep(5)
        }
        assertEquals(2, File(dir, HistoryBackup.DIR).listFiles { f -> f.name.endsWith(".db") }!!.size)
    }
}
