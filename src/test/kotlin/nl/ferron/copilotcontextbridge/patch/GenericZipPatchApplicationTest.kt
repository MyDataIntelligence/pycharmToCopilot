package nl.ferron.copilotcontextbridge.patch

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GenericZipPatchApplicationTest : BasePlatformTestCase() {
    fun testPowerShellStyleBomZipParsesAsValidPythonAndStartsUnselected() {
        myFixture.addFileToProject("src/existing.py", "VALUE = 1\n")
        val repository = Files.createTempDirectory("ccb-bom-platform-")
        try {
            val bytes =
                ByteArrayOutputStream()
                    .also { output ->
                        ZipOutputStream(output).use { zip ->
                            zip.putNextEntry(ZipEntry("src/generated.py"))
                            zip.write("\uFEFFdef generated() -> int:\r\n    return 42\r\n".toByteArray())
                        }
                    }.toByteArray()
            val patch = GenericCodeZipParser().parse(bytes, repository, project.name)
            val validation =
                PatchValidator(
                    project,
                    testRepositoryId = project.name,
                    testRootVirtualFile = myFixture.tempDirFixture.getFile("src")!!.parent,
                ).validate(patch)

            val target = validation.targets.single().validated
            assertEquals(target.message, ReplacementStatus.NEW, target.status)
            assertFalse(target.selected)
            assertTrue(target.newText.startsWith("def generated"))
        } finally {
            Files.walk(repository).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    fun testSourceOnlyZipNewFileRequiresExplicitSelection() {
        myFixture.addFileToProject("src/existing.py", "VALUE = 1\n")
        val request =
            FunctionReplacement(
                "add_file",
                "src/generated.py",
                FILE_OPERATION_QUALIFIED_NAME,
                null,
                "VALUE = 2\n",
                "archive:generated.py",
            )
        val patch = CopilotPatch(1, project.name, "generic-zip-add-test", listOf(request))
        val validation =
            PatchValidator(
                project,
                testRepositoryId = project.name,
                testRootVirtualFile = myFixture.tempDirFixture.getFile("src")!!.parent,
            ).validate(patch)

        val target = validation.targets.single().validated
        assertEquals(ReplacementStatus.NEW, target.status)
        assertFalse(target.selected)

        val skipped = PythonFunctionReplacementService(project).apply(validation, emptySet(), emptySet())
        assertTrue(skipped.applied.isEmpty())
        assertNull(myFixture.tempDirFixture.getFile("src/generated.py"))

        val key = "src/generated.py::$FILE_OPERATION_QUALIFIED_NAME"
        val applied = PythonFunctionReplacementService(project).apply(validation, setOf(key), emptySet())
        assertEquals(1, applied.applied.size)
        assertNotNull(myFixture.tempDirFixture.getFile("src/generated.py"))
    }

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
        assertFalse(target.validated.selected)
        assertTrue(target.validated.unifiedDiff.contains("value"))

        val skipped = PythonFunctionReplacementService(project).apply(validation, emptySet(), emptySet())
        assertTrue(skipped.applied.isEmpty())
        assertEquals("{\"value\":1}\n", file.text)

        val key = "src/config.json::$FILE_OPERATION_QUALIFIED_NAME"
        val applied = PythonFunctionReplacementService(project).apply(validation, setOf(key), emptySet())
        assertEquals(1, applied.applied.size)
        assertEquals("{\"value\":2}\n", file.text)
    }

    fun testPowerShellAndBatchFilesAreReviewedAsTextInsteadOfBinary() {
        val batch = myFixture.addFileToProject("scripts/run.bat", "@echo off\r\necho old\r\n")
        val replacements =
            listOf(
                FunctionReplacement(
                    "replace_file",
                    "scripts/run.bat",
                    FILE_OPERATION_QUALIFIED_NAME,
                    FileContentHasher.hash(batch),
                    "@echo off\necho new\n",
                    "archive:scripts/run.bat",
                ),
                FunctionReplacement(
                    "add_file",
                    "scripts/install.ps1",
                    FILE_OPERATION_QUALIFIED_NAME,
                    null,
                    "Write-Output 'ready'\n",
                    "archive:scripts/install.ps1",
                ),
            )
        val validation =
            PatchValidator(
                project,
                testRepositoryId = project.name,
                testRootVirtualFile = myFixture.tempDirFixture.getFile("scripts")!!.parent,
            ).validate(CopilotPatch(1, project.name, "generic-zip-script-text", replacements))

        assertTrue(validation.validation.errors.isEmpty())
        assertEquals(listOf(ReplacementStatus.MATCH, ReplacementStatus.NEW), validation.targets.map { it.validated.status })
        assertTrue(validation.targets.all { it.validated.newLineCount > 0 })
        assertTrue(validation.targets.none { it.validated.message.contains("binary", ignoreCase = true) })
        assertTrue(validation.targets.none { it.validated.selected })
    }
}
