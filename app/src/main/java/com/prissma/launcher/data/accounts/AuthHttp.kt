package com.prissma.launcher.data.accounts

import org.json.JSONObject
import java.io.IOException
import java.net.URI
import javax.net.ssl.HttpsURLConnection

internal open class AccountError(message: String) : IOException(message)

internal class AuthRequest(
    val url: String,
    val payload: String? = null,
    val contentType: String? = null,
    val bearer: String? = null
)

internal class AuthResponse(val status: Int, val json: JSONObject)

internal fun JSONObject.requiredText(key: String): String = (opt(key) as? String)?.takeIf { it.isNotBlank() && it.length <= 32768 }
    ?: throw AccountError("El servicio de cuentas devolvió una respuesta incompleta.")

internal fun sendAuthRequest(request: AuthRequest): AuthResponse {
    val endpoint = URI(request.url)
    val connection = (endpoint.toURL().openConnection() as HttpsURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 12_000
        readTimeout = 15_000
        setRequestProperty("Accept", "application/json")
        setRequestProperty("Cache-Control", "no-store")
        if (endpoint.host.endsWith(".xboxlive.com")) setRequestProperty("x-xbl-contract-version", "1")
        request.bearer?.let { setRequestProperty("Authorization", "Bearer $it") }
    }
    try {
        request.payload?.let { payload ->
            val bytes = payload.toByteArray(Charsets.UTF_8)
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", request.contentType)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val json = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            val buffer = CharArray(4096)
            val text = StringBuilder()
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                if (text.length + count > 65536) throw AccountError("El servicio de cuentas devolvió una respuesta demasiado grande.")
                text.append(buffer, 0, count)
            }
            runCatching { JSONObject(text.toString()) }.getOrDefault(JSONObject())
        } ?: JSONObject()
        return AuthResponse(status, json)
    } finally {
        connection.disconnect()
    }
}
