package nl.ferron.copilotcontextbridge.guidelines

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import nl.ferron.copilotcontextbridge.ProjectRoot
import nl.ferron.copilotcontextbridge.security.IgnoreMatcher
import nl.ferron.copilotcontextbridge.security.PathSafety
import nl.ferron.copilotcontextbridge.settings.AppSettings
import nl.ferron.copilotcontextbridge.settings.ContextPolicyState
import nl.ferron.copilotcontextbridge.settings.Defaults
import nl.ferron.copilotcontextbridge.settings.ProjectSettings
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class GuidelineService(
    private val project: Project,
) {
    data class Source(
        val relativePath: String,
        val enabled: Boolean,
        val content: String,
    )

    data class Merged(
        val markdown: String,
        val sources: List<Source>,
    )

    private val preferred =
        listOf(
            ".github/copilot-instructions.md",
            ".github/skills/code-guidelines/SKILL.md",
            "AGENTS.md",
            "CONTRIBUTING.md",
            "README.md",
            "pyproject.toml",
        )

    fun detect(): List<Source> {
        val root = ProjectRoot.path(project)
        val settings = project.getService(ProjectSettings::class.java).state
        val enabledConfigured = settings.enabledGuidelineSources.toSet()
        val paths = preferred.toMutableList()
        val references = root.resolve(".github/skills/code-guidelines/references")
        if (Files.isDirectory(references)) {
            Files.list(references).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".md") }
                    .sorted()
                    .forEach { paths += root.relativize(it).joinToString("/") }
            }
        }
        // Scoped AGENTS.md files carry subtree-specific instructions and must be
        // discoverable alongside the root file. Respect repository/plugin ignores
        // so generated, vendored and virtual-environment trees are not scanned into
        // the guideline set.
        val ignoreMatcher = IgnoreMatcher(AppSettings.getInstance().state.ignorePatterns + settings.customIgnorePatterns)
        runCatching {
            Files.walk(root).use { stream ->
                stream
                    .filter { it != root && Files.isRegularFile(it) && it.fileName.toString().equals("AGENTS.md", ignoreCase = true) }
                    .map { root.relativize(it).joinToString("/") }
                    .filter { !ignoreMatcher.isIgnored(it) }
                    .sorted()
                    .forEach { paths += it }
            }
        }
        return paths.distinct().mapNotNull { relative ->
            val path = root.resolve(relative)
            if (!Files.isRegularFile(path)) return@mapNotNull null
            val defaultEnabled = relative !in setOf("README.md", "pyproject.toml")
            Source(
                relative,
                if (!settings.guidelineSelectionConfigured) defaultEnabled else relative in enabledConfigured,
                read(path, relative),
            )
        }
    }

    fun merge(
        skillPrompt: String,
        skillGuidelines: String,
        policy: ContextPolicyState? = null,
    ): Merged {
        val sources =
            detect().map { source ->
                val resolver =
                    when {
                        source.relativePath == "AGENTS.md" || source.relativePath.endsWith("/AGENTS.md") -> "guidelines.agents"
                        source.relativePath == ".github/copilot-instructions.md" -> "guidelines.copilotInstructions"
                        else -> "guidelines.project"
                    }
                if (policy != null) source.copy(enabled = source.enabled && policy.isEnabled(resolver)) else source
            }
        val repository = sources.filter { it.enabled }.joinToString("\n\n") { "## Source: ${it.relativePath}\n\n${it.content}" }
        val global = AppSettings.getInstance().state.globalGuidelines
        val markdown =
            buildString {
                appendLine("# Effective coding guidelines")
                appendLine()
                appendLine(
                    "Priority: explicit current-chat instruction → selected prompt-skill guidelines → repository conventions → global personal guidelines → plugin defaults.",
                )
                appendLine()
                appendLine("## Selected prompt skill")
                appendLine()
                appendLine(skillPrompt)
                if (skillGuidelines.isNotBlank()) {
                    appendLine()
                    appendLine("### Skill guidelines")
                    appendLine()
                    appendLine(skillGuidelines)
                }
                if (repository.isNotBlank()) {
                    appendLine()
                    appendLine("## Repository-specific guidelines")
                    appendLine()
                    appendLine(repository)
                }
                appendLine()
                appendLine("## Plugin Python authoring rules")
                appendLine()
                appendLine(Defaults.PYTHON_AUTHORING_RULES)
                appendLine()
                appendLine("## Global personal guidelines")
                appendLine()
                appendLine(global)
            }.trim()
        return Merged(markdown, sources)
    }

    fun sourceText(relativePath: String): String {
        require(detect().any { it.relativePath == relativePath }) {
            "Not a detected repository guideline source: $relativePath"
        }
        val path = PathSafety.resolveInside(ProjectRoot.path(project), relativePath)
        return readRaw(path)
    }

    /** Writes only an already detected guideline source, and only after the UI's explicit Save action. */
    fun saveSource(
        relativePath: String,
        content: String,
    ) {
        require(content.toByteArray(StandardCharsets.UTF_8).size <= MAX_GUIDELINE_BYTES) {
            "Repository guideline content exceeds the 2 MB safety limit."
        }
        require(detect().any { it.relativePath == relativePath }) {
            "Not a detected repository guideline source: $relativePath"
        }
        val root = ProjectRoot.path(project)
        val path = PathSafety.resolveInside(root, relativePath)
        require(Files.isRegularFile(path)) { "Repository guideline source no longer exists: $relativePath" }
        val virtualFile =
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
                ?: throw IllegalStateException("Repository guideline source is unavailable in the project: $relativePath")
        checkNotNull(FileDocumentManager.getInstance().getDocument(virtualFile)) {
            "Repository guideline source is not editable text: $relativePath"
        }
        WriteCommandAction
            .writeCommandAction(project)
            .withName("Save Copilot repository guideline")
            .run<RuntimeException> {
                VfsUtil.saveText(virtualFile, content)
            }
        FileDocumentManager.getInstance().getDocument(virtualFile)?.let(FileDocumentManager.getInstance()::saveDocument)
    }

    private fun read(
        path: Path,
        relative: String,
    ): String {
        val text = readRaw(path)
        if (relative == "pyproject.toml") {
            return text
                .lineSequence()
                .filter {
                    it.startsWith("[tool.") ||
                        it.startsWith("requires-python") ||
                        it.startsWith("target-version") ||
                        it.contains("line-length") ||
                        it.contains("testpaths") ||
                        it.contains("pythonpath")
                }.joinToString("\n")
                .ifBlank { "pyproject.toml detected; no concise guideline keys extracted." }
        }
        if (relative == "README.md") {
            val sections = text.split(Regex("(?m)(?=^#{1,3} )"))
            return sections
                .filter { it.lines().firstOrNull()?.contains(Regex("(?i)contribut|develop|style|test|guideline")) == true }
                .joinToString("\n")
                .ifBlank { "README detected but no explicit guideline section was found." }
        }
        return text
    }

    private fun readRaw(path: Path): String {
        val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(path)
        return virtualFile
            ?.let { FileDocumentManager.getInstance().getCachedDocument(it)?.text }
            ?: Files.readString(path, StandardCharsets.UTF_8)
    }

    companion object {
        private const val MAX_GUIDELINE_BYTES = 2 * 1024 * 1024
    }
}
