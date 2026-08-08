package nl.ferron.copilotcontextbridge.patch

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals

class GenericZipPatchApplicationTest : BasePlatformTestCase() {
    fun testReplaceFileUsesDiffValidationAndExplicitSelection() {
        val file = myFixture.addFileToProject("src/config.json", "{\"value\":1}\n")
        val request =
            FunctionReplacement(
                "replace_file",
                "src/config.json",
                FILE_OPERATION_QUALIFIED_NAME,
                FileContentHasher.hash(file),
                "{\"value\":2}\n",
                "archive:generated/config.json",
            )
        val patch = CopilotPatch(1, project.name, "generic-zip-test", listOf(request))
        val validation =
            PatchValidator(
                project,
                testRepositoryId = project.name,
                testRootVirtualFile = myFixture.tempDirFixture.getFile("src")!!.parent,
            ).validate(patch)
        val target = validation.targets.single()
        assertEquals(ReplacementStatus.MATCH, target.validated.status)
        assertTrue(target.validated.unifiedDiff.contains("value"))

        val skipped = PythonFunctionReplacementService(project).apply(validation, emptySet(), emptySet())
        assertTrue(skipped.applied.isEmpty())
        assertEquals("{\"value\":1}\n", file.text)

        val key = "src/config.json::$FILE_OPERATION_QUALIFIED_NAME"
        val applied = PythonFunctionReplacementService(project).apply(validation, setOf(key), emptySet())
        assertEquals(1, applied.applied.size)
        assertEquals("{\"value\":2}\n", file.text)
    }
}
