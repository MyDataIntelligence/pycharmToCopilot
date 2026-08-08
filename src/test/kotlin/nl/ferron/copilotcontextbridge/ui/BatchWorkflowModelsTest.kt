package nl.ferron.copilotcontextbridge.ui

import junit.framework.TestCase
import nl.ferron.copilotcontextbridge.settings.AppSettings
import nl.ferron.copilotcontextbridge.settings.Defaults

class BatchWorkflowModelsTest : TestCase() {
    fun testDropdownKeepsPreviousNonEmptyCategory() {
        assertEquals(
            BatchFileCategory.AUTOMATIC,
            BatchFileCategoryModel.selectedCategory(BatchFileCategory.AUTOMATIC, pinnedCount = 4, automaticCount = 39),
        )
        assertEquals(
            BatchFileCategory.PINNED,
            BatchFileCategoryModel.selectedCategory(BatchFileCategory.PINNED, pinnedCount = 4, automaticCount = 39),
        )
    }

    fun testDropdownFallsBackToNonEmptyCategory() {
        assertEquals(
            BatchFileCategory.AUTOMATIC,
            BatchFileCategoryModel.selectedCategory(BatchFileCategory.PINNED, pinnedCount = 0, automaticCount = 39),
        )
        assertEquals(listOf("Pinned (0)", "Automatic (39)"), BatchFileCategoryModel.choices(0, 39).map { it.toString() })
    }

    fun testKickoffPromptNamesIndexSkillSessionAndFutureBatches() {
        val skill = AppSettings.PromptSkillState("general", "General change", "", "Prompt")
        val prompt = BatchKickoffPromptBuilder.build(Defaults.KICKOFF_PROMPT_TEMPLATE, "session-abc", 3, skill)

        assertTrue(prompt.contains("00_REPO_CONTEXT.md first"))
        assertTrue(prompt.contains("General change"))
        assertTrue(prompt.contains("batch 3 in session session-abc"))
        assertTrue(prompt.contains("More batches may follow"))
        assertTrue(prompt.contains("Wait until I confirm"))
    }
}
