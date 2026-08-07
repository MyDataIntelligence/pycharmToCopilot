package nl.ferron.copilotcontextbridge.patch

import com.intellij.openapi.command.undo.UndoManager
import com.intellij.psi.PsiManager
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.python.psi.PyFile
import nl.ferron.copilotcontextbridge.analysis.FunctionHasher
import nl.ferron.copilotcontextbridge.settings.ProjectSettings

class PatchApplicationTest : BasePlatformTestCase() {
    fun testReplacesOneFunctionAndPreservesSurroundingCode() {
        val file =
            myFixture.addFileToProject(
                "src/service.py",
                listOf(
                    "BEFORE = 1",
                    "",
                    "def changed(value: int) -> int:",
                    "    '''Return the old result.'''",
                    "    return value + 1",
                    "",
                    "def untouched() -> str:",
                    "    return 'keep me'",
                    "",
                    "AFTER = 2",
                ).joinToString("\n"),
            ) as PyFile
        val target = PythonFunctionLocator.find(file, "changed").single()
        val request =
            FunctionReplacement(
                operation = "replace_function",
                path = "src/service.py",
                qualifiedName = "changed",
                originalHash = FunctionHasher.hash(target.text),
                replacement =
                    """
                    def changed(value: int) -> int:
                        '''Return the new result.'''
                        return value + 2
                    """.trimIndent(),
                replacementFile = null,
            )

        val validation = validator(request.path, file).validate(patch(request))
        val validated = validation.targets.single().validated
        assertEquals(
            validated.toString(),
            ReplacementStatus.MATCH,
            validated.status,
        )
        val key = "src/service.py::changed"
        val result = PythonFunctionReplacementService(project).apply(validation, setOf(key), emptySet())

        assertEquals(listOf("src/service.py:changed"), result.applied)
        val updated = PsiManager.getInstance(project).findFile(file.virtualFile)!!.text
        assertTrue(updated.contains("return value + 2"))
        assertTrue(updated.contains("return 'keep me'"))
        assertTrue(updated.contains("BEFORE = 1"))
        assertTrue(updated.contains("AFTER = 2"))
        assertTrue(UndoManager.getInstance(project).isUndoAvailable(null))
    }

    fun testAddsNewClassMethodAtRequestedLocation() {
        val file =
            myFixture.addFileToProject(
                "src/client.py",
                listOf(
                    "class Client:",
                    "    def existing(self) -> str:",
                    "        return 'existing'",
                    "",
                    "def outside() -> str:",
                    "    return 'outside'",
                ).joinToString("\n"),
            ) as PyFile
        val request =
            FunctionReplacement(
                operation = "add_function",
                path = "src/client.py",
                qualifiedName = "Client.created",
                originalHash = null,
                replacement =
                    """
                    def created(self, value: int) -> int:
                        '''Return a newly supported value.'''
                        return value
                    """.trimIndent(),
                replacementFile = null,
                parentQualifiedName = "Client",
                insertAfterQualifiedName = "Client.existing",
            )

        val validation = validator(request.path, file).validate(patch(request))
        val validated = validation.targets.single().validated
        assertEquals(
            validated.toString(),
            ReplacementStatus.NEW,
            validated.status,
        )
        val key = "src/client.py::Client.created"
        val result = PythonFunctionReplacementService(project).apply(validation, setOf(key), emptySet())

        assertEquals(listOf("src/client.py:Client.created"), result.applied)
        val updated = PsiManager.getInstance(project).findFile(file.virtualFile) as PyFile
        assertSize(1, PythonFunctionLocator.find(updated, "Client.created"))
        assertSize(1, PythonFunctionLocator.find(updated, "outside"))
    }

