package com.geoffrey.cancitroen.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.geoffrey.cancitroen.swc.SwcAction
import com.geoffrey.cancitroen.swc.SwcButton
import com.geoffrey.cancitroen.swc.SwcEndpoint
import com.geoffrey.cancitroen.swc.SwcMapping
import com.geoffrey.cancitroen.swc.SwcMappingEntry
import com.geoffrey.cancitroen.swc.defaultMapping
import com.geoffrey.cancitroen.ui.theme.AccentPreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Toggles d'affichage UI (formats des pills, sources, etc.). */
data class DisplaySettings(
    val fuelDisplayLiters: Boolean = false,
    val oilDisplayLevel: Boolean = false,
    val speedShowGps: Boolean = false,
    val rangeShowConsumption: Boolean = false,
)

/** Comportement service / launcher. */
data class BehaviorSettings(
    /** Déclare l'app comme home + ouvre le picker Android (voie "douce", sans root). */
    val launchAsHome: Boolean = false,
    /**
     * Enforcement launcher via ROOT. Active [com.geoffrey.cancitroen.system.LauncherGuard] :
     *  - `cmd package set-home-activity` (nous fixe home par défaut, persistant)
     *  - `persist.lsec.launcher` (launcher natif du ROM FYT)
     *  - service root détaché qui nous re-foreground quand l'activité HOME du
     *    launcher d'origine repasse devant (et elle seule), et relance l'app
     *    si elle est tuée.
     */
    val forceHomeRoot: Boolean = false,
    /**
     * Mode agressif : en plus du service root, `pm disable` l'activité HOME
     * des launchers concurrents (pas le package ; réversible). Opt-in : le ROM
     * peut réagir mal à la disparition de son launcher.
     */
    val aggressiveDisableLaunchers: Boolean = false,
)

/** Outils dev. */
data class DevSettings(
    val simulatorEnabled: Boolean = false,
)

/** Carburant : prix, capacité du réservoir. */
data class FuelSettings(
    /** Prix au litre en € (modifiable, peut être historisé par plein si besoin). */
    val pricePerLiter: Double = 1.85,
    /** Capacité réservoir (C2 = 41 L). Sert au calcul "litres ajoutés" sur un plein. */
    val tankCapacityLiters: Float = 41f,
)

/** Calibration des capteurs (ajustement à la lecture). */
data class CalibrationSettings(
    /** Décalage en °C ajouté à la T° extérieure CAN (compense les offsets véhicule). */
    val tExtOffsetC: Int = 0,
)

/** Thème visuel — couleur principale de l'app (accent). Pensé pour matcher
 *  l'ambiance lumineuse de l'habitacle voiture. */
data class ThemeSettings(
    val accent: AccentPreset = AccentPreset.CYAN,
)

/** Simulateur de bruit moteur (sortie audio synthétisée). */
data class EngineSoundSettings(
    val enabled: Boolean = false,
    val profileId: String = "FOUR_CYL_SPORT",
    val volume: Float = 0.6f,
    /** Si non-null, mode "meme" : un son court fired à chaque cycle de
     *  combustion (id du fichier dans assets/memes/engine/, sans extension). */
    val memeEngineId: String? = null,
)

data class AppSettings(
    val display: DisplaySettings = DisplaySettings(),
    val behavior: BehaviorSettings = BehaviorSettings(),
    val dev: DevSettings = DevSettings(),
    val fuel: FuelSettings = FuelSettings(),
    val calibration: CalibrationSettings = CalibrationSettings(),
    val engineSound: EngineSoundSettings = EngineSoundSettings(),
    val theme: ThemeSettings = ThemeSettings(),
    /** slot index → packageName, override la liste par défaut des shortcuts */
    val shortcutOverrides: Map<Int, String> = emptyMap(),
    /** Bouton volant → (action, endpoint) ; remplaçable par l'utilisateur. */
    val swcMapping: SwcMapping = SwcMapping(),
)

private val Context.dataStore by preferencesDataStore("settings")

class AppSettingsRepository(private val context: Context) {

