package nl.ferron.copilotcontextbridge.staging

import com.google.gson.GsonBuilder
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import nl.ferron.copilotcontextbridge.ProjectRoot
import nl.ferron.copilotcontextbridge.analysis.FunctionHasher
import nl.ferron.copilotcontextbridge.context.ContextPackService
import nl.ferron.copilotcontextbridge.model.ContextPack
import nl.ferron.copilotcontextbridge.model.StagedFile
import nl.ferron.copilotcontextbridge.security.PathSafety
import nl.ferron.copilotcontextbridge.settings.AppSettings
import nl.ferron.copilotcontextbridge.settings.ProjectSettings
import nl.ferron.copilotcontextbridge.state.ContextSelectionService
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

class StagingService(
    private val project: Project,
) {
    data class StagingResult(
        val directory: Path,
        val files: List<StagedFile>,
        val manifest: Path,
    )

    fun stage(pack: ContextPack): StagingResult {
        require(pack.selection.valid) { pack.selection.validationErrors.joinToString("\n") }
        val projectRoot = ProjectRoot.path(project)
        val stagingRoot = Path.of(System.getProperty("java.io.tmpdir"), "CopilotContextBridge")
        Files.createDirectories(stagingRoot)
        cleanup(stagingRoot)
        val directory = stagingRoot.resolve("${pack.repositoryId}_${pack.sessionId}")
        Files.createDirectory(directory)
        val metadata = directory.resolve(".session")
        Files.createDirectory(metadata)
        val contextPath = directory.resolve("00_REPO_CONTEXT.md")
        Files.writeString(contextPath, pack.markdown, StandardCharsets.UTF_8)
        val staged =
            mutableListOf(
                StagedFile(
                    "00_REPO_CONTEXT.md",
                    "00_REPO_CONTEXT.md",
                    contextPath,
                    FunctionHasher.hash(pack.markdown),
                    "generated repository context",
                    false,
                ),
            )
        val names = StagedFilenameService.namesFor(pack.selection.included.map { it.relativePath })
        pack.selection.included.forEach { candidate ->
            val source = PathSafety.resolveInside(projectRoot, candidate.relativePath)
            val target = directory.resolve(names.getValue(candidate.relativePath))
            val vf = LocalFileSystem.getInstance().findFileByNioFile(source)
            val document = vf?.let { FileDocumentManager.getInstance().getCachedDocument(it) }
            if (document != null && FileDocumentManager.getInstance().isDocumentUnsaved(document)) {
                Files.writeString(target, document.text, vf.charset)
            } else {
                Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES)
            }
            staged +=
                StagedFile(
                    candidate.relativePath,
                    target.fileName.toString(),
                    target,
                    sha256(target),
                    candidate.relations.joinToString(",") { it.type.name }.ifBlank { "manual" },
                    candidate.pinned,
                )
        }
        val service = project.getService(ContextPackService::class.java)
        val manifestData =
            linkedMapOf<String, Any>(
                "formatVersion" to 1,
                "sessionId" to pack.sessionId,
                "repositoryId" to pack.repositoryId,
                "repositoryFingerprint" to service.repositoryFingerprint(),
                "repositoryRoot" to projectRoot.toString(),
                "createdAt" to Instant.now().toString(),
                "pluginVersion" to "1.0.0",
                "promptSkillId" to pack.promptSkillId,
                "files" to
                    staged.map {
                        mapOf(
                            "path" to it.relativePath,
                            "stagedName" to it.stagedName,
                            "sha256" to it.sha256,
                            "reason" to it.reason,
                            "pinned" to it.pinned,
                        )
                    },
                "functionHashes" to
                    pack.symbols.flatMap { (path, symbols) ->
                        symbols.filter { it.hash != null }.map {
                            mapOf(
                                "path" to path,
                                "qualifiedName" to it.qualifiedName,
                                "hash" to it.hash,
                            )
                        }
                    },
                "relations" to pack.relations,
                "guidelineSources" to pack.guidelineSources,
            )
        val manifest = metadata.resolve("context-session.json")
        Files.writeString(manifest, GsonBuilder().setPrettyPrinting().create().toJson(manifestData), StandardCharsets.UTF_8)
        val settings = project.getService(ProjectSettings::class.java).state
        val skill = AppSettings.getInstance().skill(pack.promptSkillId)
        project.getService(ContextSelectionService::class.java).markExported(
            pack.sessionId,
            skill.name,
            pack.selection.included.map { it.relativePath },
            settings.clearActiveSelectionAfterExport,
        )
        return StagingResult(directory, staged, manifest)
    }

    fun deleteSession(directory: Path) {
        val root = Path.of(System.getProperty("java.io.tmpdir"), "CopilotContextBridge").toAbsolutePath().normalize()
        val target = directory.toAbsolutePath().normalize()
        require(target.parent == root && target != root) { "Refusing to delete outside the staging root." }
        if (Files.exists(target)) Files.walk(target).sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }

    fun keepSession(directory: Path) {
        Files.writeString(directory.resolve(".session/.keep"), "kept by user\n", StandardCharsets.UTF_8)
    }

    private fun cleanup(root: Path) {
        val cutoff =
            Instant.now().minusSeconds(
                AppSettings
                    .getInstance()
                    .state.stagingRetentionDays
                    .toLong() * 86400,
            )
        SessionCleanupService.cleanup(root, cutoff)
    }

    private fun sha256(path: Path): String =
        "sha256:" +
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(Files.readAllBytes(path))
                .joinToString("") { "%02x".format(it) }
}
