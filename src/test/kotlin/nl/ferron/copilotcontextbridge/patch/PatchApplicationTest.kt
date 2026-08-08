package nl.ferron.copilotcontextbridge.patch

import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
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
        assertTrue("Structured conflicts start selected so Apply can report the unresolved decision", validated.selected)
        val key = "module.py::work"
        val skipped = PythonFunctionReplacementService(project).apply(validation, setOf(key), emptySet())
        assertEmpty(skipped.applied)
        assertTrue(skipped.skipped.single().contains("CHANGED"))

        val forced = PythonFunctionReplacementService(project).apply(validation, setOf(key), setOf(key))
        assertEquals(listOf("module.py:work"), forced.applied)
    }

    fun testAddsCompletePythonFileAndSupportsUndo() {
        val existing = myFixture.addFileToProject("src/existing.py", "VALUE = 1\n")
        val request =
            FunctionReplacement(
                operation = "add_file",
                path = "src/generated.py",
                qualifiedName = FILE_OPERATION_QUALIFIED_NAME,
                originalHash = null,
                replacement = "def generated() -> int:\n    return 42\n",
                replacementFile = null,
            )

        val validation =
            PatchValidator(
                project,
                testRepositoryId = "fixture-repository",
                testRootVirtualFile = rootOf("src/existing.py", existing as PyFile),
            ).validate(patch(request))
        assertEquals(
            validation.targets
                .single()
                .validated
                .toString(),
            ReplacementStatus.NEW,
            validation.targets
                .single()
                .validated.status,
        )

        val result =
            PythonFunctionReplacementService(project).apply(
                validation,
                setOf("src/generated.py::$FILE_OPERATION_QUALIFIED_NAME"),
                emptySet(),
            )

        assertEquals(listOf("src/generated.py:$FILE_OPERATION_QUALIFIED_NAME"), result.applied)
        val created = myFixture.findFileInTempDir("src/generated.py")
        assertNotNull(created)
        assertTrue(
            PsiManager
                .getInstance(project)
                .findFile(created)!!
                .text
                .contains("return 42"),
        )
        assertTrue(UndoManager.getInstance(project).isUndoAvailable(null))
    }

    fun testRejectsInvalidPythonForNewFile() {
        val existing = myFixture.addFileToProject("src/existing.py", "VALUE = 1\n") as PyFile
        val request =
            FunctionReplacement(
                "add_file",
                "src/broken.py",
                FILE_OPERATION_QUALIFIED_NAME,
                null,
                "def broken(:\n",
                null,
            )

        val validation =
            PatchValidator(project, testRepositoryId = "fixture-repository", testRootVirtualFile = rootOf("src/existing.py", existing))
                .validate(patch(request))

        assertEquals(
            ReplacementStatus.INVALID,
            validation.targets
                .single()
                .validated.status,
        )
    }

    fun testDeletesCompleteFileOnlyWhenExactHashMatches() {
        val file = myFixture.addFileToProject("src/obsolete.py", "VALUE = 'obsolete'\n") as PyFile
        val request =
            FunctionReplacement(
                "delete_file",
                "src/obsolete.py",
                FILE_OPERATION_QUALIFIED_NAME,
                FileContentHasher.hash(file.text),
                null,
                null,
            )
        val validation = validator(request.path, file).validate(patch(request))
        assertEquals(
            validation.targets
                .single()
                .validated
                .toString(),
            ReplacementStatus.MATCH,
            validation.targets
                .single()
                .validated.status,
        )

        val result =
            PythonFunctionReplacementService(project).apply(
                validation,
                setOf("src/obsolete.py::$FILE_OPERATION_QUALIFIED_NAME"),
                emptySet(),
            )

        assertEquals(listOf("src/obsolete.py:$FILE_OPERATION_QUALIFIED_NAME"), result.applied)
        assertNull(myFixture.findFileInTempDir("src/obsolete.py"))
        assertTrue(UndoManager.getInstance(project).isUndoAvailable(null))
    }

    fun testChangedFileHashRequiresForceBeforeDelete() {
        val file = myFixture.addFileToProject("src/changed.py", "VALUE = 2\n") as PyFile
        val request =
            FunctionReplacement(
                "delete_file",
                "src/changed.py",
                FILE_OPERATION_QUALIFIED_NAME,
                FileContentHasher.hash("VALUE = 1\n"),
                null,
                null,
            )
        val validation = validator(request.path, file).validate(patch(request))
        val key = "src/changed.py::$FILE_OPERATION_QUALIFIED_NAME"

        assertEquals(
            validation.targets
                .single()
                .validated
                .toString(),
            ReplacementStatus.CHANGED,
            validation.targets
                .single()
                .validated.status,
        )
        assertEmpty(PythonFunctionReplacementService(project).apply(validation, setOf(key), emptySet()).applied)
        assertEquals(
            listOf("src/changed.py:$FILE_OPERATION_QUALIFIED_NAME"),
            PythonFunctionReplacementService(project).apply(validation, setOf(key), setOf(key)).applied,
        )
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

    fun testInvalidPatchCannotBypassValidationThroughApplyService() {
        val file = myFixture.addFileToProject("module.py", "def work():\n    return 1\n") as PyFile
        val request = replacement("module.py", file, "work", "def work():\n    return 2\n")
        val invalid =
            PatchValidator(project, { file }, "different-repository")
                .validate(patch(request))

        assertFalse(invalid.validation.valid)
        val result = PythonFunctionReplacementService(project).apply(invalid, setOf("module.py::work"), emptySet())

        assertEmpty(result.applied)
        assertEquals(listOf("Patch validation failed; no project files were modified."), result.failures)
        assertTrue(file.text.contains("return 1"))
    }

    fun testDeleteFileHashUsesExactSavedBytesInsteadOfNormalizedPsiText() {
        val file = myFixture.addFileToProject("src/windows_lines.py", "VALUE = 1\r\n") as PyFile
        FileDocumentManager.getInstance().saveAllDocuments()
        val exactHash =
            "sha256:" +
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(file.virtualFile.contentsToByteArray())
                    .joinToString("") { "%02x".format(it) }
        val request =
            FunctionReplacement(
                "delete_file",
                "src/windows_lines.py",
                FILE_OPERATION_QUALIFIED_NAME,
                exactHash,
                null,
                null,
            )

        val validation = validator(request.path, file).validate(patch(request))

        assertEquals(
            ReplacementStatus.MATCH,
            validation.targets
                .single()
                .validated.status,
        )
    }

    fun testOneUndoRestoresReplacementAdditionNewFileAndDeletionTogether() {
        val changed = myFixture.addFileToProject("src/changed.py", "def changed():\n    return 1\n") as PyFile
        val additions = myFixture.addFileToProject("src/additions.py", "def existing():\n    return 0\n") as PyFile
        val deleted = myFixture.addFileToProject("src/deleted.py", "VALUE = 'keep until apply'\n") as PyFile
        val root = rootOf("src/changed.py", changed)
        val requests =
            listOf(
                replacement("src/changed.py", changed, "changed", "def changed():\n    return 2\n"),
                FunctionReplacement(
                    "add_function",
                    "src/additions.py",
                    "created",
                    null,
                    "def created():\n    return 3\n",
                    null,
                    parentQualifiedName = "",
                    insertAfterQualifiedName = "existing",
                ),
                FunctionReplacement(
                    "add_file",
                    "src/new_file.py",
                    FILE_OPERATION_QUALIFIED_NAME,
                    null,
                    "def new_file():\n    return 4\n",
                    null,
                ),
                FunctionReplacement(
                    "delete_file",
                    "src/deleted.py",
                    FILE_OPERATION_QUALIFIED_NAME,
                    FileContentHasher.hash(deleted),
                    null,
                    null,
                ),
            )
        val files = mapOf("src/changed.py" to changed, "src/additions.py" to additions, "src/deleted.py" to deleted)
        val validation = PatchValidator(project, files::get, "fixture-repository", root).validate(patch(*requests.toTypedArray()))
        val keys = requests.mapTo(linkedSetOf()) { "${it.path}::${it.qualifiedName}" }

        val result = PythonFunctionReplacementService(project).apply(validation, keys, emptySet())

        assertEquals(4, result.applied.size)
        assertTrue(changed.text.contains("return 2"))
        assertSize(1, PythonFunctionLocator.find(additions, "created"))
        assertNotNull(myFixture.findFileInTempDir("src/new_file.py"))
        assertNull(myFixture.findFileInTempDir("src/deleted.py"))

        UndoManager.getInstance(project).undo(null)

        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val restoredChanged = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("src/changed.py")) as PyFile
        val restoredAdditions = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("src/additions.py")) as PyFile
        assertTrue(restoredChanged.text.contains("return 1"))
        assertEmpty(PythonFunctionLocator.find(restoredAdditions, "created"))
        assertNull(myFixture.findFileInTempDir("src/new_file.py"))
        assertNotNull(myFixture.findFileInTempDir("src/deleted.py"))
    }

    fun testMultipleNewFunctionsSharingAnchorKeepPatchOrder() {
        val file = myFixture.addFileToProject("src/order.py", "def anchor():\n    return 0\n") as PyFile
        val additions =
            listOf("first", "second").map { name ->
                FunctionReplacement(
                    "add_function",
                    "src/order.py",
                    name,
                    null,
                    "def $name():\n    return '$name'\n",
                    null,
                    parentQualifiedName = "",
                    insertAfterQualifiedName = "anchor",
                )
            }
        val validation = validator("src/order.py", file).validate(patch(*additions.toTypedArray()))
        val result =
            PythonFunctionReplacementService(project).apply(
                validation,
                additions.mapTo(linkedSetOf()) { "${it.path}::${it.qualifiedName}" },
                emptySet(),
            )

        assertEquals(2, result.applied.size)
        assertTrue(file.text.indexOf("def anchor") < file.text.indexOf("def first"))
        assertTrue(file.text.indexOf("def first") < file.text.indexOf("def second"))
    }

    fun testReplacesCompleteDecoratedAsyncMethodAndNestedFunction() {
        val file =
            myFixture.addFileToProject(
                "src/complex.py",
                """
                class Client:
                    @staticmethod
                    async def fetch(value: int) -> int:
                        '''Old docstring.'''
                        return value

                def outer() -> int:
                    prefix = 10
                    def inner(value: int) -> int:
                        return value + 1
                    return inner(prefix)
                """.trimIndent() + "\n",
            ) as PyFile
        val method =
            replacement(
                "src/complex.py",
                file,
                "Client.fetch",
                """
                @staticmethod
                async def fetch(value: int) -> int:
                    '''New complete docstring.'''
                    adjusted = value + 1
                    return adjusted
                """.trimIndent(),
            )
        val nested =
            replacement(
                "src/complex.py",
                file,
                "outer.inner",
                "def inner(value: int) -> int:\n    return value + 2\n",
            )
        val validation = validator("src/complex.py", file).validate(patch(method, nested))

        val result =
            PythonFunctionReplacementService(project).apply(
                validation,
                setOf("src/complex.py::Client.fetch", "src/complex.py::outer.inner"),
                emptySet(),
            )

        assertEquals(2, result.applied.size)
        val updatedMethod = PythonFunctionLocator.find(file, "Client.fetch").single()
        assertTrue(updatedMethod.text.contains("@staticmethod"))
        assertTrue(
            nl.ferron.copilotcontextbridge.analysis.SymbolIndexer
                .isAsync(updatedMethod),
        )
        assertTrue(updatedMethod.text.contains("'''New complete docstring.'''") && updatedMethod.text.contains("return adjusted"))
        assertTrue(
            PythonFunctionLocator
                .find(file, "outer.inner")
                .single()
                .text
                .contains("return value + 2"),
        )
        assertTrue(file.text.contains("prefix = 10") && file.text.contains("return inner(prefix)"))
    }

    fun testOptimizeImportsRunsOnlyWhenExplicitlyEnabled() {
        myFixture.addFileToProject("src/used_module.py", "VALUE = True\n")
        myFixture.addFileToProject("src/unused_module.py", "VALUE = False\n")
        val file =
            myFixture.addFileToProject(
                "src/imports.py",
                "import used_module\nimport unused_module\n\ndef run():\n    return used_module.VALUE\n",
            ) as PyFile
        PsiTestUtil.addSourceRoot(module, file.virtualFile.parent)
        val request =
            replacement(
                "src/imports.py",
                file,
                "run",
                "def run():\n    return not used_module.VALUE\n",
            )
        val validation = validator(request.path, file).validate(patch(request))
        project.getService(ProjectSettings::class.java).state.optimizeImports = true

        PythonFunctionReplacementService(project).apply(validation, setOf("src/imports.py::run"), emptySet())

        assertTrue(file.text.contains("import used_module"))
        assertFalse(file.text.contains("import unused_module"))
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
    ) = PatchValidator(
        project,
        { requested -> file.takeIf { requested == relativePath } },
        "fixture-repository",
        rootOf(relativePath, file),
    )

    private fun rootOf(
        relativePath: String,
        file: PyFile,
    ): com.intellij.openapi.vfs.VirtualFile {
        var current = file.virtualFile
        repeat(relativePath.replace('\\', '/').split('/').size) { current = current.parent }
        return current
    }

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
