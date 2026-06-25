package com.vnote.appilot.core.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vnote.appilot.core.model.LaunchTarget
import com.vnote.appilot.core.model.RatioRect
import com.vnote.appilot.core.model.Ratio
import com.vnote.appilot.core.model.ReadRegion
import com.vnote.appilot.core.model.RegulatorConfig
import com.vnote.appilot.core.model.TempBand
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * Process-wide [DataStore] singleton backing the regulator config.
 *
 * Design note: we use `DataStore<Preferences>` and store the whole
 * [RegulatorConfig] as a single JSON string produced by the existing
 * `kotlinx.serialization` setup. This is cleaner than a typed
 * `Serializer<RegulatorConfig>` here because the model is already
 * `@Serializable`, the "absent" state maps naturally to `null` (no need to
 * invent a non-null default for `Serializer.defaultValue`), and we avoid
 * hand-writing read/write/corruption handling.
 */
private val Context.configDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "regulator_config",
)

/**
 * Persists a single [RegulatorConfig] (the v1 single source/actuator pair).
 *
 * - [save] writes the config as JSON.
 * - [configFlow] / [load] read it back, round-tripping equal.
 * - Absent config reads back as `null`; callers may fall back to [DEFAULT].
 *
 * Digit-template PNG bytes and captured-intent URI blobs are persisted
 * byte-identical via [TemplateStore]; this store only holds the structured
 * config.
 */
class ConfigStore(
    private val context: Context,
    private val json: Json = DEFAULT_JSON,
) {
    /** Emits the persisted config, or `null` when nothing has been saved. */
    val configFlow: Flow<RegulatorConfig?> =
        context.configDataStore.data.map { prefs ->
            prefs[CONFIG_JSON_KEY]?.let { json.decodeFromString(RegulatorConfig.serializer(), it) }
        }

    /** Persists [config] as JSON, replacing any previously stored config. */
    suspend fun save(config: RegulatorConfig) {
        val encoded = json.encodeToString(RegulatorConfig.serializer(), config)
        context.configDataStore.edit { prefs ->
            prefs[CONFIG_JSON_KEY] = encoded
        }
    }

    /** Reads the current config once, or `null` when nothing has been saved. */
    suspend fun load(): RegulatorConfig? = configFlow.first()

    /** Removes the persisted config, returning subsequent reads to `null`. */
    suspend fun clear() {
        context.configDataStore.edit { prefs -> prefs.remove(CONFIG_JSON_KEY) }
    }

    companion object {
        private val CONFIG_JSON_KEY = stringPreferencesKey("config_json")

        private val DEFAULT_JSON: Json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        /**
         * A sensible empty/default config used when nothing is persisted yet:
         * an 18..26 C comfort band, 1 C step, the default 10-minute interval,
         * no launch targets wired and no tap targets, with a centered read
         * region placeholder the calibration UI later overwrites.
         */
        val DEFAULT: RegulatorConfig = RegulatorConfig(
            band = TempBand(lowC = 18.0, highC = 26.0),
            step = 1.0,
            intervalMinutes = 10,
            source = LaunchTarget.App(packageName = ""),
            actuator = LaunchTarget.App(packageName = ""),
            tapTargets = emptyList(),
            readRegion = ReadRegion(
                rect = RatioRect(
                    left = Ratio(0.4),
                    top = Ratio(0.4),
                    right = Ratio(0.6),
                    bottom = Ratio(0.6),
                ),
            ),
        )
    }
}
