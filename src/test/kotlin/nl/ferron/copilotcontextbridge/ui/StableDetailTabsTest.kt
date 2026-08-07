package nl.ferron.copilotcontextbridge.ui

import junit.framework.TestCase
import javax.swing.JTabbedPane

class StableDetailTabsTest : TestCase() {
    fun testMoreNavigationRemainsInOneStableRow() {
        val tabs = createStableDetailTabs()

        assertEquals(JTabbedPane.SCROLL_TAB_LAYOUT, tabs.tabLayoutPolicy)
    }
}
