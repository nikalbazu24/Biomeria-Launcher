package com.prissma.launcher.data.accounts

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

internal data class MicrosoftIdentity(val playerName: String, val profileId: String)

internal class MinecraftAccounts(context: Context) {
    private val storage = MicrosoftSessionStorage(File(context.noBackupFilesDir, "java-microsoft-session"))
    private val microsoft = MicrosoftLogin()

    suspend fun linked(): MicrosoftIdentity? = withContext(Dispatchers.IO) {
        lock.withLock { storage.read()?.identity() }
    }

    suspend fun start(applicationId: String): DeviceCode = withContext(Dispatchers.IO) { microsoft.start(applicationId) }

    suspend fun authorize(code: DeviceCode): MicrosoftIdentity = withContext(Dispatchers.IO) {
        val session = microsoft.finish(code)
        lock.withLock {
            currentCoroutineContext().ensureActive()
            storage.write(session)
        }
        session.identity()
    }

    suspend fun prepareLaunch(): MicrosoftIdentity = withContext(Dispatchers.IO) {
        lock.withLock {
            val saved = storage.read() ?: throw signInRequired()
            if (saved.validFor(System.currentTimeMillis(), bufferMillis = 60_000)) return@withLock saved.identity()
            try {
                val refreshed = microsoft.refresh(saved, storage::write)
                storage.write(refreshed)
                refreshed.identity()
            } catch (error: MicrosoftLoginError) {
                if (error.relink) storage.clear()
                throw error
            }
        }
    }

    /** The game activity reads the token directly from encrypted storage in its own process. */
    suspend fun launchToken(profileId: String): String = withContext(Dispatchers.IO) {
        val saved = storage.read() ?: throw signInRequired()
        if (saved.profileId != profileId || !saved.validFor(System.currentTimeMillis())) throw signInRequired()
        saved.minecraftToken
    }

    suspend fun signOut() = withContext(Dispatchers.IO) { lock.withLock { storage.clear() } }

    private fun signInRequired() = MicrosoftLoginError("Vincula tu cuenta Microsoft en el perfil de PRISSMA y vuelve a intentarlo.")

    private companion object {
        val lock = Mutex()
    }
}

private fun MicrosoftSession.identity() = MicrosoftIdentity(name, profileId)

private class MicrosoftSessionStorage(private val file: File) {
    fun read(): MicrosoftSession? {
        val stream = try {
            file.inputStream()
        } catch (error: FileNotFoundException) {
            if (!file.exists()) return null
            throw MicrosoftLoginError("No se pudo abrir la sesión guardada. Vuelve a vincular la cuenta.", relink = true)
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
                MicrosoftSession.decode(JSONObject(plaintext.toString(Charsets.UTF_8)))
            } finally {
                plaintext.fill(0)
            }
        } catch (error: Exception) {
            throw MicrosoftLoginError("No se pudo leer la sesión guardada. Vuelve a vincular la cuenta.", relink = true)
        }
    }

    fun write(session: MicrosoftSession) {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
            check(cipher.iv.size == 12)
            val plaintext = session.encode().toString().toByteArray(Charsets.UTF_8)
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
            throw MicrosoftLoginError("Android no pudo guardar de forma segura la sesión de Minecraft.")
        }
    }

    fun clear() {
        Files.deleteIfExists(file.toPath())
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        if (!create) throw IOException("Missing session encryption key")
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "prissma-java-microsoft-v1"
    }
}
