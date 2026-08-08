package nl.ferron.copilotcontextbridge.ui

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

internal object BatchFileCategoryModel {
    fun choices(
        pinnedCount: Int,
        automaticCount: Int,
    ) = listOf(
        BatchFileCategoryChoice(BatchFileCategory.PINNED, pinnedCount),
        BatchFileCategoryChoice(BatchFileCategory.AUTOMATIC, automaticCount),
    )

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
