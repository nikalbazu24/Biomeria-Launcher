package com.biomeria.launcher.engine.java

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.UUID

internal const val INTERNET_RELAY_HOST = "bore.pub"
private const val RELAY_CONTROL_PORT = 7835
private const val MAX_RELAY_FRAME = 256
private const val RELAY_TIMEOUT = 5000
private const val RELAY_HEARTBEAT_TIMEOUT = 6500

/** Client for Bore's null-delimited control protocol. Only proxies the current Minecraft LAN port. */
internal class BoreWorldTunnel(
    private val localPort: Int,
    private val relayHost: String = INTERNET_RELAY_HOST,
    private val controlPort: Int = RELAY_CONTROL_PORT
) : Closeable {
    private val lock = Any()
    private val sockets = mutableSetOf<Socket>()
    private val connections = Semaphore(8)
    private var closed = false

    init {
        require(localPort in 1..65535 && controlPort in 1..65535)
    }

    suspend fun serve(onAddress: (String) -> Unit): Unit = withContext(Dispatchers.IO) {
        try {
            val control = openControl().apply { soTimeout = RELAY_TIMEOUT }
            val input = control.getInputStream().buffered()
            control.sendFrame(JSONObject().put("Hello", 0))
            val response = input.readRelayFrame()
            val remotePort = response?.optInt("Hello", 0) ?: 0
            if (remotePort !in 1..65535) throw IOException("El relay no pudo reservar una dirección.")
            control.soTimeout = RELAY_HEARTBEAT_TIMEOUT

            coroutineScope {
                try {
                    onAddress("$relayHost:$remotePort")
                    while (true) {
                        val message = input.readRelayFrame()
                        if (message == null) continue
                        if (message.has("Error")) throw IOException("El relay cerró la sala.")
                        val raw = message.optString("Connection")
                        val peer = try { UUID.fromString(raw).takeIf { it.toString() == raw } }
                        catch (_: IllegalArgumentException) { null }
                        if (peer != null && connections.tryAcquire()) {
                            launch {
                                try {
                                    forward(control.inetAddress, peer)
                                } finally {
                                    connections.release()
                                }
                            }
                        }
                    }
                } finally {
                    close()
                }
            }
        } finally {
            close()
        }
    }

    private fun openControl(): Socket {
        var failure: IOException? = null
        for (address in InetAddress.getAllByName(relayHost)) {
            try {
                return connect(address, controlPort)
            } catch (error: IOException) {
                failure = error
            }
        }
        throw IOException("No se pudo conectar con $relayHost.", failure)
    }

    private suspend fun forward(address: InetAddress, peer: UUID) {
        var relay: Socket? = null
        var local: Socket? = null
        try {
            val upstream = connect(address, controlPort).also { relay = it }
            val game = connect(InetAddress.getByName("127.0.0.1"), localPort).also { local = it }
            upstream.sendFrame(JSONObject().put("Accept", peer.toString()))
            coroutineScope {
                launch { transfer(game, upstream) }
                transfer(upstream, game)
            }
        } catch (_: IOException) {
            // One abandoned connection must not close the invitation.
        } finally {
            local?.let(::release)
            relay?.let(::release)
        }
    }

    private fun transfer(source: Socket, destination: Socket) {
        try {
            source.getInputStream().copyTo(destination.getOutputStream(), 16 * 1024)
            destination.shutdownOutput()
        } catch (_: IOException) {
            release(source)
            release(destination)
        }
    }

    private fun connect(address: InetAddress, port: Int): Socket {
        val socket = synchronized(lock) {
            if (closed) throw SocketException("La sala está cerrada.")
            Socket().also(sockets::add)
        }
        try {
            socket.connect(InetSocketAddress(address, port), RELAY_TIMEOUT)
            socket.tcpNoDelay = true
            return socket
        } catch (error: IOException) {
            release(socket)
            throw error
        }
    }

    private fun release(socket: Socket) {
        synchronized(lock) { sockets.remove(socket) }
        runCatching { socket.close() }
    }

    override fun close() {
        val open = synchronized(lock) {
            closed = true
            sockets.toList().also { sockets.clear() }
        }
        open.forEach { runCatching { it.close() } }
    }
}

private fun Socket.sendFrame(message: JSONObject) {
    getOutputStream().apply {
        write(message.toString().toByteArray(Charsets.UTF_8))
        write(0)
        flush()
    }
}

private fun InputStream.readRelayFrame(): JSONObject? {
    val frame = ByteArrayOutputStream(80)
    while (frame.size() <= MAX_RELAY_FRAME) {
        val byte = read()
        if (byte == -1) throw EOFException("Se cerró la conexión con el relay.")
        if (byte == 0) {
            val body = frame.toString("UTF-8")
            if (body == "\"Heartbeat\"") return null
            try {
                return JSONObject(body)
            } catch (error: JSONException) {
                throw IOException("El relay envió una respuesta inválida.", error)
            }
        }
        frame.write(byte)
    }
    throw IOException("El relay envió una respuesta demasiado grande.")
}
