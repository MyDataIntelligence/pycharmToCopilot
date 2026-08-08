package nl.ferron.copilotcontextbridge.staging

import com.google.gson.GsonBuilder
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.jetbrains.python.psi.PyFile
import nl.ferron.copilotcontextbridge.ProjectRoot
import nl.ferron.copilotcontextbridge.analysis.FunctionHasher
import nl.ferron.copilotcontextbridge.context.ContextPackService
import nl.ferron.copilotcontextbridge.model.AttachmentKind
import nl.ferron.copilotcontextbridge.model.ContextPack
import nl.ferron.copilotcontextbridge.model.PlannedAttachment
import nl.ferron.copilotcontextbridge.model.StagedFile
import nl.ferron.copilotcontextbridge.model.displayRepository
import nl.ferron.copilotcontextbridge.model.sourceKey
import nl.ferron.copilotcontextbridge.patch.PythonFunctionLocator
import nl.ferron.copilotcontextbridge.security.PathSafety
import nl.ferron.copilotcontextbridge.settings.AppSettings
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
        try {
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
            pack.attachmentPlan.attachments.forEach { attachment ->
                val target = directory.resolve(attachment.stagedName)
                when (attachment.kind) {
                    AttachmentKind.PINNED_ORIGINAL -> writePinnedAttachment(projectRoot, attachment, target)
                    AttachmentKind.AUTOMATIC_BUNDLE -> writeAutomaticBundle(projectRoot, pack.repositoryId, attachment, target)
                    AttachmentKind.GENERATED_CONTEXT -> Files.writeString(target, attachment.generatedContent, StandardCharsets.UTF_8)
                }
                staged +=
                    StagedFile(
                        if (attachment.kind == AttachmentKind.PINNED_ORIGINAL) {
                            attachment.candidates.single().relativePath
                        } else {
                            "generated:${attachment.stagedName}"
                        },
                        attachment.stagedName,
                        target,
                        sha256(target),
                        attachment.candidates.joinToString(", ") { candidateReason(it) },
                        attachment.candidates.singleOrNull()?.pinned == true,
                    )
            }
            val service = project.getService(ContextPackService::class.java)
            val baseFunctions = captureBaseFunctions(pack)
            val baseFunctionsFile = metadata.resolve("base-functions.json")
            Files.writeString(baseFunctionsFile, GsonBuilder().setPrettyPrinting().create().toJson(baseFunctions), StandardCharsets.UTF_8)
            val manifestData =
                linkedMapOf<String, Any>(
                    "formatVersion" to 1,
                    "sessionId" to pack.sessionId,
                    "repositoryId" to pack.repositoryId,
                    "repositoryFingerprint" to service.repositoryFingerprint(),
                    "repositoryRoot" to projectRoot.toString(),
                    "createdAt" to Instant.now().toString(),
                    "pluginVersion" to "1.0.0",
                    "baseFunctionsFile" to ".session/base-functions.json",
                    "promptSkillId" to pack.promptSkillId,
                    "physicalAttachmentCount" to staged.size,
                    "repositoryFileCount" to pack.attachmentPlan.repositoryFileCount,
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
                    "repositoryFiles" to
                        pack.selection.included.map { candidate ->
                            mapOf(
                                "path" to candidate.relativePath,
                                "repositoryId" to candidate.repositoryId.ifBlank { pack.repositoryId },
                                "repositoryName" to candidate.displayRepository,
                                "preparedAttachment" to pack.attachmentPlan.repositoryToAttachment[candidate.sourceKey],
                                "sha256" to currentSourceHash(resolveCandidateSource(projectRoot, candidate)),
                                "reason" to candidateReason(candidate),
                                "pinned" to candidate.pinned,
                            )
                        },
                )
            val manifest = metadata.resolve("context-session.json")
            Files.writeString(manifest, GsonBuilder().setPrettyPrinting().create().toJson(manifestData), StandardCharsets.UTF_8)
            val skill = AppSettings.getInstance().skill(pack.promptSkillId)
            project.getService(ContextSelectionService::class.java).markExported(
                pack.sessionId,
                skill.name,
                pack.selection.included.map { it.relativePath },
                false,
                pack.selection.included.map { it.sourceKey },
            )
            return StagingResult(directory, staged, manifest)
        } catch (error: Exception) {
            // A failed preparation must never leave a half-session that blocks retrying the
            // same immutable context pack. The target was created above directly beneath the
            // controlled staging root, so the existing guarded deletion method is safe here.
            runCatching { deleteSession(directory) }
            throw error
        }
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

    private fun captureBaseFunctions(pack: ContextPack): List<Map<String, String>> =
        ReadAction
            .nonBlocking<List<Map<String, String>>> {
                val root = ProjectRoot.virtualFile(project)
                val psiManager = PsiManager.getInstance(project)
                pack.symbols.flatMap { (path, symbols) ->
                    val virtualFile = root.findFileByRelativePath(path) ?: return@flatMap emptyList()
                    val pyFile = psiManager.findFile(virtualFile) as? PyFile ?: return@flatMap emptyList()
                    symbols.mapNotNull { symbol ->
                        val exportedHash = symbol.hash ?: return@mapNotNull null
                        val function = PythonFunctionLocator.find(pyFile, symbol.qualifiedName).singleOrNull() ?: return@mapNotNull null
                        val currentHash = FunctionHasher.hash(function.text)
                        require(currentHash == exportedHash) {
                            "Cannot stage stale context: $path::${symbol.qualifiedName} changed after context generation. Recalculate first."
                        }
                        mapOf(
                            "path" to path,
                            "qualifiedName" to symbol.qualifiedName,
                            "hash" to currentHash,
                            "text" to function.text,
                        )
                    }
                }
            }.executeSynchronously()

    private fun writePinnedAttachment(
        projectRoot: Path,
        attachment: PlannedAttachment,
        target: Path,
    ) {
        val candidate = attachment.candidates.single()
        val source = resolveCandidateSource(projectRoot, candidate)
        if (attachment.convertedTextCopy) {
            val originalText = readCurrentText(source)
            val converted =
                buildString {
                    appendLine("COPILOT CONTEXT BRIDGE TEXT COPY")
                    appendLine()
                    appendLine("Original path: ${candidate.relativePath}")
                    appendLine("Repository: ${candidate.displayRepository}")
                    appendLine("Repository ID: ${candidate.repositoryId.ifBlank { "current" }}")
                    appendLine("Original extension: .${candidate.relativePath.substringAfterLast('.', "")}")
                    appendLine("Original SHA-256: ${currentSourceHash(source)}")
                    appendLine("The repository source was not renamed or modified.")
                    appendLine()
                    appendLine("--- ORIGINAL CONTENT ---")
                    append(originalText)
                }
            Files.writeString(target, converted, StandardCharsets.UTF_8)
            return
        }
        val vf = LocalFileSystem.getInstance().findFileByNioFile(source)
        val document = vf?.let { FileDocumentManager.getInstance().getCachedDocument(it) }
        if (document != null && FileDocumentManager.getInstance().isDocumentUnsaved(document)) {
            Files.writeString(target, document.text, vf.charset)
        } else {
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES)
        }
    }

    private fun writeAutomaticBundle(
        projectRoot: Path,
        repositoryId: String,
        attachment: PlannedAttachment,
        target: Path,
    ) {
        val content =
            buildString {
                appendLine("# Automatic ${attachment.bundleGroup.ifBlank { "reference" }} context")
                appendLine()
                appendLine(
                    "Generated by Copilot Context Bridge. This is a generated context bundle, not an original repository source file.",
                )
                appendLine("Repository: $repositoryId")
                appendLine()
                attachment.candidates.forEachIndexed { index, candidate ->
                    val source = resolveCandidateSource(projectRoot, candidate)
                    val text = readCurrentText(source)
                    appendLine("## SOURCE FILE ${index + 1}")
                    appendLine()
                    appendLine("Repository: ${candidate.displayRepository.ifBlank { repositoryId }}")
                    appendLine("Repository ID: ${candidate.repositoryId.ifBlank { repositoryId }}")
                    appendLine("Original path: ${candidate.relativePath}")
                    appendLine("Original SHA-256: ${sha256Text(text)}")
                    appendLine("Original extension: ${candidate.relativePath.substringAfterLast('.', "(none)")}")
                    appendLine("Reason included: ${candidateReason(candidate)}")
                    appendLine("Context Policy rule: ${policyRule(candidate)}")
                    appendLine()
                    appendLine("```" + candidate.relativePath.substringAfterLast('.', "text"))
                    appendLine(text)
                    appendLine("```")
                    appendLine()
                }
            }
        Files.writeString(target, content, StandardCharsets.UTF_8)
    }

    private fun readCurrentText(source: Path): String {
        val vf = LocalFileSystem.getInstance().findFileByNioFile(source)
        val document = vf?.let { FileDocumentManager.getInstance().getCachedDocument(it) }
        return if (document != null &&
            FileDocumentManager.getInstance().isDocumentUnsaved(document)
        ) {
            document.text
        } else {
            val vf = LocalFileSystem.getInstance().findFileByNioFile(source)
            if (vf != null) String(Files.readAllBytes(source), vf.charset) else Files.readString(source)
        }
    }

    private fun candidateReason(candidate: nl.ferron.copilotcontextbridge.model.ContextCandidate): String =
        candidate.relations
            .joinToString("; ") { relation -> "${relation.type.name.lowercase()}: ${relation.evidence}" }
            .ifBlank { if (candidate.pinned) "manually pinned" else "automatic context" }

    private fun policyRule(candidate: nl.ferron.copilotcontextbridge.model.ContextCandidate): String =
        when {
            candidate.pinned -> "explicit.pinnedFiles"
            candidate.relations.any { it.type.name == "RELATED_TEST" } -> "python.matchingTests"
            candidate.relations.any { it.type.name == "DIRECT_IMPORT" } -> "python.directImports"
            candidate.relations.any { it.type.name == "REFERENCED_CONFIGURATION" } -> "text.referencedConfiguration"
            else -> "repository.references"
        }

    private fun sha256(path: Path): String =
        "sha256:" +
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(Files.readAllBytes(path))
                .joinToString("") { "%02x".format(it) }

    private fun sha256Text(text: String): String =
        "sha256:" +
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(text.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

    private fun currentSourceHash(source: Path): String {
        val vf = LocalFileSystem.getInstance().findFileByNioFile(source)
        val document = vf?.let { FileDocumentManager.getInstance().getCachedDocument(it) }
        return if (document != null && FileDocumentManager.getInstance().isDocumentUnsaved(document)) {
            val bytes = document.text.toByteArray(vf.charset)
            "sha256:" +
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(bytes)
                    .joinToString("") { "%02x".format(it) }
        } else {
            sha256(source)
        }
    }

    private fun resolveCandidateSource(
        projectRoot: Path,
        candidate: nl.ferron.copilotcontextbridge.model.ContextCandidate,
    ): Path = PathSafety.resolveInside(candidate.repositoryRoot ?: projectRoot, candidate.relativePath)
}
