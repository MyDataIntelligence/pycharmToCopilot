package nl.ferron.copilotcontextbridge.patch

import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.application.WriteAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DiffFacadeTest : BasePlatformTestCase() {
    fun testSafeFunctionUsesNativeTwoSideDiffWithSelectionActions() {
        val request =
            JetBrainsDiffFacade(project).createRequest(
                replacement(ReplacementStatus.MATCH, base = ""),
            ) { _, _, _ -> }

        assertEquals(2, request.contents.size)
        assertEquals(listOf("CURRENT", "COPILOT PROPOSED"), request.contentTitles)
        assertEquals(2, request.getUserData(DiffUserDataKeys.CONTEXT_ACTIONS)?.size)
        assertTrue(booleanArrayOf(true, false).contentEquals(request.getUserData(DiffUserDataKeys.FORCE_READ_ONLY_CONTENTS)))
    }

    fun testHashConflictUsesNativeBaseCurrentProposedThreeWayDiff() {
        val request =
            JetBrainsDiffFacade(project).createRequest(
                replacement(ReplacementStatus.CHANGED, base = "def run():\n    return 0\n"),
            ) { _, _, _ -> }

        assertEquals(3, request.contents.size)
        assertEquals(listOf("BASE (exported)", "CURRENT (local)", "PROPOSED (Copilot)"), request.contentTitles)
        assertEquals(4, request.getUserData(DiffUserDataKeys.CONTEXT_ACTIONS)?.size)
        assertTrue(booleanArrayOf(true, true, false).contentEquals(request.getUserData(DiffUserDataKeys.FORCE_READ_ONLY_CONTENTS)))
    }

    fun testProposedResultUsesAnInMemoryEditableDocument() {
        val facade = JetBrainsDiffFacade(project)
        val request =
            facade.createRequest(
                replacement(ReplacementStatus.MATCH, base = ""),
            ) { _, _, _ -> }

        val proposed = facade.proposedDocument(request)
        WriteAction.run<RuntimeException> { proposed.setText("def run():\n    return 99\n") }

        assertEquals("def run():\n    return 99\n", proposed.text)
        assertEquals("def run():\n    return 1\n", (request.contents.first() as com.intellij.diff.contents.DocumentContent).document.text)
    }

    fun testWholeFileNonPythonDiffUsesTheTargetFileType() {
        val request =
            JetBrainsDiffFacade(project).createRequest(
                replacement(
                    ReplacementStatus.MATCH,
                    base = "",
                ).copy(
                    request =
                        FunctionReplacement(
                            operation = "replace_file",
                            path = "config/settings.yaml",
                            qualifiedName = FILE_OPERATION_QUALIFIED_NAME,
                            originalHash = "sha256:base",
                            replacement = "enabled: false\n",
                            replacementFile = null,
                        ),
                    oldText = "enabled: true\n",
                    newText = "enabled: false\n",
                ),
            ) { _, _, _ -> }

        val type = (request.contents.first() as com.intellij.diff.contents.DocumentContent).contentType
        assertEquals("YAML", type?.name)
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
