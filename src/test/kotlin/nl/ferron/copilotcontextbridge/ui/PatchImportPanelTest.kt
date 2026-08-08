package nl.ferron.copilotcontextbridge.ui

import junit.framework.TestCase

class PatchImportPanelTest : TestCase() {
    fun testApplyIsDisabledWhileSelectedConflictHasNoResolution() {
        assertFalse(canApplySelected(amount = 2, unresolved = 1))
        assertTrue(canApplySelected(amount = 2, unresolved = 0))
        assertFalse(canApplySelected(amount = 0, unresolved = 0))
    }
}
