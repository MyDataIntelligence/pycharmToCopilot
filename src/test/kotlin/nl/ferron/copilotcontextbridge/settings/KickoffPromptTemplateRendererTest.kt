package nl.ferron.copilotcontextbridge.settings

import junit.framework.TestCase

class KickoffPromptTemplateRendererTest : TestCase() {
    fun testRendersAllSupportedPlaceholders() {
        val rendered =
            KickoffPromptTemplateRenderer.render(
                template = "Read 00_REPO_CONTEXT.md for {promptSkill}; batch {batchNumber}, session {sessionId}.",
                sessionId = "session-42",
                batchNumber = 3,
                promptSkill = "Debug problem",
            )

        assertEquals("Read 00_REPO_CONTEXT.md for Debug problem; batch 3, session session-42.", rendered)
    }

    fun testDefaultTemplatePreservesContextAndMultiBatchSemantics() {
        assertTrue(KickoffPromptTemplateRenderer.validationErrors(Defaults.KICKOFF_PROMPT_TEMPLATE).isEmpty())
        assertTrue(Defaults.KICKOFF_PROMPT_TEMPLATE.contains("00_REPO_CONTEXT.md"))
        assertTrue(Defaults.KICKOFF_PROMPT_TEMPLATE.contains("More batches may follow"))
        assertTrue(Defaults.KICKOFF_PROMPT_TEMPLATE.contains("all batches are uploaded"))
    }

    fun testValidationReportsEveryMissingRequiredSemantic() {
        val errors = KickoffPromptTemplateRenderer.validationErrors("Start now")

        assertEquals(4, errors.size)
        assertTrue(errors.any { it.contains("00_REPO_CONTEXT.md") })
        assertTrue(errors.any { it.contains("{sessionId}") })
        assertTrue(errors.any { it.contains("{batchNumber}") })
        assertTrue(errors.any { it.contains("{promptSkill}") })
    }

    fun testRenderRejectsInvalidTemplateAndBatchValues() {
        assertFailsWithIllegalArgument {
            KickoffPromptTemplateRenderer.render("invalid", "session", 1, "Skill")
        }
        assertFailsWithIllegalArgument {
            KickoffPromptTemplateRenderer.render(Defaults.KICKOFF_PROMPT_TEMPLATE, "session", 0, "Skill")
        }
    }

    private fun assertFailsWithIllegalArgument(action: () -> Unit) {
        try {
            action()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
