package nl.ferron.copilotcontextbridge.analysis

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.jetbrains.python.psi.PyFile
import nl.ferron.copilotcontextbridge.ProjectRoot
import nl.ferron.copilotcontextbridge.model.ContextCandidate
import nl.ferron.copilotcontextbridge.model.DependencyRelation
import nl.ferron.copilotcontextbridge.model.RelationConfidence
import nl.ferron.copilotcontextbridge.model.RelationType
import nl.ferron.copilotcontextbridge.security.PathSafety
import nl.ferron.copilotcontextbridge.security.SecretDetector
import nl.ferron.copilotcontextbridge.settings.AppSettings
import nl.ferron.copilotcontextbridge.settings.ProjectSettings
import nl.ferron.copilotcontextbridge.state.ContextSelectionService
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class DependencyAnalyzer(
    private val project: Project,
) {
    data class Result(
        val snapshot: RepositoryScanner.Snapshot,
        val tree: String,
        val candidates: List<ContextCandidate>,
        val relations: List<DependencyRelation>,
        val symbols: Map<String, List<nl.ferron.copilotcontextbridge.model.PythonSymbol>>,
        val warnings: List<String>,
    )

    fun analyze(indicator: ProgressIndicator? = null): Result {
        val rootVf = ProjectRoot.virtualFile(project)
        val root = ProjectRoot.path(project)
        val app = AppSettings.getInstance().state
        val settings = project.getService(ProjectSettings::class.java).state
        val selection = project.getService(ContextSelectionService::class.java)
        val pinned = selection.pinnedPaths().toSet()
        val scanner = RepositoryScanner(root, app.ignorePatterns, settings.customIgnorePatterns)
        val scanned = scanner.scan()
        val explicitlyPinned =
            pinned.mapNotNull { relative ->
                runCatching {
                    val path = PathSafety.resolveInside(root, relative)
                    if (Files.isRegularFile(path) && scanned.files.none { it.relativePath == relative }) {
                        RepositoryScanner.Entry(relative, path, false, Files.size(path))
                    } else {
                        null
                    }
                }.getOrNull()
            }
        val snapshot =
            scanned.copy(
                entries = (scanned.entries + explicitlyPinned).distinctBy { it.relativePath }.sortedBy { it.relativePath.lowercase() },
            )
        val byPath = snapshot.files.associateBy { it.relativePath }
        val discoveryRoots = selection.discoveryRoots()
        val sent = selection.sentPaths()
        val psiManager = PsiManager.getInstance(project)
        val warnings = mutableListOf<String>()
        val relations = mutableListOf<DependencyRelation>()
        val symbols = linkedMapOf<String, List<nl.ferron.copilotcontextbridge.model.PythonSymbol>>()

        val pythonFiles = snapshot.files.filter { it.relativePath.endsWith(".py") }
        pythonFiles.forEachIndexed { index, entry ->
            indicator?.checkCanceled()
            indicator?.fraction = if (pythonFiles.isEmpty()) 0.0 else index.toDouble() / pythonFiles.size
            val vf = rootVf.findFileByRelativePath(entry.relativePath) ?: return@forEachIndexed
            val pyFile = psiManager.findFile(vf) as? PyFile ?: return@forEachIndexed
            symbols[entry.relativePath] = SymbolIndexer.index(pyFile)
            pyFile.accept(
                object : PsiRecursiveElementWalkingVisitor() {
                    override fun visitElement(element: com.intellij.psi.PsiElement) {
                        element.references.forEach { reference ->
                            val resolved = runCatching { reference.resolve() }.getOrNull() ?: return@forEach
                            val target = resolved.containingFile?.virtualFile ?: return@forEach
                            if (!target.isInLocalFileSystem || target == vf) return@forEach
                            val targetPath =
                                target.path
                                    .removePrefix(rootVf.path)
                                    .trimStart('/')
                                    .replace('\\', '/')
                            if (targetPath !in byPath) return@forEach
                            val insideImport =
                                generateSequence(element.parent) { it.parent }
                                    .takeWhile { it !is PyFile }
                                    .any { it.javaClass.simpleName.contains("Import") }
                            relations +=
                                DependencyRelation(
                                    entry.relativePath,
                                    targetPath,
                                    if (insideImport) RelationType.DIRECT_IMPORT else RelationType.TEXT_REFERENCE,
                                    RelationConfidence.CONFIRMED,
                                    evidence = if (insideImport) "resolved Python import" else "resolved PSI reference",
                                )
                        }
                        super.visitElement(element)
                    }
                },
            )
        }

        if (settings.includeReferencedConfiguration) {
            addTextRelations(snapshot, root, settings.textualScanLimitBytes, relations, indicator)
        }
        addTestRelations(snapshot, relations)

        val secretDetector = SecretDetector(app.secretFilenamePatterns)
        val candidates = mutableListOf<ContextCandidate>()
        val candidatePaths = linkedSetOf<String>().apply { addAll(pinned) }
        val candidateDepths =
            DependencyTraversal
                .collect(
                    pinned,
                    relations,
                    DependencyTraversal.Options(
                        settings.includeDirectImports,
                        settings.includeDirectDependents,
                        settings.includeRelatedTests,
                        settings.includeReferencedConfiguration,
                        settings.includeSecondLevelDependencies,
                    ),
                ).toMutableMap()
        discoveryRoots.forEach { directory ->
            snapshot.files.filter { directory.isBlank() || it.relativePath.startsWith("$directory/") }.forEach {
                candidatePaths +=
                    it.relativePath
                candidateDepths.putIfAbsent(it.relativePath, 0)
            }
        }

        candidatePaths.addAll(candidateDepths.keys)

        if (settings.includePackageFolders) {
            pinned.mapNotNull { it.substringBeforeLast('/', "").takeIf(String::isNotBlank) }.forEach { dir ->
                snapshot.files.filter { it.relativePath.startsWith("$dir/") }.forEach {
                    candidatePaths += it.relativePath
                    candidateDepths.putIfAbsent(it.relativePath, 1)
                }
            }
        }
        if (pinned.any { it.endsWith(".py") }) {
            listOf("pyproject.toml", "setup.cfg", "tox.ini").filterTo(candidatePaths) { it in byPath }
        }
        packageInitPaths(candidatePaths.toList()).filterTo(candidatePaths) { it in byPath }

        candidatePaths.forEach { relative ->
            val entry = byPath[relative]
            if (entry == null) {
                warnings += "Pinned path no longer exists: $relative"
                return@forEach
            }
            if (!RepositoryScanner.isSupportedText(entry.path)) {
                candidates +=
                    baseCandidate(
                        entry,
                        pinned,
                        sent,
                        emptyList(),
                        0,
                        "unsupported or binary file",
                        null,
                        depth = candidateDepths[relative] ?: 0,
                    )
                return@forEach
            }
            val relevant =
                relations.filter { relation ->
                    (relation.to == relative && relation.from in pinned) || (relation.from == relative && relation.to in pinned)
                }
            val relationType =
                when {
                    relative in pinned -> RelationType.PINNED
                    candidateDepths[relative] == 2 -> RelationType.SECOND_LEVEL
                    relevant.any { it.type == RelationType.DIRECT_IMPORT && it.from in pinned } -> RelationType.DIRECT_IMPORT
                    relevant.any { it.type == RelationType.RELATED_TEST } -> RelationType.RELATED_TEST
                    relevant.any { it.to in pinned } -> RelationType.DIRECT_DEPENDENT
                    relative.endsWith("/__init__.py") || relative == "__init__.py" -> RelationType.PACKAGE_INIT
                    relative in setOf("pyproject.toml", "setup.cfg", "tox.ini") -> RelationType.PROJECT_CONFIGURATION
                    settings.includePackageFolders -> RelationType.SAME_PACKAGE
                    else -> relevant.firstOrNull()?.type ?: RelationType.SECOND_LEVEL
                }
            var score = settings.scores[relationType.name] ?: 0
            val generated = relative.contains("/generated/") || relative.startsWith("generated/") || relative.contains("/build/")
            if (generated) score += settings.scores["GENERATED_PENALTY"] ?: -500
            val text = if (settings.detectLikelySecrets && entry.size <= settings.textualScanLimitBytes) readText(entry.path) else null
            val secret = if (settings.detectLikelySecrets) secretDetector.describe(entry.path, text) else null
            val previouslySent = relative in sent
            val ignored =
                if (previouslySent &&
                    settings.avoidPreviouslySentFiles &&
                    relative !in pinned
                ) {
                    "already exported in an earlier batch"
                } else {
                    null
                }
            candidates += baseCandidate(entry, pinned, sent, relevant, score, ignored, secret, generated, candidateDepths[relative] ?: 0)
        }

        return Result(snapshot, scanner.renderTree(snapshot, root.fileName.toString()), candidates, relations.distinct(), symbols, warnings)
    }

    private fun baseCandidate(
        entry: RepositoryScanner.Entry,
        pinned: Set<String>,
        sent: Set<String>,
        relations: List<DependencyRelation>,
        score: Int,
        ignored: String?,
        secret: String?,
        generated: Boolean = false,
        depth: Int = 0,
    ) = ContextCandidate(
        entry.relativePath,
        entry.path,
        score,
        depth,
        relations.minByOrNull { it.confidence.ordinal }?.confidence ?: RelationConfidence.INFERRED,
        relations,
        pinned = entry.relativePath in pinned,
        generated = generated,
        secretWarning = secret,
        ignoredReason = ignored,
        previouslySent = entry.relativePath in sent,
        size = entry.size,
    )

    private fun addTestRelations(
        snapshot: RepositoryScanner.Snapshot,
        relations: MutableList<DependencyRelation>,
    ) {
        val python = snapshot.files.filter { it.relativePath.endsWith(".py") }
        python
            .filter {
                it.relativePath.substringAfterLast('/').startsWith("test_") ||
                    it.relativePath.contains("/tests/") ||
                    it.relativePath.startsWith("tests/")
            }.forEach { test ->
                val stem =
                    test.relativePath
                        .substringAfterLast('/')
                        .removePrefix("test_")
                        .removeSuffix("_test.py")
                        .removeSuffix(".py")
                python
                    .filter { candidate ->
                        candidate != test && candidate.relativePath.substringAfterLast('/').removeSuffix(".py") == stem
                    }.forEach { production ->
                        relations +=
                            DependencyRelation(
                                test.relativePath,
                                production.relativePath,
                                RelationType.RELATED_TEST,
                                RelationConfidence.INFERRED,
                                evidence = "test filename convention",
                            )
                    }
            }
    }

    private fun addTextRelations(
        snapshot: RepositoryScanner.Snapshot,
        root: Path,
        limit: Long,
        relations: MutableList<DependencyRelation>,
        indicator: ProgressIndicator?,
    ) {
        val byPath = snapshot.files.associateBy { it.relativePath.lowercase() }
        val byStem =
            snapshot.files.groupBy {
                it.path.fileName
                    .toString()
                    .substringBeforeLast('.')
                    .lowercase()
            }
        val pathPattern = Regex("(?i)[A-Za-z0-9_./\\\\-]+\\.(?:py|json|ya?ml|toml|sql|csv|ipynb|ps1|sh)")
        val notebookPattern = Regex("(?i)(?:notebookutils|mssparkutils)\\.notebook\\.run\\(\\s*['\"]([^'\"]+)")
        snapshot.files.filter { it.size in 1..limit && RepositoryScanner.isSupportedText(it.path) }.forEach { source ->
            indicator?.checkCanceled()
            val text = readText(source.path) ?: return@forEach
            pathPattern.findAll(text).map { it.value.replace('\\', '/').trimStart('.', '/') }.forEach { raw ->
                val candidates = listOf(raw, source.relativePath.substringBeforeLast('/', "").let { if (it.isBlank()) raw else "$it/$raw" })
                val target = candidates.firstNotNullOfOrNull { byPath[it.lowercase()] }
                if (target != null && target.relativePath != source.relativePath) {
                    relations +=
                        DependencyRelation(
                            source.relativePath,
                            target.relativePath,
                            RelationType.REFERENCED_CONFIGURATION,
                            RelationConfidence.INFERRED,
                            evidence = "textual path reference",
                        )
                }
            }
            notebookPattern.findAll(text).forEach { match ->
                val stem =
                    match.groupValues[1]
                        .substringAfterLast('/')
                        .substringBeforeLast('.')
                        .lowercase()
                byStem[stem].orEmpty().filter { it.relativePath != source.relativePath }.forEach { target ->
                    relations +=
                        DependencyRelation(
                            source.relativePath,
                            target.relativePath,
                            RelationType.TEXT_REFERENCE,
                            RelationConfidence.INFERRED,
                            evidence = "Fabric notebook run reference",
                        )
                }
            }
            if (text.contains("ExecutePipeline") || text.contains("NotebookActivity")) {
                byStem.forEach { (stem, targets) ->
                    if (stem.length >= 4 && Regex("(?i)[\"']${Regex.escape(stem)}[\"']").containsMatchIn(text)) {
                        targets.filter { it.relativePath != source.relativePath }.forEach { target ->
                            relations +=
                                DependencyRelation(
                                    source.relativePath,
                                    target.relativePath,
                                    RelationType.TEXT_REFERENCE,
                                    RelationConfidence.INFERRED,
                                    evidence = "Fabric pipeline or notebook name",
                                )
                        }
                    }
                }
            }
        }
    }

    private fun packageInitPaths(paths: List<String>): Set<String> =
        buildSet {
            paths.filter { it.endsWith(".py") }.forEach { path ->
                var parent = path.substringBeforeLast('/', "")
                while (parent.isNotBlank()) {
                    add("$parent/__init__.py")
                    parent = parent.substringBeforeLast('/', "")
                }
            }
        }

    private fun readText(path: Path): String? = runCatching { Files.readString(path, StandardCharsets.UTF_8) }.getOrNull()
}
