package nl.ferron.copilotcontextbridge.settings

import junit.framework.TestCase

class PromptSkillLibraryCodecTest : TestCase() {
    fun testRoundTripPreservesPromptAndGuidelines() {
        val skill = AppSettings.PromptSkillState("custom-test", "Test skill", "Description", "Prompt body", "Guideline body")

        val decoded = PromptSkillLibraryCodec.decode(PromptSkillLibraryCodec.encode(listOf(skill))).single()

        assertEquals(skill.id, decoded.id)
        assertEquals(skill.prompt, decoded.prompt)
        assertEquals(skill.guidelines, decoded.guidelines)
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
