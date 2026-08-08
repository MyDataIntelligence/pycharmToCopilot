package nl.ferron.copilotcontextbridge.ui

import nl.ferron.copilotcontextbridge.model.ContextCandidate

/** Stable, user-facing labels for how a repository file entered a context pack. */
internal object ContextSelectionLabels {
    fun category(candidate: ContextCandidate): String = if (candidate.pinned) "Manually selected (Pinned)" else "Automatically added"

    fun detail(candidate: ContextCandidate): String {
        if (candidate.pinned) return "Manually selected by you; kept as an individual attachment"
        val reasons =
            candidate.relations
                .map {
                    it.type.name
                        .lowercase()
                        .replace('_', ' ')
                }.distinct()
                .joinToString(", ")
                .ifBlank { "repository relation" }
        return "Automatically added; reason: $reasons"
    }
}
