package nl.ferron.copilotcontextbridge.staging

import nl.ferron.copilotcontextbridge.model.StagedFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files

object CombinedContextTextBuilder {
    fun build(
        introduction: String,
        files: List<StagedFile>,
    ): String =
        buildString {
            appendLine("# Complete Copilot context batch")
            appendLine()
            appendLine(introduction.trim())
            appendLine()
            appendLine("Files in this text copy: ${files.size}")
            files.forEachIndexed { index, file ->
                appendLine()
                appendLine("## ${index + 1}. ${file.relativePath}")
                appendLine()
                appendLine("- Original path: `${file.relativePath}`")
                appendLine("- Staged filename: `${file.stagedName}`")
                appendLine("- SHA-256: `${file.sha256}`")
                appendLine("- Selection: ${if (file.pinned) "manually pinned" else file.reason}")
                appendLine()
                val bytes = Files.readAllBytes(file.stagedPath)
                if (bytes.any { it == 0.toByte() }) {
                    appendLine("[Binary content is not representable as clipboard text. Use the staged file instead.]")
                } else {
                    val content = String(bytes, StandardCharsets.UTF_8)
                    val fence = "`".repeat((longestBacktickRun(content) + 1).coerceAtLeast(3))
                    appendLine("$fence${language(file.relativePath)}")
                    append(content)
                    if (!content.endsWith('\n')) appendLine()
                    appendLine(fence)
                }
            }
        }

    private fun longestBacktickRun(text: String): Int {
        var longest = 0
        var current = 0
        text.forEach { character ->
            current = if (character == '`') current + 1 else 0
            if (current > longest) longest = current
        }
        return longest
    }

    private fun language(path: String): String =
        when (path.substringAfterLast('.', "").lowercase()) {
            "py" -> "python"
            "json" -> "json"
            "yml", "yaml" -> "yaml"
            "toml" -> "toml"
            "sql" -> "sql"
            "md" -> "markdown"
            "xml" -> "xml"
            "kt", "kts" -> "kotlin"
            else -> "text"
        }
}
