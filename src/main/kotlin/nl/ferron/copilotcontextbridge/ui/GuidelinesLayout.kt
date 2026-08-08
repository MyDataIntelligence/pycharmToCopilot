package nl.ferron.copilotcontextbridge.ui

import kotlin.math.roundToInt

/** Responsive sizing rules for the repository-source and global-guidelines editors. */
internal object GuidelinesLayout {
    private const val RESIZE_WEIGHT = 0.38
    private const val MINIMUM_LEFT = 150
    private const val MINIMUM_RIGHT = 220

    fun resizeWeight(): Double = RESIZE_WEIGHT

    fun dividerLocationForWidth(width: Int): Int {
        if (width <= 0) return 0
        val available = width.coerceAtLeast(MINIMUM_LEFT + MINIMUM_RIGHT)
        return (available * RESIZE_WEIGHT)
            .roundToInt()
            .coerceIn(MINIMUM_LEFT, available - MINIMUM_RIGHT)
    }
}
