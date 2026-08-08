package nl.ferron.copilotcontextbridge.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryReviewPromptTest {
    @Test
    fun reviewFollowsImplementationSkillsAndCoversReuseAndGuidelines() {
        val skills = AppSettings.defaultPromptSkills()
        val review = skills.first { it.id == RepositoryReviewPrompt.ID }

        assertEquals(4, skills.indexOf(review))
        assertTrue(review.prompt.length > 3_000)
        assertTrue(review.prompt.contains("Reuse audit"))
        assertTrue(review.prompt.contains("Duplication and size"))
        assertTrue(review.prompt.contains("Guideline compliance"))
        assertTrue(review.prompt.contains("CODE_REVIEW.md"))
    }

    @Test
    fun legacyReviewIsUpgradedButCustomizedReviewIsPreserved() {
        val legacy = AppSettings.defaultPromptSkills().toMutableList()
        legacy.first { it.id == RepositoryReviewPrompt.ID }.prompt =
            "After clarification, review the supplied code. Prioritize concrete correctness, security and regression findings with repository-relative locations. Do not invent unseen code."
        RepositoryReviewPrompt.upgradeLegacy(legacy)
        assertTrue(legacy.first { it.id == RepositoryReviewPrompt.ID }.prompt.contains("Reuse audit"))

        legacy.first { it.id == RepositoryReviewPrompt.ID }.prompt = "my custom review"
        RepositoryReviewPrompt.upgradeLegacy(legacy)
        assertEquals("my custom review", legacy.first { it.id == RepositoryReviewPrompt.ID }.prompt)
    }

    @Test
    fun settingsUpgradeMovesReviewAfterNewWorkflowsWithoutLosingCustomization() {
        val settings = AppSettings()
        val state = AppSettings.Data()
        val review = state.promptSkills.first { it.id == RepositoryReviewPrompt.ID }
        review.prompt = "custom review instructions"

        settings.loadState(state)

        assertEquals(4, settings.state.promptSkills.indexOfFirst { it.id == RepositoryReviewPrompt.ID })
        assertEquals("custom review instructions", settings.state.promptSkills[4].prompt)
    }
}
