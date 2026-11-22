package com.prissma.launcher.data.accounts

import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

private const val MICROSOFT_OAUTH = "https://login.microsoftonline.com/consumers/oauth2/v2.0"
private const val MINECRAFT_SERVICES = "https://api.minecraftservices.com"
private const val SCOPE = "XboxLive.signin offline_access"
private val applicationId = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
private val minecraftId = Regex("[0-9a-fA-F]{32}")
private val minecraftName = Regex("[A-Za-z0-9_]{1,16}")

internal fun validMicrosoftApplicationId(value: String) = applicationId.matches(value)

internal class MicrosoftLoginError(message: String, val relink: Boolean = false) : IOException(message)

internal class DeviceCode(
    val userCode: String,
    val verificationUrl: String,
    internal val applicationId: String,
    internal val deviceCode: String,
    internal val intervalSeconds: Long,
    internal val deadlineSeconds: Long
)

internal class MicrosoftSession(
    val applicationId: String,
    val name: String,
    val profileId: String,
    val minecraftToken: String,
    val refreshToken: String,
    val expiresAt: Long
) {
    fun validFor(now: Long, bufferMillis: Long = 0) = expiresAt - now > bufferMillis

    fun encode() = JSONObject().apply {
        put("applicationId", applicationId)
        put("name", name)
        put("profileId", profileId)
        put("minecraftToken", minecraftToken)
        put("refreshToken", refreshToken)
        put("expiresAt", expiresAt)
    }

    companion object {
        fun decode(data: JSONObject): MicrosoftSession {
            val id = data.requiredText("profileId")
            val name = data.requiredText("name")
            val client = data.requiredText("applicationId")
            if (!validMicrosoftApplicationId(client) || !minecraftName.matches(name) || !minecraftId.matches(id)) {
                throw MicrosoftLoginError("La sesión guardada es inválida. Vuelve a iniciar sesión.", relink = true)
            }
            return MicrosoftSession(client, name, id.lowercase(), data.requiredText("minecraftToken"),
                data.requiredText("refreshToken"), data.optLong("expiresAt"))
        }
    }
}

internal class AuthRequest(
    val url: String,
    val payload: String? = null,
    val contentType: String? = null,
    val bearer: String? = null
)

internal class AuthResponse(val status: Int, val json: JSONObject)

