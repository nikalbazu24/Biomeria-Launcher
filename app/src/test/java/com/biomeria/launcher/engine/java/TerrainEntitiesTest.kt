package com.biomeria.launcher.engine.java

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class TerrainEntitiesTest {
    private fun encode(magic: Int = ENTITY_MAGIC, block: DataOutputStream.() -> Unit): DataInputStream {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { it.writeInt(magic); it.block() }
        return DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
    }

    private fun DataOutputStream.entity(kind: Int, id: String, name: String, x: Double, z: Double) {
        writeByte(kind); writeUTF(id); writeUTF(name); writeDouble(x); writeFloat(64f); writeDouble(z)
    }

    @Test
    fun readsCreaturesSentByTheMod() {
        val read = decodeTerrainEntities(encode {
            writeShort(2)
            entity(1, "minecraft:zombie", "", 10.5, -3.0)
            entity(0, "minecraft:player", "Nico", 0.0, 0.0)
        })
        assertEquals(2, read.size)
        assertEquals(EntityKind.HOSTILE, read[0].kind)
        assertEquals("Zombi", read[0].label)
        assertEquals("Nico", read[1].label)
    }

    @Test
    fun rejectsForeignOrImpossibleData() {
        assertTrue(decodeTerrainEntities(encode(magic = 7) { writeShort(0) }).isEmpty())
        assertTrue(decodeTerrainEntities(encode { writeShort(500) }).isEmpty())
        assertTrue(decodeTerrainEntities(encode { writeShort(1); entity(2, "minecraft:cow", "", Double.NaN, 0.0) }).isEmpty())
    }

    @Test
    fun namesModMobsFromTheirIdAndKnowsVanillaFaces() {
        assertEquals("Frost Wraith", entityName("mymod:frost_wraith"))
        assertEquals(8, faceRegion("minecraft:creeper")?.x)
        assertNull(faceRegion("mymod:frost_wraith"))
    }
}
