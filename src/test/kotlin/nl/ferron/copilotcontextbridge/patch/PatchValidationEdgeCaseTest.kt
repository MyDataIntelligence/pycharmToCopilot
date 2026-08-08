package nl.ferron.copilotcontextbridge.patch

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.python.psi.PyFile
import nl.ferron.copilotcontextbridge.analysis.FunctionHasher

class PatchValidationEdgeCaseTest : BasePlatformTestCase() {
    fun testRejectsInvalidSyntaxAndMultipleFunctions() {
        val file = pyFile("src/module.py", "def run():\n    return 1\n")
        val invalid = validate(file, request(file, "run", "def run(:\n    return 2\n"))
        val multiple = validate(file, request(file, "run", "def run():\n    return 2\n\ndef extra():\n    return 3\n"))

        assertEquals(
            ReplacementStatus.INVALID,
            invalid.targets
                .single()
                .validated.status,
        )
        assertTrue(
            invalid.targets
                .single()
                .validated.message
                .contains("invalid Python syntax"),
        )
        assertEquals(
            ReplacementStatus.INVALID,
            multiple.targets
                .single()
                .validated.status,
        )
        assertTrue(
            multiple.targets
                .single()
                .validated.message
                .contains("exactly one function"),
        )
    }

    fun testSyncAsyncAndDecoratorChangesRequireExplicitFlags() {
        val asyncFile = pyFile("async_module.py", "def run():\n    return 1\n")
        val asyncRequest = request(asyncFile, "run", "async def run():\n    return 2\n")
        assertEquals(
            ReplacementStatus.INVALID,
            validate(asyncFile, asyncRequest)
                .targets
                .single()
                .validated.status,
        )
        assertEquals(
            ReplacementStatus.MATCH,
            validate(asyncFile, asyncRequest.copy(allowAsyncChange = true))
                .targets
                .single()
                .validated.status,
        )

        val methodFile = pyFile("client.py", "class Client:\n    @staticmethod\n    def run():\n        return 1\n")
        val methodRequest = request(methodFile, "Client.run", "def run():\n    return 2\n")
        assertEquals(
            ReplacementStatus.INVALID,
            validate(methodFile, methodRequest)
                .targets
                .single()
                .validated.status,
        )
        assertEquals(
            ReplacementStatus.MATCH,
            validate(methodFile, methodRequest.copy(allowDecoratorKindChange = true))
                .targets
                .single()
                .validated.status,
        )
    }

    fun testRejectsTraversalAbsoluteWrongRepositoryAndNonPythonPath() {
        val file = pyFile("safe.py", "def run():\n    return 1\n")
        val traversal = request(file, "run", "def run():\n    return 2\n").copy(path = "../../outside.py")
        val absolute = traversal.copy(path = "C:\\Windows\\outside.py")
        val nonPython = traversal.copy(path = "config/settings.yaml")

        assertEquals(
            ReplacementStatus.INVALID,
            validate(file, traversal)
                .targets
                .single()
                .validated.status,
        )
        assertEquals(
            ReplacementStatus.INVALID,
            validate(file, absolute)
                .targets
                .single()
                .validated.status,
        )
        assertEquals(
            ReplacementStatus.INVALID,
            validate(file, nonPython)
                .targets
                .single()
                .validated.status,
        )

        val wrongRepository = validator(file).validate(patch(request(file, "run", "def run():\n    return 2\n"), repositoryId = "other"))
        assertTrue(
            wrongRepository.validation.errors
                .single()
                .contains("does not match"),
        )
    }

    fun testMissingAndAmbiguousQualifiedNamesStayUnselectable() {
        val file = pyFile("duplicates.py", "def duplicate():\n    return 1\n\ndef duplicate():\n    return 2\n")
        val ambiguous = validate(file, request(file, "duplicate", "def duplicate():\n    return 3\n"))
        val missing = validate(file, request(file, "missing", "def missing():\n    return 3\n", hash = "sha256:unused"))

        assertEquals(
            ReplacementStatus.AMBIGUOUS,
            ambiguous.targets
                .single()
                .validated.status,
        )
        assertFalse(
            ambiguous.targets
                .single()
                .validated.selected,
        )
        assertEquals(
            ReplacementStatus.MISSING,
            missing.targets
                .single()
                .validated.status,
        )
        assertFalse(
            missing.targets
                .single()
                .validated.selected,
        )
    }

    fun testOverlappingParentAndNestedReplacementsAreRejectedTogether() {
        val file =
            pyFile(
                "nested.py",
                "def outer():\n    def inner():\n        return 1\n    return inner()\n",
            )
        val outer = request(file, "outer", "def outer():\n    def inner():\n        return 2\n    return inner()\n")
        val inner = request(file, "outer.inner", "def inner():\n    return 2\n")

        val result = validator(file).validate(patch(outer, inner))

        assertEquals(listOf(ReplacementStatus.INVALID, ReplacementStatus.INVALID), result.targets.map { it.validated.status })
        assertTrue(result.targets.all { it.validated.message.contains("Overlapping") })
    }

