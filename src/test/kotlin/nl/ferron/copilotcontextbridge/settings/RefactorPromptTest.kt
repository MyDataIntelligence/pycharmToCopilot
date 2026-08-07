package nl.ferron.copilotcontextbridge.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RefactorPromptTest {
    @Test
    fun refactorFollowsReviewAndCoversRepositoryWideReuse() {
        val skills = AppSettings.defaultPromptSkills()
        val reviewIndex = skills.indexOfFirst { it.id == RepositoryReviewPrompt.ID }
        val refactor = skills.first { it.id == RefactorPrompt.ID }

        assertEquals(reviewIndex + 1, skills.indexOf(refactor))
        assertTrue(refactor.prompt.length > 4_000)
        assertTrue(refactor.prompt.contains("repository reuse inventory"))
        assertTrue(refactor.prompt.contains("Extensibility check"))
        assertTrue(refactor.prompt.contains("INSPECT_REQUIRED"))
        assertTrue(refactor.prompt.contains("copilot-refactor-result.zip"))
    }

    @Test
    fun settingsUpgradeAddsRefactorOnceAfterReview() {
        val settings = AppSettings()
        val state = AppSettings.Data().apply { promptSkills.removeAll { it.id == RefactorPrompt.ID } }

        settings.loadState(state)
        settings.loadState(settings.state)

        val reviewIndex = settings.state.promptSkills.indexOfFirst { it.id == RepositoryReviewPrompt.ID }
        assertEquals(1, settings.state.promptSkills.count { it.id == RefactorPrompt.ID })
        assertEquals(RefactorPrompt.ID, settings.state.promptSkills[reviewIndex + 1].id)
    }
}
