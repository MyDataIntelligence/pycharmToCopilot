package nl.ferron.copilotcontextbridge.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DevelopmentPromptLibraryTest {
    @Test
    fun newCodeAndFixSkillsAreSecondAndThird() {
        val skills = AppSettings.defaultPromptSkills()

        assertEquals("general-change", skills[0].id)
        assertEquals(DevelopmentPromptLibrary.NEW_CODE_ID, skills[1].id)
        assertEquals(DevelopmentPromptLibrary.FIX_ISSUE_ID, skills[2].id)
        assertEquals(DevelopmentPromptLibrary.USER_STORY_ID, skills[3].id)
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
        assertTrue(userStory.prompt.length > 3_500)
        assertTrue(userStory.prompt.contains("## Copilot execution brief"))
        assertTrue(userStory.prompt.contains("## Source traceability"))
        assertTrue(userStory.prompt.contains("docs/user-stories/"))
    }

    @Test
    fun existingSettingsAreUpgradedAndReorderedWithoutLosingEdits() {
        val settings = AppSettings()
        val existing = AppSettings.Data()
        existing.promptSkills = mutableListOf(AppSettings.defaultPromptSkills().first())

        settings.loadState(existing)
        settings.state.promptSkills[1].prompt = "customized"
        settings.loadState(settings.state)

        assertEquals(DevelopmentPromptLibrary.NEW_CODE_ID, settings.state.promptSkills[1].id)
        assertEquals(DevelopmentPromptLibrary.FIX_ISSUE_ID, settings.state.promptSkills[2].id)
        assertEquals(DevelopmentPromptLibrary.USER_STORY_ID, settings.state.promptSkills[3].id)
        assertEquals("customized", settings.state.promptSkills[1].prompt)
        assertEquals(1, settings.state.promptSkills.count { it.id == DevelopmentPromptLibrary.NEW_CODE_ID })
        assertEquals(1, settings.state.promptSkills.count { it.id == DevelopmentPromptLibrary.FIX_ISSUE_ID })
        assertEquals(1, settings.state.promptSkills.count { it.id == DevelopmentPromptLibrary.USER_STORY_ID })
    }
}
