package com.biomeria.launcher.data

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File

/**
 * Minecraft's menu music in the launcher. It plays the tracks from the game files Biomeria already
 * downloaded for Java (the asset index), so nothing copyrighted ships inside the APK. Without a
 * downloaded Java version there is simply no music.
 */
object MenuMusic {
    private const val PREFS = "biomeria_music"
    private const val ENABLED = "enabled"
    private const val VOLUME = "volume"
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var tracks: List<File> = emptyList()
    private var lastTrack: File? = null
    private var wanted = false
    private var appContext: Context? = null

    fun enabled(context: Context) = prefs(context).getBoolean(ENABLED, true)
    fun volume(context: Context) = prefs(context).getFloat(VOLUME, .5f)

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(ENABLED, value).apply()
        if (value) start(context) else stop()
    }

    fun setVolume(context: Context, value: Float) {
        prefs(context).edit().putFloat(VOLUME, value.coerceIn(0f, 1f)).apply()
        player?.setVolume(value, value)
    }

    /** True when there are tracks to play (a Java version was downloaded). */
    fun available(context: Context): Boolean = findTracks(context).isNotEmpty()

    /** Call from onResume: plays (or resumes) if the player turned music on. */
    fun start(context: Context) {
        appContext = context.applicationContext
        wanted = true
        if (!enabled(context)) return
        val current = player
        if (current != null) {
            if (!current.isPlaying) runCatching { current.start() }
            return
        }
        Thread {
            val found = findTracks(context.applicationContext)
            handler.post {
                tracks = found
                if (wanted && player == null && found.isNotEmpty()) playNext()
            }
        }.start()
    }

    /** Call from onPause: the game or another app is in front. */
    fun pause() {
        wanted = false
        handler.removeCallbacksAndMessages(null)
        player?.takeIf { it.isPlaying }?.let { runCatching { it.pause() } }
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }

    private fun playNext() {
        val context = appContext ?: return
        if (!wanted || !enabled(context) || tracks.isEmpty()) return
        val next = (tracks - setOfNotNull(lastTrack)).ifEmpty { tracks }.random()
        lastTrack = next
        player?.release()
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setDataSource(next.absolutePath)
                val volume = volume(context)
                setVolume(volume, volume)
                setOnCompletionListener {
                    // A short silence between songs, like the game does.
                    handler.postDelayed({ playNext() }, 20_000L)
                }
                setOnErrorListener { _, _, _ -> handler.postDelayed({ playNext() }, 2_000L); true }
                prepare()
                start()
            }
        }.getOrNull()
    }

    private fun findTracks(context: Context): List<File> {
        val assets = File(context.filesDir, "minecraft/assets")
        val index = File(assets, "indexes").listFiles { file -> file.extension == "json" }
            ?.maxByOrNull { it.lastModified() } ?: return emptyList()
        return runCatching {
            val objects = JSONObject(index.readText()).getJSONObject("objects")
            objects.keys().asSequence()
                .filter { it.startsWith("minecraft/sounds/music/menu/") && it.endsWith(".ogg") }
                .mapNotNull { name ->
                    val hash = objects.getJSONObject(name).optString("hash")
                    File(assets, "objects/" + hash.take(2) + "/" + hash).takeIf { hash.length > 2 && it.isFile }
                }.toList()
        }.getOrDefault(emptyList())
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
