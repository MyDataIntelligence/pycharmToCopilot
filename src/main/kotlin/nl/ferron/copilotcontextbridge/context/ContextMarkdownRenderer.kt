package nl.ferron.copilotcontextbridge.context

import nl.ferron.copilotcontextbridge.model.BatchSummary
import nl.ferron.copilotcontextbridge.model.ContextCandidate
import nl.ferron.copilotcontextbridge.model.DependencyRelation
import nl.ferron.copilotcontextbridge.model.PythonSymbol
import nl.ferron.copilotcontextbridge.model.RankedSelection
import nl.ferron.copilotcontextbridge.model.displayRepository
import nl.ferron.copilotcontextbridge.model.sourceKey
import nl.ferron.copilotcontextbridge.settings.AppSettings
import nl.ferron.copilotcontextbridge.settings.CopilotReturnMode

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
        val physicalAttachmentCount: Int,
        val previousBatches: List<BatchSummary>,
        val generateMermaid: Boolean,
        val includeAbsolutePath: String? = null,
        val returnMode: CopilotReturnMode = CopilotReturnMode.COPILOT_PATCH_FILE,
        val returnInstructions: String = AppSettings.getInstance().state.returnFileInstruction,
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
            appendLine("## Attachment plan")
            appendLine()
            appendLine("Repository files represented: ${input.selection.included.size}")
            appendLine("Physical Copilot attachments: ${input.physicalAttachmentCount}")
            val repositories = input.selection.included.groupBy { repositoryLabel(it, input.repositoryId) }
            if (repositories.size > 1 || input.selection.included.any { it.repositoryId.isNotBlank() }) {
                appendLine("Repositories represented: ${repositories.size}")
                appendLine()
                appendLine("### Repository-separated source index")
                appendLine()
                repositories.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (repository, candidates) ->
                    appendLine("REPO: $repository")
                    candidates.sortedBy { it.relativePath }.forEach { appendLine("  ${it.relativePath}") }
                    appendLine()
                }
                appendLine("Paths from different repositories are independent even when their relative paths are identical.")
            }
            appendLine()
            appendLine("### MANUALLY SELECTED (PINNED) — selected by the user and kept as individual attachments")
            appendLine()
            input.selection.included.filter { it.pinned }.forEach { candidate ->
                appendLine(
                    "- `[${repositoryLabel(candidate, input.repositoryId)}] ${candidate.relativePath}` -> `${input.stagedNames.getValue(
                        candidate.sourceKey,
                    )}`",
                )
            }
            if (input.selection.included.none { it.pinned }) appendLine("- None")
            appendLine()
            appendLine(
                "### AUTOMATICALLY ADDED — selected by dependency/context analysis; represented in generated bundles or separate policy-required attachments",
            )
            appendLine()
            input.selection.included.filterNot { it.pinned }.forEach { candidate ->
                val reason = candidate.relations.joinToString(", ") { it.type.name.lowercase() }.ifBlank { "automatic context" }
                appendLine(
                    "- `[${repositoryLabel(candidate, input.repositoryId)}] ${candidate.relativePath}` -> `${input.stagedNames.getValue(
                        candidate.sourceKey,
                    )}` - $reason",
                )
            }
            if (input.selection.included.all { it.pinned }) appendLine("- None")
            appendLine()
            appendLine("## Uploaded files and original-path mapping")
            appendLine()
            appendLine("| Prepared attachment | Repository | Original repository path | Selection | Depth | Relationship |")
            appendLine("|---|---|---|---|---:|---|")
            input.selection.included.forEach { candidate ->
                val relation = candidate.relations.joinToString(", ") { "${it.type} (${it.confidence})" }.ifBlank { "manual selection" }
                appendLine(
                    "| `${input.stagedNames[candidate.sourceKey]}` | `${repositoryLabel(candidate, input.repositoryId)}` | " +
                        "`${candidate.relativePath}` | " +
                        "${if (candidate.pinned) "manually selected (pinned)" else "automatically added"} | " +
                        "${candidate.depth} | $relation |",
                )
            }
            appendLine()
            appendLine("## Dependency map")
            appendLine()
            input.relations
                .filter { relation ->
                    relationMatchesIncluded(relation, input.selection.included)
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
            appendLine(returnHeading(input.returnMode))
            appendLine()
            if (input.returnMode in setOf(CopilotReturnMode.COPILOT_PATCH_FILE, CopilotReturnMode.CODE_TOOL_FILES)) {
                appendLine(
                    "Mandatory ZIP rule: every returned ZIP must contain a versioned `changes.json` at its root. " +
                        "A loose source-only ZIP is only an import fallback and is never the requested primary format.",
                )
                appendLine()
            }
            appendLine(input.returnInstructions)
            if (input.returnMode == CopilotReturnMode.COPILOT_PATCH_FILE) appendPatchExample(input, this)
        }

    private fun repositoryLabel(
        candidate: ContextCandidate,
        currentRepositoryId: String,
    ): String = if (candidate.repositoryId.isBlank()) currentRepositoryId else candidate.displayRepository

    private fun returnHeading(mode: CopilotReturnMode): String =
        when (mode) {
            CopilotReturnMode.COPILOT_PATCH_FILE -> "## WHEN RETURNING CODE CHANGES (.copilotpatch)"
            CopilotReturnMode.CODE_TOOL_FILES -> "## WHEN RETURNING CODE OR FILES"
            CopilotReturnMode.TEXT_ONLY -> "## WHEN RETURNING THE RESULT"
            CopilotReturnMode.DIRECT_REPOSITORY_EDIT -> "## WHEN APPLYING DIRECT REPOSITORY CHANGES"
        }

    private fun appendPatchExample(
        input: Input,
        target: StringBuilder,
    ) {
        target.appendLine()
        target.appendLine("Example schema:")
        target.appendLine()
        target.appendLine("```json")
        target.appendLine(
            "{\"formatVersion\":1,\"repositoryId\":\"${input.repositoryId}\",\"sessionId\":\"${input.sessionId}\",\"summary\":{\"overview\":\"One concise paragraph.\",\"functions\":[{\"path\":\"src/example.py\",\"qualifiedName\":\"Example.run\",\"change\":\"What changed\",\"reason\":\"Why\"}],\"testsPerformed\":[\"Exact tests actually run, or: Not run\"],\"risks\":[\"Known risk, or: None known\"],\"limitations\":[\"Effects of omitted files, or: None\"]},\"replacements\":[{\"operation\":\"replace_function\",\"path\":\"src/example.py\",\"qualifiedName\":\"Example.run\",\"originalHash\":\"sha256:...\",\"replacement\":\"def run():\\n    ...\\n\"}]}",
        )
        target.appendLine("```")
        target.appendLine()
        target.appendLine(
            "For a new function use `operation: add_function`, omit `originalHash`, provide `parentQualifiedName` (empty for module level), and optionally provide `insertAfterQualifiedName`. Never use add_function when a function with that qualified name already exists.",
        )
        target.appendLine(
            "If returning ZIP, `changes.json` at the root is mandatory. Put snippets under `replacements/` and also create `CHANGE_SUMMARY.md` using the same Overview / Functions / Tests / Risks / Limitations headings. Never return a loose source-only ZIP as the primary result.",
        )
    }

    private fun appendMermaid(
        relations: List<DependencyRelation>,
        selection: RankedSelection,
        target: StringBuilder,
    ) {
        val included = selection.included
        val relevant = relations.filter { relation -> relationEndpointsIncluded(relation, included) }.distinct()
        if (relevant.isEmpty()) return
        target.appendLine()
        target.appendLine("```mermaid")
        target.appendLine("flowchart LR")
        val keys =
            relevant
                .flatMap { relation ->
                    listOf(
                        relationEndpointKey(relation.from, relation.fromRepositoryId, included),
                        relationEndpointKey(relation.to, relation.toRepositoryId, included),
                    )
                }.filterNotNull()
                .toSet()
        val duplicatePaths =
            included
                .groupingBy { it.relativePath }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        val ids = keys.sorted().associateWith { "n" + FunctionHasherAdapter.short(it) }
        ids.forEach { (key, id) ->
            val candidate = included.first { it.sourceKey == key }
            val label =
                if (candidate.relativePath in duplicatePaths) {
                    "${repositoryLabel(candidate, "repository")}: ${candidate.relativePath}"
                } else {
                    candidate.relativePath
                }
            target.appendLine("    $id[\"${label.replace("\"", "'")}\"]")
        }
        relevant.forEach { relation ->
            val from = relationEndpointKey(relation.from, relation.fromRepositoryId, included)
            val to = relationEndpointKey(relation.to, relation.toRepositoryId, included)
            if (from != null && to != null) {
                target.appendLine("    ${ids.getValue(from)} -->|${relation.type.name.lowercase()}| ${ids.getValue(to)}")
            }
        }
        target.appendLine("```")
    }

    private fun relationMatchesIncluded(
        relation: DependencyRelation,
        included: List<ContextCandidate>,
    ): Boolean = relationEndpointsIncluded(relation, included)

    private fun relationEndpointsIncluded(
        relation: DependencyRelation,
        included: List<ContextCandidate>,
    ): Boolean =
        relationEndpointKey(relation.from, relation.fromRepositoryId, included) != null &&
            relationEndpointKey(relation.to, relation.toRepositoryId, included) != null

    private fun relationEndpointKey(
        path: String,
        repositoryId: String,
        included: List<ContextCandidate>,
    ): String? =
        included
            .firstOrNull { candidate ->
                candidate.relativePath == path &&
                    (repositoryId.isBlank() || candidate.repositoryId == repositoryId)
            }?.sourceKey

    private object FunctionHasherAdapter {
        fun short(value: String): String =
            nl.ferron.copilotcontextbridge.analysis.FunctionHasher
                .hash(value)
                .removePrefix("sha256:")
                .take(12)
    }
}
