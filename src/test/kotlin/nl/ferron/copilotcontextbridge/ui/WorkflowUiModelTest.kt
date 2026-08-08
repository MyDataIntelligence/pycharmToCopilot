package nl.ferron.copilotcontextbridge.ui

import junit.framework.TestCase

class WorkflowUiModelTest : TestCase() {
    fun testPromptSkillChoicesUseStableIdentityWhenLabelsAreDuplicated() {
        val first = PromptSkillChoice("one", "Custom · Same name")
        val second = PromptSkillChoice("two", "Custom · Same name")

        assertEquals(first.toString(), second.toString())
        assertFalse(first == second)
        assertEquals("two", listOf(first, second).first { it.id == "two" }.id)
    }

    fun testStalePackCannotBePreparedOrCopiedWhileAnalysisRuns() {
        val state =
            workflowControlState(
                hasValidPack = true,
                hasStagedPack = false,
                calculating = true,
                preparing = false,
            )

        assertFalse(state.canPrepare)
        assertFalse(state.canCopyContext)
        assertFalse(state.canUsePreparedFiles)
        assertFalse(state.canStartNewSession)
    }

    fun testPreparedPackEnablesOnlyHandoffActions() {
        val state =
            workflowControlState(
                hasValidPack = true,
                hasStagedPack = true,
                calculating = false,
                preparing = false,
            )

        assertFalse(state.canPrepare)
        assertTrue(state.canCopyContext)
        assertTrue(state.canUsePreparedFiles)
        assertTrue(state.canStartNewSession)
    }

    fun testMoreWorkspaceOrderIsStableAndKeepsImportOutOfMore() {
        assertEquals(
            listOf("Context files", "Context preview", "Guidelines", "Prompt skills", "Return instructions", "Settings"),
            MoreWorkspaceModel.destinations.map { it.title },
        )
        assertEquals(listOf(1, 2, 3, 4, 5, null), MoreWorkspaceModel.destinations.map { it.tabIndex })
        assertEquals(listOf("Copy context", "Copy return instructions"), MoreWorkspaceModel.quickActions)
        assertFalse(MoreWorkspaceModel.destinations.any { it.title.contains("Import", ignoreCase = true) })
    }
}
