package com.prissma.launcher.engine.bedrock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BedrockLauncherTest {
    private val minecraft = BedrockInstallation(BedrockEdition.MINECRAFT, "1.26.10", canOpen = true)
    private val trial = BedrockInstallation(BedrockEdition.TRIAL, null, canOpen = true)

    @Test
    fun prefersFullMinecraftWhenItCanOpen() {
        val installed = listOf(trial, minecraft).associateBy { it.edition }
        val selected = findBedrock(installed::get)
        assertEquals(minecraft, selected)
        assertEquals("Minecraft Bedrock · 1.26.10", selected?.label)
        assertNull(selected.launchProblem())
    }

    @Test
    fun fallsBackToTrialWhenFullMinecraftIsAbsentOrUnavailable() {
        val disabled = minecraft.copy(canOpen = false)
        for (installed in listOf(listOf(trial), listOf(trial, disabled))) {
            val byEdition = installed.associateBy { it.edition }
            val selected = findBedrock(byEdition::get)
            assertEquals(trial, selected)
            assertEquals("Minecraft Trial", selected?.label)
            assertNull(selected.launchProblem())
        }
    }

    @Test
    fun reportsAnUnavailableInstallationDifferentlyFromAnAbsentPackage() {
        val disabled = minecraft.copy(canOpen = false)
        val unavailable = findBedrock { edition -> disabled.takeIf { it.edition == edition } }
        assertEquals(disabled, unavailable)
        assertTrue(unavailable.launchProblem().orEmpty().contains("habilitado"))

        val absent = findBedrock { null }
        assertNull(absent)
        assertTrue(absent.launchProblem().orEmpty().startsWith("Instala"))
    }
}
