package nl.ferron.copilotcontextbridge.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DevelopmentPromptLibraryTest {
    @Test
    fun primaryDropdownEntriesFollowTheDeltaOrder() {
        val skills = AppSettings.defaultPromptSkills()

        assertEquals("general-change", skills[0].id)
        assertEquals(DevelopmentPromptLibrary.FIX_ISSUE_ID, skills[1].id)
        assertEquals("write-tests", skills[2].id)
        assertEquals(RepositoryReviewPrompt.ID, skills[3].id)
        assertEquals(listOf("General change", "Debug problem", "Generate tests", "Review code"), skills.take(4).map { it.name })
    }

    @Test
    fun developmentPromptsContainRequiredWorkflowContracts() {
        val skills = DevelopmentPromptLibrary.skills()
        val newCode = skills.first { it.id == DevelopmentPromptLibrary.NEW_CODE_ID }
        val fix = skills.first { it.id == DevelopmentPromptLibrary.FIX_ISSUE_ID }
        val userStory = skills.first { it.id == DevelopmentPromptLibrary.USER_STORY_ID }

        assertTrue(newCode.prompt.length > 2_500)
        assertTrue(newCode.prompt.contains("scripts/functions/"))
        assertTrue(newCode.prompt.contains("testbestand"))
        assertTrue(newCode.prompt.contains("code/file-creation tool"))
        assertTrue(fix.prompt.length > 2_500)
        assertTrue(fix.prompt.contains("root cause"))
        assertTrue(fix.prompt.contains("regressietest"))
        assertTrue(fix.prompt.contains("copilot-result.copilotpatch"))
        assertTrue(userStory.prompt.contains("# A. USER STORY"))
        assertTrue(userStory.prompt.contains("# B. IMPLEMENTATION HINT"))
        assertTrue(userStory.prompt.contains("300 en maximaal 400 woorden"))
        assertTrue(userStory.prompt.contains("2 tot 5 concrete opleveringen"))
        assertTrue(userStory.prompt.contains("3 tot 6 onafhankelijk testbare criteria"))
        assertTrue(userStory.prompt.contains("technische stappen"))
        assertTrue(userStory.prompt.contains("geen code", ignoreCase = true))
    }

    @Test
    fun existingSettingsAreUpgradedAndReorderedWithoutLosingEdits() {
        val settings = AppSettings()
        val existing = AppSettings.Data()
        existing.promptSkills = mutableListOf(AppSettings.defaultPromptSkills().first())

        settings.loadState(existing)
        settings.state.promptSkills
            .first { it.id == DevelopmentPromptLibrary.NEW_CODE_ID }
            .prompt = "customized"
        settings.loadState(settings.state)

        assertEquals(DevelopmentPromptLibrary.FIX_ISSUE_ID, settings.state.promptSkills[1].id)
        assertEquals("write-tests", settings.state.promptSkills[2].id)
        assertEquals(RepositoryReviewPrompt.ID, settings.state.promptSkills[3].id)
        assertEquals(
            "customized",
            settings.state.promptSkills
                .first { it.id == DevelopmentPromptLibrary.NEW_CODE_ID }
                .prompt,
        )
        assertEquals(1, settings.state.promptSkills.count { it.id == DevelopmentPromptLibrary.NEW_CODE_ID })
        assertEquals(1, settings.state.promptSkills.count { it.id == DevelopmentPromptLibrary.FIX_ISSUE_ID })
        assertEquals(1, settings.state.promptSkills.count { it.id == DevelopmentPromptLibrary.USER_STORY_ID })
    }
}
