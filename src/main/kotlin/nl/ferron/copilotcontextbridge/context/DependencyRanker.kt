package nl.ferron.copilotcontextbridge.context

import nl.ferron.copilotcontextbridge.model.ContextCandidate
import nl.ferron.copilotcontextbridge.model.RankedSelection
import nl.ferron.copilotcontextbridge.model.sourceKey

object DependencyRanker {
    fun allocate(
        candidates: Collection<ContextCandidate>,
        maximumFiles: Int,
        reserveContextFile: Boolean = true,
    ): RankedSelection {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val capacity = (maximumFiles.coerceIn(1, 500) - if (reserveContextFile) 1 else 0).coerceAtLeast(0)
        val unique =
            candidates.groupBy { it.sourceKey }.values.map { group ->
                group.maxWithOrNull(comparator())!!
            }
        val pinned = unique.filter { it.pinned }.sortedWith(comparator())
        if (pinned.size > capacity) {
            errors +=
                "Cannot generate context: ${pinned.size} files are manually pinned, but only $capacity source-file slots are available."
        }
        pinned.filter { it.secretWarning != null }.forEach {
            errors += "Pinned file ${it.relativePath} requires explicit secret confirmation: ${it.secretWarning}."
        }
        pinned.filter { it.ignoredReason != null }.forEach {
            errors += "Pinned file ${it.relativePath} is excluded: ${it.ignoredReason}."
        }
        val includedPinned = pinned.take(capacity)
        val automatic =
            unique
                .filterNot { it.pinned || it.ignoredReason != null || it.secretWarning != null }
                .sortedWith(comparator())
        val slots = (capacity - includedPinned.size).coerceAtLeast(0)
        val includedAutomatic = automatic.take(slots)
        val includedPaths = (includedPinned + includedAutomatic).mapTo(hashSetOf()) { it.sourceKey }
        val omitted = unique.filterNot { it.sourceKey in includedPaths }.sortedWith(comparator())
        if (omitted.isNotEmpty()) warnings += "${omitted.size} dependency candidates were omitted or blocked."
        return RankedSelection(includedPinned + includedAutomatic, omitted, errors, warnings)
    }

    fun comparator(): Comparator<ContextCandidate> =
        compareByDescending<ContextCandidate> { it.pinned }
            .thenBy { it.previouslySent }
            .thenByDescending { it.score }
            .thenBy { it.depth }
            .thenBy { confidenceOrder(it.confidence) }
            .thenBy { it.size }
            .thenBy { it.repositoryId.lowercase() }
            .thenBy { it.relativePath.lowercase() }

    private fun confidenceOrder(confidence: nl.ferron.copilotcontextbridge.model.RelationConfidence): Int =
        when (confidence) {
            nl.ferron.copilotcontextbridge.model.RelationConfidence.CONFIRMED -> 0
            nl.ferron.copilotcontextbridge.model.RelationConfidence.INFERRED -> 1
            nl.ferron.copilotcontextbridge.model.RelationConfidence.DYNAMIC -> 2
            nl.ferron.copilotcontextbridge.model.RelationConfidence.UNRESOLVED -> 3
        }
}
