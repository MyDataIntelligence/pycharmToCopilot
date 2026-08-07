package nl.ferron.copilotcontextbridge.context

import junit.framework.TestCase
import nl.ferron.copilotcontextbridge.settings.ContextPolicyState
import nl.ferron.copilotcontextbridge.settings.PreviousBatchMode
import nl.ferron.copilotcontextbridge.settings.ProjectSettings

class ContextPolicyProjectionTest : TestCase() {
    fun testPolicyRulesDriveAnalysisWithoutPromptSpecificBranches() {
        val fallback = ProjectSettings.Data()
        val policy = ContextPolicyState.defaultFor("custom")
        policy.rule("direct-imports")!!.enabled = false
        policy.rule("direct-callees")!!.enabled = false
        policy.rule("direct-callers")!!.enabled = true
        policy.rule("transitive-imports")!!.apply {
            enabled = true
            maxDepth = 3
        }
        policy.previousBatchMode = PreviousBatchMode.NEVER.name
        policy.maxAttachments = 17

        val projection = ContextPolicyProjection.from(policy, fallback)

        assertFalse(projection.directImports)
        assertTrue(projection.directDependents)
        assertTrue(projection.relatedTests)
        assertTrue(projection.secondLevel)
        assertFalse(projection.avoidPrevious)
        assertEquals(17, projection.maximumFiles)
    }

    fun testPolicyAttachmentLimitCannotExceedCopilotMaximum() {
        val fallback = ProjectSettings.Data().apply { maximumUploadFiles = 100 }
        val policy = ContextPolicyState.defaultFor("custom").apply { maxAttachments = 200 }

        assertEquals(20, ContextPolicyProjection.from(policy, fallback).maximumFiles)
    }
}
