package com.biomeria.launcher.engine.java

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Where the character's skin comes from. */
internal enum class SkinSource(val label: String) {
    ACCOUNT("Mi cuenta"), EXPLORER("Exploradora"), MINER("Minero"), FOREST("Bosque"), NETHER("Nether"), CUSTOM("Mi imagen")
}

internal enum class CapeChoice(val label: String) {
    ACCOUNT("De mi cuenta"), NONE("Sin capa"), FOREST("Bosque"), OCEAN("Océano")
}

/** The look chosen in Biomeria's character editor, shared by every Java world. */
internal data class CharacterLook(
    val source: SkinSource = SkinSource.ACCOUNT,
    val slim: Boolean = false,
    val cape: CapeChoice = CapeChoice.ACCOUNT,
    val hat: Boolean = true,
    val jacket: Boolean = true,
    val sleeves: Boolean = true,
    val pants: Boolean = true
) {
    /** True when the game should be left exactly as Minecraft sets it up. */
    val untouched: Boolean get() = this == CharacterLook()

    fun config(): String = buildString {
        appendLine("arms=" + if (slim) "slim" else "wide")
        appendLine("cape=" + when (cape) {
            CapeChoice.ACCOUNT -> "account"
            CapeChoice.NONE -> "none"
            else -> "custom"
        })
        appendLine("hat=" + if (hat) 1 else 0)
        appendLine("jacket=" + if (jacket) 1 else 0)
        appendLine("sleeves=" + if (sleeves) 1 else 0)
        appendLine("pants=" + if (pants) 1 else 0)
    }
}

/** Saved look plus the player's own uploaded skin. */
internal object CharacterLooks {
    private const val PREFS = "biomeria_character"
    private val state = MutableStateFlow(CharacterLook())
    private var loaded = false
    val look: StateFlow<CharacterLook> get() = state

    /** Why the last uploaded image was refused, shown in the editor. */
    val importMessage = MutableStateFlow<String?>(null)