    private object Keys {
        // DisplaySettings
        val FUEL_LITERS = booleanPreferencesKey("fuel_display_liters")
        val OIL_LEVEL = booleanPreferencesKey("oil_display_level")
        val SPEED_GPS = booleanPreferencesKey("speed_show_gps")
        val RANGE_CONS = booleanPreferencesKey("range_show_consumption")
        // BehaviorSettings
        val LAUNCH_AS_HOME = booleanPreferencesKey("launch_as_home")
        val FORCE_HOME_ROOT = booleanPreferencesKey("force_home_root")
        val AGGRESSIVE_DISABLE_LAUNCHERS = booleanPreferencesKey("aggressive_disable_launchers")
        // DevSettings
        val SIMULATOR = booleanPreferencesKey("simulator_enabled")
        // FuelSettings
        val FUEL_PRICE = doublePreferencesKey("fuel_price_per_liter")
        val TANK_CAPACITY = floatPreferencesKey("tank_capacity_liters")
        // EngineSoundSettings
        val ENGINE_SOUND_ENABLED = booleanPreferencesKey("engine_sound_enabled")
        val ENGINE_SOUND_PROFILE = stringPreferencesKey("engine_sound_profile")
        val ENGINE_SOUND_VOLUME = floatPreferencesKey("engine_sound_volume")
        val ENGINE_MEME_ID = stringPreferencesKey("engine_meme_id")
        val TEXT_OFFSET = androidx.datastore.preferences.core.intPreferencesKey("t_ext_offset_c")
        // ThemeSettings
        val THEME_ACCENT = stringPreferencesKey("theme_accent")
        // Misc
        val SHORTCUT_OVERRIDES = stringSetPreferencesKey("shortcut_overrides")
        // SwcMapping : un set d'entrées "BUTTON:ACTION:ENDPOINT"
        // v3 : ajout endpoints COMBO_UNIVERSAL / AUDIO_ADJUST_SUGGESTED / MEDIA_SESSION_VOLUME ;
        //      défauts changés vers COMBO_UNIVERSAL pour fiabilité AA
        val SWC_MAPPING = stringSetPreferencesKey("swc_mapping_v3")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            display = DisplaySettings(
                fuelDisplayLiters = prefs[Keys.FUEL_LITERS] ?: false,
                oilDisplayLevel = prefs[Keys.OIL_LEVEL] ?: false,
                speedShowGps = prefs[Keys.SPEED_GPS] ?: false,
                rangeShowConsumption = prefs[Keys.RANGE_CONS] ?: false,
            ),
            behavior = BehaviorSettings(
                launchAsHome = prefs[Keys.LAUNCH_AS_HOME] ?: false,
                forceHomeRoot = prefs[Keys.FORCE_HOME_ROOT] ?: false,
                aggressiveDisableLaunchers = prefs[Keys.AGGRESSIVE_DISABLE_LAUNCHERS] ?: false,
            ),
            dev = DevSettings(
                simulatorEnabled = prefs[Keys.SIMULATOR] ?: false,
            ),
            fuel = FuelSettings(
                pricePerLiter = prefs[Keys.FUEL_PRICE] ?: 1.85,
                tankCapacityLiters = prefs[Keys.TANK_CAPACITY] ?: 41f,
            ),
            calibration = CalibrationSettings(
                tExtOffsetC = prefs[Keys.TEXT_OFFSET] ?: 0,
            ),
            engineSound = EngineSoundSettings(
                enabled = prefs[Keys.ENGINE_SOUND_ENABLED] ?: false,
                profileId = prefs[Keys.ENGINE_SOUND_PROFILE] ?: "FOUR_CYL_SPORT",
                volume = prefs[Keys.ENGINE_SOUND_VOLUME] ?: 0.6f,
                memeEngineId = prefs[Keys.ENGINE_MEME_ID]?.takeIf { it.isNotBlank() },
            ),
            theme = ThemeSettings(
                accent = runCatching {
                    AccentPreset.valueOf(prefs[Keys.THEME_ACCENT] ?: AccentPreset.CYAN.name)
                }.getOrDefault(AccentPreset.CYAN),
            ),
            shortcutOverrides = (prefs[Keys.SHORTCUT_OVERRIDES] ?: emptySet())
                .mapNotNull { entry ->
                    val parts = entry.split(":", limit = 2)
                    if (parts.size == 2) parts[0].toIntOrNull()?.let { it to parts[1] } else null
                }
                .toMap(),
            swcMapping = parseSwcMapping(prefs[Keys.SWC_MAPPING]),
        )
    }

    private fun parseSwcMapping(stored: Set<String>?): SwcMapping {
        if (stored.isNullOrEmpty()) return SwcMapping()
        val parsed = stored.mapNotNull { entry ->
            val parts = entry.split(":", limit = 3)
            if (parts.size != 3) return@mapNotNull null
            val btn = runCatching { SwcButton.valueOf(parts[0]) }.getOrNull() ?: return@mapNotNull null
            val act = runCatching { SwcAction.valueOf(parts[1]) }.getOrNull() ?: return@mapNotNull null
            val end = runCatching { SwcEndpoint.valueOf(parts[2]) }.getOrNull() ?: return@mapNotNull null
            btn to SwcMappingEntry(act, end)
        }.toMap()
        // Combine avec les défauts pour les boutons non sauvegardés
        return SwcMapping(defaultMapping() + parsed)
    }

    suspend fun setLaunchAsHome(value: Boolean) {
        context.dataStore.edit { it[Keys.LAUNCH_AS_HOME] = value }
    }
    suspend fun setForceHomeRoot(value: Boolean) {
        context.dataStore.edit { it[Keys.FORCE_HOME_ROOT] = value }
    }
    suspend fun setAggressiveDisableLaunchers(value: Boolean) {
        context.dataStore.edit { it[Keys.AGGRESSIVE_DISABLE_LAUNCHERS] = value }
    }
    suspend fun setSimulatorEnabled(value: Boolean) {
        context.dataStore.edit { it[Keys.SIMULATOR] = value }
    }
    suspend fun setFuelDisplayLiters(value: Boolean) {
        context.dataStore.edit { it[Keys.FUEL_LITERS] = value }
    }
    suspend fun setOilDisplayLevel(value: Boolean) {
        context.dataStore.edit { it[Keys.OIL_LEVEL] = value }
    }
    suspend fun setSpeedShowGps(value: Boolean) {
        context.dataStore.edit { it[Keys.SPEED_GPS] = value }
    }
    suspend fun setRangeShowConsumption(value: Boolean) {
        context.dataStore.edit { it[Keys.RANGE_CONS] = value }
    }
    suspend fun setFuelPrice(value: Double) {
        context.dataStore.edit { it[Keys.FUEL_PRICE] = value }
    }
    suspend fun setTankCapacity(value: Float) {
        context.dataStore.edit { it[Keys.TANK_CAPACITY] = value }
    }
    suspend fun setEngineSoundEnabled(value: Boolean) {
        context.dataStore.edit { it[Keys.ENGINE_SOUND_ENABLED] = value }
    }
    suspend fun setEngineSoundProfile(value: String) {
        context.dataStore.edit { it[Keys.ENGINE_SOUND_PROFILE] = value }
    }
    suspend fun setEngineSoundVolume(value: Float) {
        context.dataStore.edit { it[Keys.ENGINE_SOUND_VOLUME] = value }
    }
    suspend fun setTExtOffset(value: Int) {
        context.dataStore.edit { it[Keys.TEXT_OFFSET] = value }
    }
    suspend fun setEngineMemeId(value: String?) {
        context.dataStore.edit { prefs ->
            if (value == null) prefs.remove(Keys.ENGINE_MEME_ID)
            else prefs[Keys.ENGINE_MEME_ID] = value
        }
    }
    suspend fun setSwcMapping(button: SwcButton, action: SwcAction, endpoint: SwcEndpoint) {
        context.dataStore.edit { prefs ->
            val current = (prefs[Keys.SWC_MAPPING] ?: emptySet())
                .filterNot { it.startsWith("${button.name}:") }
                .toMutableSet()
            current += "${button.name}:${action.name}:${endpoint.name}"
            prefs[Keys.SWC_MAPPING] = current
        }
    }

    suspend fun resetSwcMapping() {
        context.dataStore.edit { prefs -> prefs.remove(Keys.SWC_MAPPING) }
    }

    suspend fun setAccentPreset(preset: AccentPreset) {
        context.dataStore.edit { it[Keys.THEME_ACCENT] = preset.name }
    }

    suspend fun setShortcutOverride(slot: Int, packageName: String?) {
        context.dataStore.edit { prefs ->
            val current = (prefs[Keys.SHORTCUT_OVERRIDES] ?: emptySet())
                .filterNot { it.startsWith("$slot:") }
                .toMutableSet()
            if (packageName != null) current += "$slot:$packageName"
            prefs[Keys.SHORTCUT_OVERRIDES] = current
        }
    }
}
