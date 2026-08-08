package nl.ferron.copilotcontextbridge.model

import java.nio.file.Path

enum class RelationConfidence { CONFIRMED, INFERRED, DYNAMIC, UNRESOLVED }

enum class RelationType {
    PINNED,
    DIRECT_IMPORT,
    DIRECT_CALLEE,
    DIRECT_DEPENDENT,
    RELATED_TEST,
    NEARBY_TEST,
    TEST_FIXTURE,
    REFERENCED_CONFIGURATION,
    PACKAGE_INIT,
    PROJECT_CONFIGURATION,
    SECOND_LEVEL,
    SAME_PACKAGE,
    TEXT_REFERENCE,
    BRANCH_CHANGE,
    TEMPLATE,
    SIMILAR_IMPLEMENTATION,
    INSTRUCTION,
}

data class DependencyRelation(
    val from: String,
    val to: String,
    val type: RelationType,
    val confidence: RelationConfidence,
    val depth: Int = 1,
    val evidence: String = "",
)

data class ContextCandidate(
    val relativePath: String,
    val absolutePath: Path,
    val score: Int,
    val depth: Int,
    val confidence: RelationConfidence,
    val relations: List<DependencyRelation>,
    val pinned: Boolean = false,
    val generated: Boolean = false,
    val secretWarning: String? = null,
    val ignoredReason: String? = null,
    val previouslySent: Boolean = false,
    val size: Long = 0,
    val sha256: String = "",
    /** Stable, non-machine-specific repository identifier. Empty means the open project repository. */
    val repositoryId: String = "",
    /** Root used only for safe source resolution; it is never rendered unless path exposure is explicitly enabled. */
    val repositoryRoot: Path? = null,
    val repositoryName: String = repositoryId,
)

/** Unique key used in manifests and attachment mappings without changing repository-relative paths. */
val ContextCandidate.sourceKey: String
    get() = if (repositoryId.isBlank()) relativePath else "$repositoryId::$relativePath"

val ContextCandidate.displayRepository: String
    get() = repositoryName.ifBlank { repositoryId.ifBlank { "current repository" } }

data class RankedSelection(
    val included: List<ContextCandidate>,
    val omitted: List<ContextCandidate>,
    val validationErrors: List<String>,
    val warnings: List<String>,
    val excluded: List<ContextCandidate> = emptyList(),
) {
    val valid: Boolean get() = validationErrors.isEmpty()
}

data class PythonSymbol(
    val qualifiedName: String,
    val kind: String,
    val hash: String? = null,
)

data class StagedFile(
    val relativePath: String,
    val stagedName: String,
    val stagedPath: Path,
    val sha256: String,
    val reason: String,
    val pinned: Boolean,
)

data class ContextPack(
    val sessionId: String,
    val repositoryId: String,
    val markdown: String,
    val selection: RankedSelection,
    val relations: List<DependencyRelation>,
    val symbols: Map<String, List<PythonSymbol>>,
    val repositoryTree: String,
    val guidelineSources: List<String>,
    val estimatedBytes: Long,
    val promptSkillId: String,
    val attachmentPlan: AttachmentPlan = AttachmentPlan.empty(),
)

data class AttachmentPlan(
    val attachments: List<PlannedAttachment>,
    val repositoryToAttachment: Map<String, String>,
    val omittedByPolicy: List<ContextCandidate> = emptyList(),
) {
    val attachmentCount: Int get() = attachments.size + 1 // 00_REPO_CONTEXT.md
    val repositoryFileCount: Int get() = repositoryToAttachment.size

    fun attachmentFor(candidate: ContextCandidate): String? = repositoryToAttachment[candidate.sourceKey]

    companion object {
        fun empty() = AttachmentPlan(emptyList(), emptyMap())
    }
}

data class PlannedAttachment(
    val stagedName: String,
    val kind: AttachmentKind,
    val candidates: List<ContextCandidate>,
    val bundleGroup: String = "",
    val convertedTextCopy: Boolean = false,
    val generatedContent: String = "",
)

enum class AttachmentKind { PINNED_ORIGINAL, AUTOMATIC_BUNDLE, GENERATED_CONTEXT }

data class BatchSummary(
    val sessionId: String,
    val createdAt: String,
    val promptSkillName: String,
    val paths: List<String>,
    val status: String = "PREPARED",
    val sourceKeys: List<String> = paths,
)
