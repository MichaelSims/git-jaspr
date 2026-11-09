package sims.michael.gitjaspr

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ImplicitThemeTest {

    @Test
    fun `terminal without NO_COLOR uses the default theme`() {
        assertEquals(
            "default",
            implicitThemeName(noColorRequested = false, stdoutIsTerminal = true),
        )
    }

    @Test
    fun `NO_COLOR selects mono even on a terminal`() {
        assertEquals("mono", implicitThemeName(noColorRequested = true, stdoutIsTerminal = true))
    }

    @Test
    fun `non-terminal stdout selects mono`() {
        assertEquals("mono", implicitThemeName(noColorRequested = false, stdoutIsTerminal = false))
    }

    @Test
    fun `NO_COLOR counts only when non-empty`() {
        assertTrue(isNoColorRequested("1"))
        assertFalse(isNoColorRequested(""))
        assertFalse(isNoColorRequested(null))
    }
}
