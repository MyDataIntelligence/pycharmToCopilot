package nl.ferron.copilotcontextbridge.guidelines

import com.intellij.openapi.project.Project
import nl.ferron.copilotcontextbridge.ProjectRoot
import nl.ferron.copilotcontextbridge.settings.AppSettings
import nl.ferron.copilotcontextbridge.settings.ContextPolicyState
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
                appendLine("## Global personal guidelines")
                appendLine()
                appendLine(global)
            }.trim()
        return Merged(markdown, sources)
    }

    private fun read(
        path: Path,
        relative: String,
    ): String {
        val text = Files.readString(path, StandardCharsets.UTF_8)
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
}
