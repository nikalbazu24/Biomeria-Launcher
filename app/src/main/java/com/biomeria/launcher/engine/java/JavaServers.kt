package com.biomeria.launcher.engine.java

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.IDN
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale

internal data class JavaServerAddress(val host: String, val port: Int = 25565) {
    val text: String
        get() {
            val address = if (':' in host) "[$host]" else host
            return if (port == 25565) address else "$address:$port"
        }
}

internal data class JavaServer(val name: String, val address: JavaServerAddress)
internal data class JavaServerStatus(val online: Int, val max: Int, val version: String)

internal fun parseJavaServerAddress(raw: String): JavaServerAddress? {
    val value = raw.trim()
    if (value.isEmpty() || value.length > 260 || value.any(Char::isWhitespace)) return null
    val host: String
    val portText: String
    if (value.startsWith('[')) {
        val end = value.indexOf(']')
        if (end < 2) return null
        host = value.substring(1, end)
        if (!host.matches(Regex("[0-9a-fA-F:.]{2,45}")) || ':' !in host) return null
        val suffix = value.substring(end + 1)
        if (suffix.isNotEmpty() && !suffix.startsWith(':')) return null
        portText = suffix.removePrefix(":")
        if (suffix == ":") return null
    } else {
        if (value.count { it == ':' } > 1) return null
        host = try {
            IDN.toASCII(value.substringBefore(':'), IDN.USE_STD3_ASCII_RULES)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (host.length !in 1..253 || host.any { it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' && it !in ".-" }) return null
        portText = value.substringAfter(':', "")
        if (value.endsWith(':')) return null
    }
    val port = if (portText.isEmpty()) 25565 else portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    return JavaServerAddress(host.lowercase(Locale.ROOT), port)
}

internal fun supportsJavaQuickPlay(version: String): Boolean {
    val number = Regex("^(\\d+)\\.(\\d+)(?:\\.\\d+)?$").matchEntire(version) ?: return false
    val major = number.groupValues[1].toIntOrNull() ?: return false
    val minor = number.groupValues[2].toIntOrNull() ?: return false
    return major >= 26 || major == 1 && minor >= 20
}

internal fun javaQuickPlayArguments(version: String, address: JavaServerAddress?): Array<String> =
    if (address != null && supportsJavaQuickPlay(version)) arrayOf("--quickPlayMultiplayer", address.text) else emptyArray()

internal class JavaServerBookmarks(context: Context) {
    private val storage = context.getSharedPreferences("java_server_bookmarks", Context.MODE_PRIVATE)

    fun load(instance: String): List<JavaServer> {
        val array = runCatching { JSONArray(storage.getString(instance, "[]")) }.getOrNull() ?: return emptyList()
        return (0 until minOf(array.length(), 32)).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val address = parseJavaServerAddress(item.optString("address")) ?: return@mapNotNull null
            JavaServer(item.optString("name").take(48).ifBlank { address.text }, address)
        }
    }

    fun save(instance: String, entries: List<JavaServer>) {
        val array = JSONArray()
        entries.take(32).forEach { entry ->
            array.put(JSONObject().put("name", entry.name).put("address", entry.address.text))
        }
        storage.edit().putString(instance, array.toString()).apply()
    }
}

internal fun pingJavaServer(address: JavaServerAddress): JavaServerStatus = Socket().use { socket ->
    socket.connect(InetSocketAddress(address.host, address.port), 3000)
    socket.soTimeout = 3000
    val output = DataOutputStream(socket.getOutputStream().buffered())
    val handshake = ByteArrayOutputStream()
    DataOutputStream(handshake).use { packet ->
        packet.writeVarInt(0)
        packet.writeVarInt(-1)
        val host = address.host.toByteArray(Charsets.UTF_8)
        packet.writeVarInt(host.size)
        packet.write(host)
        packet.writeShort(address.port)
        packet.writeVarInt(1)
    }
    output.writeVarInt(handshake.size())
    output.write(handshake.toByteArray())
    output.writeVarInt(1)
    output.writeVarInt(0)
    output.flush()

    DataInputStream(socket.getInputStream().buffered()).use { input ->
        val size = input.readVarInt()
        if (size !in 3..65_536) throw IOException("Respuesta de servidor inválida.")
        val packet = DataInputStream(ByteArray(size).also(input::readFully).inputStream())
        if (packet.readVarInt() != 0) throw IOException("Respuesta de estado inesperada.")
        val textSize = packet.readVarInt()
        if (textSize != packet.available() || textSize <= 0) throw IOException("Estado del servidor incompleto.")
        val status = JSONObject(ByteArray(textSize).also(packet::readFully).toString(Charsets.UTF_8))
        val players = status.optJSONObject("players")
        JavaServerStatus(players?.optInt("online")?.coerceAtLeast(0) ?: 0,
            players?.optInt("max")?.coerceAtLeast(0) ?: 0, status.optJSONObject("version")?.optString("name").orEmpty().take(48))
    }
}

private fun DataOutputStream.writeVarInt(value: Int) {
    var rest = value
    while (rest and 127.inv() != 0) {
        writeByte((rest and 127) or 128)
        rest = rest ushr 7
    }
    writeByte(rest)
}

private fun DataInputStream.readVarInt(): Int {
    var value = 0
    for (bit in 0..28 step 7) {
        val b = readUnsignedByte()
        if (bit == 28 && b > 15) throw IOException("Longitud de paquete inválida.")
        value = value or ((b and 127) shl bit)
        if (b and 128 == 0) return value
    }
    throw IOException("Longitud de paquete inválida.")
}
