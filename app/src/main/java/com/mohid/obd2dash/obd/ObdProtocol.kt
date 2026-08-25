package com.mohid.obd2dash.obd

/**
 * Turning the ELM327's ASCII chatter into numbers.
 *
 * Everything here is pure Kotlin with no Android dependency, so the awkward
 * parts (clone adapters that leave spaces in, ECUs that pad DTC replies)
 * can be exercised from plain unit tests.
 */
object ElmProtocol {

    /** The prompt the adapter prints when it is ready for the next command. */
    const val PROMPT = '>'

    /**
     * Adapter-level failures. These come back in place of data, so any of them
     * means "this request produced nothing", not "the connection is dead",
     * except [isFatal] ones, which mean the link needs re-initialising.
     */
    private val errorTokens = listOf(
        "UNABLETOCONNECT",
        "BUSINIT",
        "BUSERROR",
        "BUSBUSY",
        "CANERROR",
        "DATAERROR",
        "FBERROR",
        "BUFFERFULL",
        "RXERROR",
        "LVRESET",
        "LOWPOWER",
        "STOPPED",
        "NODATA",
        "ERROR",
    )

    private val fatalTokens = setOf("UNABLETOCONNECT", "BUSINIT", "LVRESET", "LOWPOWER")

    fun isFatal(token: String): Boolean = token in fatalTokens

    /**
     * Collapses a raw adapter reply into a single uppercase hex string:
     * strips the prompt, whitespace, `SEARCHING...` notices, and the `0:` /
     * `1:` frame counters that appear on multi-frame CAN replies.
     */
    fun sanitize(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (line in raw.uppercase().split('\r', '\n')) {
            val trimmed = line.trim().trim(PROMPT).trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("SEARCHING")) continue
            // Drop a leading frame counter such as "0:" or "1:".
            val body = if (trimmed.length > 2 && trimmed[1] == ':') trimmed.substring(2) else trimmed
            for (c in body) if (!c.isWhitespace() && c != PROMPT) sb.append(c)
        }
        return sb.toString()
    }

    /** Returns the error token present in a sanitized reply, or null if it looks like data. */
    fun errorToken(sanitized: String): String? = errorTokens.firstOrNull { sanitized.contains(it) }
        ?: if (sanitized == "?") "?" else null

    /**
     * Pulls the data bytes out of a reply to [responseHeader] (e.g. `410C`).
     *
     * Returns null when the header is absent, the reply is an error, or fewer
     * than [expectedBytes] bytes followed the header.
     */
    fun extractData(raw: String, responseHeader: String, expectedBytes: Int): IntArray? {
        val clean = sanitize(raw)
        if (clean.isEmpty() || errorToken(clean) != null) return null

        val start = clean.indexOf(responseHeader)
        if (start < 0) return null

        val payload = clean.substring(start + responseHeader.length)
        val bytes = hexToBytes(payload) ?: return null
        if (bytes.size < expectedBytes) return null
        return if (bytes.size == expectedBytes) bytes else bytes.copyOf(expectedBytes)
    }

    /**
     * Parses an even-length hex string into unsigned byte values. A trailing
     * half-byte is dropped rather than failing the whole frame, because some clones
     * truncate the last character under load.
     */
    fun hexToBytes(hex: String): IntArray? {
        val usable = hex.length - (hex.length % 2)
        if (usable == 0) return IntArray(0)
        val out = IntArray(usable / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return if (i == 0) null else out.copyOf(i)
            out[i] = (hi shl 4) or lo
        }
        return out
    }
}

/**
 * Decodes the `0100` / `0120` / `0140` support bitmasks.
 *
 * Each reply is four bytes covering the next 32 PIDs. The most significant bit
 * of the first byte is `base + 1`; the least significant bit of the last byte
 * is `base + 32`. A set bit at `base + 0x20` means "ask me about the next
 * block too".
 */
object SupportedPids {

    /** The support-enquiry PIDs, in the order they must be walked. */
    val enquiryPids: List<Int> = listOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0)

    fun decode(base: Int, data: IntArray): Set<Int> {
        if (data.size < 4) return emptySet()
        var mask = 0L
        for (i in 0 until 4) mask = (mask shl 8) or (data[i].toLong() and 0xFF)
        val out = LinkedHashSet<Int>()
        for (i in 0 until 32) {
            if ((mask shr (31 - i)) and 1L == 1L) out += base + i + 1
        }
        return out
    }

    /** True when the reply for [base] says the following block is worth asking for. */
    fun continuesPastBlock(base: Int, supported: Set<Int>): Boolean = (base + 0x20) in supported
}

/** A stored, pending, or permanent trouble code. */
data class DiagnosticCode(
    val code: String,
    val kind: Kind,
) {
    enum class Kind(val label: String, val mode: Int) {
        STORED("Stored", 0x03),
        PENDING("Pending", 0x07),
        PERMANENT("Permanent", 0x0A),
    }

    /** P = powertrain, C = chassis, B = body, U = network. */
    val system: String
        get() = when (code.firstOrNull()) {
            'P' -> "Powertrain"
            'C' -> "Chassis"
            'B' -> "Body"
            'U' -> "Network"
            else -> "Unknown"
        }
}

object DtcDecoder {

    private const val SYSTEM_LETTERS = "PCBU"

    /**
     * Decodes a Mode 03/07/0A reply into trouble codes.
     *
     * ECUs disagree on the framing: CAN units usually insert a DTC count byte
     * after the `43` echo while older K-line units do not. An odd number of
     * trailing bytes can only be explained by that count byte, so that is the
     * tell used to skip it. All-zero pairs are padding and end the list.
     */
    fun decode(raw: String, kind: DiagnosticCode.Kind): List<DiagnosticCode> {
        val clean = ElmProtocol.sanitize(raw)
        if (clean.isEmpty() || ElmProtocol.errorToken(clean) != null) return emptyList()

        val header = "%02X".format(kind.mode + 0x40)
        val start = clean.indexOf(header)
        if (start < 0) return emptyList()

        var bytes = ElmProtocol.hexToBytes(clean.substring(start + header.length)) ?: return emptyList()
        if (bytes.size % 2 == 1) bytes = bytes.copyOfRange(1, bytes.size)

        val codes = LinkedHashSet<String>()
        var i = 0
        while (i + 1 < bytes.size) {
            val a = bytes[i]
            val b = bytes[i + 1]
            if (a == 0 && b == 0) break
            codes += format(a, b)
            i += 2
        }
        return codes.map { DiagnosticCode(it, kind) }
    }

    fun format(a: Int, b: Int): String {
        val letter = SYSTEM_LETTERS[(a shr 6) and 0x03]
        val d1 = (a shr 4) and 0x03
        val d2 = a and 0x0F
        val d3 = (b shr 4) and 0x0F
        val d4 = b and 0x0F
        return "$letter$d1${d2.toString(16).uppercase()}${d3.toString(16).uppercase()}${d4.toString(16).uppercase()}"
    }
}

/** PID 0101: the malfunction indicator lamp state and stored-code count. */
data class MonitorStatus(
    val milOn: Boolean,
    val dtcCount: Int,
) {
    companion object {
        fun parse(raw: String): MonitorStatus? {
            val data = ElmProtocol.extractData(raw, "4101", 4) ?: return null
            val a = data[0]
            return MonitorStatus(milOn = (a and 0x80) != 0, dtcCount = a and 0x7F)
        }
    }
}
