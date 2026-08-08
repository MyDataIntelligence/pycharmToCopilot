package nl.ferron.copilotcontextbridge.ui

import nl.ferron.copilotcontextbridge.model.ContextCandidate
import nl.ferron.copilotcontextbridge.model.displayRepository
import nl.ferron.copilotcontextbridge.settings.AppSettings
import nl.ferron.copilotcontextbridge.settings.KickoffPromptTemplateRenderer

internal enum class BatchFileCategory {
    PINNED,
    AUTOMATIC,
}

internal data class BatchFileCategoryChoice(
    val category: BatchFileCategory,
    val count: Int,
) {
    override fun toString(): String =
        when (category) {
            BatchFileCategory.PINNED -> "Pinned ($count)"
            BatchFileCategory.AUTOMATIC -> "Automatic ($count)"
        }
}

/** A row in the compact batch selector. Category rows are followed by every file in that category. */
internal data class BatchFileDropdownItem(
    val category: BatchFileCategory,
    val candidate: ContextCandidate? = null,
    val invalidPath: String? = null,
    val count: Int = 0,
) {
    val isCategory: Boolean get() = candidate == null && invalidPath == null

    override fun toString(): String {
        if (isCategory) {
            val label = if (category == BatchFileCategory.PINNED) "Pinned" else "Automatic"
            return "$label ($count)"
        }
        val path =
            candidate?.let {
                if (it.repositoryId.isBlank()) it.relativePath else "${it.displayRepository}: ${it.relativePath}"
            } ?: invalidPath.orEmpty()
        val reason =
            candidate
                ?.relations
                ?.firstOrNull()
                ?.type
                ?.name
                ?.lowercase()
                ?.replace('_', ' ')
                ?: "invalid pin"
        return "$path  [$reason]"
    }
}

internal object BatchFileCategoryModel {
    fun choices(
        pinnedCount: Int,
        automaticCount: Int,
    ) = listOf(
        BatchFileCategoryChoice(BatchFileCategory.PINNED, pinnedCount),
        BatchFileCategoryChoice(BatchFileCategory.AUTOMATIC, automaticCount),
    )

    /** Builds one flat popup model: category selectors plus every file (no hidden '+N more' rows). */
    fun dropdownItems(
        category: BatchFileCategory,
        pinned: List<ContextCandidate>,
        automatic: List<ContextCandidate>,
        invalidPinnedPaths: List<String> = emptyList(),
    ): List<BatchFileDropdownItem> {
        val candidates = if (category == BatchFileCategory.PINNED) pinned else automatic
        val rows = candidates.map { BatchFileDropdownItem(category, candidate = it) }
        val invalid =
            if (category == BatchFileCategory.PINNED) {
                invalidPinnedPaths.map { BatchFileDropdownItem(BatchFileCategory.PINNED, invalidPath = it) }
            } else {
                emptyList()
            }
        val pinnedCount = pinned.size + invalidPinnedPaths.size
        val automaticCount = automatic.size
        val activeHeader =
            if (category == BatchFileCategory.PINNED) {
                BatchFileDropdownItem(BatchFileCategory.PINNED, count = pinnedCount)
            } else {
                BatchFileDropdownItem(BatchFileCategory.AUTOMATIC, count = automaticCount)
            }
        val alternateHeader =
            if (category == BatchFileCategory.PINNED) {
                BatchFileDropdownItem(BatchFileCategory.AUTOMATIC, count = automaticCount)
            } else {
                BatchFileDropdownItem(BatchFileCategory.PINNED, count = pinnedCount)
            }
        return listOf(activeHeader, alternateHeader) + rows + invalid
    }

    fun selectedCategory(
        previous: BatchFileCategory?,
        pinnedCount: Int,
        automaticCount: Int,
    ): BatchFileCategory =
        when {
            previous == BatchFileCategory.PINNED && pinnedCount > 0 -> BatchFileCategory.PINNED
            previous == BatchFileCategory.AUTOMATIC && automaticCount > 0 -> BatchFileCategory.AUTOMATIC
            pinnedCount > 0 -> BatchFileCategory.PINNED
            automaticCount > 0 -> BatchFileCategory.AUTOMATIC
            else -> previous ?: BatchFileCategory.PINNED
        }
}

internal object BatchKickoffPromptBuilder {
    fun build(
        template: String,
        sessionId: String,
        batchNumber: Int,
        skill: AppSettings.PromptSkillState,
    ): String = KickoffPromptTemplateRenderer.render(template, sessionId, batchNumber, skill.name)
}
