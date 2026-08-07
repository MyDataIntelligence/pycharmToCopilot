package nl.ferron.copilotcontextbridge.patch

import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
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
import nl.ferron.copilotcontextbridge.analysis.SymbolIndexer
import nl.ferron.copilotcontextbridge.context.ContextPackService
import nl.ferron.copilotcontextbridge.security.PathSafety
import java.nio.file.Files
import java.nio.file.Path

class PatchValidator(
    private val project: Project,
    private val testFileResolver: ((String) -> PyFile?)? = null,
    private val testRepositoryId: String? = null,
    private val testRootVirtualFile: VirtualFile? = null,
) {
    data class Target(
        val validated: ValidatedReplacement,
        val function: PyFunction?,
        val parsed: PyFunction?,
        val insertionParent: PsiElement? = null,
        val insertionAnchor: PsiElement? = null,
        val file: PyFile? = null,
        val fileParent: PsiDirectory? = null,
        val fileOperationReady: Boolean = false,
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
                testRootVirtualFile ?: ProjectRoot.virtualFile(project)
            }.getOrElse { return Result(PatchValidationResult(null, emptyList(), listOf("No project root."), emptyList()), emptyList()) }
        val root = if (testRootVirtualFile == null) ProjectRoot.path(project) else null
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
        val exportedBaseTexts = loadExportedBaseTexts(patch, warnings)
        val targets =
            patch.replacements.map { request ->
                val preliminary =
                    runCatching {
                        val relative = PathSafety.normalizeRelative(request.path)
                        require(relative.endsWith(".py", true)) { "Target must be a Python file." }
                        when (request.operation) {
                            "add_file" -> validateFileAddition(request, root, rootVf, relative)
                            "delete_file" -> {
                                val file = testFileResolver?.invoke(relative) ?: resolveProjectFile(checkNotNull(root), rootVf, relative)
                                validateFileDeletion(request, file)
                            }
                            "add_function" -> {
                                val file = testFileResolver?.invoke(relative) ?: resolveProjectFile(checkNotNull(root), rootVf, relative)
                                validateAddition(request, file)
                            }
                            else -> {
                                val file = testFileResolver?.invoke(relative) ?: resolveProjectFile(checkNotNull(root), rootVf, relative)
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
                                    else ->
                                        validateFunction(
                                            request,
                                            matches.single(),
                                            exportedBaseTexts["${request.path}::${request.qualifiedName}"].orEmpty(),
                                        )
                                }
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

    private fun validateFileAddition(
        request: FunctionReplacement,
        root: Path?,
        rootVf: com.intellij.openapi.vfs.VirtualFile,
        relative: String,
    ): Target {
        val targetPath = root?.let { PathSafety.resolveInside(it, relative, mustExist = false) }
        require(targetPath == null || !Files.exists(targetPath)) { "Target file already exists." }
        require(rootVf.findFileByRelativePath(relative) == null) { "Target file already exists." }
        val parentRelative = relative.substringBeforeLast('/', "")
        val parentVf = if (parentRelative.isBlank()) rootVf else rootVf.findFileByRelativePath(parentRelative)
        require(parentVf != null && parentVf.isDirectory) { "Target parent directory is not a project directory." }
        require(parentVf == rootVf || ProjectFileIndex.getInstance(project).isInContent(parentVf)) {
            "Target parent is outside project content roots."
        }
        val parentDirectory =
            PsiManager.getInstance(project).findDirectory(parentVf)
                ?: error("Target parent is not a PSI directory.")
        val text = request.replacement ?: error("New file content is missing.")
        val parsed =
            PsiFileFactory
                .getInstance(project)
                .createFileFromText(relative.substringAfterLast('/'), PythonFileType.INSTANCE, text) as PyFile
        val syntaxError = PsiTreeUtil.findChildOfType(parsed, PsiErrorElement::class.java)
        if (syntaxError != null) {
            return Target(
                invalid(request, ReplacementStatus.INVALID, "New Python file has invalid syntax: ${syntaxError.errorDescription}"),
                null,
                null,
                fileOperationReady = true,
            )
        }
        val validated =
            ValidatedReplacement(
                request = request,
                status = ReplacementStatus.NEW,
                message = "New Python file is syntactically valid and its destination is safe.",
                newText = text,
                newLineCount = text.lines().size,
                unifiedDiff = UnifiedDiff.create(relative, "", text),
            )
        return Target(validated, null, null, file = parsed, fileParent = parentDirectory, fileOperationReady = true)
    }

    private fun validateFileDeletion(
        request: FunctionReplacement,
        file: PyFile,
    ): Target {
        val currentHash = FileContentHasher.hash(file.text)
        val status = if (currentHash == request.originalHash) ReplacementStatus.MATCH else ReplacementStatus.CHANGED
        val message =
            if (status == ReplacementStatus.MATCH) {
                "Current file exactly matches the exported file hash and can be deleted."
            } else {
                "The file changed after export; explicit force is required before deletion."
            }
        val validated =
            ValidatedReplacement(
                request = request,
                status = status,
                message = message,
                oldText = file.text,
                oldLineCount = file.text.lines().size,
                unifiedDiff = UnifiedDiff.create(request.path, file.text, ""),
                selected = status == ReplacementStatus.MATCH,
            )
        return Target(validated, null, null, file = file, fileOperationReady = true)
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
        baseText: String,
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
        val parsedName = SymbolIndexer.functionName(parsed)
        val targetName = SymbolIndexer.functionName(target)
        if (parsedName !=
            targetName
        ) {
            return Target(
                invalid(request, ReplacementStatus.INVALID, "Replacement name '$parsedName' does not match '$targetName'."),
                target,
                parsed,
            )
        }
        if (!request.allowAsyncChange &&
            SymbolIndexer.isAsync(parsed) != SymbolIndexer.isAsync(target)
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
                baseText = baseText,
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
        val parsedName = SymbolIndexer.functionName(parsed).orEmpty()
        val expectedName = if (parentName.isBlank()) parsedName else "$parentName.$parsedName"
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

    private fun loadExportedBaseTexts(
        patch: CopilotPatch,
        warnings: MutableList<String>,
    ): Map<String, String> {
        val stagingRoot = Path.of(System.getProperty("java.io.tmpdir"), "CopilotContextBridge")
        if (!Files.isDirectory(stagingRoot)) return emptyMap()
        val baseFile =
            Files.list(stagingRoot).use { stream ->
                stream
                    .filter { it.fileName.toString().endsWith("_${patch.sessionId}") }
                    .map { it.resolve(".session/base-functions.json") }
                    .filter(Files::isRegularFile)
                    .findFirst()
                    .orElse(null)
            } ?: return emptyMap()
        return runCatching {
            JsonParser
                .parseString(Files.readString(baseFile))
                .asJsonArray
                .associate { item ->
                    val value = item.asJsonObject
                    "${value.get("path").asString}::${value.get("qualifiedName").asString}" to value.get("text").asString
                }
        }.onFailure { warnings += "Exported BASE function content could not be read; 3-way conflict diff is unavailable." }
            .getOrDefault(emptyMap())
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
