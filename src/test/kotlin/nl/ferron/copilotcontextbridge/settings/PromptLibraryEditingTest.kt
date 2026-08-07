package nl.ferron.copilotcontextbridge.settings

import junit.framework.TestCase

class PromptLibraryEditingTest : TestCase() {
    fun testPromptSpecificResetRestoresPrPolicyInsteadOfGenericDefaults() {
        val policy = AppSettings.defaultPolicyForPrompt(DeltaPromptLibrary.PREPARE_PR_ID)
        policy.target = CopilotTarget.GITHUB_COPILOT.name
        policy.returnMode = CopilotReturnMode.COPILOT_PATCH_FILE.name
        policy.previousBatchMode = PreviousBatchMode.ALWAYS.name
        policy.rule("branch-changes")?.enabled = false
        policy.rule("direct-imports")?.enabled = true

        ContextPolicyEditor.resetToPromptDefault(policy, DeltaPromptLibrary.PREPARE_PR_ID)

        assertEquals(CopilotTarget.MICROSOFT_365.name, policy.target)
        assertEquals(CopilotReturnMode.TEXT_ONLY.name, policy.returnMode)
        assertEquals(PreviousBatchMode.NEVER.name, policy.previousBatchMode)
        assertTrue(policy.rule("branch-changes")?.enabled == true)
        assertTrue(policy.rule("branch-changes")?.required == true)
        assertFalse(policy.rule("direct-imports")?.enabled == true)
    }

    fun testRepeatedRuleDuplicationCreatesStableUniqueIndependentIds() {
        val rules = mutableListOf(ContextRuleState("tests", "python.matchingTests", 100))

        val first = ContextPolicyEditor.duplicateRule(rules, 0)
        val second = ContextPolicyEditor.duplicateRule(rules, 0)
        first.priority = 1

        assertEquals(listOf("tests", "tests-copy", "tests-copy-2"), rules.map { it.id })
        assertEquals(100, second.priority)
        assertEquals(3, rules.map { it.id }.distinct().size)
    }

    fun testAddDuplicateAndRemovePreserveIndependentPromptState() {
        val skills = mutableListOf<AppSettings.PromptSkillState>()
        val added = PromptSkillLibraryEditor.add(skills, "custom-one")
        added.guidelines = "Repository-specific guideline"
        added.returnInstructionsAddition = "Return a concise summary."
        added.contextPolicy.rule("matching-tests")?.priority = 123

        val duplicate = PromptSkillLibraryEditor.duplicate(skills, 0, "custom-two")
        duplicate.contextPolicy.rule("matching-tests")?.priority = 7

        assertEquals("Custom", added.category)
        assertEquals("custom-one-policy", added.contextPolicy.id)
        assertEquals("custom-two-policy", duplicate.contextPolicy.id)
        assertEquals(added.guidelines, duplicate.guidelines)
        assertEquals(added.returnInstructionsAddition, duplicate.returnInstructionsAddition)
        assertEquals(123, added.contextPolicy.rule("matching-tests")?.priority)
        assertTrue(PromptSkillLibraryEditor.remove(skills, 0))
        assertFalse(PromptSkillLibraryEditor.remove(skills, 0))
        assertEquals("custom-two", skills.single().id)
    }

    fun testCodecRejectsUnsafePolicyValuesAndDuplicateRuleIds() {
        val skill = AppSettings.PromptSkillState("custom", "Custom", "", "Prompt")
        skill.contextPolicy.maxAttachments = 21
        assertInvalid(PromptSkillLibraryCodec.encode(listOf(skill)))

        skill.contextPolicy.maxAttachments = 20
        skill.contextPolicy.target = "UNKNOWN"
        assertInvalid(PromptSkillLibraryCodec.encode(listOf(skill)))

        skill.contextPolicy.target = CopilotTarget.MICROSOFT_365.name
        skill.contextPolicy.rules +=
            skill.contextPolicy.rules
                .first()
                .copyOf()
        assertInvalid(PromptSkillLibraryCodec.encode(listOf(skill)))
    }

    fun testCodecRepairsLegacyCustomPolicyIdentityBeforeImport() {
        val decoded =
            PromptSkillLibraryCodec
                .decode(
                    """[{"id":"custom-x","name":"Custom X","prompt":"Do X"}]""",
                ).single()

        assertEquals("custom-x-policy", decoded.contextPolicy.id)
        assertTrue(decoded.contextPolicy.rules.isNotEmpty())
    }

    private fun assertInvalid(json: String) {
        try {
            PromptSkillLibraryCodec.decode(json)
            fail("Unsafe Prompt Library policy must be rejected.")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
