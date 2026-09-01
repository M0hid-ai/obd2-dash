package com.mohid.obd2dash.obd

/**
 * Instant and trip fuel use, from whatever the ECU actually publishes.
 *
 * SAE J1979 PID `015E` is litres per hour and is the honest number. Plenty of
 * cars never answer it. Those still almost always answer MAF (`0110`), and
 * mass air flow plus a stoichiometric AFR is enough to estimate volume to a
 * useful average over a whole drive — not to a lab-grade instant MPG readout.
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

    fun litresPerHourFromMaf(mafGramsPerSec: Float, diesel: Boolean = false): Float {
        val afr = if (diesel) DIESEL_AFR else PETROL_AFR
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

    fun formatLPer100(value: Float): String = "%.1f L/100 km".format(value)

    fun formatLitres(value: Double): String = "%.2f L".format(value)
}

enum class FuelSource(val label: String) {
    ECU_RATE("ECU fuel rate"),
    MAF_ESTIMATE("Estimated from MAF"),
}
