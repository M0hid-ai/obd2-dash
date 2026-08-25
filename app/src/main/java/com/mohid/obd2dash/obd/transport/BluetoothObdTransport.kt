package com.mohid.obd2dash.obd.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import android.util.Log
import com.mohid.obd2dash.obd.ElmProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Bluetooth Classic (RFCOMM/SPP) link to an ELM327.
 *
 * Caller must already hold BLUETOOTH_CONNECT on API 31+.
 */
@SuppressLint("MissingPermission")
class BluetoothObdTransport(
    private val device: BluetoothDevice,
    private val adapter: BluetoothAdapter?,
) : ObdTransport {

    private companion object {
        const val TAG = "BtObdTransport"

        /** The well-known Serial Port Profile service. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** How long to nap when the input buffer is empty. Cheap, and keeps cancellation responsive. */
        const val IDLE_POLL_MS = 4L
    }

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    /** The adapter answers one command at a time; this enforces that. */
    private val lock = Mutex()

    override val name: String = device.name ?: device.address ?: "ELM327"

    override val isOpen: Boolean
        get() = socket?.isConnected == true

    override suspend fun open(): Unit = withContext(Dispatchers.IO) {
        close()
        // Discovery is bandwidth-hungry and will make the connect attempt fail.
        runCatching { adapter?.cancelDiscovery() }

        val connected = try {
            device.createRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
        } catch (primary: IOException) {
            Log.w(TAG, "SPP connect failed, trying channel-1 fallback", primary)
            // Many ELM327 clones publish a malformed SDP record. Going straight
            // to RFCOMM channel 1 through the hidden constructor is the
            // long-standing workaround for those.
            try {
                fallbackSocket().also { it.connect() }
            } catch (fallback: Exception) {
                throw IOException(
                    "Could not open an RFCOMM channel to $name. Make sure the adapter is paired " +
                        "and not already connected to another app.",
                    primary,
                ).also { it.addSuppressed(fallback) }
            }
        }

        socket = connected
        input = connected.inputStream
        output = connected.outputStream
        Log.i(TAG, "Connected to $name")
    }

    private fun fallbackSocket(): BluetoothSocket {
        val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
        return method.invoke(device, 1) as BluetoothSocket
    }

    override suspend fun request(command: String, timeoutMs: Long): String = lock.withLock {
        withContext(Dispatchers.IO) {
            val out = output ?: throw IOException("Transport is not open")
            val inp = input ?: throw IOException("Transport is not open")

            discardPending(inp)
            out.write((command + "\r").toByteArray(Charsets.US_ASCII))
            out.flush()
            readUntilPrompt(inp, command, timeoutMs)
        }
    }

    /**
     * Throws away anything left over from a timed-out previous command, so a
     * late reply is never mistaken for the answer to this one.
     */
    private fun discardPending(inp: InputStream) {
        val scratch = ByteArray(256)
        while (inp.available() > 0) {
            if (inp.read(scratch) <= 0) break
        }
    }

    private suspend fun readUntilPrompt(inp: InputStream, command: String, timeoutMs: Long): String {
        val sb = StringBuilder(64)
        val buffer = ByteArray(256)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs

        while (SystemClock.elapsedRealtime() < deadline) {
            val pending = inp.available()
            if (pending <= 0) {
                delay(IDLE_POLL_MS)
                continue
            }
            val read = inp.read(buffer, 0, minOf(pending, buffer.size))
            if (read < 0) throw IOException("Adapter closed the stream")
            for (i in 0 until read) {
                val c = buffer[i].toInt().toChar()
                if (c == ElmProtocol.PROMPT) return sb.toString()
                sb.append(c)
            }
        }
        throw ObdTimeoutException(command, timeoutMs, sb.toString())
    }

    override fun close() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }
}
