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

        /** An auto-search walks every protocol in turn, so it needs room. */
        const val PROTOCOL_SEARCH_MS = 10_000L

        /** A forced protocol either answers quickly or is the wrong one. */
        const val FORCED_PROTOCOL_MS = 5_000L

        /**
         * Tried in order when the auto-search comes up empty. CAN first: it
         * has been mandatory in most markets since 2008, so it is what almost
         * every car on the road now speaks. The K-line protocols below it are
         * only reachable on genuinely old vehicles.
         */
        val FALLBACK_PROTOCOLS = listOf(
            "6" to "CAN 11 bit, 500 kbaud",
            "7" to "CAN 29 bit, 500 kbaud",
            "8" to "CAN 11 bit, 250 kbaud",
            "9" to "CAN 29 bit, 250 kbaud",
            "5" to "KWP2000 fast init",
            "4" to "KWP2000 5 baud init",
            "3" to "ISO 9141-2",
            "1" to "SAE J1850 PWM",
            "2" to "SAE J1850 VPW",
        )

        /** The ELM327's `ATDPN` digits. */
        val PROTOCOL_NAMES = mapOf(
            '1' to "SAE J1850 PWM",
            '2' to "SAE J1850 VPW",
            '3' to "ISO 9141-2",
            '4' to "ISO 14230-4 KWP (5 baud)",
            '5' to "ISO 14230-4 KWP (fast)",
            '6' to "ISO 15765-4 CAN (11 bit, 500k)",
            '7' to "ISO 15765-4 CAN (29 bit, 500k)",
            '8' to "ISO 15765-4 CAN (11 bit, 250k)",
            '9' to "ISO 15765-4 CAN (29 bit, 250k)",
            'A' to "SAE J1939 CAN",
            'B' to "User1 CAN",
            'C' to "User2 CAN",
        )
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
        // ~100ms per-request ceiling. The previous 200ms value was the adapter
        // sitting idle after a fast ECU had already answered, which is what
        // made a "300ms poll interval" feel like half a second on a good link.
        send("ATST19", AT_TIMEOUT_MS)

        onProgress("Negotiating protocol…")
        val protocol = negotiateProtocol(onProgress)

        Log.i(TAG, "Adapter=$version protocol=$protocol")
        return AdapterInfo(version = version.take(40), protocol = protocol.take(60))
    }

    /**
     * Finds a protocol the car actually answers on.
     *
     * `ATSP0` handles the overwhelming majority of cars on its own, but its
     * auto-search gives up on some ECUs that are perfectly happy once told
     * which protocol to speak. So a failed auto-search falls through to trying
     * the common ones explicitly, newest first, rather than reporting the car
     * as unreachable.
     */
    private suspend fun negotiateProtocol(onProgress: (String) -> Unit): String {
        send("ATSP0", AT_TIMEOUT_MS)
        // The first real request is what actually triggers detection; it can
        // take a few seconds and answers with SEARCHING... first.
        if (probeEcu(PROTOCOL_SEARCH_MS)) return describeProtocol()

        for ((code, label) in FALLBACK_PROTOCOLS) {
            onProgress("Trying $label…")
            Log.i(TAG, "Auto-detect failed, forcing protocol $code ($label)")
            send("ATSP$code", AT_TIMEOUT_MS)
            if (probeEcu(FORCED_PROTOCOL_MS)) return describeProtocol()
        }

        // Leave the adapter back on auto so the next attempt starts clean.
        send("ATSP0", AT_TIMEOUT_MS)
        return describeProtocol()
    }

    /** True when `0100` came back as real data rather than an error or silence. */
    private suspend fun probeEcu(timeoutMs: Long): Boolean {
        val raw = try {
            send("0100", timeoutMs)
        } catch (e: ObdTimeoutException) {
            return false
        }
        val clean = ElmProtocol.sanitize(raw)
        return clean.contains("4100") && ElmProtocol.errorToken(clean) == null
    }

    /**
     * Names the protocol in use.
     *
     * `ATDP` answers in prose and is the nicer label, but several clones return
     * a bare "AUTO," from it when the search has only partly completed, which
     * is useless on a trip report. `ATDPN` answers with a single digit that
     * maps to a known name, so it is the source of truth and the prose is only
     * used when it is actually more specific.
     */
    private suspend fun describeProtocol(): String {
        val numeric = runCatching { ElmProtocol.sanitize(send("ATDPN", AT_TIMEOUT_MS)) }.getOrNull()
        // A leading 'A' means the adapter reached this protocol automatically.
        val auto = numeric?.startsWith("A") == true
        val named = numeric?.trimStart('A')?.firstOrNull()?.let { PROTOCOL_NAMES[it] }

        val prose = runCatching {
            send("ATDP", AT_TIMEOUT_MS)
                .lines()
                .joinToString(" ")
                .replace(">", "")
                .trim()
                .trim(',')
                .trim()
        }.getOrNull().orEmpty()

        return when {
            named != null && auto -> "Auto · $named"
            named != null -> named
            // Only trust the prose if it says more than "AUTO".
            prose.length > 5 -> prose
            else -> "unknown"
        }
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

    /**
     * VIN first, calibration id as a tie-breaker, then a fingerprint of the
     * Mode 01 support bitmask. The fingerprint is only used when Mode 09 is
     * silent, which is common on clone adapters.
     */
    suspend fun readVehicleIdentity(supportedMode01: Set<Int>): VehicleIdentity.Info {
        val vin = runCatching {
            VehicleIdentity.decodeVin(send("0902", SCAN_TIMEOUT_MS))
        }.getOrNull()
        val calid = runCatching {
            VehicleIdentity.decodeCalid(send("0904", SCAN_TIMEOUT_MS))
        }.getOrNull()
        val identity = vin ?: VehicleIdentity.fingerprint(supportedMode01, calid)
        Log.i(TAG, "Vehicle identity=$identity vin=${vin ?: "none"} calid=${calid ?: "none"}")
        return VehicleIdentity.Info(identity = identity, vin = vin, calid = calid)
    }

    private suspend fun send(command: String, timeoutMs: Long): String =
        transport.request(command, timeoutMs)
}
