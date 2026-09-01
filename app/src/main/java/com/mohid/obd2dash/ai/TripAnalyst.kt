package com.mohid.obd2dash.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Sends a finished trip to a language model and asks what it makes of it.
 *
 * Written against `HttpURLConnection` and `org.json`, both of which are in the
 * platform, rather than pulling in an HTTP stack and a serialiser for one
 * request. The whole client is small enough that the dependency would cost more
 * than it saved.
 */
class TripAnalyst {

    private companion object {
        const val TAG = "TripAnalyst"
        const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 120_000

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
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                return@withContext Result.Failure("No API key set. Add one in Settings.")
            }
            try {
                request(apiKey, model, briefing)
            } catch (e: Exception) {
                Log.e(TAG, "Analysis failed", e)
                Result.Failure(e.message ?: "The request failed.")
            }
        }

    private fun request(apiKey: String, model: String, briefing: String): Result {
        val url = URL("$ENDPOINT/$model:generateContent")
        val body = JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))),
            )
            put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "parts",
                            JSONArray().put(
                                JSONObject().put(
                                    "text",
                                    "Here is the log from one drive. Analyse it.\n\n$briefing",
                                ),
                            ),
                        ),
                ),
            )
        }.toString()

        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            // Header rather than a query parameter, so the key stays out of
            // any URL that might be logged along the way.
            setRequestProperty("x-goog-api-key", apiKey)
        }

        try {
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            return if (code in 200..299) parse(text) else Result.Failure(describe(code, text))
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(payload: String): Result {
        val root = JSONObject(payload)
        val candidates = root.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            // A prompt blocked by a safety filter comes back 200 with no
            // candidates at all, which is not an error the transport can see.
            val reason = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            return Result.Failure(
                if (reason.isNotBlank()) "The model refused the request ($reason)."
                else "The model returned nothing.",
            )
        }
        val parts = candidates.getJSONObject(0)
            .optJSONObject("content")
            ?.optJSONArray("parts")
        val text = buildString {
            for (i in 0 until (parts?.length() ?: 0)) {
                append(parts!!.getJSONObject(i).optString("text"))
            }
        }.trim()
        return if (text.isEmpty()) Result.Failure("The model returned an empty answer.")
        else Result.Success(text)
    }

    /** Turns the API's own error body into something worth showing a person. */
    private fun describe(code: Int, payload: String): String {
        val apiMessage = runCatching {
            JSONObject(payload).optJSONObject("error")?.optString("message")
        }.getOrNull()
        return when (code) {
            400 -> apiMessage ?: "The request was rejected. Check the model name."
            401, 403 -> "That API key was refused. Check it in Settings."
            404 -> "No such model. Check the model name in Settings."
            429 -> "Rate limited by the API. Try again shortly."
            in 500..599 -> "The API is having trouble ($code). Try again shortly."
            else -> apiMessage ?: "The request failed ($code)."
        }
    }
}
