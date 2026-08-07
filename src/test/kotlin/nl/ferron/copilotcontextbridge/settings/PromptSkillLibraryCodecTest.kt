package nl.ferron.copilotcontextbridge.settings

import junit.framework.TestCase

class PromptSkillLibraryCodecTest : TestCase() {
    fun testRoundTripPreservesPromptAndGuidelines() {
        val policy =
            ContextPolicyState
                .defaultFor("custom-test")
                .apply { rule("matching-tests")?.priority = 123 }
        val skill =
            AppSettings
                .PromptSkillState(
                    "custom-test",
                    "Test skill",
                    "Description",
                    "Prompt body",
                    "Guideline body",
                    policy,
                    "Custom category",
                ).apply {
                    returnInstructionsAddition = "Return a validation matrix."
                }

        val decoded = PromptSkillLibraryCodec.decode(PromptSkillLibraryCodec.encode(listOf(skill))).single()

        assertEquals(skill.id, decoded.id)
        assertEquals(skill.prompt, decoded.prompt)
        assertEquals(skill.guidelines, decoded.guidelines)
        assertEquals(skill.category, decoded.category)
        assertEquals(skill.contextPolicy.id, decoded.contextPolicy.id)
        assertEquals(123, decoded.contextPolicy.rule("matching-tests")?.priority)
        assertEquals("Return a validation matrix.", decoded.returnInstructionsAddition)
    }

    fun testRejectsEmptyDuplicateAndIncompleteLibraries() {
        assertFails("[]")
        assertFails("[{\"id\":\"same\",\"name\":\"One\",\"prompt\":\"P\"},{\"id\":\"same\",\"name\":\"Two\",\"prompt\":\"P\"}]")
        assertFails("[{\"id\":\"one\",\"name\":\"\",\"prompt\":\"P\"}]")
    }

    private fun assertFails(json: String) {
        try {
            PromptSkillLibraryCodec.decode(json)
            fail("Invalid prompt library should be rejected.")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
