package com.mohid.obd2dash.obd

/**
 * Instant and trip fuel use, from whatever the ECU actually publishes.
 *
 * There are two completely different qualities of answer here and the report
 * says which one it used, because the difference matters.
 *
 * SAE J1979 PID `015E` is litres per hour computed by the ECU itself, from the
 * injector pulse widths it is commanding. That is the real number and nothing
 * here improves on it.
 *
 * Plenty of cars never answer `015E`. Those almost always answer MAF (`0110`),
 * and air mass over an air/fuel ratio is fuel mass. The naive version of that
 * assumes the engine always runs at stoichiometric, which is wrong in the two
 * places it costs the most:
 *
 *  - **Enrichment.** Under load, at wide open throttle and during warm-up the
 *    ECU deliberately runs rich, down to roughly 12:1. Assuming 14.7 there
 *    under-reads fuel by up to a fifth exactly when the most is being burned.
 *  - **Overrun.** Lift off above idle and the injectors shut off completely,
 *    but air keeps pumping through the MAF. Assuming that air still has fuel
 *    mixed into it invents consumption during every deceleration, which in
 *    city driving is a large fraction of the drive.
 *
 * Both are correctable from PIDs the same ECU already offers, so both are
 * corrected here. What remains is still an estimate: injector dead time,
 * fuel density against temperature and MAF sensor drift are all unmodelled,
 * and a MAF-derived trip average should be read as being good to a few percent
 * rather than to the millilitre.
 */
object FuelEconomy {

    const val PETROL_AFR = 14.7f
    const val DIESEL_AFR = 14.5f

    /** Typical petrol density at ambient, grams per litre. */
    const val PETROL_GRAMS_PER_LITRE = 737f
    const val DIESEL_GRAMS_PER_LITRE = 832f

    /** Below this the L/100 km figure is just noise from idle and GPS jitter. */
    const val MIN_SPEED_KPH = 5f

    /** A trip shorter than this does not get an average written on the report. */
    const val MIN_DISTANCE_METERS = 200.0
    const val MIN_LITRES = 0.02

    /**
     * Overrun needs the engine turning well above idle: the injectors only cut
     * when the wheels are driving the engine rather than the other way round.
     */
    const val FUEL_CUT_MIN_RPM = 1_200f

    /** Throttle this far above its closed resting position still counts as shut. */
    const val CLOSED_THROTTLE_MARGIN = 1.5f

    /** Any real demand for torque rules overrun out, whatever the throttle says. */
    const val FUEL_CUT_MAX_LOAD = 20f

    /**
     * Equivalence ratio outside this is a decode error rather than a mixture.
     * Even a cold start does not command richer than about 0.65.
     */
    private val PLAUSIBLE_LAMBDA = 0.55f..1.6f

    /** A trim beyond this is a fault, not a correction worth applying. */
    private const val MAX_TRIM_PCT = 40f

    /**
     * The air/fuel ratio the engine is actually running, not the textbook one.
     *
     * Commanded equivalence ratio (`0144`) is the direct answer and is used
     * whenever the ECU publishes it: lambda is by definition actual AFR over
     * stoichiometric, so multiplying gives what is being commanded right now,
     * enrichment included.
     *
     * Without it, fuel trims are the next best thing. They only describe
     * closed-loop corrections around stoichiometric and say nothing about
     * deliberate enrichment, so this is a smaller correction than lambda, not
     * a substitute for it.
     */
    fun effectiveAfr(
        diesel: Boolean = false,
        lambda: Float? = null,
        shortTrimPct: Float? = null,
        longTrimPct: Float? = null,
    ): Float {
        val base = if (diesel) DIESEL_AFR else PETROL_AFR
        if (lambda != null && lambda in PLAUSIBLE_LAMBDA) return base * lambda

        val trim = (shortTrimPct ?: 0f) + (longTrimPct ?: 0f)
        if (shortTrimPct == null && longTrimPct == null) return base
        if (trim !in -MAX_TRIM_PCT..MAX_TRIM_PCT) return base
        // A positive trim means the ECU is adding fuel, so the mixture is
        // richer than stoichiometric and the effective ratio drops.
        return base / (1f + trim / 100f)
    }

    /**
     * True when the engine is being driven by the wheels with the injectors
     * shut off. All three conditions are required: a closed throttle at idle
     * is just idling, and a closed throttle with real load is a torque
     * converter doing its job.
     */
    fun isFuelCut(
        rpm: Float?,
        speedKph: Float?,
        throttlePct: Float?,
        closedThrottlePct: Float?,
        loadPct: Float?,
    ): Boolean {
        if (rpm == null || rpm < FUEL_CUT_MIN_RPM) return false
        if ((speedKph ?: 0f) <= 0f) return false
        if (throttlePct == null || closedThrottlePct == null) return false
        if (throttlePct > closedThrottlePct + CLOSED_THROTTLE_MARGIN) return false
        if (loadPct != null && loadPct > FUEL_CUT_MAX_LOAD) return false
        return true
    }

    fun litresPerHourFromMaf(
        mafGramsPerSec: Float,
        diesel: Boolean = false,
        lambda: Float? = null,
        shortTrimPct: Float? = null,
        longTrimPct: Float? = null,
    ): Float {
        val afr = effectiveAfr(diesel, lambda, shortTrimPct, longTrimPct)
        val density = if (diesel) DIESEL_GRAMS_PER_LITRE else PETROL_GRAMS_PER_LITRE
        if (mafGramsPerSec <= 0f || afr <= 0f || density <= 0f) return 0f
        return (mafGramsPerSec / afr / density) * 3600f
    }

    fun litresPer100Km(litresPerHour: Float, speedKph: Float): Float? {
        if (speedKph < MIN_SPEED_KPH || litresPerHour <= 0f) return null
        return litresPerHour / speedKph * 100f
    }

    fun tripLitresPer100Km(litres: Double, distanceMeters: Double): Float? {
        if (litres < MIN_LITRES || distanceMeters < MIN_DISTANCE_METERS) return null
        return (litres / (distanceMeters / 1000.0) * 100.0).toFloat()
    }

    fun kmPerLitre(litresPer100Km: Float): Float? =
        if (litresPer100Km <= 0f) null else 100f / litresPer100Km

    fun format(litresPer100Km: Float, unit: FuelUnit): String = when (unit) {
        FuelUnit.KM_PER_LITRE -> kmPerLitre(litresPer100Km)
            ?.let { "%.1f km/L".format(it) }
            ?: "—"

        FuelUnit.L_PER_100KM -> "%.1f L/100 km".format(litresPer100Km)
    }

    fun formatLPer100(value: Float): String = "%.1f L/100 km".format(value)

    fun formatLitres(value: Double): String = "%.2f L".format(value)
}

/**
 * How to show a fuel average.
 *
 * Two ways of saying the same measurement, and they run in opposite
 * directions: bigger is better in km/L, smaller is better in L/100 km.
 */
enum class FuelUnit(val label: String, val blurb: String) {
    KM_PER_LITRE("km/L", "Distance per litre. Higher is better."),
    L_PER_100KM("L/100 km", "Litres per hundred kilometres. Lower is better."),
}

enum class FuelSource(val label: String) {
    ECU_RATE("ECU fuel rate"),
    MAF_ESTIMATE("Estimated from MAF"),
}
