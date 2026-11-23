package com.biomeria.launcher.data.accounts

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class MinecraftAccounts(context: Context) {
    private val microsoftStorage = EncryptedSessionFile(File(context.noBackupFilesDir, "java-microsoft-session"),
        "biomeria-java-microsoft-v1", MicrosoftSession.Companion::decode, MicrosoftSession::encode)
    private val elyStorage = EncryptedSessionFile(File(context.noBackupFilesDir, "java-ely-session"),
        "biomeria-java-ely-v1", ElySession.Companion::decode, ElySession::encode)
    private val microsoft = MicrosoftLogin()
    private val ely = ElyLogin()

    suspend fun elyAgentMetadata(): String = withContext(Dispatchers.IO) { ely.agentMetadata() }

    suspend fun linked(mode: JavaAccountMode): JavaAccountIdentity? = withContext(Dispatchers.IO) {
        lock.withLock {
            when (mode) {
                JavaAccountMode.MICROSOFT -> microsoftStorage.read()?.identity()
                JavaAccountMode.ELY_BY -> elyStorage.read()?.identity()
                JavaAccountMode.OFFLINE -> null
            }
        }
    }

    suspend fun start(applicationId: String): DeviceCode = withContext(Dispatchers.IO) { microsoft.start(applicationId) }

    suspend fun authorize(code: DeviceCode): JavaAccountIdentity = withContext(Dispatchers.IO) {
        val session = microsoft.finish(code)
        lock.withLock {
            currentCoroutineContext().ensureActive()
            microsoftStorage.write(session)
        }
        session.identity()
    }

    suspend fun authorizeEly(username: String, password: String, oneTimeCode: String): JavaAccountIdentity = withContext(Dispatchers.IO) {
        val session = ely.signIn(username, password, oneTimeCode)
        lock.withLock {
            currentCoroutineContext().ensureActive()
            elyStorage.write(session)
        }
        session.identity()
    }

    suspend fun prepareLaunch(mode: JavaAccountMode): JavaAccountIdentity? = withContext(Dispatchers.IO) {
        lock.withLock {
            when (mode) {
                JavaAccountMode.MICROSOFT -> prepareMicrosoft()
                JavaAccountMode.ELY_BY -> prepareEly()
                JavaAccountMode.OFFLINE -> null
            }
        }
    }

    private fun prepareMicrosoft(): JavaAccountIdentity {
        val saved = microsoftStorage.read() ?: throw signInRequired(JavaAccountMode.MICROSOFT)
        if (saved.validFor(System.currentTimeMillis(), bufferMillis = 60_000)) return saved.identity()
        try {
            val refreshed = microsoft.refresh(saved, microsoftStorage::write)
            microsoftStorage.write(refreshed)
            return refreshed.identity()
        } catch (error: MicrosoftLoginError) {
            if (error.relink) microsoftStorage.clear()
            throw error
        }
    }

    private fun prepareEly(): JavaAccountIdentity {
        val saved = elyStorage.read() ?: throw signInRequired(JavaAccountMode.ELY_BY)
        try {
            val refreshed = ely.prepare(saved)
            if (refreshed !== saved) elyStorage.write(refreshed)
            return refreshed.identity()
        } catch (error: ElyLoginError) {
            if (error.relink) elyStorage.clear()
            throw error
        }
    }

    /** The game activity reads the token directly from encrypted storage in its own process. */
    suspend fun launchToken(mode: JavaAccountMode, profileId: String): String = withContext(Dispatchers.IO) {
        val token = when (mode) {
            JavaAccountMode.MICROSOFT -> microsoftStorage.read()?.takeIf {
                it.profileId == profileId && it.validFor(System.currentTimeMillis())
            }?.minecraftToken
            JavaAccountMode.ELY_BY -> elyStorage.read()?.takeIf { it.profileId == profileId }?.accessToken
            JavaAccountMode.OFFLINE -> "0"
        }
        token ?: throw signInRequired(mode)
    }

    suspend fun signOut() = withContext(Dispatchers.IO) { lock.withLock { microsoftStorage.clear() } }

    suspend fun signOutEly(): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val saved = try { elyStorage.read() } catch (error: AccountError) { null }
            val revoked = try { saved == null || ely.invalidate(saved) } catch (error: IOException) { false }
            elyStorage.clear()
            revoked
        }
    }

    private fun signInRequired(mode: JavaAccountMode) = AccountError("Vincula tu cuenta de ${mode.label} en el perfil de Biomeria.")

    private companion object {
        val lock = Mutex()
    }
}

private fun MicrosoftSession.identity() = JavaAccountIdentity(name, profileId)
private fun ElySession.identity() = JavaAccountIdentity(name, profileId)

private class EncryptedSessionFile<T>(
    private val file: File,
    private val keyAlias: String,
    private val decode: (JSONObject) -> T,
    private val encode: (T) -> JSONObject
) {
    fun read(): T? {
        val stream = try {
            file.inputStream()
        } catch (error: FileNotFoundException) {
            if (!file.exists()) return null
            throw AccountError("No se pudo abrir la sesión guardada. Vuelve a vincular la cuenta.")
        }
        try {
            val bytes = stream.use {
                if (it.channel.size() > 131072) throw IOException("Invalid encrypted session size")
                it.readBytes()
            }
            if (bytes.size !in 30..131072 || bytes[0] != 1.toByte()) throw IOException("Invalid encrypted session")
            val nonce = bytes.copyOfRange(1, 13)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, nonce))
            val plaintext = cipher.doFinal(bytes, 13, bytes.size - 13)
            return try {
                decode(JSONObject(plaintext.toString(Charsets.UTF_8)))
            } finally {
                plaintext.fill(0)
            }
        } catch (error: Exception) {
            throw AccountError("No se pudo leer la sesión guardada. Vuelve a vincular la cuenta.")
        }
    }

    fun write(session: T) {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
            check(cipher.iv.size == 12)
            val plaintext = encode(session).toString().toByteArray(Charsets.UTF_8)
            val encrypted = try {
                cipher.doFinal(plaintext)
            } finally {
                plaintext.fill(0)
            }
            // Atomic replacement lets the game's separate process read a complete session.
            val staged = Files.createTempFile(file.parentFile!!.toPath(), "java-session-", ".tmp")
            try {
                FileOutputStream(staged.toFile()).use { output ->
                    output.write(1)
                    output.write(cipher.iv)
                    output.write(encrypted)
                    output.fd.sync()
                }
                Files.move(staged, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(staged)
            }
        } catch (error: Exception) {
            throw AccountError("Android no pudo guardar de forma segura la sesión de Minecraft.")
        }
    }

    fun clear() {
        Files.deleteIfExists(file.toPath())
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing
        if (!create) throw IOException("Missing session encryption key")
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }
}
