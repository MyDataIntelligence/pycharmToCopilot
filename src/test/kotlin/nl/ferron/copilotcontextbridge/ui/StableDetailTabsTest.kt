package nl.ferron.copilotcontextbridge.ui

import junit.framework.TestCase
import javax.swing.JLabel

class StableDetailTabsTest : TestCase() {
    fun testMoreNavigationUsesStableCardsWithoutAVisibleTabStrip() {
        val deck = createStableDetailTabs()
        deck.addTab("More", JLabel("more"))
        deck.addTab("Prompt skills", JLabel("prompts"))

        assertEquals(listOf("More", "Prompt skills"), deck.destinationTitles)
        deck.selectedIndex = 1
        assertEquals(1, deck.selectedIndex)
        assertEquals(2, deck.componentCount)
    }
}
