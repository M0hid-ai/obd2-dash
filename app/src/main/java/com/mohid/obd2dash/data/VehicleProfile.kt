package com.mohid.obd2dash.data

/**
 * A car this phone has already met.
 *
 * [identity] is the VIN when Mode 09 answered, otherwise a fingerprint of the
 * ECU's supported PIDs. [turbo] is the driver's answer, not something guessed
 * from manifold pressure: a turbo car driven gently never proves it, and MAP
 * still exists on plenty of NA engines.
 */
data class VehicleProfile(
    val identity: String,
    val vin: String?,
    val turbo: Boolean,
    val labeledAt: Long,
    /** Manufacturer, read off the VIN's first three characters. */
    val make: String? = null,
    /** Model year, from the VIN's tenth character. */
    val modelYear: Int? = null,
    /**
     * The half of the name a VIN cannot give you. The section that encodes the
     * model is manufacturer-specific and unpublished, so this is typed once by
     * whoever owns the car and then reused on every trip afterwards.
     */
    val model: String? = null,
) {
    /** "Daihatsu Move 2023", degrading to whatever is actually known. */
    val displayName: String
        get() = listOfNotNull(make, model, modelYear?.toString())
            .joinToString(" ")
            .ifBlank { vin ?: identity }

    /** True once there is a real name here rather than a raw VIN. */
    val isNamed: Boolean get() = make != null || model != null

    fun serialize(): String = listOf(
        identity,
        vin.orEmpty(),
        if (turbo) "1" else "0",
        labeledAt.toString(),
        make.orEmpty(),
        modelYear?.toString().orEmpty(),
        model.orEmpty(),
    ).joinToString("\u001f")

    companion object {
        fun deserialize(raw: String): VehicleProfile? {
            val parts = raw.split('\u001f')
            if (parts.size < 4) return null
            val turbo = parts[2] == "1"
            val labeledAt = parts[3].toLongOrNull() ?: return null
            // Profiles written before the name fields existed have four
            // parts, so anything past that is read only when it is there.
            return VehicleProfile(
                identity = parts[0],
                vin = parts[1].ifBlank { null },
                turbo = turbo,
                labeledAt = labeledAt,
                make = parts.getOrNull(4)?.ifBlank { null },
                modelYear = parts.getOrNull(5)?.toIntOrNull(),
                model = parts.getOrNull(6)?.ifBlank { null },
            )
        }
    }
}

data class VehiclePrompt(
    val identity: String,
    val vin: String?,
    /** What the VIN decodes to, so the dialog can name the car it found. */
    val make: String? = null,
    val modelYear: Int? = null,
) {
    val label: String?
        get() = listOfNotNull(make, modelYear?.toString()).joinToString(" ").ifBlank { null }
}
