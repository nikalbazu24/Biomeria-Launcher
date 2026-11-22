package com.prissma.launcher.engine.java

import java.io.DataOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TerrainMapTest {
    @get:Rule val temporary = TemporaryFolder()

    private val scope = "74ed699fd7e7c3b9720f8aaadbe5ebb6"

    @Test
    fun readsOnlyFreshPositionWithSafeWorldScopeAndFiniteCoordinates() {
        val file = temporary.newFile("terrain-state")
        fun write(world: String, x: Double = -12.5, yaw: Float = 175f) {
            DataOutputStream(file.outputStream()).use {
                it.writeInt(TERRAIN_MAGIC)
                it.writeUTF(world)
                it.writeDouble(x)
                it.writeDouble(64.0)
                it.writeDouble(300.75)
                it.writeFloat(yaw)
            }
        }
        val bridge = DualScreenBridge(temporary.root)
        write(scope)
        assertEquals(TerrainPosition(scope, -12.5, 64.0, 300.75, 175f), bridge.readTerrainPosition(file.lastModified()))
        assertNull(bridge.readTerrainPosition(file.lastModified() + 2000))
        write("../" + "a".repeat(29))
        assertNull(bridge.readTerrainPosition(file.lastModified()))
        write(scope, yaw = Float.NaN)
        assertNull(bridge.readTerrainPosition(file.lastModified()))
        write(scope, x = 30_000_001.0)
        assertNull(bridge.readTerrainPosition(file.lastModified()))
    }

    @Test
    fun tilesAreScopedPerWorldAndBrokenTilesAreIgnored() {
        val tile = File(temporary.newFolder(scope), "-1_0.tile")
        DataOutputStream(tile.outputStream()).use { out -> repeat(256) { out.writeInt(0xff334455.toInt()) } }
        val chunk = TerrainChunk(-1, 0)
        val requests = listOf(chunk, TerrainChunk(0, 0))
        assertEquals(1, readTerrainTiles(temporary.root, scope, requests, emptyMap()).size)
        val tiles = readTerrainTiles(temporary.root, scope, requests, emptyMap())
        assertEquals(0xff334455.toInt(), tiles.getValue(chunk).pixels.last())
        assertSame(tiles[chunk], readTerrainTiles(temporary.root, scope, requests, tiles)[chunk])
        assertTrue(readTerrainTiles(temporary.root, "0".repeat(32), requests, tiles).isEmpty())
        tile.writeBytes(byteArrayOf(1))
        tile.setLastModified(tiles.getValue(chunk).modifiedAt + 1000)
        assertTrue(readTerrainTiles(temporary.root, scope, requests, tiles).isEmpty())
    }

    @Test
    fun cameraHandlesNegativeChunksAndStopsFollowingWhenPanned() {
        val initial = TerrainCamera(-.1, 0.0, 32f)
        assertTrue(TerrainChunk(-2, 0) in initial.chunks())
        assertTrue(TerrainChunk(0, 1) in initial.chunks())
        val panned = initial.pan(-32.0, 16.0)
        assertFalse(panned.follow)
        assertEquals(-32.1, panned.x, .001)
        assertEquals(16.0, panned.z, .001)
        assertEquals(32f, panned.zoom(.1f).span)
        assertEquals(320f, panned.zoom(100f).span)
        assertTrue(panned.zoom(100f).chunks().size <= 21 * 21)
    }
}