    /** Bumped whenever the uploaded image changes, so previews redraw. */
    val customRevision = MutableStateFlow(0)

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        state.value = CharacterLook(
            runCatching { SkinSource.valueOf(prefs.getString("source", null) ?: "") }.getOrDefault(SkinSource.ACCOUNT),
            prefs.getBoolean("slim", false),
            runCatching { CapeChoice.valueOf(prefs.getString("cape", null) ?: "") }.getOrDefault(CapeChoice.ACCOUNT),
            prefs.getBoolean("hat", true), prefs.getBoolean("jacket", true),
            prefs.getBoolean("sleeves", true), prefs.getBoolean("pants", true)
        ).let { if (it.source == SkinSource.CUSTOM && !customFile(context).isFile) it.copy(source = SkinSource.ACCOUNT) else it }
    }

    fun update(context: Context, look: CharacterLook) {
        state.value = look
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("source", look.source.name).putBoolean("slim", look.slim).putString("cape", look.cape.name)
            .putBoolean("hat", look.hat).putBoolean("jacket", look.jacket)
            .putBoolean("sleeves", look.sleeves).putBoolean("pants", look.pants).apply()
    }

    fun customFile(context: Context) = File(context.filesDir, "biomeria-character/skin.png")

    /** Saves an uploaded skin. Returns an explanation in Spanish when the image cannot be used. */
    fun importSkin(context: Context, uri: Uri): String? {
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > MAX_SKIN_BYTES) return "La imagen pesa demasiado para ser una skin."
                }
                out.toByteArray()
            } ?: return "No se pudo abrir la imagen."
        } catch (error: IOException) {
            return "No se pudo leer la imagen."
        } catch (error: SecurityException) {
            return "No hay permiso para leer esa imagen."
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return "Ese archivo no es una imagen PNG."
        val skin = normalizeSkin(decoded) ?: return "La skin debe medir 64×64 (o 64×32, el formato antiguo)."
        val file = customFile(context)
        file.parentFile?.mkdirs()
        val staged = File(file.parentFile, "skin.png.part")
        staged.outputStream().use { skin.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (!staged.renameTo(file)) {
            staged.delete()
            return "No se pudo guardar la skin."
        }
        customRevision.value++
        update(context, state.value.copy(source = SkinSource.CUSTOM))
        return null
    }

    /** The 64×64 skin Biomeria makes for this look, or null to keep the account's own skin. */
    fun skinFor(context: Context, look: CharacterLook): Bitmap? = when (look.source) {
        SkinSource.ACCOUNT -> null
        SkinSource.CUSTOM -> BitmapFactory.decodeFile(customFile(context).path)?.let(::normalizeSkin)
        else -> presetSkin(look.source)
    }

    /** Sends the look to the running game through the dual-screen mod. */
    fun send(context: Context, bridge: DualScreenBridge, look: CharacterLook) {
        val folder = File(bridge.directory, "skin")
        val marker = File(bridge.directory, "skin-generation")
        if (look.untouched) {
            marker.delete()
            return
        }
        folder.mkdirs()
        File(folder, "skin.png").delete()
        File(folder, "cape.png").delete()
        skinFor(context, look)?.let { writePng(File(folder, "skin.png"), it) }
        capeFor(look.cape)?.let { writePng(File(folder, "cape.png"), it) }
        File(folder, "config").writeText(look.config(), Charsets.UTF_8)
        val staged = File(bridge.directory, "skin-generation.part")
        staged.writeText(System.currentTimeMillis().toString(), Charsets.US_ASCII)
        if (!staged.renameTo(marker)) staged.delete()
    }

    /** The skin the account really has, as reported by the game, for drawing "Mi cuenta". */
    fun accountSkin(context: Context, bridge: DualScreenBridge?): AccountSkin? {
        val report = bridge?.let { File(it.directory, "skin-account") }?.takeIf { it.isFile && it.length() < 2048 } ?: return null
        val lines = report.readLines(Charsets.UTF_8)
        val url = lines.getOrNull(0).orEmpty()
        val texture = lines.getOrNull(1).orEmpty()
        val slim = lines.getOrNull(2) == "slim"
        val bitmap = if (url.startsWith("https://") || url.startsWith("http://")) download(context, url)
        else {
            val (namespace, path) = ItemIcons.split(texture)
            ItemIcons.current?.resource(namespace, path)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }
        return bitmap?.let(::normalizeSkin)?.let { AccountSkin(it, slim) }
    }

    private fun download(context: Context, address: String): Bitmap? {
        val digest = MessageDigest.getInstance("SHA-1").digest(address.toByteArray()).joinToString("") { "%02x".format(it) }
        val cached = File(context.cacheDir, "biomeria-skins/$digest.png")
        if (cached.isFile) return BitmapFactory.decodeFile(cached.path)
        return try {
            val connection = URL(address).openConnection() as HttpURLConnection
            connection.connectTimeout = 6000
            connection.readTimeout = 6000
            connection.instanceFollowRedirects = true
            val bytes = connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > MAX_SKIN_BYTES) return null
                }
                out.toByteArray()
            }
            connection.disconnect()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also {
                cached.parentFile?.mkdirs()
                cached.writeBytes(bytes)
            }
        } catch (error: IOException) {
            null
        } catch (error: IllegalArgumentException) {
            null
        }
    }

    private fun writePng(file: File, bitmap: Bitmap) {
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private const val MAX_SKIN_BYTES = 256 * 1024
}

internal data class AccountSkin(val bitmap: Bitmap, val slim: Boolean)

/** Accepts modern 64×64 skins and upgrades the old 64×32 layout by copying the right limbs to the left. */
internal fun normalizeSkin(source: Bitmap): Bitmap? {
    if (source.width != 64 || (source.height != 64 && source.height != 32)) return null
    if (source.height == 64) return source.copy(Bitmap.Config.ARGB_8888, false)
    val skin = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(64 * 32)
    source.getPixels(pixels, 0, 64, 0, 0, 64, 32)
    skin.setPixels(pixels, 0, 64, 0, 0, 64, 32)
    fun copy(fromX: Int, fromY: Int, toX: Int, toY: Int) {
        val block = IntArray(16 * 16)
        source.getPixels(block, 0, 16, fromX, fromY, 16, 16)
        skin.setPixels(block, 0, 16, toX, toY, 16, 16)
    }
    copy(0, 16, 16, 48) // right leg -> left leg
    copy(40, 16, 32, 48) // right arm -> left arm
    return skin
}

// ---- Original preset skins and capes, drawn by Biomeria (no Mojang art). ----

private data class Palette(val hair: Int, val skin: Int, val shirt: Int, val pants: Int)

