package nl.ferron.copilotcontextbridge.model

import java.nio.file.Path

enum class RelationConfidence { CONFIRMED, INFERRED, DYNAMIC, UNRESOLVED }

enum class RelationType {
    PINNED,
    DIRECT_IMPORT,
    DIRECT_DEPENDENT,
    RELATED_TEST,
    REFERENCED_CONFIGURATION,
    PACKAGE_INIT,
    PROJECT_CONFIGURATION,
    SECOND_LEVEL,
    SAME_PACKAGE,
    TEXT_REFERENCE,
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
)

data class RankedSelection(
    val included: List<ContextCandidate>,
    val omitted: List<ContextCandidate>,
    val validationErrors: List<String>,
    val warnings: List<String>,
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
)

data class BatchSummary(
    val sessionId: String,
    val createdAt: String,
    val promptSkillName: String,
    val paths: List<String>,
    val status: String = "PREPARED",
)
