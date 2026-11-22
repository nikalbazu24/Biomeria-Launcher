package com.prissma.launcher.data.accounts

import org.json.JSONObject
import java.util.Base64
import java.util.UUID

private const val ELY_AUTH_URL = "https://authserver.ely.by/auth"
internal const val ELY_AGENT_ENDPOINT = "https://account.ely.by/api/authlib-injector"
private val elyProfileId = Regex("[0-9a-fA-F]{32}")
private val elyPlayerName = Regex("[A-Za-z0-9_]{1,16}")
private const val OTP_REQUIRED = "Account protected with two factor auth."

internal class ElyLoginError(message: String, val needsCode: Boolean = false, val relink: Boolean = false) : AccountError(message)

internal class ElySession(val name: String, val profileId: String, val accessToken: String, val clientToken: String) {
    fun encode() = JSONObject().put("name", name).put("profileId", profileId)
        .put("accessToken", accessToken).put("clientToken", clientToken)

    companion object {
        fun decode(json: JSONObject): ElySession = parse(json.requiredText("name"), json.requiredText("profileId"),
            json.requiredText("accessToken"), json.requiredText("clientToken"))

        fun fromReply(reply: JSONObject, expectedClientToken: String): ElySession {
            val selected = reply.optJSONObject("selectedProfile")
                ?: throw ElyLoginError("Ely.by no devolvió un perfil de Minecraft.")
            val clientToken = reply.requiredText("clientToken")
            if (clientToken != expectedClientToken) throw ElyLoginError("La sesión de Ely.by no corresponde a este dispositivo.")
            return parse(selected.requiredText("name"), selected.requiredText("id"), reply.requiredText("accessToken"), clientToken)
        }

        private fun parse(name: String, id: String, accessToken: String, clientToken: String): ElySession {
            if (!elyPlayerName.matches(name) || !elyProfileId.matches(id)) throw ElyLoginError("Ely.by devolvió un perfil de Minecraft inválido.")
            return ElySession(name, id.lowercase(), accessToken, clientToken)
        }
    }
}

internal class ElyLogin(private val send: (AuthRequest) -> AuthResponse = ::sendAuthRequest) {
    fun signIn(username: String, password: String, oneTimeCode: String): ElySession {
        if (username.isBlank() || password.isEmpty()) throw ElyLoginError("Introduce tu usuario y contraseña de Ely.by.")
        if (oneTimeCode.isNotEmpty() && !oneTimeCode.matches(Regex("[0-9]{6}"))) {
            throw ElyLoginError("Introduce los seis dígitos de tu autenticador.", needsCode = true)
        }
        val clientToken = UUID.randomUUID().toString()
        val credential = if (oneTimeCode.isEmpty()) password else "$password:$oneTimeCode"
        val reply = post("authenticate", JSONObject().put("username", username.trim()).put("password", credential)
            .put("clientToken", clientToken).put("agent", JSONObject().put("name", "Minecraft").put("version", 1)))
        if (reply.status == 401 && reply.json.optString("errorMessage") == OTP_REQUIRED) {
            throw ElyLoginError("Tu cuenta requiere un código de verificación.", needsCode = true)
        }
        if (reply.status in 400..403) throw ElyLoginError(
            if (oneTimeCode.isEmpty()) "Ely.by no aceptó el usuario o la contraseña." else "Ely.by no aceptó las credenciales o el código.",
            needsCode = oneTimeCode.isNotEmpty())
        if (!reply.successful()) throw ElyLoginError("Ely.by no pudo iniciar sesión. Inténtalo de nuevo más tarde.")
        return ElySession.fromReply(reply.json, clientToken)
    }

    fun prepare(saved: ElySession): ElySession {
        val valid = post("validate", JSONObject().put("accessToken", saved.accessToken))
        if (valid.successful()) return saved
        if (valid.status !in setOf(400, 401, 403)) throw ElyLoginError("Ely.by no pudo verificar la sesión. Inténtalo de nuevo más tarde.")

        val renewed = post("refresh", sessionData(saved))
        if (renewed.status in setOf(400, 401, 403)) throw ElyLoginError("La sesión de Ely.by caducó. Vuelve a vincular la cuenta.", relink = true)
        if (!renewed.successful()) throw ElyLoginError("Ely.by no pudo renovar la sesión. Inténtalo de nuevo más tarde.")
        val session = ElySession.fromReply(renewed.json, saved.clientToken)
        if (session.profileId != saved.profileId) throw ElyLoginError("Cambió el perfil de Ely.by. Vuelve a vincular la cuenta.", relink = true)
        return session
    }

    fun invalidate(saved: ElySession): Boolean = post("invalidate", sessionData(saved)).successful()

    fun agentMetadata(): String {
        val reply = send(AuthRequest(ELY_AGENT_ENDPOINT))
        val key = reply.json.optString("signaturePublickey")
        if (!reply.successful() || reply.json.optJSONObject("meta") == null ||
            reply.json.optJSONArray("skinDomains") == null || !key.startsWith("-----BEGIN PUBLIC KEY-----")) {
            throw ElyLoginError("Ely.by no devolvió la configuración de autenticación para Minecraft.")
        }
        return Base64.getEncoder().encodeToString(reply.json.toString().toByteArray(Charsets.UTF_8))
    }

    private fun sessionData(saved: ElySession) = JSONObject().put("accessToken", saved.accessToken).put("clientToken", saved.clientToken)

    private fun post(path: String, body: JSONObject) = send(AuthRequest("$ELY_AUTH_URL/$path", body.toString(), "application/json; charset=UTF-8"))

    private fun AuthResponse.successful() = status in 200..299 && !json.has("error")
}
