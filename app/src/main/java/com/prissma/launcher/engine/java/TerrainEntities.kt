package com.prissma.launcher.engine.java

import androidx.compose.ui.graphics.Color
import java.io.DataInputStream
import java.io.File
import java.io.IOException

internal const val ENTITY_MAGIC = 0x50454e31
private const val ENTITY_LIMIT = 80
private const val ENTITY_LIMIT_COORD = 30_000_000.0

internal enum class EntityKind(val label: String, val color: Color) {
    PLAYER("Jugador", Color(0xFF4FB3D9)),
    HOSTILE("Hostil", Color(0xFFE5484D)),
    PASSIVE("Pacífico", Color(0xFF7BC74D)),
    OTHER("Otro", Color(0xFFF5E663))
}

/** One creature near the player, sent by the Biomeria mod in worlds this device hosts. */
internal data class TerrainEntity(
    val kind: EntityKind,
    val id: String,
    val name: String,
    val x: Double,
    val y: Float,
    val z: Double
) {
    val label: String get() = name.ifBlank { entityName(id) }
}

internal fun DualScreenBridge.readTerrainEntities(now: Long = System.currentTimeMillis()): List<TerrainEntity> {
    val file = File(directory, "terrain-entities")
    if (!file.isFile || file.length() > 64_000L || now - file.lastModified() !in 0..1800) return emptyList()
    return try {
        DataInputStream(file.inputStream().buffered()).use(::decodeTerrainEntities)
    } catch (_: IOException) {
        emptyList()
    }
}

internal fun decodeTerrainEntities(input: DataInputStream): List<TerrainEntity> {
    if (input.readInt() != ENTITY_MAGIC) return emptyList()
    val count = input.readUnsignedShort()
    if (count > ENTITY_LIMIT) return emptyList()
    return List(count) {
        val kind = EntityKind.entries.getOrNull(input.readUnsignedByte()) ?: EntityKind.OTHER
        val id = input.readUTF()
        val name = input.readUTF()
        val x = input.readDouble()
        val y = input.readFloat()
        val z = input.readDouble()
        if (!x.isFinite() || !z.isFinite() || !y.isFinite() ||
            kotlin.math.abs(x) > ENTITY_LIMIT_COORD || kotlin.math.abs(z) > ENTITY_LIMIT_COORD) return emptyList()
        TerrainEntity(kind, id, name, x, y, z)
    }
}

private val spanishNames = mapOf(
    "zombie" to "Zombi", "husk" to "Zombi momificado", "drowned" to "Ahogado", "skeleton" to "Esqueleto",
    "stray" to "Esqueleto glacial", "wither_skeleton" to "Esqueleto del Wither", "creeper" to "Creeper",
    "spider" to "Araña", "cave_spider" to "Araña de cueva", "enderman" to "Enderman", "witch" to "Bruja",
    "slime" to "Slime", "phantom" to "Phantom", "pillager" to "Saqueador", "vindicator" to "Vindicador",
    "evoker" to "Evocador", "piglin" to "Piglin", "zombified_piglin" to "Piglin zombi", "blaze" to "Blaze",
    "ghast" to "Ghast", "silverfish" to "Lepisma", "guardian" to "Guardián", "warden" to "Warden",
    "pig" to "Cerdo", "cow" to "Vaca", "sheep" to "Oveja", "chicken" to "Gallina", "horse" to "Caballo",
    "wolf" to "Lobo", "cat" to "Gato", "fox" to "Zorro", "rabbit" to "Conejo", "bee" to "Abeja",
    "villager" to "Aldeano", "zombie_villager" to "Aldeano zombi", "iron_golem" to "Gólem de hierro",
    "wandering_trader" to "Comerciante", "squid" to "Calamar", "bat" to "Murciélago", "goat" to "Cabra",
    "llama" to "Llama", "parrot" to "Loro", "turtle" to "Tortuga", "axolotl" to "Ajolote", "frog" to "Rana"
)

/** Readable name for an entity id: Spanish for common vanilla mobs, otherwise the id's own words. */
internal fun entityName(id: String): String {
    val (namespace, path) = ItemIcons.split(id)
    if (namespace == "minecraft") spanishNames[path]?.let { return it }
    return path.split('_').filter(String::isNotEmpty).joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        .ifBlank { "Criatura" }
}

/** Where a mob's face sits in its 1.21.4 texture: path under textures/, then x, y, width, height. */
internal data class FaceRegion(val texture: String, val x: Int, val y: Int, val width: Int, val height: Int)

internal fun faceRegion(id: String): FaceRegion? {
    val (namespace, path) = ItemIcons.split(id)
    if (namespace != "minecraft") return null
    fun head(texture: String, width: Int = 8, height: Int = 8) = FaceRegion("entity/$texture.png", 8, 8, width, height)
    return when (path) {
        "zombie", "husk", "drowned" -> head("zombie/$path")
        "skeleton", "stray", "wither_skeleton" -> head("skeleton/$path")
        "creeper" -> head("creeper/creeper")
        "enderman" -> head("enderman/enderman")
        "piglin", "zombified_piglin", "piglin_brute" -> head("piglin/$path", width = 10)
        "villager" -> head("villager/villager", height = 10)
        "zombie_villager" -> head("zombie_villager/zombie_villager", height = 10)
        "witch" -> head("witch", height = 10)
        "pillager", "vindicator", "evoker" -> head("illager/$path", height = 10)
        "player" -> head("player/wide/steve")
        "pig" -> head("pig/pig")
        "spider", "cave_spider" -> FaceRegion("entity/spider/$path.png", 40, 12, 8, 8)
        "cow" -> FaceRegion("entity/cow/cow.png", 6, 6, 8, 8)
        "sheep" -> FaceRegion("entity/sheep/sheep.png", 8, 8, 6, 6)
        "chicken" -> FaceRegion("entity/chicken.png", 3, 3, 4, 6)
        else -> null
    }
}
