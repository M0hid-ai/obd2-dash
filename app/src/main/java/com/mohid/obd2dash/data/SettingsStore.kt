package com.mohid.obd2dash.data

import android.content.Context
import androidx.compose.ui.graphics.Color
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

/**
 * Which dial face the four main gauges wear.
 *
 * Each one is modelled on a real instrument cluster rather than being a recolour
 * of the same drawing. SHOWCASE puts a different face on each of the four dials
 * at once, which is the default until a favourite has been picked.
 */
enum class GaugeSkin(val label: String, val blurb: String) {
    SHOWCASE(
        "Compare all",
        "A different face on each of the four dials, so they can be judged side by side on real data.",
    ),
    CLASSIC(
        "Original",
        "The dial this app shipped with. Thick lit track, redline printed outside it, short blade needle.",
    ),
    HEXA(
        "Hexa",
        "Hexagonal bezel, wedge graduations and a hard edged sweep, after the Lamborghini Aventador cluster.",
    ),
    HERITAGE(
        "Heritage — Steel",
        "Metal bezel, numerals printed on a black face and a full length needle, after the Porsche 911.",
    ),
    HERITAGE_GUNMETAL(
        "Heritage — Gunmetal",
        "The same traditional dial in a dark, almost matte bezel. Understated rather than shiny.",
    ),
    HERITAGE_TITANIUM(
        "Heritage — Titanium",
        "The same traditional dial in a cooler, lighter bezel with a faint blue cast.",
    ),
    HERITAGE_CARBON(
        "Heritage — Carbon",
        "The same traditional dial with a woven carbon fibre bezel under a glossy clear coat.",
    ),
    COCKPIT(
        "Cockpit",
        "One hairline ring, a puck for a pointer and a very large number, after the Audi virtual cockpit.",
    ),
    CIRCUIT(
        "Circuit",
        "A segmented shift bar bent into an arc, with a peak hold marker, after GT-R and race car displays.",
    ),
    ;

    /**
     * The face an individual dial should draw. Only SHOWCASE varies by position;
     * every other choice applies to all four.
     */
    fun resolve(position: Int): GaugeSkin = when {
        this != SHOWCASE -> this
        else -> showcaseOrder[position % showcaseOrder.size]
    }

    private companion object {
        /**
         * Deliberately paired to the metric each face suits: the aggressive one
         * on the tachometer, the traditional dial on road speed, the calm
         * minimal one on coolant, and the segmented bar with peak hold on boost,
         * where the spike is over before you can look down at it.
         */
        val showcaseOrder = listOf(HEXA, HERITAGE, COCKPIT, CIRCUIT)
    }
}

/**
 * The colour the healthy band of a gauge lights up in. Warning and danger
 * bands never change, on purpose: this only restyles what "everything is
 * fine" looks like, so amber and red keep meaning the same thing regardless
 * of which colour is picked here.
 */
enum class GaugeAccent(val label: String, val color: Color) {
    GREEN("Green", Color(0xFF2ED573)),
    CYAN("Cyan", Color(0xFF35D0E0)),
    ICE("Ice", Color(0xFF7FD9FF)),
    VIOLET("Violet", Color(0xFFA78BFA)),
    ROSE("Rose", Color(0xFFFF6B9D)),
    EMBER("Ember", Color(0xFFFF7A45)),
    GOLD("Gold", Color(0xFFE8C468)),
    LIME("Lime", Color(0xFFA6E22E)),
}

data class AppSettings(
    val pollIntervalMs: Int = 300,
    val useFrameCountHint: Boolean = true,
    val demoMode: Boolean = false,
    val lastDeviceAddress: String? = null,
    val autoStartTripOnConnect: Boolean = false,
    val liveMode: Boolean = false,
    val alertSoundEnabled: Boolean = true,
    val pressureUnit: PressureUnit = PressureUnit.BAR,
    val gaugeSkin: GaugeSkin = GaugeSkin.SHOWCASE,
    val gaugeAccent: GaugeAccent = GaugeAccent.GREEN,
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
        val gaugeSkin = stringPreferencesKey("gaugeSkin")
        val gaugeAccent = stringPreferencesKey("gaugeAccent")
        val thresholds = stringSetPreferencesKey("thresholds")
        val vehicles = stringSetPreferencesKey("vehicles")
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
            gaugeSkin = prefs[Keys.gaugeSkin]
                ?.let { name -> GaugeSkin.entries.firstOrNull { it.name == name } }
                ?: defaults.gaugeSkin,
            gaugeAccent = prefs[Keys.gaugeAccent]
                ?.let { name -> GaugeAccent.entries.firstOrNull { it.name == name } }
                ?: defaults.gaugeAccent,
            thresholds = mergeThresholds(prefs[Keys.thresholds]),
        )
    }

    val vehicles: Flow<List<VehicleProfile>> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.vehicles].orEmpty().mapNotNull(VehicleProfile::deserialize)
            .sortedByDescending { it.labeledAt }
    }

    suspend fun rememberVehicle(profile: VehicleProfile) = edit { prefs ->
        val rest = prefs[Keys.vehicles].orEmpty()
            .mapNotNull(VehicleProfile::deserialize)
            .filter { it.identity != profile.identity }
        prefs[Keys.vehicles] = (rest + profile).map { it.serialize() }.toSet()
    }

    suspend fun forgetVehicle(identity: String) = edit { prefs ->
        val rest = prefs[Keys.vehicles].orEmpty()
            .mapNotNull(VehicleProfile::deserialize)
            .filter { it.identity != identity }
        prefs[Keys.vehicles] = rest.map { it.serialize() }.toSet()
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
    suspend fun setGaugeSkin(value: GaugeSkin) = edit { it[Keys.gaugeSkin] = value.name }
    suspend fun setGaugeAccent(value: GaugeAccent) = edit { it[Keys.gaugeAccent] = value.name }

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
