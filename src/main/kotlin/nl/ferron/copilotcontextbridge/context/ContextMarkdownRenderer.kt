package nl.ferron.copilotcontextbridge.context

import nl.ferron.copilotcontextbridge.model.BatchSummary
import nl.ferron.copilotcontextbridge.model.DependencyRelation
import nl.ferron.copilotcontextbridge.model.PythonSymbol
import nl.ferron.copilotcontextbridge.model.RankedSelection
import nl.ferron.copilotcontextbridge.settings.AppSettings

object ContextMarkdownRenderer {
    data class Input(
        val repositoryId: String,
        val sessionId: String,
        val selection: RankedSelection,
        val repositoryTree: String,
        val relations: List<DependencyRelation>,
        val symbols: Map<String, List<PythonSymbol>>,
        val stagedNames: Map<String, String>,
        val guidelines: String,
        val guidelineSources: List<String>,
        val promptSkillName: String,
        val promptSkillPrompt: String,
        val previousBatches: List<BatchSummary>,
        val generateMermaid: Boolean,
        val includeAbsolutePath: String? = null,
    )

    fun render(input: Input): String =
        buildString {
            appendLine("# Copilot repository context")
            appendLine()
            appendLine("This document contains instructions for Microsoft 365 Copilot. It does not instruct the PyCharm plugin.")
            appendLine()
            appendLine("## MANDATORY FIRST RESPONSE")
            appendLine()
            appendLine(
                "Before analysing files, proposing changes, writing code, or producing a solution, first ask what the user wants to achieve.",
            )
            appendLine("Your first response must contain only this question:")
            appendLine()
            appendLine("> \"${AppSettings.getInstance().state.mandatoryFirstQuestion}\"")
            appendLine()
            appendLine("Wait for the user's answer before continuing.")
            appendLine()
            appendLine("## Multi-batch delivery protocol")
            appendLine()
            appendLine(
                "This context is batch ${input.previousBatches.size + 1} of a potentially multi-batch upload. The user may add another 20 files, or more batches, to the same chat.",
            )
            appendLine(
                "Retain context from earlier uploaded batches. Do not assume that every planned batch has arrived and do not begin a complete solution until the user confirms that all intended batches are uploaded.",
            )
            appendLine("The plugin can only confirm that earlier batches were prepared; ask the user if their upload status matters.")
            if (input.previousBatches.isNotEmpty()) {
                appendLine()
                appendLine("Previously prepared batches:")
                input.previousBatches.forEachIndexed { index, batch ->
                    appendLine(
                        "- Batch ${index + 1}: `${batch.sessionId}` — ${batch.paths.size} source files — ${batch.status.lowercase()} — skill: ${batch.promptSkillName}",
                    )
                    batch.paths.forEach { appendLine("  - `$it`") }
                }
            }
            appendLine()
            appendLine("## Selected prompt skill: ${input.promptSkillName}")
            appendLine()
            appendLine(input.promptSkillPrompt)
            appendLine()
            appendLine("## General instructions")
            appendLine()
            appendLine("- Treat all paths as relative to the repository root.")
            appendLine("- Preserve project structure and unrelated behavior and formatting.")
            appendLine("- Follow effective coding guidelines and existing utilities before creating new ones.")
            appendLine("- Do not invent contents for files that were omitted or not uploaded.")
            appendLine("- Inspect known dependencies and dependents before changing public behavior.")
            appendLine("- Do not silently modify unrelated files.")
            appendLine()
            appendLine("## Repository information")
            appendLine()
            appendLine("- Repository ID: `${input.repositoryId}`")
            appendLine("- Context session: `${input.sessionId}`")
            input.includeAbsolutePath?.let { appendLine("- Explicitly included local path: `$it`") }
            appendLine()
            appendLine("```text")
            appendLine(input.repositoryTree)
            appendLine("```")
            appendLine()
            appendLine("## Uploaded files (${input.selection.included.size + 1} total including this context file)")
            appendLine()
            appendLine("| Staged filename | Original repository path | Selection | Depth | Relationship |")
            appendLine("|---|---|---|---:|---|")
            input.selection.included.forEach { candidate ->
                val relation = candidate.relations.joinToString(", ") { "${it.type} (${it.confidence})" }.ifBlank { "manual selection" }
                appendLine(
                    "| `${input.stagedNames[candidate.relativePath]}` | `${candidate.relativePath}` | ${if (candidate.pinned) "pinned" else "automatic"} | ${candidate.depth} | $relation |",
                )
            }
            appendLine()
            appendLine("## Dependency map")
            appendLine()
            input.relations
                .filter { relation ->
                    input.selection.included.any {
                        it.relativePath == relation.from ||
                            it.relativePath == relation.to
                    }
                }.distinct()
                .forEach {
                    appendLine(
                        "- `${it.from}` → `${it.to}`: ${it.type}, ${it.confidence}, depth ${it.depth}${if (it.evidence.isBlank()) "" else " — ${it.evidence}"}",
                    )
                }
            if (input.generateMermaid) appendMermaid(input.relations, input.selection, this)
            appendLine()
            appendLine("## Python symbol index and function hashes")
            appendLine()
            input.selection.included.forEach { candidate ->
                val fileSymbols = input.symbols[candidate.relativePath].orEmpty()
                if (fileSymbols.isNotEmpty()) {
                    appendLine("### `${candidate.relativePath}`")
                    appendLine()
                    fileSymbols.forEach { symbol ->
                        val hashSuffix = symbol.hash?.let { " — `$it`" }.orEmpty()
                        appendLine("- `${symbol.qualifiedName}` — ${symbol.kind}$hashSuffix")
                    }
                    appendLine()
                }
            }
            appendLine("## Omitted files")
            appendLine()
            if (input.selection.omitted.isEmpty()) appendLine("No dependency candidates were omitted.")
            input.selection.omitted.forEach { candidate ->
                val reason =
                    candidate.ignoredReason ?: candidate.secretWarning?.let { "blocked by secret detection: $it" }
                        ?: "file limit; lower rank"
                val relationship = candidate.relations.joinToString(", ") { it.type.name }.ifBlank { "candidate" }
                appendLine("- `${candidate.relativePath}` — $relationship — score ${candidate.score} — $reason")
                input.symbols[candidate.relativePath]
                    .orEmpty()
                    .take(
                        8,
                    ).forEach { appendLine("  - known symbol: `${it.qualifiedName}` (${it.kind})") }
            }
            appendLine()
            appendLine("**The contents of omitted files were not supplied. Do not invent them.**")
            appendLine()
            appendLine(input.guidelines)
            appendLine()
            appendLine("## WHEN RETURNING CODE CHANGES")
            appendLine()
            appendLine(AppSettings.getInstance().state.returnFileInstruction)
            appendLine("Do not return complete source files unless explicitly requested.")
            appendLine(
                "For every function actually changed, include its original repository-relative path, fully qualified name, original SHA-256 hash, and complete replacement function including decorators, full signature, type hints, docstring and body.",
            )
            appendLine(
                "Return only changed functions. Never return partial functions or use line numbers as identity. Do not invent code from omitted files. Put all replacements in one change set.",
            )
            appendLine()
            appendLine("```json")
            appendLine(
                "{\"formatVersion\":1,\"repositoryId\":\"${input.repositoryId}\",\"sessionId\":\"${input.sessionId}\",\"summary\":{\"overview\":\"One concise paragraph.\",\"functions\":[{\"path\":\"src/example.py\",\"qualifiedName\":\"Example.run\",\"change\":\"What changed\",\"reason\":\"Why\"}],\"testsPerformed\":[\"Exact tests actually run, or: Not run\"],\"risks\":[\"Known risk, or: None known\"],\"limitations\":[\"Effects of omitted files, or: None\"]},\"replacements\":[{\"operation\":\"replace_function\",\"path\":\"src/example.py\",\"qualifiedName\":\"Example.run\",\"originalHash\":\"sha256:...\",\"replacement\":\"def run():\\n    ...\\n\"}]}",
            )
            appendLine("```")
            appendLine()
            appendLine(
                "For a new function use `operation: add_function`, omit `originalHash`, provide `parentQualifiedName` (empty for module level), and optionally provide `insertAfterQualifiedName`. Never use add_function when a function with that qualified name already exists.",
            )
            appendLine(
                "If returning ZIP, put `changes.json` at the root, snippets under `replacements/`, and also create `CHANGE_SUMMARY.md` using the same Overview / Functions / Tests / Risks / Limitations headings.",
            )
        }

    private fun appendMermaid(
        relations: List<DependencyRelation>,
        selection: RankedSelection,
        target: StringBuilder,
    ) {
        val paths = selection.included.map { it.relativePath }.toSet()
        val relevant = relations.filter { it.from in paths && it.to in paths }.distinct()
        if (relevant.isEmpty()) return
        target.appendLine()
        target.appendLine("```mermaid")
        target.appendLine("flowchart LR")
        val ids = paths.sorted().associateWith { "n" + FunctionHasherAdapter.short(it) }
        ids.forEach { (path, id) -> target.appendLine("    $id[\"${path.replace("\"", "'")}\"]") }
        relevant.forEach { relation ->
            target.appendLine("    ${ids[relation.from]} -->|${relation.type.name.lowercase()}| ${ids[relation.to]}")
        }
        target.appendLine("```")
    }

    private object FunctionHasherAdapter {
        fun short(value: String): String =
            nl.ferron.copilotcontextbridge.analysis.FunctionHasher
                .hash(value)
                .removePrefix("sha256:")
                .take(12)
    }
}
