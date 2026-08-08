package nl.ferron.copilotcontextbridge.analysis

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.jetbrains.python.psi.PyFile
import nl.ferron.copilotcontextbridge.ProjectRoot
import nl.ferron.copilotcontextbridge.context.ContextPolicyProjection
import nl.ferron.copilotcontextbridge.context.ContextResolverRegistry
import nl.ferron.copilotcontextbridge.context.ResolverStrategy
import nl.ferron.copilotcontextbridge.context.contextResolvers
import nl.ferron.copilotcontextbridge.model.ContextCandidate
import nl.ferron.copilotcontextbridge.model.DependencyRelation
import nl.ferron.copilotcontextbridge.model.RelationConfidence
import nl.ferron.copilotcontextbridge.model.RelationType
import nl.ferron.copilotcontextbridge.security.PathSafety
import nl.ferron.copilotcontextbridge.security.SecretDetector
import nl.ferron.copilotcontextbridge.settings.AppSettings
import nl.ferron.copilotcontextbridge.settings.ContextPolicyState
import nl.ferron.copilotcontextbridge.settings.PreviousBatchMode
import nl.ferron.copilotcontextbridge.settings.ProjectSettings
import nl.ferron.copilotcontextbridge.staging.TextFileSupport
import nl.ferron.copilotcontextbridge.state.ContextSelectionService
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class DependencyAnalyzer(
    private val project: Project,
    private val policy: ContextPolicyState? = null,
    private val additionalSeedPaths: Set<String> = emptySet(),
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
        val projection = policy?.let { ContextPolicyProjection.from(it, settings) }
        val selection = project.getService(ContextSelectionService::class.java)
        val pinned = selection.pinnedPaths().toSet()
        val seedPaths = pinned + additionalSeedPaths
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
        val previousBatchMode =
            policy
                ?.previousBatchMode
                ?.let { runCatching { PreviousBatchMode.valueOf(it) }.getOrDefault(PreviousBatchMode.SAME_SESSION_ONLY) }
                ?: PreviousBatchMode.SAME_SESSION_ONLY
        val sent =
            when (previousBatchMode) {
                PreviousBatchMode.NEVER -> emptySet()
                PreviousBatchMode.SAME_SESSION_ONLY -> selection.sentPaths()
                PreviousBatchMode.ALWAYS -> selection.allSentPaths()
            }
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
                                    if (insideImport) RelationType.DIRECT_IMPORT else RelationType.DIRECT_CALLEE,
                                    RelationConfidence.CONFIRMED,
                                    evidence = if (insideImport) "resolved Python import" else "resolved Python symbol reference",
                                )
                        }
                        super.visitElement(element)
                    }
                },
            )
        }

        if (projection?.configuration ?: settings.includeReferencedConfiguration) {
            addTextRelations(snapshot, root, settings.textualScanLimitBytes, relations, indicator)
        }
        addTestRelations(snapshot, relations)

        val secretDetector = SecretDetector((app.secretFilenamePatterns + settings.projectSecretFilenamePatterns).distinct())
        val candidates = mutableListOf<ContextCandidate>()
        val candidatePaths = linkedSetOf<String>().apply { addAll(seedPaths) }
        val candidateDepths =
            DependencyTraversal
                .collect(
                    seedPaths,
                    relations,
                    DependencyTraversal.Options(
                        directImports = projection?.directImports ?: settings.includeDirectImports,
                        directCallees = projection?.directCallees ?: settings.includeDirectImports,
                        directDependents = projection?.directDependents ?: settings.includeDirectDependents,
                        relatedTests = projection?.relatedTests ?: settings.includeRelatedTests,
                        nearbyTests = projection?.nearbyTests ?: false,
                        referencedConfiguration = projection?.configuration ?: settings.includeReferencedConfiguration,
                        maximumDepth =
                            projection?.maximumDependencyDepth
                                ?: if (settings.includeSecondLevelDependencies) 2 else 1,
                        resolverLimits = projection?.resolverLimits.orEmpty(),
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
        applyPolicyResolvers(snapshot, seedPaths, candidatePaths, candidateDepths, relations, symbols)

        if (projection?.packageFolders ?: settings.includePackageFolders) {
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
            val extension = entry.relativePath.substringAfterLast('.', "").lowercase()
            if (extension in app.excludedContextExtensions.map { it.removePrefix(".").lowercase() } ||
                !TextFileSupport.isLikelyText(entry.path)
            ) {
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
                        resolverId =
                            if (relative in
                                pinned
                            ) {
                                "explicit.pinnedFiles"
                            } else if (relative in additionalSeedPaths) {
                                "git.branchChanges"
                            } else {
                                ""
                            },
                        policyRuleId =
                            when (relative) {
                                in pinned ->
                                    policy
                                        ?.rules
                                        ?.firstOrNull { it.enabled && it.resolver == "explicit.pinnedFiles" }
                                        ?.id
                                        .orEmpty()
                                in additionalSeedPaths ->
                                    policy
                                        ?.rules
                                        ?.firstOrNull { it.enabled && it.resolver == "git.branchChanges" }
                                        ?.id
                                        .orEmpty()
                                else -> ""
                            },
                    )
                return@forEach
            }
            val relevant =
                relations.filter { relation ->
                    (relation.to == relative && relation.from in candidatePaths) ||
                        (relation.from == relative && relation.to in candidatePaths)
                }
            val relationType =
                when {
                    relative in pinned -> RelationType.PINNED
                    relative in additionalSeedPaths -> RelationType.BRANCH_CHANGE
                    relevant.any { it.type == RelationType.TEST_FIXTURE } -> RelationType.TEST_FIXTURE
                    relevant.any { it.type == RelationType.TEMPLATE } -> RelationType.TEMPLATE
                    relevant.any { it.type == RelationType.SIMILAR_IMPLEMENTATION } -> RelationType.SIMILAR_IMPLEMENTATION
                    relevant.any { it.type == RelationType.INSTRUCTION } -> RelationType.INSTRUCTION
                    relevant.any { it.type == RelationType.DIRECT_IMPORT && it.from in pinned } -> RelationType.DIRECT_IMPORT
                    relevant.any { it.type == RelationType.DIRECT_CALLEE && it.from in pinned } -> RelationType.DIRECT_CALLEE
                    relevant.any { it.type == RelationType.RELATED_TEST } -> RelationType.RELATED_TEST
                    relevant.any { it.type == RelationType.NEARBY_TEST } -> RelationType.NEARBY_TEST
                    relevant.any { it.to in pinned } -> RelationType.DIRECT_DEPENDENT
                    (candidateDepths[relative] ?: 0) >= 2 -> RelationType.SECOND_LEVEL
                    relative.endsWith("/__init__.py") || relative == "__init__.py" -> RelationType.PACKAGE_INIT
                    relative in setOf("pyproject.toml", "setup.cfg", "tox.ini") -> RelationType.PROJECT_CONFIGURATION
                    (projection?.packageFolders ?: settings.includePackageFolders) -> RelationType.SAME_PACKAGE
                    else -> relevant.firstOrNull()?.type ?: RelationType.SECOND_LEVEL
                }
            var score = settings.scores[relationType.name] ?: 0
            score += policyPriorityAdjustment(relationType)
            val generated = relative.contains("/generated/") || relative.startsWith("generated/") || relative.contains("/build/")
            if (generated) score += settings.scores["GENERATED_PENALTY"] ?: -500
            val text = if (settings.detectLikelySecrets && entry.size <= settings.textualScanLimitBytes) readText(entry.path) else null
            val secret = if (settings.detectLikelySecrets) secretDetector.describe(entry.path, text) else null
            val previouslySent = relative in sent
            val ignored =
                if (previouslySent &&
                    (projection?.avoidPrevious ?: settings.avoidPreviouslySentFiles) &&
                    relative !in pinned
                ) {
                    "already exported in an earlier batch"
                } else {
                    null
                }
            val resolverIds = relationType.contextResolvers()
            val primaryRule =
                policy
                    ?.rules
                    ?.filter { it.enabled && it.resolver in resolverIds }
                    ?.maxWithOrNull(
                        compareBy<nl.ferron.copilotcontextbridge.settings.ContextRuleState> { it.priority }.thenByDescending { it.id },
                    )
            candidates +=
                baseCandidate(
                    entry,
                    pinned,
                    sent,
                    relevant,
                    score,
                    ignored,
                    secret,
                    generated,
                    candidateDepths[relative] ?: 0,
                    resolverId = primaryRule?.resolver ?: resolverIds.firstOrNull().orEmpty(),
                    policyRuleId = primaryRule?.id.orEmpty(),
                )
        }

        return Result(snapshot, scanner.renderTree(snapshot, root.fileName.toString()), candidates, relations.distinct(), symbols, warnings)
    }

    private fun applyPolicyResolvers(
        snapshot: RepositoryScanner.Snapshot,
        seedPaths: Set<String>,
        candidatePaths: MutableSet<String>,
        candidateDepths: MutableMap<String, Int>,
        relations: MutableList<DependencyRelation>,
        symbols: Map<String, List<nl.ferron.copilotcontextbridge.model.PythonSymbol>>,
    ) {
        val activePolicy = policy ?: return
        val rulesByStrategy =
            activePolicy.rules
                .asSequence()
                .filter { it.enabled }
                .mapNotNull { rule -> ContextResolverRegistry.find(rule.resolver)?.let { it.strategy to rule } }
                .groupBy({ it.first }, { it.second })

        fun firstRule(strategy: ResolverStrategy) = rulesByStrategy[strategy].orEmpty().maxByOrNull { it.priority }

        fun include(
            source: String,
            target: String,
            type: RelationType,
            evidence: String,
        ) {
            candidatePaths += target
            candidateDepths[target] = minOf(candidateDepths[target] ?: Int.MAX_VALUE, 1)
            relations += DependencyRelation(source, target, type, RelationConfidence.INFERRED, evidence = evidence)
        }

        firstRule(ResolverStrategy.TEST_FIXTURES)?.let { rule ->
            val tests = candidatePaths.filter { it.substringAfterLast('/').startsWith("test_") || "/tests/" in "/$it" }
            val fixtures =
                snapshot.files
                    .filter { entry -> entry.relativePath.endsWith("conftest.py") || "/fixtures/" in "/${entry.relativePath}" }
                    .sortedBy { it.relativePath }
                    .take(rule.maxFiles)
            tests.forEach { test ->
                fixtures
                    .filter { fixture ->
                        fixture.relativePath == "conftest.py" ||
                            test.startsWith(fixture.relativePath.substringBeforeLast('/', "") + "/")
                    }.forEach { fixture -> include(test, fixture.relativePath, RelationType.TEST_FIXTURE, "test fixture for $test") }
            }
        }

        firstRule(ResolverStrategy.NEARBY_TESTS)?.let { rule ->
            val matchingTests =
                relations
                    .filter { it.type == RelationType.RELATED_TEST && (it.from in seedPaths || it.to in seedPaths) }
                    .map { relation -> if (relation.from in seedPaths) relation.to else relation.from }
                    .filter { it.substringAfterLast('/').startsWith("test_") || "/tests/" in "/$it" }
                    .toSet()
            val testDirectories = matchingTests.map { it.substringBeforeLast('/', "") }.toSet()
            snapshot.files
                .asSequence()
                .filter { entry ->
                    val path = entry.relativePath
                    path !in seedPaths &&
                        path !in matchingTests &&
                        path.endsWith(".py") &&
                        (path.substringAfterLast('/').startsWith("test_") || "/tests/" in "/$path") &&
                        path.substringBeforeLast('/', "") in testDirectories
                }.sortedBy { it.relativePath }
                .take(rule.maxFiles)
                .forEach { nearby ->
                    include(seedPaths.firstOrNull().orEmpty(), nearby.relativePath, RelationType.NEARBY_TEST, "nearby test module")
                }
        }

        firstRule(ResolverStrategy.TEMPLATES)?.let { rule ->
            val extensions = seedPaths.map { it.substringAfterLast('.', "").lowercase() }.filter(String::isNotBlank).toSet()
            snapshot.files
                .filter { entry ->
                    val lower = "/${entry.relativePath.lowercase()}/"
                    ("/template" in lower || "/examples/" in lower) &&
                        (extensions.isEmpty() || entry.relativePath.substringAfterLast('.', "").lowercase() in extensions)
                }.sortedBy { it.relativePath }
                .take(rule.maxFiles)
                .forEach { template ->
                    include(seedPaths.firstOrNull().orEmpty(), template.relativePath, RelationType.TEMPLATE, "repository template/example")
                }
        }

        firstRule(ResolverStrategy.SIMILAR_IMPLEMENTATIONS)?.let { rule ->
            val seedSymbols = seedPaths.flatMap { path -> symbols[path].orEmpty() }.map { it.qualifiedName.substringAfterLast('.') }.toSet()
            symbols.entries
                .asSequence()
                .filter { (path, values) -> path !in seedPaths && values.any { it.qualifiedName.substringAfterLast('.') in seedSymbols } }
                .sortedBy { it.key }
                .take(rule.maxFiles)
                .forEach { (path) ->
                    include(
                        seedPaths.firstOrNull().orEmpty(),
                        path,
                        RelationType.SIMILAR_IMPLEMENTATION,
                        "shares repository symbols with selected code",
                    )
                }
        }

        val instructionResolvers = rulesByStrategy[ResolverStrategy.GUIDELINES].orEmpty()
        if (instructionResolvers.isNotEmpty()) {
            val instructionPaths =
                snapshot.files
                    .map { it.relativePath }
                    .filter { path ->
                        path == "AGENTS.md" ||
                            path.endsWith("/AGENTS.md") ||
                            path == ".github/copilot-instructions.md" ||
                            path.endsWith("SKILL.md") ||
                            path == "CONTRIBUTING.md"
                    }.sorted()
                    .take(instructionResolvers.maxOf { it.maxFiles })
            instructionPaths.forEach { path ->
                include(
                    seedPaths.firstOrNull().orEmpty(),
                    path,
                    RelationType.INSTRUCTION,
                    "repository instruction selected by Context Policy",
                )
            }
        }
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
        resolverId: String = "",
        policyRuleId: String = "",
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
        sha256 = sha256(entry.path),
        resolverId = resolverId,
        policyRuleId = policyRuleId,
    )

    private fun sha256(path: Path): String =
        runCatching {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            "sha256:" + digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrDefault("")

    private fun policyPriorityAdjustment(type: RelationType): Int {
        val activePolicy = policy ?: return 0
        return type.contextResolvers().maxOfOrNull { activePolicy.priorityFor(it, 0) }?.times(10) ?: 0
    }

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
                python
                    .asSequence()
                    .filter { candidate -> candidate != test && !isTestPath(candidate.relativePath) }
                    .map { production -> production to TestFileMatcher.score(test.relativePath, production.relativePath) }
                    .filter { (_, score) -> score >= 72 }
                    .sortedWith(compareByDescending<Pair<RepositoryScanner.Entry, Int>> { it.second }.thenBy { it.first.relativePath })
                    .take(MAX_FUZZY_TEST_MATCHES)
                    .forEach { (production, score) ->
                        relations +=
                            DependencyRelation(
                                test.relativePath,
                                production.relativePath,
                                RelationType.RELATED_TEST,
                                if (score == 100) RelationConfidence.CONFIRMED else RelationConfidence.INFERRED,
                                evidence = if (score == 100) "test filename convention" else "fuzzy test filename match ($score%)",
                            )
                    }
            }
    }

    private fun isTestPath(path: String): Boolean =
        path.substringAfterLast('/').startsWith("test_") || path.substringBeforeLast('.').endsWith("_test") || "/tests/" in "/$path"

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

    companion object {
        private const val MAX_FUZZY_TEST_MATCHES = 3
    }
}
