package nl.ferron.copilotcontextbridge.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptLibraryDeltaTest {
    @Test
    fun requiredEntriesAreGroupedAndOrdered() {
        val skills = AppSettings.defaultPromptSkills()
        val requiredIds =
            listOf(
                "general-change",
                DevelopmentPromptLibrary.FIX_ISSUE_ID,
                "write-tests",
                RepositoryReviewPrompt.ID,
                DeltaPromptLibrary.PREPARE_PR_ID,
                DeltaPromptLibrary.ANALYZE_STORY_ID,
                DevelopmentPromptLibrary.USER_STORY_ID,
                "skill-creator",
                "slash-command-creator",
                "agents-md-creator",
            )
        val inDropdownOrder = skills.filter { it.id in requiredIds }

        assertEquals(requiredIds, inDropdownOrder.map { it.id })
        assertEquals(
            listOf(
                "Code",
                "Code",
                "Code",
                "Code",
                "Git / PR",
                "User stories",
                "User stories",
                "GitHub Copilot customization",
                "GitHub Copilot customization",
                "GitHub Copilot customization",
            ),
            inDropdownOrder.map { it.category },
        )
    }

    @Test
    fun everyBuiltInOwnsAnIndependentPolicyWithHighPriorityMatchingTests() {
        val skills = AppSettings.defaultPromptSkills()

        assertEquals(skills.size, skills.map { it.contextPolicy.id }.distinct().size)
        skills.forEach { skill ->
            assertEquals("${skill.id}-policy", skill.contextPolicy.id)
            val matchingTests = skill.contextPolicy.rule("matching-tests")
            assertTrue("${skill.name} must include matching tests", matchingTests?.enabled == true)
            assertTrue("${skill.name} must prioritize matching tests", (matchingTests?.priority ?: 0) >= 90)
        }

        val first = skills.first().contextPolicy
        val second = skills[1].contextPolicy
        first.rule("matching-tests")?.priority = 1
        assertNotEquals(first.rule("matching-tests")?.priority, second.rule("matching-tests")?.priority)
    }

    @Test
    fun storyPoliciesStayFocusedAndCreateStoryUsesTheShortTwoPartContract() {
        val analyze = AppSettings.defaultPromptSkills().first { it.id == DeltaPromptLibrary.ANALYZE_STORY_ID }
        val create = AppSettings.defaultPromptSkills().first { it.id == DevelopmentPromptLibrary.USER_STORY_ID }

        assertTrue(analyze.contextPolicy.rule("matching-tests")?.enabled == true)
        assertTrue(analyze.contextPolicy.rule("referenced-config")?.enabled == true)
        assertTrue(analyze.contextPolicy.rule("similar-implementations")?.enabled == true)
        assertFalse(analyze.contextPolicy.rule("transitive-imports")?.enabled == true)
        assertEquals(CopilotReturnMode.TEXT_ONLY.name, create.contextPolicy.returnMode)
        assertTrue(create.prompt.contains("# A. USER STORY"))
        assertTrue(create.prompt.contains("# B. IMPLEMENTATION HINT"))
        assertTrue(create.prompt.contains("300 en maximaal 400 woorden"))
        assertTrue(create.prompt.contains("2 tot 5 concrete opleveringen"))
        assertTrue(create.prompt.contains("3 tot 6 onafhankelijk testbare criteria"))
        assertTrue(create.prompt.contains("technische stappen"))
    }

    @Test
    fun prAndCreatorPoliciesSelectTheirOwnTargetsAndResolvers() {
        val pr = AppSettings.defaultPromptSkills().first { it.id == DeltaPromptLibrary.PREPARE_PR_ID }
        val branch = pr.contextPolicy.rule("branch-changes")

        assertTrue(branch?.enabled == true)
        assertTrue(branch?.required == true)
        assertEquals(PreviousBatchMode.NEVER.name, pr.contextPolicy.previousBatchMode)
        assertFalse(pr.contextPolicy.rule("direct-imports")?.enabled == true)
        assertFalse(pr.contextPolicy.rule("similar-implementations")?.enabled == true)

        CreatorPromptLibrary.skills().forEach { creator ->
            assertEquals(CopilotTarget.GITHUB_COPILOT.name, creator.contextPolicy.target)
            assertEquals(CopilotReturnMode.DIRECT_REPOSITORY_EDIT.name, creator.contextPolicy.returnMode)
            assertEquals(PreviousBatchMode.NEVER.name, creator.contextPolicy.previousBatchMode)
        }
        val skillCreator = CreatorPromptLibrary.skills().first { it.id == "skill-creator" }
        val slashCreator = CreatorPromptLibrary.skills().first { it.id == "slash-command-creator" }
        val agentsCreator = CreatorPromptLibrary.skills().first { it.id == "agents-md-creator" }
        assertTrue(skillCreator.contextPolicy.rule("similar-implementations")?.enabled == true)
        assertTrue(skillCreator.prompt.contains(".github/skills/<skill-slug>/"))
        assertTrue(slashCreator.prompt.contains(".github/prompts/<command-name>.prompt.md"))
        assertTrue(agentsCreator.prompt.contains("direct in de repository"))
        assertFalse(creatorMentionsPatchReturn(skillCreator))
        assertFalse(creatorMentionsPatchReturn(slashCreator))
        assertFalse(creatorMentionsPatchReturn(agentsCreator))
    }

    @Test
    fun loadingOldStateMigratesCategoriesAndPreservesCustomizedPromptText() {
        val state = AppSettings.Data()
        val create = state.promptSkills.first { it.id == DevelopmentPromptLibrary.USER_STORY_ID }
        create.category = "Code"
        create.prompt = "custom create-story instructions"
        state.promptSkills.reverse()

        val settings = AppSettings()
        settings.loadState(state)

        val migrated = settings.state.promptSkills.first { it.id == DevelopmentPromptLibrary.USER_STORY_ID }
        assertEquals("User stories", migrated.category)
        assertEquals("custom create-story instructions", migrated.prompt)
        assertEquals(
            listOf("General change", "New reusable Python code", "Debug problem", "Generate tests"),
            settings.state.promptSkills
                .take(4)
                .map { it.name },
        )
    }

    private fun creatorMentionsPatchReturn(skill: AppSettings.PromptSkillState): Boolean =
        skill.prompt.contains("retourneer een `.copilotpatch`") ||
            skill.prompt.contains("retourneer een JSON-vervangingsset")
}
