package com.biomeria.launcher.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesTest {
    @Test
    fun displaysReleaseHeadingsAndBulletsWithoutMarkdownDelimiters() {
        val source = "### Mando físico\n\n- Cursor visible en el menú.\n* B para volver.\n"
        assertEquals("Mando físico\n\n• Cursor visible en el menú.\n• B para volver.", readableReleaseNotes(source))
    }

    @Test
    fun keepsLongNotesAndTheirLineBreaks() {
        val details = "Mejoras de controles y navegación. ".repeat(12).trim()
        val source = "Primera línea\n\n$details\n\nÚltima línea"
        assertEquals(source, readableReleaseNotes(source))
    }
}