private fun palette(source: SkinSource) = when (source) {
    SkinSource.MINER -> Palette(0xFF2A2A30.toInt(), 0xFFB57B55.toInt(), 0xFFC9A23A.toInt(), 0xFF3A3A44.toInt())
    SkinSource.FOREST -> Palette(0xFF7A4A1E.toInt(), 0xFFE8B48E.toInt(), 0xFF4C9A3F.toInt(), 0xFF5A3A22.toInt())
    SkinSource.NETHER -> Palette(0xFFB04A2A.toInt(), 0xFF8D5A3B.toInt(), 0xFF7A2E1C.toInt(), 0xFF2A1A1A.toInt())
    else -> Palette(0xFF5A3A22.toInt(), 0xFFD9A27A.toInt(), 0xFF3F8F8C.toInt(), 0xFF2E3A6B.toInt())
}

internal fun presetColors(source: SkinSource): List<Int> = palette(source).let { listOf(it.hair, it.skin, it.shirt, it.pants) }

private fun shade(color: Int, factor: Float): Int {
    val r = ((color shr 16 and 255) * factor).toInt().coerceIn(0, 255)
    val g = ((color shr 8 and 255) * factor).toInt().coerceIn(0, 255)
    val b = ((color and 255) * factor).toInt().coerceIn(0, 255)
    return (color.toLong() and 0xFF000000L).toInt() or (r shl 16) or (g shl 8) or b
}

private class SkinCanvas(val pixels: IntArray = IntArray(64 * 64)) {
    fun set(x: Int, y: Int, color: Int) {
        if (x in 0 until 64 && y in 0 until 64) pixels[y * 64 + x] = color
    }

    /** Fills a model box in Minecraft's UV layout; [row] colours each row of the side faces from the top. */
    fun box(u: Int, v: Int, w: Int, h: Int, d: Int, row: (Int) -> Int) {
        for (x in 0 until w) for (y in 0 until d) {
            set(u + d + x, v + y, row(0))
            set(u + d + w + x, v + y, row(h - 1))
        }
        val faces = listOf(u to d, u + d to w, u + d + w to d, u + d + w + d to w)
        for ((index, face) in faces.withIndex()) {
            val (start, width) = face
            val dim = if (index == 1) 1f else .86f
            for (x in 0 until width) for (y in 0 until h) {
                val base = row(y)
                val grain = if ((x * 7 + y * 3 + index) % 5 == 0) .92f else 1f
                set(start + x, v + d + y, shade(base, dim * grain))
            }
        }
    }
}

internal fun presetSkin(source: SkinSource): Bitmap {
    val p = palette(source)
    val canvas = SkinCanvas()
    canvas.box(0, 0, 8, 8, 8) { if (it <= 1) p.hair else p.skin }
    // Hair over the back and sides of the head.
    for (x in 24 until 32) for (y in 8 until 16) canvas.set(x, y, shade(p.hair, if ((x + y) % 3 == 0) .9f else 1f))
    for (x in 0 until 8) for (y in 8 until 11) canvas.set(x, y, p.hair)
    for (x in 16 until 24) for (y in 8 until 11) canvas.set(x, y, p.hair)
    for (x in 8 until 16) for (y in 0 until 8) canvas.set(x, y, shade(p.hair, if ((x * y) % 4 == 0) .88f else 1f))
    // Face: eyes, brows and a small smile.
    val white = 0xFFF2F5EF.toInt()
    val eye = 0xFF2B2B33.toInt()
    canvas.set(9, 12, white); canvas.set(10, 12, eye); canvas.set(13, 12, eye); canvas.set(14, 12, white)
    canvas.set(10, 11, shade(p.hair, .8f)); canvas.set(13, 11, shade(p.hair, .8f))
    canvas.set(11, 14, shade(p.skin, .7f)); canvas.set(12, 14, shade(p.skin, .7f))
    canvas.box(16, 16, 8, 12, 4) { if (it >= 10) shade(p.pants, .8f) else p.shirt }
    for (y in 20 until 32) canvas.set(23, y, shade(p.shirt, .78f))
    canvas.box(40, 16, 4, 12, 4) { if (it < 4) p.shirt else p.skin }
    canvas.box(32, 48, 4, 12, 4) { if (it < 4) p.shirt else p.skin }
    canvas.box(0, 16, 4, 12, 4) { if (it >= 10) shade(p.pants, .55f) else p.pants }
    canvas.box(16, 48, 4, 12, 4) { if (it >= 10) shade(p.pants, .55f) else p.pants }
    return Bitmap.createBitmap(canvas.pixels, 64, 64, Bitmap.Config.ARGB_8888)
}