internal class MicrosoftLogin(
    private val send: (AuthRequest) -> AuthResponse = ::sendAuthRequest,
    private val wallTime: () -> Long = System::currentTimeMillis,
    private val elapsedSeconds: () -> Long = { TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()) },
    private val wait: suspend (Long) -> Unit = { delay(it) }
) {
    fun start(client: String): DeviceCode {
        if (!validMicrosoftApplicationId(client)) throw MicrosoftLoginError("Introduce un ID de aplicación de Microsoft válido.")
        val reply = form("devicecode", "client_id" to client, "scope" to SCOPE)
        if (reply.status !in 200..299) throw oauthError(reply, "Microsoft no pudo generar el código de acceso.")
        val url = reply.json.optString("verification_uri").ifBlank { reply.json.optString("verification_url") }
        val verification = runCatching { URI(url) }.getOrNull()
        if (verification?.scheme != "https" || verification.userInfo != null ||
            verification.port !in listOf(-1, 443) || verification.host?.lowercase() !in verificationHosts) {
            throw MicrosoftLoginError("Microsoft devolvió una dirección de verificación inválida.")
        }
        val expires = reply.json.optLong("expires_in").takeIf { it in 1..3600 }
            ?: throw MicrosoftLoginError("Microsoft devolvió un código sin vencimiento válido.")
        return DeviceCode(reply.json.requiredText("user_code"), url, client, reply.json.requiredText("device_code"),
            reply.json.optLong("interval", 5).coerceIn(1, 60), elapsedSeconds() + expires)
    }

    suspend fun finish(code: DeviceCode): MicrosoftSession {
        var interval = code.intervalSeconds
        while (elapsedSeconds() + interval < code.deadlineSeconds) {
            wait(interval * 1000)
            if (elapsedSeconds() >= code.deadlineSeconds) break
            val reply = form("token", "client_id" to code.applicationId,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code", "device_code" to code.deviceCode)
            if (reply.status in 200..299) return exchange(code.applicationId, reply.json)
            when (reply.json.optString("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5
                else -> throw oauthError(reply, "Microsoft no pudo completar el inicio de sesión.")
            }
        }
        throw MicrosoftLoginError("El código caducó. Genera otro para iniciar sesión.")
    }

    fun refresh(session: MicrosoftSession, saveRotation: (MicrosoftSession) -> Unit): MicrosoftSession {
        val reply = form("token", "client_id" to session.applicationId, "grant_type" to "refresh_token",
            "refresh_token" to session.refreshToken, "scope" to SCOPE)
        if (reply.status !in 200..299) throw oauthError(reply, "Microsoft no pudo renovar tu sesión.")
        val refresh = reply.json.optString("refresh_token").ifBlank { session.refreshToken }
        if (refresh != session.refreshToken) {
            saveRotation(MicrosoftSession(session.applicationId, session.name, session.profileId,
                session.minecraftToken, refresh, session.expiresAt))
        }
        val renewed = exchange(session.applicationId, reply.json, refresh)
        if (renewed.profileId != session.profileId) {
            throw MicrosoftLoginError("Cambió el perfil de Minecraft. Vuelve a vincular tu cuenta.", relink = true)
        }
        return renewed
    }

    private fun exchange(client: String, oauth: JSONObject, refresh: String = oauth.requiredText("refresh_token")): MicrosoftSession {
        val xbox = json("https://user.auth.xboxlive.com/user/authenticate", JSONObject().apply {
            put("Properties", JSONObject().put("AuthMethod", "RPS").put("SiteName", "user.auth.xboxlive.com")
                .put("RpsTicket", "d=" + oauth.requiredText("access_token")))
            put("RelyingParty", "http://auth.xboxlive.com")
            put("TokenType", "JWT")
        })
        if (xbox.status !in 200..299) throw MicrosoftLoginError("Xbox no pudo validar esta cuenta.")
        val xsts = json("https://xsts.auth.xboxlive.com/xsts/authorize", JSONObject().apply {
            put("Properties", JSONObject().put("SandboxId", "RETAIL").put("UserTokens", JSONArray().put(xbox.json.requiredText("Token"))))
            put("RelyingParty", "rp://api.minecraftservices.com/")
            put("TokenType", "JWT")
        })
        if (xsts.status !in 200..299) throw MicrosoftLoginError(when (xsts.json.optLong("XErr")) {
            2148916233L -> "La cuenta necesita un perfil de Xbox. Créalo en Xbox y vuelve a intentarlo."
            2148916238L -> "La configuración familiar de Xbox debe permitir el acceso a esta cuenta."
            else -> "Xbox no autorizó esta cuenta para Minecraft."
        })
        val hash = xsts.json.optJSONObject("DisplayClaims")?.optJSONArray("xui")?.optJSONObject(0)?.requiredText("uhs")
            ?: throw MicrosoftLoginError("Xbox no devolvió la identidad de la cuenta.")
        val login = json("$MINECRAFT_SERVICES/authentication/login_with_xbox",
            JSONObject().put("identityToken", "XBL3.0 x=$hash;${xsts.json.requiredText("Token")}"))
        if (login.status == 403) throw MicrosoftLoginError("Minecraft rechazó este ID de aplicación. Comprueba su autorización para Minecraft Java.")
        if (login.status !in 200..299) throw MicrosoftLoginError("Minecraft no pudo abrir la sesión de Xbox.")
        val token = login.json.requiredText("access_token")
        val seconds = login.json.optLong("expires_in").takeIf { it in 1..172800 }
            ?: throw MicrosoftLoginError("Minecraft devolvió una sesión sin vencimiento válido.")
        val expiresAt = wallTime() + seconds * 1000
        val profile = send(AuthRequest("$MINECRAFT_SERVICES/minecraft/profile", bearer = token))
        if (profile.status == 404) throw MicrosoftLoginError("Esta cuenta aún no tiene un perfil de Minecraft Java.")
        if (profile.status !in 200..299) throw MicrosoftLoginError("No se pudo consultar el perfil de Minecraft Java.")
        val id = profile.json.requiredText("id")
        val name = profile.json.requiredText("name")
        if (!minecraftId.matches(id) || !minecraftName.matches(name)) throw MicrosoftLoginError("Minecraft devolvió un perfil inválido.")
        return MicrosoftSession(client, name, id.lowercase(), token, refresh, expiresAt)
    }

    private fun form(path: String, vararg fields: Pair<String, String>) = send(AuthRequest("$MICROSOFT_OAUTH/$path",
        fields.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" },
        "application/x-www-form-urlencoded; charset=UTF-8"))

    private fun json(url: String, body: JSONObject) = send(AuthRequest(url, body.toString(), "application/json; charset=UTF-8"))

    private fun oauthError(reply: AuthResponse, fallback: String) = when (reply.json.optString("error")) {
        "authorization_declined", "access_denied" -> MicrosoftLoginError("La autorización se canceló en Microsoft.")
        "expired_token" -> MicrosoftLoginError("El código caducó. Genera otro para iniciar sesión.")
        "invalid_grant", "interaction_required" -> MicrosoftLoginError("La sesión de Microsoft caducó. Vuelve a vincular tu cuenta.", relink = true)
        "invalid_client", "unauthorized_client", "invalid_scope" -> MicrosoftLoginError(
            "Microsoft no autorizó este ID de aplicación. Revisa que sea un cliente público habilitado para cuentas personales.")
        else -> MicrosoftLoginError(fallback)
    }

    private companion object {
        val verificationHosts = setOf("microsoft.com", "www.microsoft.com", "login.microsoftonline.com")
    }
}

private fun JSONObject.requiredText(key: String): String = (opt(key) as? String)?.takeIf { it.isNotBlank() && it.length <= 32768 }
    ?: throw MicrosoftLoginError("El servicio de cuentas devolvió una respuesta incompleta.")

private fun sendAuthRequest(request: AuthRequest): AuthResponse {
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
                if (text.length + count > 65536) throw MicrosoftLoginError("El servicio de cuentas devolvió una respuesta demasiado grande.")
                text.append(buffer, 0, count)
            }
            runCatching { JSONObject(text.toString()) }.getOrDefault(JSONObject())
        } ?: JSONObject()
        return AuthResponse(status, json)
    } finally {
        connection.disconnect()
    }
}
