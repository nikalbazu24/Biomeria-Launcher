package com.prissma.launcher.engine.java

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

class BoreWorldTunnelTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun listener() = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 4000 }

    @Test
    fun relayForwardsBothWaysAndCloseDisconnectsControlAndGame() = runBlocking {
        listener().use { relay ->
            listener().use { game ->
                val tunnel = BoreWorldTunnel(game.localPort, "127.0.0.1", relay.localPort)
                val address = CompletableDeferred<String>()
                val hosting = launch { runCatching { tunnel.serve { address.complete(it) } } }
                try {
                    withContext(Dispatchers.IO) {
                        relay.accept().withTimeout().use { control ->
                            assertEquals(0, JSONObject(control.getInputStream().controlFrame()).getInt("Hello"))
                            control.writeControl("""{"Hello":25569}""")
                            assertEquals("127.0.0.1:25569", withTimeout(3000) { address.await() })
                            control.writeControl(""""Heartbeat"""")
                            val connectionId = UUID.randomUUID()
                            control.writeControl(JSONObject().put("Connection", connectionId.toString()).toString())
                            relay.accept().withTimeout().use { peer ->
                                game.accept().withTimeout().use { session ->
                                    val accepted = JSONObject(peer.getInputStream().controlFrame()).getString("Accept")
                                    assertEquals(connectionId.toString(), accepted)
                                    peer.getOutputStream().write(41)
                                    assertEquals(41, session.getInputStream().read())
                                    session.getOutputStream().write(99)
                                    assertEquals(99, peer.getInputStream().read())
                                    tunnel.close()
                                    assertEquals(-1, control.getInputStream().read())
                                    assertEquals(-1, session.getInputStream().read())
                                    assertEquals(-1, peer.getInputStream().read())
                                }
                            }
                        }
                    }
                    withTimeout(3500) { hosting.join() }
                } finally {
                    tunnel.close()
                    hosting.cancelAndJoin()
                }
            }
        }
    }

    @Test
    fun closingMinecraftWorldWithdrawsTheInvitationAndRequest() = runBlocking {
        listener().use { relay ->
            val bridge = DualScreenBridge(temporary.root)
            val owner = InternetWorldShare(bridge, this) { port -> BoreWorldTunnel(port, "127.0.0.1", relay.localPort) }
            val marker = File(temporary.root, "internet-open")
            val state = File(temporary.root, "internet-world")
            try {
                owner.start()
                withTimeout(3000) {
                    while (!marker.isFile) delay(30)
                }
                val token = marker.readText()
                state.writeText("$token 32109")
                withContext(Dispatchers.IO) {
                    relay.accept().withTimeout().use { control ->
                        assertEquals(0, JSONObject(control.getInputStream().controlFrame()).getInt("Hello"))
                        control.writeControl("""{"Hello":25570}""")
                        withTimeout(3000) {
                            while (owner.state.value !is InternetRoomState.Open) delay(25)
                        }
                        assertEquals(InternetRoomState.Open("127.0.0.1:25570"), owner.state.value)
                        state.writeText("$token 0")
                        assertEquals(-1, control.getInputStream().read())
                    }
                }
                withTimeout(3000) {
                    while (owner.state.value !is InternetRoomState.Closed) delay(25)
                }
                assertFalse(marker.exists())
                assertTrue((owner.state.value as InternetRoomState.Closed).message.contains("mundo se cerró"))
            } finally {
                owner.close()
            }
        }
    }

    @Test
    fun rejectsAnotherWorldsResponseAndExpiredHeartbeats() {
        val bridge = DualScreenBridge(temporary.root)
        val token = UUID.randomUUID()
        val state = File(temporary.root, "internet-world")
        assertTrue(bridge.requestInternetWorld(token))
        state.writeText("${UUID.randomUUID()} 25565")
        assertNull(bridge.readInternetWorld(token))
        state.writeText("$token 99999")
        assertNull(bridge.readInternetWorld(token))
        state.writeText("$token 25565")
        assertEquals(25565, bridge.readInternetWorld(token))
        assertTrue(state.setLastModified(System.currentTimeMillis() - 4000))
        assertNull(bridge.readInternetWorld(token))
    }

    private fun Socket.withTimeout() = apply { soTimeout = 4000 }

    private fun Socket.writeControl(value: String) {
        getOutputStream().apply { write(value.toByteArray(Charsets.UTF_8)); write(0); flush() }
    }

    private fun InputStream.controlFrame(): String = buildString {
        repeat(257) {
            val next = read()
            check(next >= 0)
            if (next == 0) return@buildString
            append(next.toChar())
        }
        error("oversized relay frame")
    }
}
