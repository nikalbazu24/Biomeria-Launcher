package com.prissma.launcher.data.accounts

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ElyLoginTest {
    private val id = "c1d8332e3fe34836a9efe9f147bb88f5"
    private val saved = ElySession("JugadorEly", id, "old.token", "stored-client")

    @Test
    fun promptsFor2faAndSendsTheCombinedCredentialOnlyToElyBy() {
        val sent = mutableListOf<AuthRequest>()
        val login = ElyLogin { request ->
            sent.add(request)
            val data = JSONObject(request.payload!!)
            if (sent.size == 1) AuthResponse(401, JSONObject().put("error", "ForbiddenOperationException")
                .put("errorMessage", "Account protected with two factor auth."))
            else response(client = data.getString("clientToken"))
        }
        assertTrue(assertThrows(ElyLoginError::class.java) { login.signIn("tester@example.org", "sample secret", "") }.needsCode)
        val session = login.signIn(" tester@example.org ", "sample secret", "012345")
        val combined = JSONObject(sent.last().payload!!)
        assertEquals("tester@example.org", combined.getString("username"))
        assertEquals("sample secret:012345", combined.getString("password"))
        assertEquals("Minecraft", combined.getJSONObject("agent").getString("name"))
        assertTrue(sent.all { it.url == "https://authserver.ely.by/auth/authenticate" && it.bearer == null })
        assertEquals(id, session.profileId)
        assertFalse(session.encode().toString().contains("sample secret"))
        assertEquals("ely.token", ElySession.decode(session.encode()).accessToken)
    }

    @Test
    fun reusesAValidSessionAndRefreshesAnExpiredOneWithTheBoundClientToken() {
        val valid = Fixture(listOf(AuthResponse(204, JSONObject())))
        assertSame(saved, valid.login.prepare(saved))
        assertTrue(valid.calls.single().url.endsWith("/validate"))
        val expired = Fixture(listOf(AuthResponse(401, JSONObject()), response(client = saved.clientToken)))
        val refreshed = expired.login.prepare(saved)
        assertEquals("ely.token", refreshed.accessToken)
        assertEquals(id, refreshed.profileId)
        val request = expired.calls.last()
        assertTrue(request.url.endsWith("/refresh"))
        val body = JSONObject(request.payload!!)
        assertEquals(saved.accessToken, body.getString("accessToken"))
        assertEquals(saved.clientToken, body.getString("clientToken"))
        assertFalse(body.has("password"))
    }

    @Test
    fun rejectsMismatchedClientOrProfileAndKeepsTransientErrorsDistinctFromRevocation() {
        val client = Fixture(listOf(AuthResponse(401, JSONObject()), response(client = "wrong")))
        assertThrows(ElyLoginError::class.java) { client.login.prepare(saved) }
        val profile = Fixture(listOf(AuthResponse(401, JSONObject()), response(client = saved.clientToken, profileId = "b".repeat(32))))
        assertTrue(assertThrows(ElyLoginError::class.java) { profile.login.prepare(saved) }.relink)
        val unavailable = Fixture(listOf(AuthResponse(503, JSONObject().put("message", "old.token"))))
        val failure = assertThrows(ElyLoginError::class.java) { unavailable.login.prepare(saved) }
        assertFalse(failure.relink)
        assertFalse(failure.message.orEmpty().contains("old.token"))
        val expired = Fixture(listOf(AuthResponse(401, JSONObject()), AuthResponse(403, JSONObject())))
        assertTrue(assertThrows(ElyLoginError::class.java) { expired.login.prepare(saved) }.relink)
    }

    @Test
    fun usesPublicMetadataFromThePinnedEndpointAndRevokesOnlyTheSelectedSession() {
        val metadata = JSONObject().put("meta", JSONObject().put("serverName", "Ely.by"))
            .put("skinDomains", JSONArray().put("ely.by"))
            .put("signaturePublickey", "-----BEGIN PUBLIC KEY-----\nsample-public-key\n-----END PUBLIC KEY-----")
        val fixture = Fixture(listOf(AuthResponse(200, metadata), AuthResponse(204, JSONObject())))
        val decoded = Base64.getDecoder().decode(fixture.login.agentMetadata()).toString(Charsets.UTF_8)
        assertEquals("Ely.by", JSONObject(decoded).getJSONObject("meta").getString("serverName"))
        assertEquals(ELY_AGENT_ENDPOINT, fixture.calls.first().url)
        assertNull(fixture.calls.first().payload)
        assertNull(fixture.calls.first().bearer)
        assertTrue(fixture.login.invalidate(saved))
        assertTrue(fixture.calls.last().url.endsWith("/invalidate"))
        assertEquals(saved.clientToken, JSONObject(fixture.calls.last().payload!!).getString("clientToken"))
    }

    @Test
    fun keepsAccountModesAndTheirMinecraftUserTypesDistinct() {
        assertEquals(JavaAccountMode.ELY_BY, JavaAccountMode.fromProfile("ely.by"))
        assertEquals("mojang", JavaAccountMode.ELY_BY.sessionUserType)
        assertEquals(JavaAccountMode.MICROSOFT, JavaAccountMode.fromProfile("Microsoft"))
        assertEquals("msa", JavaAccountMode.MICROSOFT.sessionUserType)
        assertEquals(JavaAccountMode.OFFLINE, JavaAccountMode.fromProfile("Otro"))
        assertEquals("legacy", JavaAccountMode.OFFLINE.sessionUserType)
    }

    private class Fixture(replies: List<AuthResponse>) {
        private val remaining = ArrayDeque(replies)
        val calls = mutableListOf<AuthRequest>()
        val login = ElyLogin { request ->
            calls.add(request)
            remaining.removeFirst()
        }
    }

    private fun response(client: String, profileId: String = id) = AuthResponse(200, JSONObject()
        .put("accessToken", "ely.token").put("clientToken", client)
        .put("selectedProfile", JSONObject().put("id", profileId).put("name", "JugadorEly")))
}
