package com.mohid.obd2dash

import com.mohid.obd2dash.ai.GeminiClient
import com.mohid.obd2dash.ai.VehicleIdentifier
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleIdentifierTest {

    /** Answers with a canned payload instead of reaching the network. */
    private class FakeClient(private val reply: GeminiClient.Result) : GeminiClient() {
        var lastPrompt: String? = null
        override suspend fun generate(
            apiKey: String,
            model: String,
            systemPrompt: String,
            userPrompt: String,
            json: Boolean,
        ): Result {
            lastPrompt = userPrompt
            return reply
        }
    }

    private fun identifier(payload: String) =
        VehicleIdentifier(FakeClient(GeminiClient.Result.Success(payload)))

    // Position 7 numeric, year code 2, so the local decode says Toyota 2002.
    private val vin = "JTDBR32E720000001"

    @Test
    fun `the model supplies the name a vin cannot encode`() = runTest {
        val result = identifier(
            """{"make":"Toyota","model":"Corolla","modelYear":2002,"trim":null,"confidence":"high"}""",
        ).identify("key", "gemini-2.5-flash", vin)
        val identity = (result as VehicleIdentifier.Result.Success).identity
        assertEquals("Corolla", identity.model)
        assertEquals("Toyota Corolla 2002", identity.label)
    }

    @Test
    fun `a low confidence model name is discarded rather than shown`() = runTest {
        // It would otherwise be stamped on every trip title from here on.
        val result = identifier(
            """{"make":"Toyota","model":"Probably a Vitz","modelYear":2002,"confidence":"low"}""",
        ).identify("key", "gemini-2.5-flash", vin)
        val identity = (result as VehicleIdentifier.Result.Success).identity
        assertNull(identity.model)
        assertEquals("Toyota 2002", identity.label)
    }

    @Test
    fun `the local decode wins over the model on maker and year`() = runTest {
        val result = identifier(
            """{"make":"Honda","model":"Civic","modelYear":2015,"confidence":"high"}""",
        ).identify("key", "gemini-2.5-flash", vin)
        val identity = (result as VehicleIdentifier.Result.Success).identity
        assertEquals("Toyota", identity.make)
        assertEquals(2002, identity.modelYear)
    }

    @Test
    fun `a trim is only kept when the answer is confident`() = runTest {
        val medium = identifier(
            """{"make":"Toyota","model":"Corolla","trim":"GLi","confidence":"medium"}""",
        ).identify("key", "m", vin)
        assertNull((medium as VehicleIdentifier.Result.Success).identity.trim)

        val high = identifier(
            """{"make":"Toyota","model":"Corolla","trim":"GLi","confidence":"high"}""",
        ).identify("key", "m", vin)
        assertEquals("GLi", (high as VehicleIdentifier.Result.Success).identity.trim)
    }

    @Test
    fun `a malformed vin never reaches the network`() = runTest {
        val client = FakeClient(GeminiClient.Result.Success("{}"))
        val result = VehicleIdentifier(client).identify("key", "m", "NOTAVIN")
        assertTrue(result is VehicleIdentifier.Result.Failure)
        assertNull("no request should have been made", client.lastPrompt)
    }

    @Test
    fun `unparseable json is reported rather than thrown`() = runTest {
        val result = identifier("sorry, I am not sure").identify("key", "m", vin)
        assertTrue(result is VehicleIdentifier.Result.Failure)
    }
}
