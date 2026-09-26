package com.geoffrey.cancitroen.history

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.geoffrey.cancitroen.history.dao.HistoryDao
import com.geoffrey.cancitroen.history.entities.RefuelEvent
import com.geoffrey.cancitroen.history.entities.Trip
import com.geoffrey.cancitroen.history.entities.VehicleSample

@Database(
    entities = [
        VehicleSample::class,
        RefuelEvent::class,
        Trip::class,
        com.geoffrey.cancitroen.history.entities.AggregatedSample::class,
    ],
    version = 5,
    // Schéma exporté dans app/schemas/ : tout bump de `version` doit
    // s'accompagner d'un `autoMigrations = [AutoMigration(from = N, to = N+1)]`
    // (ou d'une Migration manuelle), sinon l'historique est effacé.
    exportSchema = true,
)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

    companion object {
        private const val TAG = "HistoryDatabase"
        @Volatile private var instance: HistoryDatabase? = null

        fun get(context: Context): HistoryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                HistoryDatabase::class.java,
                "history.db",
            )
                // Dernier recours seulement, si une migration manque : l'app est le
                // launcher de la voiture, elle ne doit jamais crasher en boucle au
                // démarrage. Les vraies évolutions passent par des migrations.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .addCallback(object : Callback() {
                    override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                        Log.w(TAG, "⚠ Migration destructive : tout l'historique a été effacé suite au bump de version Room")
                    }
                })
                .build()
                .also { instance = it }
        }
    }
}
