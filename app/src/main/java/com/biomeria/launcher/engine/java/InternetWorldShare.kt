package com.biomeria.launcher.engine.java

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.util.UUID

internal sealed interface InternetRoomState {
    data object Idle : InternetRoomState
    data object Starting : InternetRoomState
    data object Stopping : InternetRoomState
    data class Open(val address: String) : InternetRoomState
    data class Closed(val message: String) : InternetRoomState
}

/** Owns the invitation independently of the dialog, until the world or activity closes. */
internal class InternetWorldShare(
    private val bridge: DualScreenBridge,
    private val scope: CoroutineScope,
    private val createTunnel: (Int) -> BoreWorldTunnel = { BoreWorldTunnel(it) }
) : Closeable {
    private val status = MutableStateFlow<InternetRoomState>(InternetRoomState.Idle)
    val state = status.asStateFlow()
    private var session: Job? = null
    private var tunnel: BoreWorldTunnel? = null

    fun start() {
        if (session?.isCompleted == false) return
        status.value = InternetRoomState.Starting
        session = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var worldEnded = false
            var failure = "Se cerró la conexión con el relay."
            val token = UUID.randomUUID()
            try {
                val port = requestPort(token)
                val forwarding = createTunnel(port)
                tunnel = forwarding
                coroutineScope {
                    val monitor = launch {
                        while (true) {
                            delay(200)
                            val currentPort = withContext(Dispatchers.IO) { bridge.readInternetWorld(token) }
                            if (currentPort != port) {
                                worldEnded = true
                                forwarding.close()
                                break
                            }
                        }
                    }
                    try {
                        forwarding.serve { address ->
                            status.compareAndSet(InternetRoomState.Starting, InternetRoomState.Open(address))
                        }
                    } finally {
                        monitor.cancel()
                    }
                }
            } catch (error: IOException) {
                failure = error.message ?: "El relay está temporalmente fuera de servicio."
            } finally {
                tunnel?.close()
                tunnel = null
                withContext(NonCancellable + Dispatchers.IO) { bridge.stopInternetWorld() }
                status.value = when {
                    status.value == InternetRoomState.Stopping -> InternetRoomState.Idle
                    worldEnded -> InternetRoomState.Closed("El mundo se cerró. La invitación dejó de estar activa.")
                    else -> InternetRoomState.Closed(failure)
                }
            }
        }
    }

    private suspend fun requestPort(token: UUID): Int {
        if (!withContext(Dispatchers.IO) { bridge.requestInternetWorld(token) }) {
            throw IOException("No se pudo solicitar la sala a Minecraft.")
        }
        repeat(40) {
            val port = withContext(Dispatchers.IO) { bridge.readInternetWorld(token) }
            when {
                port == 0 -> throw IOException("Entra a un mundo de un jugador para compartirlo.")
                port == -1 -> throw IOException("Minecraft no pudo abrir el mundo.")
                port != null -> return port
            }
            delay(125)
        }
        throw IOException("Minecraft tardó demasiado en responder.")
    }

    override fun close() {
        if (session?.isCompleted != false) return
        status.value = InternetRoomState.Stopping
        tunnel?.close()
        session?.cancel()
    }
}
