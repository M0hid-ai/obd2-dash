package com.mohid.obd2dash.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mohid.obd2dash.alerts.DefaultThresholds
import com.mohid.obd2dash.alerts.ThresholdRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("obd2dash_settings")

enum class PressureUnit(val label: String, val suffix: String) {
    KPA("kPa", "kPa"),
    BAR("bar", "bar"),
    PSI("psi", "psi"),
    ;

    fun from(kpa: Float): Float = when (this) {
        KPA -> kpa
        BAR -> kpa / 100f
        PSI -> kpa * 0.1450377f
    }

    /** Boost swings either side of zero, so bar and psi want a decimal place. */
    val decimals: Int get() = if (this == KPA) 0 else 2
}

data class AppSettings(
    val pollIntervalMs: Int = 300,
    val useFrameCountHint: Boolean = true,
    val demoMode: Boolean = false,
    val lastDeviceAddress: String? = null,
    val autoStartTripOnConnect: Boolean = true,
    val liveMode: Boolean = false,
    val alertSoundEnabled: Boolean = true,
    val pressureUnit: PressureUnit = PressureUnit.BAR,
    val thresholds: List<ThresholdRule> = DefaultThresholds.rules,
) {
    fun thresholdFor(metricKey: String): ThresholdRule? = thresholds.firstOrNull { it.metricKey == metricKey }
}

class SettingsStore(private val context: Context) {

    private object Keys {
        val pollIntervalMs = intPreferencesKey("pollIntervalMs")
        val frameCountHint = booleanPreferencesKey("frameCountHint")
        val demoMode = booleanPreferencesKey("demoMode")
        val lastDeviceAddress = stringPreferencesKey("lastDeviceAddress")
        val autoStartTrip = booleanPreferencesKey("autoStartTrip")
        val liveMode = booleanPreferencesKey("liveMode")
        val alertSound = booleanPreferencesKey("alertSound")
        val pressureUnit = stringPreferencesKey("pressureUnit")
        val thresholds = stringSetPreferencesKey("thresholds")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        val defaults = AppSettings()
        AppSettings(
            pollIntervalMs = prefs[Keys.pollIntervalMs] ?: defaults.pollIntervalMs,
            useFrameCountHint = prefs[Keys.frameCountHint] ?: defaults.useFrameCountHint,
            demoMode = prefs[Keys.demoMode] ?: defaults.demoMode,
            lastDeviceAddress = prefs[Keys.lastDeviceAddress],
            autoStartTripOnConnect = prefs[Keys.autoStartTrip] ?: defaults.autoStartTripOnConnect,
            liveMode = prefs[Keys.liveMode] ?: defaults.liveMode,
            alertSoundEnabled = prefs[Keys.alertSound] ?: defaults.alertSoundEnabled,
            pressureUnit = prefs[Keys.pressureUnit]
                ?.let { name -> PressureUnit.entries.firstOrNull { it.name == name } }
                ?: defaults.pressureUnit,
            thresholds = mergeThresholds(prefs[Keys.thresholds]),
        )
    }

    /**
     * Stored rules win, but any metric the user has never touched falls back to
     * the shipped default, so adding a new default rule in an update reaches
     * existing installs instead of being masked by an old saved set.
     */
    private fun mergeThresholds(stored: Set<String>?): List<ThresholdRule> {
        if (stored.isNullOrEmpty()) return DefaultThresholds.rules
        val overrides = stored.mapNotNull(ThresholdRule::deserialize).associateBy { it.metricKey }
        val merged = DefaultThresholds.rules.map { overrides[it.metricKey] ?: it }
        val extras = overrides.values.filter { rule -> DefaultThresholds.rules.none { it.metricKey == rule.metricKey } }
        return merged + extras
    }

    suspend fun setPollIntervalMs(value: Int) = edit { it[Keys.pollIntervalMs] = value.coerceIn(100, 2000) }
    suspend fun setFrameCountHint(value: Boolean) = edit { it[Keys.frameCountHint] = value }
    suspend fun setDemoMode(value: Boolean) = edit { it[Keys.demoMode] = value }
    suspend fun setAutoStartTrip(value: Boolean) = edit { it[Keys.autoStartTrip] = value }
    suspend fun setLiveMode(value: Boolean) = edit { it[Keys.liveMode] = value }
    suspend fun setAlertSound(value: Boolean) = edit { it[Keys.alertSound] = value }
    suspend fun setPressureUnit(value: PressureUnit) = edit { it[Keys.pressureUnit] = value.name }

    suspend fun setLastDeviceAddress(address: String?) = edit { prefs ->
        if (address == null) prefs.remove(Keys.lastDeviceAddress) else prefs[Keys.lastDeviceAddress] = address
    }

    suspend fun saveThreshold(rule: ThresholdRule) = edit { prefs ->
        val existing = prefs[Keys.thresholds].orEmpty()
            .mapNotNull(ThresholdRule::deserialize)
            .filter { it.metricKey != rule.metricKey }
        prefs[Keys.thresholds] = (existing + rule).map { it.serialize() }.toSet()
    }

    suspend fun resetThresholds() = edit { it.remove(Keys.thresholds) }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }
}
