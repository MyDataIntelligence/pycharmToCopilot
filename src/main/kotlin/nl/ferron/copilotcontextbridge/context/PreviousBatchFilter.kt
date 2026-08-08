package nl.ferron.copilotcontextbridge.context

import nl.ferron.copilotcontextbridge.model.ContextCandidate

/**
 * Marks files exported by an earlier batch as unavailable for automatic context selection.
 *
 * The Python analyzer already attaches this reason to current-repository files.  External and
 * archive candidates are assembled later, so applying the same rule to the combined candidate
 * list keeps Next batch semantics consistent across repository sources.  Marking rather than
 * removing candidates is intentional: the ranker places them in the omitted list and the user
 * can see why they were not selected.
 */
internal object PreviousBatchFilter {
    private const val REASON = "already exported in an earlier batch"

    fun markIgnored(
        candidates: Collection<ContextCandidate>,
        avoidPrevious: Boolean,
    ): List<ContextCandidate> =
        candidates.map { candidate ->
            if (avoidPrevious && candidate.previouslySent && !candidate.pinned) {
                candidate.copy(ignoredReason = candidate.ignoredReason ?: REASON)
            } else {
                candidate
            }
        }
}
