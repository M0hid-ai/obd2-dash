package com.mohid.obd2dash.ai

import com.mohid.obd2dash.obd.VinDecoder
import org.json.JSONObject

/**
 * Turns a VIN into the name a person would use for the car.
 *
 * [VinDecoder] gets the manufacturer and the model year out of a VIN without
 * asking anyone, and that part is arithmetic rather than opinion. What it
 * cannot get is the model: characters four to eight are assigned by each
 * manufacturer and published nowhere, so "Move" is simply not derivable
 * offline.
 *
 * A model that has read the manufacturer's own catalogues often does know that
 * mapping, so with a key present it is worth asking. Everything it returns is
 * checked against the parts of the VIN that decode locally, because a model
 * asked to name a car will name one whether or not it recognises the VIN, and a
 * confident wrong answer is worse than no answer.
 */
class VehicleIdentifier(private val client: GeminiClient) {

    private companion object {
        val SYSTEM_PROMPT = """
            You decode vehicle identification numbers. You are given one VIN and
            you return what car it belongs to.

            Rules:
            - Reply with a JSON object and nothing else, with the keys:
              make, model, modelYear, trim, confidence.
            - make is the manufacturer as an owner would say it, e.g. "Toyota".
            - model is the model name alone, without the maker, e.g. "Corolla".
              Include the generation or body code only if the VIN identifies it.
            - modelYear is a four digit integer, or null.
            - trim is the variant if the VIN determines it, otherwise null.
              Never guess a trim from a VIN that does not encode one.
            - confidence is "high", "medium" or "low". Use "high" only when you
              recognise this manufacturer's VIN scheme and the model is
              genuinely determined by these characters. Use "low" if you are
              inferring from the manufacturer prefix alone.
            - If you do not recognise the VIN, set model to null and confidence
              to "low". Do not invent a plausible model. A missing answer is
              useful; a confident wrong one is not.
            - Japanese domestic market vehicles frequently have VINs that encode
              a chassis code rather than a marketing name. Return the name the
              owner would recognise where you can.
        """.trimIndent()
    }

    data class Identity(
        val make: String?,
        val model: String?,
        val modelYear: Int?,
        val trim: String?,
        val confidence: String?,
    ) {
        /** "Daihatsu Move 2023", from whichever parts came back. */
        val label: String?
            get() = listOfNotNull(make, model, modelYear?.toString())
                .joinToString(" ")
                .ifBlank { null }

        val isUsable: Boolean get() = make != null || model != null
    }

    sealed interface Result {
        data class Success(val identity: Identity) : Result
        data class Failure(val message: String) : Result
    }

    suspend fun identify(apiKey: String, model: String, vin: String): Result {
        val facts = VinDecoder.decode(vin)
            ?: return Result.Failure("That VIN is not well formed, so there is nothing to look up.")

        return when (
            val reply = client.generate(
                apiKey = apiKey,
                model = model,
                systemPrompt = SYSTEM_PROMPT,
                userPrompt = "VIN: $vin",
                json = true,
            )
        ) {
            is GeminiClient.Result.Failure -> Result.Failure(reply.message)
            is GeminiClient.Result.Success -> parse(reply.text, facts)
        }
    }

    /**
     * The maker and year decode locally from parts of the VIN that are centrally
     * assigned, so those are the ground truth and the model's version of them is
     * only accepted where the local decode had nothing to say. What the model is
     * really being asked for is the model name, which is the part nothing else
     * can supply.
     */
    private fun parse(payload: String, facts: VinDecoder.Facts): Result {
        val json = runCatching { JSONObject(payload) }.getOrNull()
            ?: return Result.Failure("The model did not return usable JSON.")

        val confidence = json.optStringOrNull("confidence")?.lowercase()
        val claimedYear = json.opt("modelYear")?.let { (it as? Number)?.toInt() }
        val identity = Identity(
            make = facts.make ?: json.optStringOrNull("make"),
            // A model name offered with low confidence is a guess dressed up as
            // an answer, and it would end up on every trip title from here on.
            model = json.optStringOrNull("model")?.takeIf { confidence != "low" },
            modelYear = facts.modelYear ?: claimedYear,
            trim = json.optStringOrNull("trim")?.takeIf { confidence == "high" },
            confidence = confidence,
        )
        return if (identity.isUsable) {
            Result.Success(identity)
        } else {
            Result.Failure("The model did not recognise that VIN.")
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (isNull(key)) return null
        return optString(key).trim().takeIf { it.isNotBlank() && !it.equals("null", true) }
    }
}
