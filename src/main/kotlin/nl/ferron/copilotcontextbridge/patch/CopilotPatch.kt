package nl.ferron.copilotcontextbridge.patch

data class CopilotPatch(
    val formatVersion: Int,
    val repositoryId: String,
    val sessionId: String,
    val replacements: List<FunctionReplacement>,
    val summary: PatchSummary? = null,
)

data class PatchSummary(
    val overview: String,
    val functions: List<PatchSummaryItem>,
    val testsPerformed: List<String>,
    val risks: List<String>,
    val limitations: List<String>,
)

data class PatchSummaryItem(
    val path: String,
    val qualifiedName: String,
    val change: String,
    val reason: String,
)

data class FunctionReplacement(
    val operation: String,
    val path: String,
    val qualifiedName: String,
    val originalHash: String?,
    val replacement: String?,
    val replacementFile: String?,
    val allowAsyncChange: Boolean = false,
    val allowDecoratorKindChange: Boolean = false,
    val parentQualifiedName: String? = null,
    val insertAfterQualifiedName: String? = null,
)

enum class ReplacementStatus { MATCH, NEW, CHANGED, MISSING, AMBIGUOUS, INVALID }

data class ValidatedReplacement(
    val request: FunctionReplacement,
    val status: ReplacementStatus,
    val message: String,
    val oldText: String = "",
    val newText: String = "",
    val oldLineCount: Int = 0,
    val newLineCount: Int = 0,
    val unifiedDiff: String = "",
    val selected: Boolean = status == ReplacementStatus.MATCH || status == ReplacementStatus.NEW,
)

data class PatchValidationResult(
    val patch: CopilotPatch?,
    val replacements: List<ValidatedReplacement>,
    val errors: List<String>,
    val warnings: List<String>,
) {
    val valid: Boolean get() = patch != null && errors.isEmpty()
}
