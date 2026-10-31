package com.geoffrey.cancitroen.history

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.File

/**
 * Copie la base avant une migration : les tickets de plein ne se retrouvent
 * pas ailleurs, et une migration ratée efface tout (filet destructif).
 * Copies dans `databases/backup/`, restauration : voir docs/app/developpement.md.
 */
internal object HistoryBackup {
    const val DIR = "backup"
    private const val TAG = "HistoryBackup"
    private const val KEPT = 2

    /** Renvoie la copie créée, null si aucune migration n'est à venir. */
    fun backupIfUpgrading(dbFile: File, targetVersion: Int): File? {
        if (!dbFile.exists()) return null
        // Lecture-écriture : la fermeture intègre le WAL, la copie tient en un fichier.
        val version = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
            .use { it.version }
        if (version == targetVersion || version == 0) return null

        val dir = File(dbFile.parentFile, DIR).apply { mkdirs() }
        val copy = File(dir, "history-v$version-${System.currentTimeMillis()}.db")
        dbFile.copyTo(copy)
        File(dbFile.path + "-wal").takeIf { it.exists() }?.copyTo(File(copy.path + "-wal"))
        dir.listFiles { f -> f.name.endsWith(".db") }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .drop(KEPT)
            .forEach { old ->
                old.delete()
                File(old.path + "-wal").delete()
            }
        Log.i(TAG, "Base v$version copiée avant migration vers v$targetVersion : ${copy.name}")
        return copy
    }
}

/**
 * Passe par l'ouverture de la base, seul point qui précède la migration et
 * s'exécute hors du thread principal (pas de ralentissement au démarrage).
 */
internal class BackupBeforeMigrationFactory(
    private val context: Context,
    private val delegate: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory(),
) : SupportSQLiteOpenHelper.Factory {

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val helper = delegate.create(configuration)
        return object : SupportSQLiteOpenHelper by helper {
            private var checked = false

            override val writableDatabase: SupportSQLiteDatabase
                get() {
                    backupOnce()
                    return helper.writableDatabase
                }

            override val readableDatabase: SupportSQLiteDatabase
                get() {
                    backupOnce()
                    return helper.readableDatabase
                }

            @Synchronized
            private fun backupOnce() {
                if (checked) return
                checked = true
                val name = configuration.name ?: return
                // Une copie impossible (disque plein) ne doit pas empêcher d'ouvrir la base.
                runCatching { HistoryBackup.backupIfUpgrading(context.getDatabasePath(name), configuration.callback.version) }
                    .onFailure { Log.e("HistoryBackup", "Copie avant migration impossible", it) }
            }
        }
    }
}