    fun testChangedHashRequiresExplicitForce() {
        val file = myFixture.addFileToProject("module.py", "def work():\n    return 1\n") as PyFile
        val request =
            FunctionReplacement(
                operation = "replace_function",
                path = "module.py",
                qualifiedName = "work",
                originalHash = "sha256:not-the-current-hash",
                replacement = "def work():\n    return 2\n",
                replacementFile = null,
            )

        val validation = validator(request.path, file).validate(patch(request))
        val validated = validation.targets.single().validated
        assertEquals(
            validated.toString(),
            ReplacementStatus.CHANGED,
            validated.status,
        )
        val key = "module.py::work"
        val skipped = PythonFunctionReplacementService(project).apply(validation, setOf(key), emptySet())
        assertEmpty(skipped.applied)
        assertTrue(skipped.skipped.single().contains("CHANGED"))

        val forced = PythonFunctionReplacementService(project).apply(validation, setOf(key), setOf(key))
        assertEquals(listOf("module.py:work"), forced.applied)
    }

    fun testAppliesMultipleFunctionsAcrossFilesAndPreservesUnselectedFunction() {
        val first =
            myFixture.addFileToProject(
                "src/first.py",
                "def one():\n    return 1\n\ndef untouched():\n    return 'keep'\n",
            ) as PyFile
        val second = myFixture.addFileToProject("src/second.py", "def two():\n    return 2\n") as PyFile
        val requests =
            listOf(
                replacement("src/first.py", first, "one", "def one():\n    return 10\n"),
                replacement("src/second.py", second, "two", "def two():\n    return 20\n"),
            )
        val files = mapOf("src/first.py" to first, "src/second.py" to second)
        val validation = PatchValidator(project, files::get, "fixture-repository").validate(patch(*requests.toTypedArray()))

        val result =
            PythonFunctionReplacementService(project).apply(
                validation,
                setOf("src/first.py::one", "src/second.py::two"),
                emptySet(),
            )

        assertEquals(listOf("src/first.py:one", "src/second.py:two"), result.applied)
        assertTrue(first.text.contains("return 10"))
        assertTrue(first.text.contains("return 'keep'"))
        assertTrue(second.text.contains("return 20"))
        assertTrue(UndoManager.getInstance(project).isUndoAvailable(null))
    }

    fun testOptimizeImportsRunsOnlyWhenExplicitlyEnabled() {
        val file =
            myFixture.addFileToProject(
                "src/imports.py",
                "import json\nimport pathlib\n\ndef run():\n    return json.dumps({'ok': True})\n",
            ) as PyFile
        PsiTestUtil.addSourceRoot(module, file.virtualFile.parent)
        val request =
            replacement(
                "src/imports.py",
                file,
                "run",
                "def run():\n    return json.dumps({'ok': False})\n",
            )
        val validation = validator(request.path, file).validate(patch(request))
        project.getService(ProjectSettings::class.java).state.optimizeImports = true

        PythonFunctionReplacementService(project).apply(validation, setOf("src/imports.py::run"), emptySet())

        assertTrue(file.text.contains("import json"))
        assertFalse(file.text.contains("import pathlib"))
    }

    private fun replacement(
        path: String,
        file: PyFile,
        name: String,
        text: String,
    ): FunctionReplacement {
        val target = PythonFunctionLocator.find(file, name).single()
        return FunctionReplacement("replace_function", path, name, FunctionHasher.hash(target.text), text, null)
    }

    private fun validator(
        relativePath: String,
        file: PyFile,
    ) = PatchValidator(project, { requested -> file.takeIf { requested == relativePath } }, "fixture-repository")

    private fun patch(vararg requests: FunctionReplacement): CopilotPatch =
        CopilotPatch(
            formatVersion = 1,
            repositoryId = "fixture-repository",
            sessionId = "missing-test-session",
            replacements = requests.toList(),
            summary =
                PatchSummary(
                    overview = "Test change.",
                    functions = requests.map { PatchSummaryItem(it.path, it.qualifiedName, "Changed", "Test") },
                    testsPerformed = listOf("Unit test"),
                    risks = listOf("None known"),
                    limitations = listOf("None"),
                ),
        )
}
