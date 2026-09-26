package com.geoffrey.cancitroen.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geoffrey.cancitroen.App
import com.geoffrey.cancitroen.audio.EngineProfiles
import com.geoffrey.cancitroen.settings.EngineSoundSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class EngineSoundViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = App.get().settings
    private val synth = App.get().engineSynth
    private val memeRunner = App.get().memeEngineRunner

    val state: StateFlow<EngineSoundSettings> = settings.flow
        .map { it.engineSound }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EngineSoundSettings())

    private val _demoRunning = MutableStateFlow(false)
    val demoRunning: StateFlow<Boolean> = _demoRunning

    private val _demoRpm = MutableStateFlow(0f)
    val demoRpm: StateFlow<Float> = _demoRpm

    private var demoJob: Job? = null

    fun setEnabled(v: Boolean) = viewModelScope.launch { settings.setEngineSoundEnabled(v) }
    fun setProfile(id: String) = viewModelScope.launch { settings.setEngineSoundProfile(id) }
    fun setVolume(v: Float) = viewModelScope.launch { settings.setEngineSoundVolume(v) }

    /** Pendant le glissé du curseur : effet audible immédiat, sans écrire DataStore. */
    fun previewVolume(v: Float) {
        synth.setVolume(v)
        memeRunner.setVolume(v)
    }
    fun setMemeEngine(id: String?) = viewModelScope.launch { settings.setEngineMemeId(id) }

    val availableMemes: List<com.geoffrey.cancitroen.audio.MemeEngine> by lazy {
        com.geoffrey.cancitroen.audio.MemeEngineCatalog.listAvailable(getApplication())
    }

    /**
     * Lance une démo de 12 s : ramp up idle → redline en 5 s, palier 1 s,
     * descente en 5 s, retour à l'idle. Override le RPM injecté dans le synth
     * via setManualRpm. Si le synth est OFF, on l'allume le temps de la démo.
     */
    fun runDemo() {
        if (_demoRunning.value) return
        demoJob = viewModelScope.launch {
            _demoRunning.value = true
            val originalEnabled = state.value.enabled
            val memeId = state.value.memeEngineId
            val isMemeMode = memeId != null
            val profile = EngineProfiles.byId(state.value.profileId)

            try {
                // Active la sortie audio si nécessaire (le service l'aiguillera
                // vers le bon runner selon memeEngineId actuel)
                if (!originalEnabled) settings.setEngineSoundEnabled(true)

                // S'assure que le bon player tourne
                // Important : on lance les players sur le scope app-wide pour
                // qu'ils survivent à la disparition de cet écran (sinon le user
                // navigue ailleurs et l'audio se coupe à mi-démo).
                val audioScope = App.get().audioScope
                if (isMemeMode) {
                    val meme = com.geoffrey.cancitroen.audio.MemeEngineCatalog
                        .listAvailable(getApplication())
                        .firstOrNull { it.id == memeId }
                    if (meme != null) {
                        memeRunner.load(meme.assetPath)
                        memeRunner.setProfile(profile.cylinders, profile.idleRpm, profile.redlineRpm)
                        memeRunner.start(audioScope)
                    }
                } else {
                    synth.ensureRunning(audioScope)
                }

                val idle = profile.idleRpm
                val redline = profile.redlineRpm
                val totalSteps = 240
                val rampUpSteps = 100
                val plateauSteps = 20
                val rampDownSteps = 100

                for (step in 0 until totalSteps) {
                    if (!isActive) break
                    val rpm = when {
                        step < rampUpSteps -> {
                            val p = step / rampUpSteps.toFloat()
                            idle + (redline - idle) * smooth(p)
                        }
                        step < rampUpSteps + plateauSteps -> redline
                        step < rampUpSteps + plateauSteps + rampDownSteps -> {
                            val p = (step - rampUpSteps - plateauSteps) / rampDownSteps.toFloat()
                            redline - (redline - idle) * smooth(p)
                        }
                        else -> idle
                    }
                    _demoRpm.value = rpm
                    if (isMemeMode) memeRunner.setManualRpm(rpm)
                    else synth.setManualRpm(rpm)
                    delay(50L)
                }
            } finally {
                synth.setManualRpm(null)
                memeRunner.setManualRpm(null)
                _demoRpm.value = 0f
                _demoRunning.value = false
                if (!originalEnabled) settings.setEngineSoundEnabled(false)
            }
        }
    }

    fun stopDemo() {
        demoJob?.cancel()
        demoJob = null
        synth.setManualRpm(null)
        memeRunner.setManualRpm(null)
        _demoRpm.value = 0f
        _demoRunning.value = false
    }

    /** Easing smoothstep pour rampe naturelle (pas linéaire). */
    private fun smooth(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3 - 2 * x)
    }

    override fun onCleared() {
        stopDemo()
        super.onCleared()
    }
}
