package nl.ferron.copilotcontextbridge.patch

import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.PythonFileType
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyStatement
import nl.ferron.copilotcontextbridge.ProjectRoot
import nl.ferron.copilotcontextbridge.analysis.FunctionHasher
import nl.ferron.copilotcontextbridge.context.ContextPackService
import nl.ferron.copilotcontextbridge.security.PathSafety
import java.nio.file.Files
import java.nio.file.Path

class PatchValidator(
    private val project: Project,
    private val testFileResolver: ((String) -> PyFile?)? = null,
    private val testRepositoryId: String? = null,
) {
    data class Target(
        val validated: ValidatedReplacement,
        val function: PyFunction?,
        val parsed: PyFunction?,
        val insertionParent: PsiElement? = null,
        val insertionAnchor: PsiElement? = null,
    )

    data class Result(
        val validation: PatchValidationResult,
        val targets: List<Target>,
    )

    fun validate(patch: CopilotPatch): Result {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val rootVf =
            runCatching {
                ProjectRoot.virtualFile(project)
            }.getOrElse { return Result(PatchValidationResult(null, emptyList(), listOf("No project root."), emptyList()), emptyList()) }
        val root = ProjectRoot.path(project)
        val expectedRepository = testRepositoryId ?: rootVf.name.replace(Regex("[^A-Za-z0-9._-]"), "-")
        if (patch.repositoryId !=
            expectedRepository
        ) {
            errors += "Patch repositoryId '${patch.repositoryId}' does not match '$expectedRepository'."
        }
        if (patch.summary ==
            null
        ) {
            warnings += "Patch has no structured change summary. Code validation continues, but the requested summary is missing."
        }
        validateSession(patch, errors, warnings)
        val targets =
            patch.replacements.map { request ->
                val preliminary =
                    runCatching {
                        val relative = PathSafety.normalizeRelative(request.path)
                        require(relative.endsWith(".py", true)) { "Target must be a Python file." }
                        val file = testFileResolver?.invoke(relative) ?: resolveProjectFile(root, rootVf, relative)
                        if (request.operation == "add_function") {
                            validateAddition(request, file)
                        } else {
                            val matches = PythonFunctionLocator.find(file, request.qualifiedName)
                            when {
                                matches.isEmpty() ->
                                    Target(
                                        invalid(request, ReplacementStatus.MISSING, "Function was not found."),
                                        null,
                                        null,
                                    )
                                matches.size > 1 ->
                                    Target(
                                        invalid(request, ReplacementStatus.AMBIGUOUS, "More than one function matched."),
                                        null,
                                        null,
                                    )
                                else -> validateFunction(request, matches.single())
                            }
                        }
                    }.getOrElse { Target(invalid(request, ReplacementStatus.INVALID, it.message ?: "Invalid replacement."), null, null) }
                preliminary
            }
        val overlapping =
            targets.mapNotNull { it.function }.let { functions ->
                functions
                    .flatMap { first ->
                        functions
                            .filter { second ->
                                first != second &&
                                    (PsiTreeUtil.isAncestor(first, second, true) || PsiTreeUtil.isAncestor(second, first, true))
                            }.flatMap { second -> listOf(first, second) }
                    }.toSet()
            }
        val adjusted =
            targets.map { target ->
                if (target.function in
                    overlapping
                ) {
                    Target(
                        invalid(
                            target.validated.request,
                            ReplacementStatus.INVALID,
                            "Overlapping parent/nested replacements are not allowed in one patch.",
                        ),
                        null,
                        null,
                    )
                } else {
                    target
                }
            }
        return Result(PatchValidationResult(patch, adjusted.map { it.validated }, errors, warnings), adjusted)
    }

    private fun resolveProjectFile(
        root: Path,
        rootVf: com.intellij.openapi.vfs.VirtualFile,
        relative: String,
    ): PyFile {
        PathSafety.resolveInside(root, relative)
        val vf = rootVf.findFileByRelativePath(relative) ?: error("Target is not a project file.")
        require(ProjectFileIndex.getInstance(project).isInContent(vf)) {
            "Target is outside project content roots."
        }
        return PsiManager.getInstance(project).findFile(vf) as? PyFile
            ?: error("Target is not parsed as Python.")
    }

    private fun validateFunction(
        request: FunctionReplacement,
        target: PyFunction,
    ): Target {
        val text = request.replacement ?: error("Replacement text is missing.")
        val dedented = dedent(text)
        val parsedFile =
            PsiFileFactory
                .getInstance(
                    project,
                ).createFileFromText("copilot-replacement.py", PythonFileType.INSTANCE, dedented) as PyFile
        val syntaxError = PsiTreeUtil.findChildOfType(parsedFile, PsiErrorElement::class.java)
        if (syntaxError !=
            null
        ) {
            return Target(
                invalid(request, ReplacementStatus.INVALID, "Replacement has invalid Python syntax: ${syntaxError.errorDescription}"),
                target,
                null,
            )
        }
        val functions = parsedFile.children.filterIsInstance<PyFunction>()
        val executable = parsedFile.children.filterIsInstance<PyStatement>().filterNot { it is PyFunction }
        if (functions.size != 1 ||
            executable.isNotEmpty()
        ) {
            return Target(
                invalid(
                    request,
                    ReplacementStatus.INVALID,
                    "Replacement must contain exactly one function and no other top-level statements.",
                ),
                target,
                null,
            )
        }
        val parsed = functions.single()
        if (parsed.name !=
            target.name
        ) {
            return Target(
                invalid(request, ReplacementStatus.INVALID, "Replacement name '${parsed.name}' does not match '${target.name}'."),
                target,
                parsed,
            )
        }
        if (!request.allowAsyncChange &&
            parsed.isAsync != target.isAsync
        ) {
            return Target(
                invalid(request, ReplacementStatus.INVALID, "Sync/async type changed without allowAsyncChange."),
                target,
                parsed,
            )
        }
        val targetDecoratorKind = decoratorKind(target.text)
        val newDecoratorKind = decoratorKind(parsed.text)
        if (!request.allowDecoratorKindChange &&
            targetDecoratorKind != newDecoratorKind
        ) {
            return Target(
                invalid(request, ReplacementStatus.INVALID, "classmethod/staticmethod decorator kind changed without permission."),
                target,
                parsed,
            )
        }
        val currentHash = FunctionHasher.hash(target.text)
        val status = if (currentHash == request.originalHash) ReplacementStatus.MATCH else ReplacementStatus.CHANGED
        val message =
            if (status ==
                ReplacementStatus.MATCH
            ) {
                "Current function matches the exported hash."
            } else {
                "The local function changed after export; explicit force is required."
            }
        val validated =
            ValidatedReplacement(
                request,
                status,
                message,
                target.text,
                parsed.text,
                target.text.lines().size,
                parsed.text.lines().size,
                UnifiedDiff.create(request.path, target.text, parsed.text),
                selected = status == ReplacementStatus.MATCH,
            )
        return Target(validated, target, parsed)
    }

    private fun validateAddition(
        request: FunctionReplacement,
        file: PyFile,
    ): Target {
        if (PythonFunctionLocator.find(file, request.qualifiedName).isNotEmpty()) {
            return Target(invalid(request, ReplacementStatus.INVALID, "A function with this qualified name already exists."), null, null)
        }
        val parsedResult = parseSingle(request)
        if (parsedResult.first != null) return Target(invalid(request, ReplacementStatus.INVALID, parsedResult.first!!), null, null)
        val parsed = parsedResult.second!!
        val parentName = request.parentQualifiedName.orEmpty()
        val expectedName = if (parentName.isBlank()) parsed.name.orEmpty() else "$parentName.${parsed.name}"
        if (request.qualifiedName != expectedName) {
            return Target(
                invalid(request, ReplacementStatus.INVALID, "qualifiedName must be '$expectedName' for this parent and function."),
                null,
                parsed,
            )
        }
        val parents: List<PsiElement> =
            if (parentName.isBlank()) {
                listOf(file)
            } else {
                buildList {
                    addAll(
                        PsiTreeUtil.findChildrenOfType(file, PyClass::class.java).filter {
                            nl.ferron.copilotcontextbridge.analysis.SymbolIndexer
                                .qualifiedName(it) ==
                                parentName
                        },
                    )
                    addAll(PythonFunctionLocator.find(file, parentName))
                }
            }
        if (parents.isEmpty()) {
            return Target(
                invalid(request, ReplacementStatus.MISSING, "Insertion parent '$parentName' was not found."),
                null,
                parsed,
            )
        }
        if (parents.size >
            1
        ) {
            return Target(invalid(request, ReplacementStatus.AMBIGUOUS, "Insertion parent '$parentName' is ambiguous."), null, parsed)
        }
        val parent = parents.single()
        val anchor =
            request.insertAfterQualifiedName?.let { anchorName ->
                val matches = PythonFunctionLocator.find(file, anchorName)
                if (matches.size !=
                    1
                ) {
                    return Target(
                        invalid(
                            request,
                            if (matches.isEmpty()) ReplacementStatus.MISSING else ReplacementStatus.AMBIGUOUS,
                            "Insertion anchor '$anchorName' was not found unambiguously.",
                        ),
                        null,
                        parsed,
                    )
                }
                val candidate = matches.single()
                val candidateParent = nearestNamedParent(candidate)
                val parentQualified =
                    if (parent is PyFile) {
                        ""
                    } else {
                        nl.ferron.copilotcontextbridge.analysis.SymbolIndexer
                            .qualifiedName(parent)
                    }
                if (candidateParent !=
                    parentQualified
                ) {
                    return Target(
                        invalid(request, ReplacementStatus.INVALID, "Insertion anchor is not a direct child of the requested parent."),
                        null,
                        parsed,
                    )
                }
                candidate
            }
        val validated =
            ValidatedReplacement(
                request,
                ReplacementStatus.NEW,
                "New function is syntactically valid and its insertion parent is unambiguous.",
                "",
                parsed.text,
                0,
                parsed.text.lines().size,
                UnifiedDiff.create(request.path, "", parsed.text),
                selected = true,
            )
        return Target(validated, null, parsed, parent, anchor)
    }

    private fun parseSingle(request: FunctionReplacement): Pair<String?, PyFunction?> {
        val text = request.replacement ?: return "Replacement text is missing." to null
        val parsedFile =
            PsiFileFactory
                .getInstance(
                    project,
                ).createFileFromText("copilot-addition.py", PythonFileType.INSTANCE, dedent(text)) as PyFile
        val syntaxError = PsiTreeUtil.findChildOfType(parsedFile, PsiErrorElement::class.java)
        if (syntaxError != null) return "Replacement has invalid Python syntax: ${syntaxError.errorDescription}" to null
        val functions = parsedFile.children.filterIsInstance<PyFunction>()
        val executable = parsedFile.children.filterIsInstance<PyStatement>().filterNot { it is PyFunction }
        if (functions.size != 1 ||
            executable.isNotEmpty()
        ) {
            return "Addition must contain exactly one function and no other top-level statements." to null
        }
        return null to functions.single()
    }

    private fun nearestNamedParent(function: PyFunction): String {
        var current: PsiElement? = function.parent
        while (current != null && current !is PyFile) {
            if (current is PyClass ||
                current is PyFunction
            ) {
                return nl.ferron.copilotcontextbridge.analysis.SymbolIndexer
                    .qualifiedName(current)
            }
            current = current.parent
        }
        return ""
    }

    private fun invalid(
        request: FunctionReplacement,
        status: ReplacementStatus,
        message: String,
    ) = ValidatedReplacement(request, status, message)

    private fun validateSession(
        patch: CopilotPatch,
        errors: MutableList<String>,
        warnings: MutableList<String>,
    ) {
        val stagingRoot = Path.of(System.getProperty("java.io.tmpdir"), "CopilotContextBridge")
        if (!Files.isDirectory(stagingRoot)) {
            warnings += "The originating context session is no longer available locally."
            return
        }
        val manifest =
            Files.list(stagingRoot).use { stream ->
                stream
                    .map { it.resolve(".session/context-session.json") }
                    .filter {
                        Files.isRegularFile(it) &&
                            it.parent.parent.fileName
                                .toString()
                                .endsWith("_${patch.sessionId}")
                    }.findFirst()
                    .orElse(null)
            }
        if (manifest ==
            null
        ) {
            warnings += "Session '${patch.sessionId}' was not found; repository and function hashes remain enforced."
            return
        }
        runCatching {
            val json = JsonParser.parseString(Files.readString(manifest)).asJsonObject
            require(json.get("repositoryId")?.asString == patch.repositoryId) { "Session repository ID does not match the patch." }
            val expected = project.getService(ContextPackService::class.java).repositoryFingerprint()
            require(json.get("repositoryFingerprint")?.asString == expected) { "Session belongs to a different local repository." }
        }.onFailure { errors += it.message ?: "Session validation failed." }
    }

    companion object {
        fun dedent(text: String): String {
            val normalized = FunctionHasher.normalize(text).trim('\n')
            val lines = normalized.lines()
            val indent = lines.filter { it.isNotBlank() }.minOfOrNull { it.takeWhile(Char::isWhitespace).length } ?: 0
            return lines.joinToString("\n") { if (it.isBlank()) "" else it.drop(indent) } + "\n"
        }

        private fun decoratorKind(text: String): String =
            when {
                Regex("(?m)^\\s*@classmethod\\b").containsMatchIn(text) -> "classmethod"
                Regex("(?m)^\\s*@staticmethod\\b").containsMatchIn(text) -> "staticmethod"
                else -> "regular"
            }
    }
}
