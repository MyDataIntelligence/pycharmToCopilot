package nl.ferron.copilotcontextbridge.ui

import java.awt.CardLayout
import java.awt.Component
import javax.swing.JPanel

/**
 * Tab-compatible card deck for More destinations.
 *
 * Navigation is intentionally owned by the labelled More cards. A visible Swing tab strip becomes
 * unreadable at tool-window width and may reorder or collapse its labels, so it must not be rendered.
 */
internal class StableDetailDeck : JPanel(CardLayout()) {
    private val titles = mutableListOf<String>()

    var selectedIndex: Int = 0
        set(value) {
            require(value in titles.indices) { "Detail destination index is out of range: $value" }
            field = value
            (layout as CardLayout).show(this, value.toString())
            revalidate()
            repaint()
        }

    val destinationTitles: List<String>
        get() = titles.toList()

    fun addTab(
        title: String,
        component: Component,
    ) {
        val index = titles.size
        titles += title
        component.accessibleContext.accessibleName = title
        add(component, index.toString())
        if (index == 0) (layout as CardLayout).show(this, "0")
    }
}

internal fun createStableDetailTabs() = StableDetailDeck()
