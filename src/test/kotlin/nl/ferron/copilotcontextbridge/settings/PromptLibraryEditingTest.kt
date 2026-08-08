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

    fun testCodecRejectsUnsafePackingLimits() {
        val skill = AppSettings.PromptSkillState("packing", "Packing", "", "Prompt")

        skill.contextPolicy.maxBundleCharacters = 9_999
        assertInvalid(PromptSkillLibraryCodec.encode(listOf(skill)))

        skill.contextPolicy.maxBundleCharacters = 10_000
        skill.contextPolicy.estimatedMaxBundleTokens = 2_499
        assertInvalid(PromptSkillLibraryCodec.encode(listOf(skill)))

        skill.contextPolicy.estimatedMaxBundleTokens = 250_001
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

    fun testListSelectionStaysOnEditedEntryAndMovesPredictablyAfterDelete() {
        assertEquals(4, PromptSkillLibraryEditor.selectionAfterRefresh(4, 8))
        assertEquals(2, PromptSkillLibraryEditor.selectionAfterRefresh(7, 3))
        assertEquals(2, PromptSkillLibraryEditor.selectionAfterRemoval(3, 3))
        assertEquals(0, PromptSkillLibraryEditor.selectionAfterRemoval(0, 2))
        assertEquals(-1, PromptSkillLibraryEditor.selectionAfterRefresh(0, 0))
    }

    fun testBuiltInPromptCannotDisappearAndCustomPromptCanBeDeleted() {
        val builtIn = AppSettings.defaultPromptSkills().first()
        val custom = AppSettings.PromptSkillState("custom-delete", "Custom", "", "Prompt")
        val skills = mutableListOf(builtIn, custom)

        assertTrue(PromptSkillLibraryEditor.isBuiltIn(builtIn))
        assertFalse(PromptSkillLibraryEditor.remove(skills, 0))
        assertTrue(PromptSkillLibraryEditor.remove(skills, 1))
        assertEquals(listOf(builtIn.id), skills.map { it.id })
    }

    fun testPolicyWorkingCopyCanBeCommittedWithoutSharingRuleState() {
        val original = AppSettings.defaultPolicyForPrompt("general-change")
        val working = original.copyOf()
        working.target = CopilotTarget.GITHUB_COPILOT.name
        working.rule("matching-tests")?.priority = 321

        assertFalse(original.target == working.target)
        assertFalse(original.rule("matching-tests")?.priority == working.rule("matching-tests")?.priority)

        ContextPolicyEditor.replaceWith(original, working)
        working.rule("matching-tests")?.priority = 7

        assertEquals(CopilotTarget.GITHUB_COPILOT.name, original.target)
        assertEquals(321, original.rule("matching-tests")?.priority)
    }

    fun testPolicyWorkingCopyCommitPreservesBundleCharacterAndTokenLimits() {
        val original = ContextPolicyState.defaultFor("general-change")
        val working =
            original.copyOf().apply {
                maxBundleCharacters = 37_000
                estimatedMaxBundleTokens = 9_250
            }

        ContextPolicyEditor.replaceWith(original, working)

        assertEquals(37_000, original.maxBundleCharacters)
        assertEquals(9_250, original.estimatedMaxBundleTokens)
    }

    fun testPromptSpecificResetRestoresBundleLimits() {
        val policy =
            ContextPolicyState.defaultFor("reset-limits").apply {
                maxBundleCharacters = 10_000
                estimatedMaxBundleTokens = 2_500
            }
        val defaults = AppSettings.defaultPolicyForPrompt("general-change")

        ContextPolicyEditor.resetToPromptDefault(policy, "general-change")

        assertEquals(defaults.maxBundleCharacters, policy.maxBundleCharacters)
        assertEquals(defaults.estimatedMaxBundleTokens, policy.estimatedMaxBundleTokens)
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
