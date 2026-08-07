package nl.ferron.copilotcontextbridge.patch

import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DiffFacadeTest : BasePlatformTestCase() {
    fun testSafeFunctionUsesNativeTwoSideDiffWithSelectionActions() {
        val request =
            JetBrainsDiffFacade(project).createRequest(
                replacement(ReplacementStatus.MATCH, base = ""),
            ) { _, _ -> }

        assertEquals(2, request.contents.size)
        assertEquals(listOf("CURRENT", "COPILOT PROPOSED"), request.contentTitles)
        assertEquals(2, request.getUserData(DiffUserDataKeys.CONTEXT_ACTIONS)?.size)
        assertEquals(true, request.getUserData(DiffUserDataKeys.FORCE_READ_ONLY))
    }

    fun testHashConflictUsesNativeBaseCurrentProposedThreeWayDiff() {
        val request =
            JetBrainsDiffFacade(project).createRequest(
                replacement(ReplacementStatus.CHANGED, base = "def run():\n    return 0\n"),
            ) { _, _ -> }

        assertEquals(3, request.contents.size)
        assertEquals(listOf("BASE (exported)", "CURRENT (local)", "PROPOSED (Copilot)"), request.contentTitles)
        assertEquals(4, request.getUserData(DiffUserDataKeys.CONTEXT_ACTIONS)?.size)
    }

    private fun replacement(
        status: ReplacementStatus,
        base: String,
    ) = ValidatedReplacement(
        request =
            FunctionReplacement(
                operation = "replace_function",
                path = "src/example.py",
                qualifiedName = "run",
                originalHash = "sha256:base",
                replacement = "def run():\n    return 2\n",
                replacementFile = null,
            ),
        status = status,
        message = "test",
        oldText = "def run():\n    return 1\n",
        newText = "def run():\n    return 2\n",
        baseText = base,
    )
}