    fun testRejectsDuplicateTargetAndWholeFileOperationMixedWithFunctionEdit() {
        val file = pyFile("mixed.py", "def run():\n    return 1\n")
        val replacement = request(file, "run", "def run():\n    return 2\n")
        val deletion =
            FunctionReplacement(
                "delete_file",
                "mixed.py",
                FILE_OPERATION_QUALIFIED_NAME,
                FileContentHasher.hash(file),
                null,
                null,
            )

        val duplicateResult = validator(file).validate(patch(replacement, replacement.copy(replacement = "def run():\n    return 3\n")))
        assertEquals(listOf(ReplacementStatus.INVALID, ReplacementStatus.INVALID), duplicateResult.targets.map { it.validated.status })
        assertTrue(duplicateResult.targets.all { it.validated.message.contains("Duplicate operations") })

        val mixedResult = validator(file).validate(patch(replacement, deletion))
        assertEquals(listOf(ReplacementStatus.INVALID, ReplacementStatus.INVALID), mixedResult.targets.map { it.validated.status })
        assertTrue(mixedResult.targets.all { it.validated.message.contains("whole-file operation") })
    }

    fun testNewNestedFunctionUsesUnambiguousParentAndAnchor() {
        val file =
            pyFile(
                "nested_add.py",
                "def outer():\n    def existing():\n        return 1\n    return existing()\n",
            )
        val addition =
            FunctionReplacement(
                "add_function",
                "nested_add.py",
                "outer.created",
                null,
                "def created():\n    return 2\n",
                null,
                parentQualifiedName = "outer",
                insertAfterQualifiedName = "outer.existing",
            )
        val validation = validate(file, addition)

        assertEquals(
            ReplacementStatus.NEW,
            validation.targets
                .single()
                .validated.status,
        )
        val result = PythonFunctionReplacementService(project).apply(validation, setOf("nested_add.py::outer.created"), emptySet())
        assertEquals(listOf("nested_add.py:outer.created"), result.applied)
        assertSize(1, PythonFunctionLocator.find(file, "outer.created"))
    }

    fun testAddsDecoratedAsyncMethodAndPreservesItsCompleteKind() {
        val file =
            pyFile(
                "complex_add.py",
                "class Client:\n    def existing(self):\n        return 1\n",
            )
        val addition =
            FunctionReplacement(
                "add_function",
                "complex_add.py",
                "Client.fetch",
                null,
                "@staticmethod\nasync def fetch(value: int) -> int:\n    '''Fetch a value.'''\n    return value\n",
                null,
                parentQualifiedName = "Client",
                insertAfterQualifiedName = "Client.existing",
            )
        val validation = validate(file, addition)

        assertEquals(
            ReplacementStatus.NEW,
            validation.targets
                .single()
                .validated.status,
        )
        val result = PythonFunctionReplacementService(project).apply(validation, setOf("complex_add.py::Client.fetch"), emptySet())

        assertEquals(listOf("complex_add.py:Client.fetch"), result.applied)
        val inserted = PythonFunctionLocator.find(file, "Client.fetch").single()
        assertTrue(inserted.text.contains("@staticmethod"))
        assertTrue(
            nl.ferron.copilotcontextbridge.analysis.SymbolIndexer
                .isAsync(inserted),
        )
        assertTrue(inserted.text.contains("'''Fetch a value.'''") && inserted.text.contains("return value"))
    }

    private fun pyFile(
        path: String,
        text: String,
    ) = myFixture.addFileToProject(path, text) as PyFile

    private fun request(
        file: PyFile,
        name: String,
        replacement: String,
        hash: String? = null,
    ): FunctionReplacement {
        val target = PythonFunctionLocator.find(file, name).singleOrNull()
        return FunctionReplacement(
            "replace_function",
            file.virtualFile.path.substringAfterLast("/src/"),
            name,
            hash ?: target?.let { FunctionHasher.hash(it.text) } ?: "sha256:missing",
            replacement,
            null,
        )
    }

    private fun validate(
        file: PyFile,
        request: FunctionReplacement,
    ) = validator(file).validate(patch(request))

    private fun validator(file: PyFile) = PatchValidator(project, { file }, "fixture-repository")

    private fun patch(
        vararg requests: FunctionReplacement,
        repositoryId: String = "fixture-repository",
    ) = CopilotPatch(
        1,
        repositoryId,
        "missing-test-session",
        requests.toList(),
        PatchSummary("Edge cases", emptyList(), listOf("Unit tests"), emptyList(), emptyList()),
    )
}
