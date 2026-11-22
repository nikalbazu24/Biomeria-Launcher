package com.prissma.launcher.engine.java

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.FutureTask
import kotlin.concurrent.thread

class JavaServersTest {
    @Test
    fun addressIsCanonicalAndQuickPlayIsOnlyUsedForSupportedVersions() {
        val address = requireNotNull(parseJavaServerAddress("  PLAY.Example.com:25566  "))
        assertEquals("play.example.com:25566", address.text)
        assertEquals("[::1]:51234", parseJavaServerAddress("[::1]:51234")?.text)
        for (bad in listOf("host:", "host:0", "host:65536", "https://host", "host with space", "../host", "[]")) {
            assertNull(bad, parseJavaServerAddress(bad))
        }
        assertArrayEquals(arrayOf("--quickPlayMultiplayer", address.text), javaQuickPlayArguments("1.21.4", address))
        assertTrue(javaQuickPlayArguments("1.16.5", address).isEmpty())
        assertTrue(javaQuickPlayArguments("1.21.4", null).isEmpty())
    }

    @Test
    fun statusQueryReadsPlayerCountFromALocalJavaProtocolFixture() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 5000
            val answer = FutureTask {
                server.accept().use { connection ->
                    connection.soTimeout = 4000
                    val input = DataInputStream(connection.getInputStream())
                    val handshakeSize = input.readUnsignedByte()
                    assertTrue(handshakeSize in 12..100)
                    input.readFully(ByteArray(handshakeSize))
                    assertEquals(1, input.readUnsignedByte())
                    assertEquals(0, input.readUnsignedByte())
                    val bytes = """{"players":{"online":3,"max":8},"version":{"name":"1.21.4"}}""".toByteArray()
                    DataOutputStream(connection.getOutputStream()).apply {
                        writeByte(bytes.size + 2)
                        writeByte(0)
                        writeByte(bytes.size)
                        write(bytes)
                        flush()
                    }
                }
            }
            thread(isDaemon = true) { answer.run() }
            val status = pingJavaServer(JavaServerAddress(server.inetAddress.hostAddress ?: "127.0.0.1", server.localPort))
            assertEquals(JavaServerStatus(3, 8, "1.21.4"), status)
            answer.get()
        }
    }
}
