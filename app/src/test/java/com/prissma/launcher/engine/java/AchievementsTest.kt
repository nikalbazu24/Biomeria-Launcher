package com.prissma.launcher.engine.java

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class AchievementsTest {
    private fun event(magic: Int = ACHIEVEMENT_MAGIC, frame: String = "goal", done: Int = 13, total: Int = 122): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use {
            it.writeInt(magic); it.writeUTF("minecraft:story/mine_diamond"); it.writeUTF(frame)
            it.writeUTF("¡Diamantes!"); it.writeUTF("Consigue diamantes."); it.writeUTF("minecraft:diamond")
            it.writeShort(done); it.writeShort(total)
            it.writeShort(2)
            it.writeUTF("create"); it.writeShort(1); it.writeShort(10)
            it.writeUTF("minecraft"); it.writeShort(12); it.writeShort(112)
        }
        return bytes.toByteArray()
    }

    @Test
    fun readsTheAdvancementTheModSent() {
        val read = requireNotNull(decodeAchievement(event()))
        assertEquals(AchievementKind.GOAL, read.kind)
        assertEquals("¡Diamantes!", read.title)
        assertEquals("minecraft:diamond", read.icon)
        assertEquals(13, read.done)
        assertEquals(1 to 10, read.byMod["create"])
        assertEquals(false, read.fromMod)
        assertEquals("Create New Age", modLabel("create_new_age"))
    }

    @Test
    fun unknownFramesAreTasksAndBadDataIsIgnored() {
        assertEquals(AchievementKind.TASK, decodeAchievement(event(frame = "mod_frame"))?.kind)
        assertNull(decodeAchievement(event(magic = 1)))
        assertNull(decodeAchievement(event(done = 5, total = 2)))
        assertNull(decodeAchievement(byteArrayOf(1, 2)))
    }
}
