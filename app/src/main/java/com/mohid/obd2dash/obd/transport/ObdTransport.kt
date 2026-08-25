package com.mohid.obd2dash.obd.transport

import java.io.IOException

/**
 * A byte pipe to something that speaks ELM327.
 *
 * Implementations must serialise requests: the adapter is a single-threaded
 * serial device and will interleave replies if two commands are in flight.
 */
interface ObdTransport {

    /** Human-readable name for the connection screen. */
    val name: String

    val isOpen: Boolean

    suspend fun open()

    /**
     * Writes [command] and reads back everything up to the `>` prompt.
     *
     * @throws ObdTimeoutException if the prompt does not arrive within [timeoutMs].
     * @throws IOException if the link itself failed.
     */
    suspend fun request(command: String, timeoutMs: Long): String

    fun close()
}

class ObdTimeoutException(command: String, timeoutMs: Long, val partial: String) :
    IOException("No prompt for '$command' within ${timeoutMs}ms (got: ${partial.take(60)})")
