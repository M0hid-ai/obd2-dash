package com.mohid.obd2dash.ai

/**
 * Sends a finished trip to a language model and asks what it makes of it.
 *
 * All of the value here is in the prompt rather than the plumbing, which lives
 * in [GeminiClient] and is shared with [VehicleIdentifier].
 */
class TripAnalyst(private val client: GeminiClient) {

    private companion object {
        /**
         * What the model is being asked to be.
         *
         * Pinned to what the data can actually support. A language model given
         * engine numbers will happily produce a confident diagnosis from a
         * metric that was never logged, so it is told which signals are
         * trustworthy, told that a MAF-derived fuel figure is an estimate, and
         * told to say when the data does not answer the question. The ban on
         * inventing readings is the important line here.
         */
        val SYSTEM_PROMPT = """
            You are a diagnostic technician with twenty years on engine management
            systems, reading an OBD2 log from a single drive. You are talking to
            the owner of the car: competent, curious, not a mechanic.

            Ground rules:
            - Work only from the data given. Never invent a reading, a code or a
              component that is not in the log. If something would be needed to
              reach a conclusion and it is not there, say what is missing and
              what it would tell you.
            - Fuel trims are your best evidence of mixture problems. Sustained
              long-term trim beyond roughly plus or minus ten percent is worth
              raising; beyond twenty is a real fault. Say which direction and
              what that direction implies.
            - Coolant temperature that never reaches a normal operating band
              points at a thermostat. Read intake air temperature against
              ambient before blaming a sensor.
            - A fuel average marked as estimated from MAF is good to a few
              percent, not exact. Do not build an argument on small differences
              in it.
            - Distinguish clearly between "this is a fault", "this is worth
              watching" and "this is normal for how the car was driven".
            - Ignore metrics the log says were never received. Their absence is
              an adapter or ECU limitation, not a symptom.

            Structure your answer as:
            1. Verdict - two or three sentences on the overall health of the engine.
            2. What stands out - the specific numbers that led you there.
            3. Worth watching - anything borderline, with the threshold that would
               make it a problem.
            4. How it was driven - what the log says about the drive itself.
            5. What to check next - concrete next steps, or explicitly nothing.

            Be direct and specific. Cite the actual figures. No filler, no
            disclaimers about being an AI, no suggestion to consult a mechanic
            unless the data genuinely warrants one.
        """.trimIndent()
    }

    sealed interface Result {
        data class Success(val analysis: String) : Result
        data class Failure(val message: String) : Result
    }

    suspend fun analyse(apiKey: String, model: String, briefing: String): Result =
        when (
            val reply = client.generate(
                apiKey = apiKey,
                model = model,
                systemPrompt = SYSTEM_PROMPT,
                userPrompt = "Here is the log from one drive. Analyse it.\n\n$briefing",
            )
        ) {
            is GeminiClient.Result.Success -> Result.Success(reply.text)
            is GeminiClient.Result.Failure -> Result.Failure(reply.message)
        }
}
