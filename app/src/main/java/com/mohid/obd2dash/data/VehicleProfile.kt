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
) {
    fun serialize(): String = listOf(
        identity,
        vin.orEmpty(),
        if (turbo) "1" else "0",
        labeledAt.toString(),
    ).joinToString("\u001f")

    companion object {
        fun deserialize(raw: String): VehicleProfile? {
            val parts = raw.split('\u001f')
            if (parts.size < 4) return null
            val turbo = parts[2] == "1"
            val labeledAt = parts[3].toLongOrNull() ?: return null
            return VehicleProfile(
                identity = parts[0],
                vin = parts[1].ifBlank { null },
                turbo = turbo,
                labeledAt = labeledAt,
            )
        }
    }
}

data class VehiclePrompt(
    val identity: String,
    val vin: String?,
)
