package nl.ferron.copilotcontextbridge.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PythonAuthoringRulesTest {
    @Test
    fun editableDefaultsContainExplicitNapoleonNamingAndDocstringRules() {
        val guidelines = Defaults.globalGuidelines

        assertTrue(guidelines.contains("sphinxcontrib-napoleon.readthedocs.io/en/latest/example_google.html"))
        assertTrue(guidelines.contains("`Args:`"))
        assertTrue(guidelines.contains("`Returns:`"))
        assertTrue(guidelines.contains("`Yields:`"))
        assertTrue(guidelines.contains("`Raises:`"))
        assertTrue(guidelines.contains("current behavior"))
        assertTrue(guidelines.contains("clear verb"))
        assertTrue(guidelines.contains("meaningful domain-specific names"))
    }

    @Test
    fun codeAuthoringSkillsContainRulesButCreatorAndUserStoryPromptsStayFocused() {
        val skills = AppSettings.defaultPromptSkills().associateBy { it.id }
        val codeSkillIds =
            setOf(
                "general-change",
                "write-tests",
                DevelopmentPromptLibrary.NEW_CODE_ID,
                DevelopmentPromptLibrary.FIX_ISSUE_ID,
                RepositoryReviewPrompt.ID,
                RefactorPrompt.ID,
            )

        codeSkillIds.forEach { id ->
            val effectiveSkillText = skills.getValue(id).let { "${it.prompt}\n${it.guidelines}" }
            assertTrue("$id must require Napoleon docstrings", effectiveSkillText.contains("sphinxcontrib-napoleon"))
            assertTrue("$id must require verb-led names", effectiveSkillText.contains("verb", ignoreCase = true))
            assertTrue("$id must reject change-history docstrings", effectiveSkillText.contains("change history"))
        }

        val focusedIds = CreatorPromptLibrary.skills().map { it.id }.toSet() + DevelopmentPromptLibrary.USER_STORY_ID
        focusedIds.forEach { id ->
            assertFalse(
                "$id should not be polluted with Python authoring details",
                skills.getValue(id).prompt.contains("sphinxcontrib-napoleon"),
            )
        }
    }

    @Test
    fun everyReturnModeCarriesThePythonAuthoringContract() {
        CopilotReturnMode.entries.forEach { mode ->
            val instructions = ReturnInstructionDefaults.forMode(mode)
            assertTrue("$mode must carry the docstring reference", instructions.contains("sphinxcontrib-napoleon"))
            assertTrue("$mode must keep docstrings functional", instructions.contains("current") && instructions.contains("behavior"))
            assertTrue("$mode must require meaningful names", instructions.contains("meaningful variable and parameter names"))
        }
    }
}
