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
 * One request to Gemini, shared by everything in the app that asks it something.
 *
 * Written against `HttpURLConnection` and `org.json`, both of which are in the
 * platform, rather than pulling in an HTTP stack and a serialiser for two
 * request shapes. The whole client is small enough that the dependency would
 * cost more than it saved.
 */
open class GeminiClient {

    private companion object {
        const val TAG = "GeminiClient"
        const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 120_000
    }

    sealed interface Result {
        data class Success(val text: String) : Result
        data class Failure(val message: String) : Result
    }

    /**
     * @param json asks the model to reply with a JSON object rather than prose.
     *   Enforced by the API rather than by the prompt, which is the difference
     *   between usually parseable and always parseable.
     */
    open suspend fun generate(
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        json: Boolean = false,
    ): Result = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.Failure("No API key set. Add one in Settings.")
        }
        try {
            request(apiKey, model, systemPrompt, userPrompt, json)
        } catch (e: Exception) {
            Log.e(TAG, "Request failed", e)
            Result.Failure(e.message ?: "The request failed.")
        }
    }

    private fun request(
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        json: Boolean,
    ): Result {
        val url = URL("$ENDPOINT/$model:generateContent")
        val body = JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))),
            )
            put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", userPrompt))),
                ),
            )
            if (json) {
                put(
                    "generationConfig",
                    JSONObject().put("responseMimeType", "application/json"),
                )
            }
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
        }.getOrNull()?.takeIf { it.isNotBlank() }
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
