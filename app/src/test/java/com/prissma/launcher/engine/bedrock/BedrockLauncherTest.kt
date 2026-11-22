package com.prissma.launcher.engine.bedrock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BedrockLauncherTest {
    private val minecraft = BedrockInstallation(BedrockEdition.MINECRAFT.packageName, "Minecraft Bedrock", "1.26.10", canOpen = true)
    private val trial = BedrockInstallation(BedrockEdition.TRIAL.packageName, "Minecraft Trial", null, canOpen = true)
    private val variant = BedrockInstallation("local.game.copy", "Minecraft (copia)", null, canOpen = true)

    @Test
    fun prefersFullMinecraftWhenItCanOpen() {
        val installed = listOf(trial, minecraft).associateBy { it.packageName }
        val selected = findBedrock(null, installed::get)
        assertEquals(minecraft, selected)
        assertEquals("Minecraft Bedrock · 1.26.10", selected?.label)
        assertNull(selected.launchProblem())
    }

    @Test
    fun fallsBackToTrialWhenFullMinecraftIsAbsentOrUnavailable() {
        val disabled = minecraft.copy(canOpen = false)
        for (installed in listOf(listOf(trial), listOf(trial, disabled))) {
            val byPackage = installed.associateBy { it.packageName }
            val selected = findBedrock(null, byPackage::get)
            assertEquals(trial, selected)
            assertEquals("Minecraft Trial", selected?.label)
            assertNull(selected.launchProblem())
        }
    }

    @Test
    fun reportsAnUnavailableInstallationDifferentlyFromAnAbsentPackage() {
        val disabled = minecraft.copy(canOpen = false)
        val unavailable = findBedrock(null) { name -> disabled.takeIf { it.packageName == name } }
        assertEquals(disabled, unavailable)
        assertTrue(unavailable.launchProblem().orEmpty().contains("habilitado"))

        val absent = findBedrock(null) { null }
        assertNull(absent)
        assertTrue(absent.launchProblem().orEmpty().contains("perfil"))
    }

    @Test
    fun explicitlySelectedAppTakesPriorityAndFallsBackIfRemoved() {
        val installed = listOf(trial, minecraft, variant).associateBy { it.packageName }
        assertEquals(variant, findBedrock(variant.packageName, installed::get))
        assertEquals(minecraft, findBedrock(variant.packageName, (installed - variant.packageName)::get))
        assertEquals(minecraft, findBedrock(null, installed::get))
    }

    @Test
    fun pickerRecognizesMinecraftNamesAndOmitsUnrelatedLauncherApps() {
        assertTrue(isBedrockCandidate(variant.packageName, "  minecraft (copia)"))
        assertTrue(isBedrockCandidate(trial.packageName, "Demo"))
        assertFalse(isBedrockCandidate("example.editor", "Add-ons para Minecraft"))
        assertFalse(isBedrockCandidate("example.games", "Minecrafting Tools"))
        assertFalse(isBedrockCandidate("com.example.calendar", "Calendario"))
    }
}
