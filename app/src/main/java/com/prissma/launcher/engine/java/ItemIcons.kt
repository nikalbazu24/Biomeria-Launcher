package com.prissma.launcher.engine.java

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LightingColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * The game's own item icons, read from the Minecraft and mod jars the player already has.
 * Nothing from Mojang is bundled in the APK. An item follows the game's chain:
 * items/<name>.json → its model → the model's textures. Flat items use layer0 (and layer1);
 * blocks are drawn as a small cube from their top and side textures. Unknown items return null
 * so the panel can fall back to the item name.
 */
internal class ItemIcons(private val archives: List<File>) {
    private val zips = LinkedHashMap<File, ZipFile>()
    private var namespaces: Map<String, List<ZipFile>>? = null
    private val cache = object : LinkedHashMap<String, Bitmap?>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap?>) = size > 600
    }

    @Synchronized
    fun cached(id: String): Bitmap? = cache[id]

    @Synchronized
    fun icon(id: String): Bitmap? {
        if (id.isBlank()) return null
        if (cache.containsKey(id)) return cache[id]
        val result = runCatching { render(id) }.getOrNull()
        cache[id] = result
        return result
    }

    private fun index(): Map<String, List<ZipFile>> {
        namespaces?.let { return it }
        val found = LinkedHashMap<String, MutableList<ZipFile>>()
        // Mods first, so a mod's own texture wins over a vanilla one with the same path.
        archives.filter { it.isFile && it.name.endsWith(".jar") }.forEach { file ->
            val zip = runCatching { ZipFile(file) }.getOrNull() ?: return@forEach
            val seen = HashSet<String>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val name = entries.nextElement().name
                if (!name.startsWith("assets/")) continue
                val namespace = name.substring(7).substringBefore('/', "")
                if (namespace.isNotEmpty() && seen.add(namespace)) found.getOrPut(namespace) { ArrayList() }.add(zip)
            }
            if (seen.isEmpty()) zip.close() else zips[file] = zip
        }
        return found.also { namespaces = it }
    }

    private fun read(namespace: String, path: String): ByteArray? {
        val owners = index()[namespace] ?: return null
        for (zip in owners) {
            val entry = zip.getEntry("assets/$namespace/$path") ?: continue
            return zip.getInputStream(entry).use { it.readBytes() }
        }
        return null
    }

    private fun json(namespace: String, path: String): JSONObject? =
        read(namespace, path)?.let { runCatching { JSONObject(it.toString(Charsets.UTF_8)) }.getOrNull() }

    private fun render(id: String): Bitmap? {
        val (namespace, name) = split(id)
        val modelRef = json(namespace, "items/$name.json")?.let(::firstModel) ?: "$namespace:item/$name"
        val textures = HashMap<String, String>()
        val parents = ArrayList<String>()
        var current: String? = modelRef
        var depth = 0
        while (current != null && depth++ < 12) {
            val (modelNamespace, modelPath) = split(current)
            if (modelPath.startsWith("builtin/")) { parents += modelPath; break }
            val model = json(modelNamespace, "models/$modelPath.json") ?: break
            model.optJSONObject("textures")?.let { map ->
                map.keys().forEach { key -> if (!textures.containsKey(key)) textures[key] = map.getString(key) }
            }
            current = model.optString("parent", "").takeIf { it.isNotEmpty() }
            current?.let { parents += split(it).second }
        }
        fun texture(vararg keys: String): Bitmap? {
            for (key in keys) {
                var value = textures[key] ?: continue
                var hops = 0
                while (value.startsWith("#") && hops++ < 8) value = textures[value.substring(1)] ?: break
                if (value.startsWith("#")) continue
                val (textureNamespace, texturePath) = split(value)
                val bytes = read(textureNamespace, "textures/$texturePath.png") ?: continue
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inScaled = false })
                    ?: continue
                // Animated textures stack their frames vertically: keep the first one.
                val frame = if (bitmap.height > bitmap.width) Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.width) else bitmap
                return tinted(name, key, frame)
            }
            return null
        }
        val flat = textures.containsKey("layer0") || parents.any { it.startsWith("item/generated") || it.startsWith("item/handheld") }
        if (flat) {
            val base = texture("layer0") ?: return null
            return flatIcon(base, texture("layer1"))
        }
        val top = texture("top", "end", "all", "up", "texture", "particle") ?: return null
        val side = texture("side", "all", "north", "south", "texture", "particle") ?: top
        val front = texture("front", "side", "all", "south", "texture", "particle") ?: side
        return cubeIcon(top, side, front)
    }

    private fun tinted(item: String, key: String, bitmap: Bitmap): Bitmap {
        // Grass and leaves are grey in the files; the game colours them by biome.
        val color = when {
            item.endsWith("leaves") && "cherry" !in item && "azalea" !in item && "pale" !in item -> 0xFF59AE30.toInt()
            item == "grass_block" && key in listOf("top", "up", "end") -> 0xFF79C05A.toInt()
            item in listOf("short_grass", "tall_grass", "fern", "large_fern", "vine", "lily_pad") -> 0xFF79C05A.toInt()
            else -> return bitmap
        }
        val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(out).drawBitmap(bitmap, 0f, 0f, Paint().apply { colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY) })
        return out
    }

    private fun flatIcon(base: Bitmap, overlay: Bitmap?): Bitmap {
        val out = Bitmap.createBitmap(ICON, ICON, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint().apply { isFilterBitmap = false }
        val target = android.graphics.Rect(0, 0, ICON, ICON)
        canvas.drawBitmap(base, null, target, paint)
        overlay?.let { canvas.drawBitmap(it, null, target, paint) }
        return out
    }

    private fun cubeIcon(top: Bitmap, left: Bitmap, right: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(ICON, ICON, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val s = ICON / 48f
        fun face(texture: Bitmap, shade: Int, vararg corners: Float) {
            val w = texture.width.toFloat()
            val h = texture.height.toFloat()
            val matrix = Matrix()
            matrix.setPolyToPoly(floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h), 0, corners.map { it * s }.toFloatArray(), 0, 4)
            val paint = Paint().apply {
                isFilterBitmap = false
                if (shade != 0xFFFFFF) colorFilter = LightingColorFilter(shade, 0)
            }
            canvas.drawBitmap(texture, matrix, paint)
        }
        face(top, 0xFFFFFF, 2f, 13f, 24f, 2f, 46f, 13f, 24f, 24f)
        face(left, 0xCCCCCC, 2f, 13f, 24f, 24f, 24f, 46f, 2f, 35f)
        face(right, 0x999999, 24f, 24f, 46f, 13f, 46f, 35f, 24f, 46f)
        return out
    }

    companion object {
        private const val ICON = 48

        @Volatile
        var current: ItemIcons? = null
            private set

        /** Called by the game activity with the jars Minecraft runs from and the instance's mods. */
        fun install(classpath: String, gameDir: File) {
            val mods = File(gameDir, "mods").listFiles { file -> file.name.endsWith(".jar") }?.sortedBy { it.name }.orEmpty()
            val libraries = classpath.split(File.pathSeparatorChar).filter { it.isNotBlank() }.map(::File)
            current = ItemIcons(mods + libraries)
        }

        internal fun split(id: String): Pair<String, String> =
            if (':' in id) id.substringBefore(':') to id.substringAfter(':') else "minecraft" to id

        /** First "model" reference in a 1.21.4 item definition, following conditions and selections. */
        internal fun firstModel(node: Any?): String? = when (node) {
            is JSONObject -> {
                val model = node.opt("model")
                if (model is String && node.optString("type").let { it.isEmpty() || it.endsWith("model") }) model
                else {
                    var found: String? = null
                    val keys = node.keys()
                    while (found == null && keys.hasNext()) found = firstModel(node.opt(keys.next()))
                    found
                }
            }
            is JSONArray -> (0 until node.length()).firstNotNullOfOrNull { firstModel(node.opt(it)) }
            else -> null
        }
    }
}

/** The icon for an item id, loaded off the main thread; null while loading or when unknown. */
@Composable
internal fun rememberItemIcon(id: String): ImageBitmap? {
    val icons = ItemIcons.current
    val icon by produceState(icons?.cached(id)?.asImageBitmap(), id, icons) {
        if (icons != null && id.isNotBlank()) value = withContext(Dispatchers.IO) { icons.icon(id)?.asImageBitmap() }
    }
    return icon
}
