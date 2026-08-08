package nl.ferron.copilotcontextbridge.context

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import nl.ferron.copilotcontextbridge.ProjectRoot
import nl.ferron.copilotcontextbridge.analysis.DependencyAnalyzer
import nl.ferron.copilotcontextbridge.analysis.RepositoryScanner
import nl.ferron.copilotcontextbridge.external.ExternalRepositoryContextAnalyzer
import nl.ferron.copilotcontextbridge.external.ExternalRepositoryDropResolver
import nl.ferron.copilotcontextbridge.external.ExternalRepositorySelectionRegistry
import nl.ferron.copilotcontextbridge.guidelines.GuidelineService
import nl.ferron.copilotcontextbridge.model.AttachmentKind
import nl.ferron.copilotcontextbridge.model.ContextPack
import nl.ferron.copilotcontextbridge.model.DependencyRelation
import nl.ferron.copilotcontextbridge.model.PlannedAttachment
import nl.ferron.copilotcontextbridge.model.RelationConfidence
import nl.ferron.copilotcontextbridge.model.RelationType
import nl.ferron.copilotcontextbridge.model.sourceKey
import nl.ferron.copilotcontextbridge.settings.AppSettings
import nl.ferron.copilotcontextbridge.settings.PreviousBatchMode
import nl.ferron.copilotcontextbridge.settings.ProjectSettings
import nl.ferron.copilotcontextbridge.settings.ReturnInstructions
import nl.ferron.copilotcontextbridge.state.ContextSelectionService
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@Service(Service.Level.PROJECT)
class ContextPackService(
    private val project: Project,
) {
    @Volatile var latestPack: ContextPack? = null
        private set

    fun build(): ContextPack {
        val settings = project.getService(ProjectSettings::class.java).state
        val selectionService = project.getService(ContextSelectionService::class.java)
        selectionService.validatePaths()
        val app = AppSettings.getInstance()
        val skill = app.skill(settings.selectedPromptSkillId)
        val policy = skill.contextPolicy
        val projection = ContextPolicyProjection.from(policy, settings)
        val gitRule = policy.rules.firstOrNull { it.enabled && it.resolver == "git.branchChanges" }
        val gitRequestedPaths =
            if (gitRule?.parameters?.get("scope")?.lowercase() in setOf("selected", "selected-changed")) {
                selectionService.pinnedPaths().toSet()
            } else {
                null
            }
        val gitContext =
            if (projection.includeBranchChanges) {
                GitBranchContextResolver(ProjectRoot.path(project)).resolve(gitRequestedPaths)
            } else {
                null
            }
        val analysis =
            ReadAction
                .nonBlocking<DependencyAnalyzer.Result> {
                    DependencyAnalyzer(project, policy, gitContext?.changedPaths.orEmpty().toSet()).analyze()
                }.executeSynchronously()
        val externalRegistry = project.getService(ExternalRepositorySelectionRegistry::class.java)
        val externalResolver =
            ExternalRepositoryDropResolver(
                ProjectRoot.path(project),
                app.state.ignorePatterns,
                settings.customIgnorePatterns,
                (app.state.secretFilenamePatterns + settings.projectSecretFilenamePatterns).distinct(),
                settings.textualScanLimitBytes,
                app.state.excludedContextExtensions,
            )
        val externalCandidates =
            externalRegistry
                .selections()
                .flatMap { externalSelection ->
                    val pinned = externalSelection.pinnedFiles.map(externalResolver::toCandidate)
                    val archive =
                        externalSelection.archiveFiles.map(externalResolver::toCandidate).map { candidate ->
                            candidate.copy(previouslySent = candidate.sourceKey in selectionService.sentSourceKeys())
                        }
                    val discovered =
                        externalSelection.discoveryDirectories.flatMap { directory ->
                            val discovery = externalResolver.discoverFiles(directory, policy.maxRepositoryFiles.coerceIn(1, 500))
                            discovery.accepted.map(externalResolver::toCandidate).map { candidate ->
                                candidate.copy(
                                    pinned = false,
                                    score = 200,
                                    confidence = RelationConfidence.INFERRED,
                                    relations =
                                        listOf(
                                            DependencyRelation(
                                                directory.relativePath,
                                                candidate.relativePath,
                                                RelationType.SAME_PACKAGE,
                                                RelationConfidence.INFERRED,
                                                evidence = "discovered from explicitly dropped directory",
                                            ),
                                        ),
                                )
                            }
                        }
                    val analyzedDiscovery =
                        ExternalRepositoryContextAnalyzer(policy, settings.textualScanLimitBytes).analyze(pinned, discovered)
                    pinned + archive + analyzedDiscovery
                }.distinctBy { it.sourceKey }
        val allCandidates =
            PreviousBatchFilter
                .markIgnored(analysis.candidates + externalCandidates, projection.avoidPrevious)
                .map { candidate ->
                    if (candidate.resolverId.isNotBlank() && candidate.policyRuleId.isNotBlank()) {
                        candidate
                    } else {
                        val primaryRule = ContextResolverRegistry.primaryRule(candidate, policy)
                        candidate.copy(
                            resolverId =
                                candidate.resolverId.ifBlank {
                                    primaryRule?.resolver
                                        ?: ContextResolverRegistry.primaryResolver(candidate, policy)
                                },
                            policyRuleId = candidate.policyRuleId.ifBlank { primaryRule?.id.orEmpty() },
                        )
                    }
                }
        val effectiveCandidates =
            allCandidates
                .filter { candidate ->
                    if (candidate.repositoryId.isNotBlank()) {
                        candidate.pinned || candidate.sourceKey !in externalRegistry.excludedSourceKeys()
                    } else {
                        candidate.pinned || candidate.relativePath !in selectionService.excludedAutomaticPaths()
                    }
                }.map { candidate ->
                    if (!settings.blockLikelySecrets) candidate.copy(secretWarning = null) else candidate
                }.let { candidates ->
                    if (settings.automaticallyFillDependencies) {
                        candidates
                    } else {
                        candidates.filter { candidate -> candidate.pinned || candidateRequiredByPolicy(candidate, policy) }
                    }
                }
        val excludedCandidates =
            allCandidates
                .filter { candidate ->
                    !candidate.pinned &&
                        if (candidate.repositoryId.isNotBlank()) {
                            candidate.sourceKey in externalRegistry.excludedSourceKeys()
                        } else {
                            candidate.relativePath in selectionService.excludedAutomaticPaths()
                        }
                }.map { candidate -> candidate.copy(ignoredReason = "excluded by the user for this batch, session, or project") }
        val ranked = DependencyRanker.allocate(effectiveCandidates, policy.maxRepositoryFiles.coerceIn(1, 500), reserveContextFile = false)
        val sourceAttachmentLimit = projection.maximumFiles - if (gitContext == null) 0 else 1
        val sourceAttachmentPlan = ContextAttachmentPacker.plan(ranked.included, policy, sourceAttachmentLimit.coerceAtLeast(1))
        val attachmentPlan =
            if (gitContext == null) {
                sourceAttachmentPlan
            } else {
                sourceAttachmentPlan.copy(
                    attachments =
                        listOf(
                            PlannedAttachment(
                                stagedName = "01_PR_CHANGES.md",
                                kind = AttachmentKind.GENERATED_CONTEXT,
                                candidates = emptyList(),
                                bundleGroup = "pr",
                                generatedContent = gitContext.markdown,
                            ),
                        ) + sourceAttachmentPlan.attachments,
                )
            }
        val representedPaths = sourceAttachmentPlan.repositoryToAttachment.keys
        val packingOmitted =
            sourceAttachmentPlan.omittedByPolicy.map {
                it.copy(ignoredReason = "physical Copilot attachment limit reached; lower-priority automatic context")
            }
        val attachmentErrors =
            buildList {
                if (projection.includeBranchChanges && gitContext == null && gitRule?.required == true) {
                    add("Cannot prepare PR context: the current project root is not an accessible Git repository.")
                }
                if (attachmentPlan.attachmentCount > projection.maximumFiles) {
                    add(
                        "Cannot prepare context: ${attachmentPlan.attachmentCount} physical attachments exceed " +
                            "policy limit ${projection.maximumFiles}. Reduce pinned files, enable automatic bundling, " +
                            "or increase the attachment limit up to 20.",
                    )
                }
            }
        val invalidPinnedErrors =
            selectionService.invalidPinnedPaths().map {
                "Pinned path no longer exists: $it. Remove it from the pinned selection or add the moved file again."
            }
        val analysisWarnings = analysis.warnings.filterNot { it.startsWith("Pinned path no longer exists:") }
        val finalSelection =
            ranked.copy(
                included = ranked.included.filter { it.sourceKey in representedPaths },
                omitted = (ranked.omitted + packingOmitted).distinctBy { it.sourceKey },
                validationErrors = ranked.validationErrors + attachmentErrors + invalidPinnedErrors,
                warnings =
                    ranked.warnings +
                        analysisWarnings +
                        if (packingOmitted.isEmpty()) {
                            emptyList()
                        } else {
                            listOf(
                                "${packingOmitted.size} automatic context files were omitted because the physical attachment limit was reached.",
                            )
                        },
                excluded = excludedCandidates,
            )
        val guidelines = GuidelineService(project).merge(skill.prompt, skill.guidelines, policy)
        val returnInstructions = ReturnInstructions.resolve(app.state, settings, skill)
        val rootVf = ProjectRoot.virtualFile(project)
        val root = ProjectRoot.path(project)
        val repositoryId = rootVf.name.replace(Regex("[^A-Za-z0-9._-]"), "-")
        val sessionId =
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + "_" + UUID.randomUUID().toString().take(6)
        val markdown =
            ContextMarkdownRenderer.render(
                ContextMarkdownRenderer.Input(
                    repositoryId,
                    sessionId,
                    finalSelection,
                    if (settings.includeRepositoryTree) {
                        repositoryTrees(
                            analysis.tree,
                            externalRegistry,
                            app,
                            settings,
                        )
                    } else {
                        "Repository tree disabled for this batch."
                    },
                    analysis.relations,
                    analysis.symbols,
                    attachmentPlan.repositoryToAttachment,
                    guidelines.markdown,
                    guidelines.sources.filter { it.enabled }.map { it.relativePath },
                    skill.name,
                    skill.prompt,
                    attachmentPlan.attachmentCount,
                    when (
                        runCatching {
                            PreviousBatchMode.valueOf(
                                policy.previousBatchMode,
                            )
                        }.getOrDefault(PreviousBatchMode.SAME_SESSION_ONLY)
                    ) {
                        PreviousBatchMode.NEVER -> emptyList()
                        PreviousBatchMode.SAME_SESSION_ONLY -> selectionService.currentSessionBatches()
                        PreviousBatchMode.ALWAYS -> selectionService.batches()
                    },
                    settings.generateMermaid,
                    root.toString().takeIf { settings.includeAbsoluteRepositoryPath },
                    returnInstructions.mode,
                    returnInstructions.effectiveText,
                ),
            )
        return ContextPack(
            sessionId,
            repositoryId,
            markdown,
            finalSelection,
            analysis.relations,
            analysis.symbols,
            if (settings.includeRepositoryTree) {
                repositoryTrees(
                    analysis.tree,
                    externalRegistry,
                    app,
                    settings,
                )
            } else {
                "Repository tree disabled for this batch."
            },
            guidelines.sources.filter { it.enabled }.map { it.relativePath },
            markdown.toByteArray().size.toLong() + finalSelection.included.sumOf { it.size },
            skill.id,
            attachmentPlan,
        ).also { latestPack = it }
    }

    fun repositoryFingerprint(): String {
        val root = project.basePath ?: "unknown"
        return MessageDigest.getInstance("SHA-256").digest(root.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun candidateRequiredByPolicy(
        candidate: nl.ferron.copilotcontextbridge.model.ContextCandidate,
        policy: nl.ferron.copilotcontextbridge.settings.ContextPolicyState,
    ): Boolean {
        policy.rule(candidate.policyRuleId)?.let { return it.enabled && it.required }
        val requiredResolvers = policy.rules.filter { it.enabled && it.required }.mapTo(hashSetOf()) { it.resolver }
        return candidate.relations.any { relation -> relation.type.contextResolvers().any { it in requiredResolvers } }
    }

    private fun repositoryTrees(
        projectTree: String,
        registry: ExternalRepositorySelectionRegistry,
        app: AppSettings,
        settings: ProjectSettings.Data,
    ): String =
        buildString {
            appendLine("REPOSITORY: ${ProjectRoot.path(project).fileName}")
            appendLine(projectTree)
            registry.selections().forEach { selection ->
                val scanner = RepositoryScanner(selection.repository.root, app.state.ignorePatterns, settings.customIgnorePatterns)
                val snapshot = scanner.scan()
                appendLine()
                appendLine("REPOSITORY: ${selection.repository.name} [${selection.repository.id}]")
                appendLine(scanner.renderTree(snapshot, selection.repository.name))
            }
        }.trimEnd()
}
