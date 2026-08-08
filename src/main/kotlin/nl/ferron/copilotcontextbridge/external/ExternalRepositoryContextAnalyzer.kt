package nl.ferron.copilotcontextbridge.external

import nl.ferron.copilotcontextbridge.analysis.TestFileMatcher
import nl.ferron.copilotcontextbridge.context.ContextResolverRegistry
import nl.ferron.copilotcontextbridge.model.ContextCandidate
import nl.ferron.copilotcontextbridge.model.DependencyRelation
import nl.ferron.copilotcontextbridge.model.RelationConfidence
import nl.ferron.copilotcontextbridge.model.RelationType
import nl.ferron.copilotcontextbridge.settings.ContextPolicyState
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** Adds policy-aware local relations to already safety-filtered external repository candidates. */
class ExternalRepositoryContextAnalyzer(
    private val policy: ContextPolicyState,
    private val textualScanLimitBytes: Long,
) {
    fun analyze(
        seeds: Collection<ContextCandidate>,
        discovered: Collection<ContextCandidate>,
    ): List<ContextCandidate> {
        if (discovered.isEmpty()) return emptyList()
        val seedTexts = seeds.associateWith(::readText)
        return discovered.map { candidate ->
            val relations = mutableListOf<DependencyRelation>()
            if (policy.isEnabled("python.matchingTests") && isTest(candidate.relativePath)) {
                seeds
                    .filter { it.relativePath.endsWith(".py") && TestFileMatcher.matches(candidate.relativePath, it.relativePath) }
                    .forEach { seed ->
                        relations +=
                            relation(seed, candidate, RelationType.RELATED_TEST, "external fuzzy test filename match")
                    }
            }
            if (policy.isEnabled("python.directImports") && candidate.relativePath.endsWith(".py")) {
                val moduleNames = moduleNames(candidate.relativePath)
                seedTexts.filterValues { it != null }.forEach { (seed, text) ->
                    if (seed.relativePath.endsWith(".py") && importsAny(text.orEmpty(), moduleNames)) {
                        relations += relation(seed, candidate, RelationType.DIRECT_IMPORT, "external repository local Python import")
                    }
                }
            }
            if (policy.isEnabled("text.referencedConfiguration") && isConfiguration(candidate.relativePath)) {
                seedTexts.filterValues { it != null }.forEach { (seed, text) ->
                    if (references(text.orEmpty(), candidate.relativePath)) {
                        relations +=
                            relation(
                                seed,
                                candidate,
                                RelationType.REFERENCED_CONFIGURATION,
                                "external repository textual configuration reference",
                            )
                    }
                }
            }
            if (instructionResolver(candidate.relativePath) != null) {
                relations +=
                    DependencyRelation(
                        seeds.firstOrNull()?.relativePath.orEmpty(),
                        candidate.relativePath,
                        RelationType.INSTRUCTION,
                        RelationConfidence.CONFIRMED,
                        evidence = "external repository instruction file",
                    )
            }
            val effectiveRelations = relations.distinct()
            val provisional =
                candidate.copy(
                    relations =
                        effectiveRelations.ifEmpty {
                            listOf(
                                DependencyRelation(
                                    seeds.firstOrNull()?.relativePath.orEmpty(),
                                    candidate.relativePath,
                                    RelationType.SAME_PACKAGE,
                                    RelationConfidence.INFERRED,
                                    evidence = "discovered from explicitly dropped external directory",
                                ),
                            )
                        },
                    score = score(effectiveRelations),
                    confidence =
                        if (effectiveRelations.isEmpty()) {
                            RelationConfidence.INFERRED
                        } else {
                            effectiveRelations
                                .minBy {
                                    it.confidence.ordinal
                                }.confidence
                        },
                )
            val instructionResolver = instructionResolver(candidate.relativePath)
            val rule =
                if (instructionResolver != null) {
                    policy.rules.filter { it.enabled && it.resolver == instructionResolver }.maxByOrNull { it.priority }
                } else {
                    ContextResolverRegistry.primaryRule(provisional, policy)
                }
            provisional.copy(
                resolverId = rule?.resolver ?: ContextResolverRegistry.primaryResolver(provisional, policy),
                policyRuleId = rule?.id.orEmpty(),
            )
        }
    }

    private fun relation(
        seed: ContextCandidate,
        target: ContextCandidate,
        type: RelationType,
        evidence: String,
    ) = DependencyRelation(seed.relativePath, target.relativePath, type, RelationConfidence.INFERRED, evidence = evidence)

    private fun score(relations: List<DependencyRelation>): Int =
        relations
            .flatMap { ContextResolverRegistry.resolversFor(it.type) }
            .maxOfOrNull { policy.priorityFor(it, 20) * 10 } ?: 200

    private fun readText(candidate: ContextCandidate): String? =
        runCatching {
            if (candidate.size > textualScanLimitBytes || !Files.isRegularFile(candidate.absolutePath)) {
                null
            } else {
                Files.readString(candidate.absolutePath, StandardCharsets.UTF_8)
            }
        }.getOrNull()

    private fun importsAny(
        text: String,
        moduleNames: Set<String>,
    ): Boolean =
        IMPORT_PATTERN.findAll(text).any { match ->
            val imported =
                match.groupValues
                    .drop(1)
                    .firstOrNull(String::isNotBlank)
                    .orEmpty()
                    .trimStart('.')
            moduleNames.any { module -> imported == module || imported.startsWith("$module.") }
        }

    private fun references(
        text: String,
        path: String,
    ): Boolean = text.contains(path, ignoreCase = true) || text.contains(path.substringAfterLast('/'), ignoreCase = true)

    private fun moduleNames(path: String): Set<String> {
        val withoutExtension = path.removeSuffix(".py")
        val dotted = withoutExtension.replace('/', '.')
        return setOf(dotted, dotted.substringAfterLast('.'))
    }

    private fun instructionResolver(path: String): String? =
        when {
            (path == "AGENTS.md" || path.endsWith("/AGENTS.md")) && policy.isEnabled("guidelines.agents") -> "guidelines.agents"
            path == ".github/copilot-instructions.md" &&
                policy.isEnabled(
                    "guidelines.copilotInstructions",
                ) -> "guidelines.copilotInstructions"
            (path == "CONTRIBUTING.md" || path.endsWith("SKILL.md")) && policy.isEnabled("guidelines.project") -> "guidelines.project"
            else -> null
        }

    private fun isTest(path: String): Boolean =
        path.substringAfterLast('/').startsWith("test_") || path.substringBeforeLast('.').endsWith("_test") || "/tests/" in "/$path"

    private fun isConfiguration(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in setOf("json", "yaml", "yml", "toml", "ini", "cfg", "xml")

    companion object {
        private val IMPORT_PATTERN = Regex("(?m)^\\s*(?:from\\s+([A-Za-z0-9_.]+)\\s+import|import\\s+([A-Za-z0-9_.]+))")
    }
}
