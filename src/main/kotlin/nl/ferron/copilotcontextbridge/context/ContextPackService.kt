package nl.ferron.copilotcontextbridge.context

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import nl.ferron.copilotcontextbridge.ProjectRoot
import nl.ferron.copilotcontextbridge.analysis.DependencyAnalyzer
import nl.ferron.copilotcontextbridge.guidelines.GuidelineService
import nl.ferron.copilotcontextbridge.model.ContextPack
import nl.ferron.copilotcontextbridge.settings.AppSettings
import nl.ferron.copilotcontextbridge.settings.ProjectSettings
import nl.ferron.copilotcontextbridge.staging.StagedFilenameService
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
        val analysis =
            ReadAction.nonBlocking<DependencyAnalyzer.Result> { DependencyAnalyzer(project).analyze() }.executeSynchronously()
        val settings = project.getService(ProjectSettings::class.java).state
        val selectionService = project.getService(ContextSelectionService::class.java)
        val effectiveCandidates =
            analysis.candidates
                .filter { candidate -> candidate.pinned || candidate.relativePath !in selectionService.excludedAutomaticPaths() }
                .map { candidate ->
                    if (!settings.blockLikelySecrets) candidate.copy(secretWarning = null) else candidate
                }.let { candidates -> if (settings.automaticallyFillDependencies) candidates else candidates.filter { it.pinned } }
        val ranked = DependencyRanker.allocate(effectiveCandidates, settings.maximumUploadFiles)
        val app = AppSettings.getInstance()
        val skill = app.skill(settings.selectedPromptSkillId)
        val guidelines = GuidelineService(project).merge(skill.prompt, skill.guidelines)
        val rootVf = ProjectRoot.virtualFile(project)
        val root = ProjectRoot.path(project)
        val repositoryId = rootVf.name.replace(Regex("[^A-Za-z0-9._-]"), "-")
        val sessionId =
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + "_" + UUID.randomUUID().toString().take(6)
        val names = StagedFilenameService.namesFor(ranked.included.map { it.relativePath })
        val markdown =
            ContextMarkdownRenderer.render(
                ContextMarkdownRenderer.Input(
                    repositoryId,
                    sessionId,
                    ranked,
                    if (settings.includeRepositoryTree) analysis.tree else "Repository tree disabled for this batch.",
                    analysis.relations,
                    analysis.symbols,
                    names,
                    guidelines.markdown,
                    guidelines.sources.filter { it.enabled }.map { it.relativePath },
                    skill.name,
                    skill.prompt,
                    selectionService.batches(),
                    settings.generateMermaid,
                    root.toString().takeIf { settings.includeAbsoluteRepositoryPath },
                ),
            )
        return ContextPack(
            sessionId,
            repositoryId,
            markdown,
            ranked,
            analysis.relations,
            analysis.symbols,
            if (settings.includeRepositoryTree) analysis.tree else "Repository tree disabled for this batch.",
            guidelines.sources.filter { it.enabled }.map { it.relativePath },
            markdown.toByteArray().size.toLong() + ranked.included.sumOf { it.size },
            skill.id,
        ).also { latestPack = it }
    }

    fun repositoryFingerprint(): String {
        val root = project.basePath ?: "unknown"
        return MessageDigest.getInstance("SHA-256").digest(root.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
