package nl.ferron.copilotcontextbridge.ui

import com.intellij.ui.components.JBTabbedPane
import javax.swing.JTabbedPane

/** Creates the fixed single-row navigation used by the More workspace. */
internal fun createStableDetailTabs() =
    JBTabbedPane().apply {
        tabLayoutPolicy = JTabbedPane.SCROLL_TAB_LAYOUT
    }
