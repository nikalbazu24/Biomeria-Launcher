package com.biomeria.launcher.engine.java

import java.io.DataInputStream
import java.io.File
import java.io.IOException
import kotlin.math.floor

internal const val TERRAIN_MAGIC = 0x50544d31
internal const val TERRAIN_TILE_SIZE = 16
private const val WORLD_LIMIT = 30_000_000
private val terrainScope = Regex("[0-9a-f]{32}")

internal data class TerrainPosition(val scope: String, val x: Double, val y: Double, val z: Double, val yaw: Float)
internal data class TerrainChunk(val x: Int, val z: Int)
internal data class TerrainTile(val modifiedAt: Long, val pixels: IntArray)
internal data class TerrainCamera(val x: Double, val z: Double, val span: Float = 112f, val follow: Boolean = true) {
    fun pan(dx: Double, dz: Double) = copy(
        x = (x + dx).coerceIn(-WORLD_LIMIT.toDouble(), WORLD_LIMIT.toDouble()),
        z = (z + dz).coerceIn(-WORLD_LIMIT.toDouble(), WORLD_LIMIT.toDouble()),
        follow = false
    )

    fun zoom(factor: Float) = copy(span = (span * factor).coerceIn(32f, 320f))

    fun chunks(): List<TerrainChunk> {
        val west = floor((x - span / 2) / TERRAIN_TILE_SIZE).toInt()
        val east = floor((x + span / 2) / TERRAIN_TILE_SIZE).toInt()
        val north = floor((z - span / 2) / TERRAIN_TILE_SIZE).toInt()
        val south = floor((z + span / 2) / TERRAIN_TILE_SIZE).toInt()
        return (north..south).flatMap { z -> (west..east).map { x -> TerrainChunk(x, z) } }
    }
}

internal fun DualScreenBridge.readTerrainPosition(now: Long = System.currentTimeMillis()): TerrainPosition? {
    val file = File(directory, "terrain-state")
    if (file.length() != 66L || now - file.lastModified() !in 0..1800) return null
    return try {
        DataInputStream(file.inputStream().buffered()).use(::decodeTerrainPosition)
    } catch (_: IOException) {
        null
    }
}

internal fun decodeTerrainPosition(input: DataInputStream): TerrainPosition? {
    if (input.readInt() != TERRAIN_MAGIC) return null
    val scope = input.readUTF()
    val x = input.readDouble()
    val y = input.readDouble()
    val z = input.readDouble()
    val yaw = input.readFloat()
    if (!terrainScope.matches(scope) || x !in -WORLD_LIMIT.toDouble()..WORLD_LIMIT.toDouble() ||
        z !in -WORLD_LIMIT.toDouble()..WORLD_LIMIT.toDouble() || !y.isFinite() || !yaw.isFinite()
    ) return null
    return TerrainPosition(scope, x, y, z, yaw)
}

internal fun readTerrainTiles(
    root: File, scope: String, chunks: List<TerrainChunk>, previous: Map<TerrainChunk, TerrainTile>
): Map<TerrainChunk, TerrainTile> {
    if (!terrainScope.matches(scope)) return emptyMap()
    val directory = File(root, scope)
    return buildMap {
        for (chunk in chunks) {
            val file = File(directory, "${chunk.x}_${chunk.z}.tile")
            val stamp = file.lastModified()
            if (stamp == 0L) continue
            val old = previous[chunk]
            if (old?.modifiedAt == stamp) {
                put(chunk, old)
                continue
            }
            if (file.length() != 1024L) continue
            try {
                val pixels = DataInputStream(file.inputStream().buffered()).use { stream ->
                    IntArray(TERRAIN_TILE_SIZE * TERRAIN_TILE_SIZE) { stream.readInt() }
                }
                put(chunk, TerrainTile(stamp, pixels))
            } catch (_: IOException) {
                // Wait for the next snapshot when the world closes during a read.
            }
        }
    }
}
