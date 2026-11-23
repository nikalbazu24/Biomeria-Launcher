package com.biomeria.launcher.data.accounts

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MicrosoftLoginTest {
    private val client = "2b2470ad-5589-44e7-a7eb-606a1b1e57d2"
    private val id = "c1d8332e3fe34836a9efe9f147bb88f5"

    @Test
    fun exchangesDeviceCodeAndRespectsThePollingInterval() = runBlocking {
        val fixture = Fixture(listOf(code(), error(400, "authorization_pending"), error(400, "slow_down"),
            oauth(), xbox(), xsts(), minecraft(), profile()))
        val started = fixture.login.start(client)
        assertEquals("A123-B456", started.userCode)
        val session = fixture.login.finish(started)

        assertEquals(listOf(4000L, 4000L, 9000L), fixture.waits)
        assertEquals("JugadorJava", session.name)
        assertEquals(id, session.profileId)
        assertEquals("game.token", session.minecraftToken)
        assertEquals(3_601_000L, session.expiresAt)
        assertTrue(fixture.calls.first().payload.orEmpty().contains("scope=XboxLive.signin+offline_access"))
        assertTrue(fixture.calls[1].payload.orEmpty().contains("device_code=private-code"))
        assertEquals("d=ms.token", JSONObject(fixture.calls[4].payload!!).getJSONObject("Properties").getString("RpsTicket"))
        assertEquals("rp://api.minecraftservices.com/", JSONObject(fixture.calls[5].payload!!).getString("RelyingParty"))
        assertEquals("XBL3.0 x=42;xsts.token", JSONObject(fixture.calls[6].payload!!).getString("identityToken"))
        assertEquals("https://api.minecraftservices.com/minecraft/profile", fixture.calls.last().url)
        assertEquals("game.token", fixture.calls.last().bearer)
        assertEquals(session.profileId, MicrosoftSession.decode(session.encode()).profileId)
    }

    @Test
    fun refusesUntrustedVerificationLinksAndExpiredCodes() {
        val untrusted = Fixture(listOf(code("https://microsoft.com@malicious.example/device")))
        assertThrows(MicrosoftLoginError::class.java) { untrusted.login.start(client) }
        val expired = Fixture(listOf(code(ttl = 3)))
        val started = expired.login.start(client)
        assertThrows(MicrosoftLoginError::class.java) { runBlocking { expired.login.finish(started) } }
        assertEquals(1, expired.calls.size)
    }

    @Test
    fun persistsRefreshRotationEvenIfTheProfileRequestTemporarilyFails() {
        val fixture = Fixture(listOf(oauth("rotated"), xbox(), xsts(), minecraft(), AuthResponse(503, JSONObject())))
        val current = MicrosoftSession(client, "JugadorJava", id, "old.game.token", "old-refresh", 0)
        var rotated: MicrosoftSession? = null
        assertThrows(MicrosoftLoginError::class.java) { fixture.login.refresh(current) { rotated = it } }

        assertEquals("rotated", rotated?.refreshToken)
        assertEquals(current.profileId, rotated?.profileId)
        assertEquals(current.minecraftToken, rotated?.minecraftToken)
        assertTrue(fixture.calls.first().payload.orEmpty().contains("grant_type=refresh_token"))
        assertTrue(fixture.calls.first().payload.orEmpty().contains("refresh_token=old-refresh"))
    }

    @Test
    fun neverAcceptsARefreshedSessionForAnotherProfile() {
        val fixture = Fixture(listOf(oauth("rotated"), xbox(), xsts(), minecraft(), profile("f".repeat(32))))
        val current = MicrosoftSession(client, "JugadorJava", id, "old.game.token", "old-refresh", 0)
        val failure = assertThrows(MicrosoftLoginError::class.java) { fixture.login.refresh(current) {} }
        assertTrue(failure.relink)
    }

    @Test
    fun handlesRejectedClientsAndMissingMinecraftProfilesWithoutExposingTokens() {
        val rejected = Fixture(listOf(error(400, "unauthorized_client")))
        assertTrue(assertThrows(MicrosoftLoginError::class.java) { rejected.login.start(client) }
            .message.orEmpty().contains("ID de aplicación"))
        val missing = Fixture(listOf(code(), oauth(), xbox(), xsts(), minecraft(), AuthResponse(404,
            JSONObject().put("message", "game.token"))))
        val pending = missing.login.start(client)
        val failure = assertThrows(MicrosoftLoginError::class.java) { runBlocking { missing.login.finish(pending) } }
        assertTrue(failure.message.orEmpty().contains("no tiene un perfil"))
        assertFalse(failure.message.orEmpty().contains("game.token"))
    }

    private class Fixture(responses: List<AuthResponse>) {
        private val remaining = ArrayDeque(responses)
        private var elapsed = 0L
        val calls = mutableListOf<AuthRequest>()
        val waits = mutableListOf<Long>()
        val login = MicrosoftLogin(send = { request -> calls.add(request); remaining.removeFirst() }, wallTime = { 1000L },
            elapsedSeconds = { elapsed }, wait = { waits.add(it); elapsed += it / 1000 })
    }

    private fun code(url: String = "https://www.microsoft.com/link", ttl: Int = 300) = reply(
        """{"user_code":"A123-B456","device_code":"private-code","verification_uri":"$url","interval":4,"expires_in":$ttl}""")
    private fun oauth(refresh: String = "refresh.token") = reply("""{"access_token":"ms.token","refresh_token":"$refresh"}""")
    private fun xbox() = reply("""{"Token":"xbox.token"}""")
    private fun xsts() = reply("""{"Token":"xsts.token","DisplayClaims":{"xui":[{"uhs":"42"}]}}""")
    private fun minecraft() = reply("""{"access_token":"game.token","expires_in":3600}""")
    private fun profile(profileId: String = id) = reply("""{"name":"JugadorJava","id":"$profileId"}""")
    private fun reply(json: String) = AuthResponse(200, JSONObject(json))
    private fun error(status: Int, error: String) = AuthResponse(status, JSONObject().put("error", error))
}