/** Biomeria's own capes, in the 64×32 layout Minecraft uses for capes and elytra. */
internal fun capeFor(choice: CapeChoice): Bitmap? {
    val (base, accent) = when (choice) {
        CapeChoice.FOREST -> 0xFF4C9A3F.toInt() to 0xFF7BC74D.toInt()
        CapeChoice.OCEAN -> 0xFF2E86B5.toInt() to 0xFF4FB3D9.toInt()
        else -> return null
    }
    val pixels = IntArray(64 * 32) { index ->
        val x = index % 64
        val y = index / 64
        when {
            choice == CapeChoice.FOREST && (x * 3 + y * 5) % 11 < 2 -> shade(base, .78f)
            choice == CapeChoice.OCEAN && (y + (x / 3) % 2) % 4 == 0 -> accent
            (x + y) % 7 == 0 -> accent
            else -> base
        }
    }
    // A dark seam around the outer face of the cape.
    for (x in 1 until 11) { pixels[1 * 64 + x] = 0xFF0B0E11.toInt(); pixels[16 * 64 + x] = 0xFF0B0E11.toInt() }
    return Bitmap.createBitmap(pixels, 64, 32, Bitmap.Config.ARGB_8888)
}

/**
 * Draws the character flat from the front or back at one texture pixel per pixel (16×32),
 * straight from the skin's UV layout, with the cape over the back.
 */
internal fun renderFigure(skin: Bitmap, slim: Boolean, back: Boolean, look: CharacterLook, cape: Bitmap?): Bitmap {
    val out = Bitmap.createBitmap(16, 32, Bitmap.Config.ARGB_8888)
    val arm = if (slim) 3 else 4
    fun part(u: Int, v: Int, w: Int, h: Int, x: Int, y: Int, overlay: Boolean = false) {
        if (u + w > skin.width || v + h > skin.height) return
        for (dx in 0 until w) for (dy in 0 until h) {
            val color = skin.getPixel(u + dx, v + dy)
            if (overlay && (color ushr 24) < 16) continue
            out.setPixel(x + dx, y + dy, if (overlay) color else color or 0xFF000000.toInt())
        }
    }
    if (!back) {
        part(8, 8, 8, 8, 4, 0)
        part(20, 20, 8, 12, 4, 8)
        part(44, 20, arm, 12, 4 - arm, 8) // right arm, on the viewer's left
        part(36, 52, arm, 12, 12, 8)
        part(4, 20, 4, 12, 4, 20)
        part(20, 52, 4, 12, 8, 20)
        if (look.hat) part(40, 8, 8, 8, 4, 0, true)
        if (look.jacket) part(20, 36, 8, 12, 4, 8, true)
        if (look.sleeves) { part(44, 36, arm, 12, 4 - arm, 8, true); part(52, 52, arm, 12, 12, 8, true) }
        if (look.pants) { part(4, 36, 4, 12, 4, 20, true); part(4, 52, 4, 12, 8, 20, true) }
    } else {
        part(24, 8, 8, 8, 4, 0)
        part(32, 20, 8, 12, 4, 8)
        part(40 + arm, 52, arm, 12, 4 - arm, 8) // left arm, on the viewer's left from behind
        part(48 + arm, 20, arm, 12, 12, 8)
        part(28, 52, 4, 12, 4, 20)
        part(12, 20, 4, 12, 8, 20)
        if (look.hat) part(56, 8, 8, 8, 4, 0, true)
        if (look.jacket) part(32, 36, 8, 12, 4, 8, true)
        if (look.sleeves) { part(60 - (4 - arm), 52, arm, 12, 4 - arm, 8, true); part(48 + arm, 36, arm, 12, 12, 8, true) }
        if (look.pants) { part(12, 52, 4, 12, 4, 20, true); part(12, 36, 4, 12, 8, 20, true) }
        if (cape != null && look.cape != CapeChoice.NONE) {
            for (dx in 0 until 10) for (dy in 0 until 16) {
                val color = cape.getPixel(1 + dx, 1 + dy)
                if ((color ushr 24) > 16) out.setPixel(3 + dx, 8 + dy, color or 0xFF000000.toInt())
            }
        }
    }
    return out
}
