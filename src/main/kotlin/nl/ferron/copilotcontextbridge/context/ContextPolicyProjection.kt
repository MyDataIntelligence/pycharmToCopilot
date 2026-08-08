package nl.ferron.copilotcontextbridge.context

import nl.ferron.copilotcontextbridge.settings.ContextPolicyState
import nl.ferron.copilotcontextbridge.settings.PreviousBatchMode
import nl.ferron.copilotcontextbridge.settings.ProjectSettings

/** Maps extensible resolver IDs to the existing analysis capabilities. */
data class ContextPolicyProjection(
    val directImports: Boolean,
    val directCallees: Boolean,
    val directDependents: Boolean,
    val relatedTests: Boolean,
    val nearbyTests: Boolean,
    val configuration: Boolean,
    val maximumDependencyDepth: Int,
    val resolverLimits: Map<String, Int>,
    val packageFolders: Boolean,
    val avoidPrevious: Boolean,
    val includeTemplates: Boolean,
    val includeSimilarImplementations: Boolean,
    val includeBranchChanges: Boolean,
    val maximumFiles: Int,
) {
    companion object {
        fun from(
            policy: ContextPolicyState,
            fallback: ProjectSettings.Data,
        ) = ContextPolicyProjection(
            directImports = policy.isEnabled("python.directImports") || policy.isEnabled("python.transitiveImports"),
            directCallees = policy.isEnabled("python.directCallees"),
            directDependents = policy.isEnabled("python.directCallers"),
            relatedTests = policy.isEnabled("python.matchingTests"),
            nearbyTests = policy.isEnabled("tests.nearby"),
            configuration = policy.isEnabled("text.referencedConfiguration"),
            maximumDependencyDepth =
                if (policy.isEnabled("python.transitiveImports")) {
                    policy.maxDepthFor("python.transitiveImports", 1).coerceAtLeast(1)
                } else {
                    1
                },
            resolverLimits =
                policy.rules
                    .filter { it.enabled }
                    .groupBy { it.resolver }
                    .mapValues { (_, rules) -> rules.maxOf { it.maxFiles.coerceAtLeast(1) } },
            packageFolders = fallback.includePackageFolders,
            avoidPrevious =
                when (
                    runCatching {
                        PreviousBatchMode.valueOf(
                            policy.previousBatchMode,
                        )
                    }.getOrDefault(PreviousBatchMode.SAME_SESSION_ONLY)
                ) {
                    PreviousBatchMode.NEVER -> false
                    PreviousBatchMode.SAME_SESSION_ONLY, PreviousBatchMode.ALWAYS -> true
                },
            includeTemplates = policy.isEnabled("repository.templates"),
            includeSimilarImplementations = policy.isEnabled("repository.similarImplementations"),
            includeBranchChanges = policy.isEnabled("git.branchChanges"),
            maximumFiles = minOf(fallback.maximumUploadFiles, policy.maxAttachments.coerceIn(2, 20)),
        )
    }
}
