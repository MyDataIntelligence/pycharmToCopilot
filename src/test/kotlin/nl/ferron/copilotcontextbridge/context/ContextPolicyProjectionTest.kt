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

        assertTrue(projection.directImports)
        assertFalse(projection.directCallees)
        assertTrue(projection.directDependents)
        assertTrue(projection.relatedTests)
        assertFalse(projection.nearbyTests)
        assertEquals(3, projection.maximumDependencyDepth)
        assertEquals(20, projection.resolverLimits["python.transitiveImports"])
        assertFalse(projection.avoidPrevious)
        assertEquals(17, projection.maximumFiles)
    }

    fun testPolicyAttachmentLimitCannotExceedCopilotMaximum() {
        val fallback = ProjectSettings.Data().apply { maximumUploadFiles = 100 }
        val policy = ContextPolicyState.defaultFor("custom").apply { maxAttachments = 200 }

        assertEquals(20, ContextPolicyProjection.from(policy, fallback).maximumFiles)
    }

    fun testPolicyPreviousBatchModesAreNotDisabledByLegacyProjectCheckbox() {
        val fallback = ProjectSettings.Data().apply { avoidPreviouslySentFiles = false }
        val policy = ContextPolicyState.defaultFor("custom")

        policy.previousBatchMode = PreviousBatchMode.SAME_SESSION_ONLY.name
        assertTrue(ContextPolicyProjection.from(policy, fallback).avoidPrevious)

        policy.previousBatchMode = PreviousBatchMode.ALWAYS.name
        assertTrue(ContextPolicyProjection.from(policy, fallback).avoidPrevious)

        policy.previousBatchMode = PreviousBatchMode.NEVER.name
        assertFalse(ContextPolicyProjection.from(policy, fallback).avoidPrevious)
    }

    fun testPolicyCopyPreservesPackingLimits() {
        val policy =
            ContextPolicyState.defaultFor("copy").apply {
                maxBundleCharacters = 42_000
                estimatedMaxBundleTokens = 10_500
            }

        val copied = policy.copyOf()

        assertEquals(42_000, copied.maxBundleCharacters)
        assertEquals(10_500, copied.estimatedMaxBundleTokens)
    }
}
