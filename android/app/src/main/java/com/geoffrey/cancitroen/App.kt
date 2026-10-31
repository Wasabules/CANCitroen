package com.geoffrey.cancitroen

import android.app.Application
import com.geoffrey.cancitroen.audio.EngineSoundSynth
import com.geoffrey.cancitroen.audio.MemeEngineRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.geoffrey.cancitroen.history.HistoryDatabase
import com.geoffrey.cancitroen.history.HistoryRepository
import com.geoffrey.cancitroen.history.dao.HistoryDao
import com.geoffrey.cancitroen.settings.AppSettingsRepository
import com.geoffrey.cancitroen.system.GpsTracker

class App : Application() {
    lateinit var settings: AppSettingsRepository
        private set
    lateinit var gpsTracker: GpsTracker
        private set
    lateinit var historyDao: HistoryDao
        private set
    lateinit var historyRepo: HistoryRepository
        private set
    lateinit var engineSynth: EngineSoundSynth
        private set
    lateinit var memeEngineRunner: MemeEngineRunner
        private set
    /** Scope app-wide pour les players audio — ne meurt pas avec un VM/écran. */
    val audioScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = AppSettingsRepository(this)
        gpsTracker = GpsTracker(this)
        historyDao = HistoryDatabase.get(this).historyDao()
        historyRepo = HistoryRepository(historyDao)
        engineSynth = EngineSoundSynth()
        memeEngineRunner = MemeEngineRunner(this)
    }

    companion object {
        @Volatile private var instance: App? = null
        fun get(): App = instance!!
    }
}
