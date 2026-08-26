package com.mohid.obd2dash.obd

import android.util.Log
import com.mohid.obd2dash.obd.transport.ObdTimeoutException
import com.mohid.obd2dash.obd.transport.ObdTransport
import kotlinx.coroutines.delay

/** What came back from asking for one PID. */
sealed interface ReadResult {
    @JvmInline
    value class Value(val value: Float) : ReadResult

    /** The ECU understood the request but has nothing for this PID. */
    data object NoData : ReadResult

    /** Adapter- or bus-level failure; [fatal] means the link needs re-initialising. */
    data class Failure(val token: String, val fatal: Boolean) : ReadResult
}

data class AdapterInfo(
    val version: String,
    val protocol: String,
)

/**
 * Drives one ELM327 conversation: bring the adapter up, find out what the ECU
 * answers to, then read values.
 *
 * Not thread-safe by itself. It relies on the transport serialising requests,
 * and on a single poll loop owning the session.
 */
class ObdSession(
    private val transport: ObdTransport,
    /**
     * Append the expected frame count to each request (`010C1`). Tells the
     * adapter to return as soon as one frame arrives instead of waiting out
     * its full timeout, which roughly doubles the achievable poll rate.
     * ELM327 v1.3 and later; harmless to turn off for a fussy clone.
     */
    private val useFrameCountHint: Boolean = true,
) {

    private companion object {
        const val TAG = "ObdSession"
        const val AT_TIMEOUT_MS = 5_000L
        const val RESET_SETTLE_MS = 900L
        const val PID_TIMEOUT_MS = 1_200L
        const val SCAN_TIMEOUT_MS = 3_000L
    }

    /**
     * Brings the adapter to a known state: echo and formatting off so replies
     * are compact, adaptive timing on, automatic protocol detection.
     */
    suspend fun initialize(onProgress: (String) -> Unit = {}): AdapterInfo {
        onProgress("Resetting adapter…")
        val version = ElmProtocol.sanitize(send("ATZ", AT_TIMEOUT_MS))
            .ifBlank { "ELM327" }
        delay(RESET_SETTLE_MS)

        onProgress("Configuring…")
        // Order matters: echo off first so later replies are not doubled up.
        send("ATE0", AT_TIMEOUT_MS)   // no command echo
        send("ATL0", AT_TIMEOUT_MS)   // no linefeeds
        send("ATS0", AT_TIMEOUT_MS)   // no spaces in hex
        send("ATH0", AT_TIMEOUT_MS)   // no CAN headers
        send("ATAT1", AT_TIMEOUT_MS)  // adaptive timing
        send("ATST32", AT_TIMEOUT_MS) // ~200ms per-request ceiling
        send("ATSP0", AT_TIMEOUT_MS)  // auto-detect protocol

        onProgress("Negotiating protocol…")
        // The first real request is what actually triggers protocol detection;
        // it can take a few seconds and reply with SEARCHING... first.
        send("0100", 10_000L)

        // ATDP answers in prose, not hex, so the usual sanitiser would strip
        // the spaces out of "AUTO, ISO 15765-4 (CAN 11/500)" and leave one
        // long unbreakable token that no label can wrap. lines() copes with
        // either line ending the adapter chooses to use.
        val protocol = send("ATDP", AT_TIMEOUT_MS)
            .lines()
            .joinToString(" ")
            .replace(">", "")
            .trim()
            .ifBlank { "unknown" }

        Log.i(TAG, "Adapter=$version protocol=$protocol")
        return AdapterInfo(version = version.take(40), protocol = protocol.take(60))
    }

    /**
     * Walks the `0100` / `0120` / `0140` … support bitmasks and returns the PID
     * numbers this ECU claims to answer.
     *
     * Support varies by ECU, so nothing is assumed. The rest of the app polls
     * only what turns up here.
     */
    suspend fun scanSupportedPids(onProgress: (Int) -> Unit = {}): Set<Int> {
        val supported = LinkedHashSet<Int>()
        for (base in SupportedPids.enquiryPids) {
            val command = "01%02X".format(base)
            val raw = try {
                send(command, SCAN_TIMEOUT_MS)
            } catch (e: ObdTimeoutException) {
                Log.w(TAG, "Support scan timed out at block $command", e)
                break
            }
            val data = ElmProtocol.extractData(raw, "41%02X".format(base), 4) ?: break
            val block = SupportedPids.decode(base, data)
            supported += block
            onProgress(supported.size)
            if (!SupportedPids.continuesPastBlock(base, block)) break
        }
        // The enquiry PIDs themselves are not readable values.
        supported.removeAll(SupportedPids.enquiryPids.toSet())
        Log.i(TAG, "ECU supports ${supported.size} PIDs")
        return supported
    }

    suspend fun read(pid: ObdPid): ReadResult {
        val command = if (useFrameCountHint) "${pid.command}1" else pid.command
        val raw = try {
            send(command, PID_TIMEOUT_MS)
        } catch (e: ObdTimeoutException) {
            return ReadResult.Failure("TIMEOUT", fatal = false)
        }

        val clean = ElmProtocol.sanitize(raw)
        ElmProtocol.errorToken(clean)?.let { token ->
            return if (token == "NODATA") {
                ReadResult.NoData
            } else {
                ReadResult.Failure(token, fatal = ElmProtocol.isFatal(token))
            }
        }

        val data = ElmProtocol.extractData(raw, pid.responseHeader, pid.dataBytes)
            ?: return ReadResult.Failure("MALFORMED", fatal = false)
        val value = pid.decode(data) ?: return ReadResult.NoData
        return ReadResult.Value(value)
    }

    suspend fun readTroubleCodes(kind: DiagnosticCode.Kind): List<DiagnosticCode> {
        val raw = try {
            send("%02X".format(kind.mode), SCAN_TIMEOUT_MS)
        } catch (e: ObdTimeoutException) {
            return emptyList()
        }
        return DtcDecoder.decode(raw, kind)
    }

    suspend fun readMonitorStatus(): MonitorStatus? = try {
        MonitorStatus.parse(send("0101", PID_TIMEOUT_MS))
    } catch (e: ObdTimeoutException) {
        null
    }

    /** Mode 04: clear stored codes and turn the MIL off. */
    suspend fun clearTroubleCodes(): Boolean {
        val raw = try {
            send("04", SCAN_TIMEOUT_MS)
        } catch (e: ObdTimeoutException) {
            return false
        }
        return ElmProtocol.sanitize(raw).contains("44")
    }

    private suspend fun send(command: String, timeoutMs: Long): String =
        transport.request(command, timeoutMs)
}
