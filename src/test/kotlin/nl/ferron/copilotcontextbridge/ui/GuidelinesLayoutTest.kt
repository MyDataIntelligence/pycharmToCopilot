package nl.ferron.copilotcontextbridge.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidelinesLayoutTest {
    @Test
    fun `narrow tool window leaves editor usable`() {
        val divider = GuidelinesLayout.dividerLocationForWidth(440)

        assertEquals(167, divider)
        assertTrue(440 - divider >= 220)
    }

    @Test
    fun `wide tool window scales divider with available space`() {
        val divider = GuidelinesLayout.dividerLocationForWidth(900)

        assertEquals(342, divider)
        assertTrue(divider in 150..680)
    }
}
