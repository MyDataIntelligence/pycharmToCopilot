package nl.ferron.copilotcontextbridge.context

import nl.ferron.copilotcontextbridge.analysis.RepositoryScanner
import nl.ferron.copilotcontextbridge.model.ContextCandidate
import nl.ferron.copilotcontextbridge.model.DependencyRelation
import nl.ferron.copilotcontextbridge.model.PythonSymbol
import nl.ferron.copilotcontextbridge.model.RelationConfidence
import nl.ferron.copilotcontextbridge.model.RelationType
import nl.ferron.copilotcontextbridge.settings.ContextPolicyState
import nl.ferron.copilotcontextbridge.settings.ContextRuleState

enum class ResolverCategory { EXPLICIT, TESTS, DEPENDENCIES, CONFIGURATION, INSTRUCTIONS, REPOSITORY, VERSION_CONTROL }

/** Analysis behavior is registered separately from the persisted string resolver ID. */
enum class ResolverStrategy { TRAVERSAL, TEST_FIXTURES, NEARBY_TESTS, TEMPLATES, SIMILAR_IMPLEMENTATIONS, GUIDELINES, GIT_BRANCH }

data class ContextResolverMetadata(
    val id: String,
    val displayName: String,
    val description: String,
    val category: ResolverCategory,
    val relationTypes: Set<RelationType>,
    val strategy: ResolverStrategy,
)

/** Mutable analysis surface exposed to dynamically registered resolver handlers. */
data class ResolverExecutionContext(
    val snapshot: RepositoryScanner.Snapshot,
    val seedPaths: Set<String>,
    val candidatePaths: MutableSet<String>,
    val candidateDepths: MutableMap<String, Int>,
    val relations: MutableList<DependencyRelation>,
    val symbols: Map<String, List<PythonSymbol>>,
    val include: (source: String, target: String, type: RelationType, evidence: String, confidence: RelationConfidence, depth: Int) -> Unit,
)

fun interface ContextResolverHandler {
    fun resolve(
        context: ResolverExecutionContext,
        rule: ContextRuleState,
    )
}

/** Single registry for policy dispatch, candidate provenance and attachment packing. */
object ContextResolverRegistry {
    private val registered =
        listOf(
            metadata(
                "explicit.pinnedFiles",
                "Pinned files",
                "Files explicitly selected by the user.",
                ResolverCategory.EXPLICIT,
                RelationType.PINNED,
            ),
            metadata(
                "python.matchingTests",
                "Matching tests",
                "Tests matched to selected production code.",
                ResolverCategory.TESTS,
                RelationType.RELATED_TEST,
            ),
            metadata(
                "tests.fixtures",
                "Test fixtures",
                "Fixtures used by selected tests.",
                ResolverCategory.TESTS,
                RelationType.TEST_FIXTURE,
                strategy = ResolverStrategy.TEST_FIXTURES,
            ),
            metadata(
                "tests.nearby",
                "Nearby tests",
                "Other tests beside a matching test.",
                ResolverCategory.TESTS,
                RelationType.NEARBY_TEST,
                strategy = ResolverStrategy.NEARBY_TESTS,
            ),
            metadata(
                "python.directImports",
                "Direct imports",
                "Files directly imported by selected code.",
                ResolverCategory.DEPENDENCIES,
                RelationType.DIRECT_IMPORT,
            ),
            metadata(
                "python.directCallees",
                "Direct callees",
                "Files containing symbols directly used by selected code.",
                ResolverCategory.DEPENDENCIES,
                RelationType.DIRECT_CALLEE,
            ),
            metadata(
                "python.directCallers",
                "Direct callers",
                "Files that directly depend on selected code.",
                ResolverCategory.DEPENDENCIES,
                RelationType.DIRECT_DEPENDENT,
            ),
            metadata(
                "python.transitiveImports",
                "Transitive dependencies",
                "Dependencies beyond the first traversal level.",
                ResolverCategory.DEPENDENCIES,
                RelationType.SECOND_LEVEL,
            ),
            metadata(
                "text.referencedConfiguration",
                "Referenced configuration",
                "Configuration or notebooks referenced by text.",
                ResolverCategory.CONFIGURATION,
                RelationType.REFERENCED_CONFIGURATION,
                RelationType.TEXT_REFERENCE,
            ),
            metadata(
                "guidelines.agents",
                "AGENTS instructions",
                "Applicable AGENTS.md instructions.",
                ResolverCategory.INSTRUCTIONS,
                RelationType.INSTRUCTION,
                strategy = ResolverStrategy.GUIDELINES,
            ),
            metadata(
                "guidelines.copilotInstructions",
                "Copilot instructions",
                "Repository Copilot instructions.",
                ResolverCategory.INSTRUCTIONS,
                RelationType.INSTRUCTION,
                strategy = ResolverStrategy.GUIDELINES,
            ),
            metadata(
                "guidelines.project",
                "Project guidelines",
                "Other project contribution guidelines.",
                ResolverCategory.INSTRUCTIONS,
                RelationType.INSTRUCTION,
                strategy = ResolverStrategy.GUIDELINES,
            ),
            metadata(
                "repository.similarImplementations",
                "Similar implementations",
                "Files with symbols similar to selected code.",
                ResolverCategory.REPOSITORY,
                RelationType.SIMILAR_IMPLEMENTATION,
                strategy = ResolverStrategy.SIMILAR_IMPLEMENTATIONS,
            ),
            metadata(
                "repository.templates",
                "Templates and examples",
                "Repository templates or examples matching selected code.",
                ResolverCategory.REPOSITORY,
                RelationType.TEMPLATE,
                strategy = ResolverStrategy.TEMPLATES,
            ),
            metadata(
                "git.branchChanges",
                "Branch changes",
                "Files changed on the current Git branch.",
                ResolverCategory.VERSION_CONTROL,
                RelationType.BRANCH_CHANGE,
                strategy = ResolverStrategy.GIT_BRANCH,
            ),
        )
    private val builtInIds = registered.mapTo(hashSetOf()) { it.id }
    private val byId = LinkedHashMap(registered.associateBy(ContextResolverMetadata::id))
    private val handlers = LinkedHashMap<String, ContextResolverHandler>()

    @Synchronized
    fun all(): List<ContextResolverMetadata> = byId.values.toList()

    @Synchronized
    fun find(id: String): ContextResolverMetadata? = byId[id]

    /** Registers an add-on resolver without changing persisted policy or core dispatch code. */
    @Synchronized
    fun register(metadata: ContextResolverMetadata) {
        require(metadata.id.isNotBlank()) { "Resolver ID must not be blank." }
        require(metadata.id !in byId) { "Resolver '${metadata.id}' is already registered." }
        byId[metadata.id] = metadata
    }

    /** Registers metadata and executable behavior for a resolver supplied by an extension/plugin. */
    @Synchronized
    fun register(
        metadata: ContextResolverMetadata,
        handler: ContextResolverHandler,
    ) {
        register(metadata)
        handlers[metadata.id] = handler
    }

    @Synchronized
    fun handler(id: String): ContextResolverHandler? = handlers[id]

    /** Removes an add-on resolver; built-ins remain stable for persisted policies. */
    @Synchronized
    fun unregister(id: String): Boolean =
        if (id !in builtInIds) {
            handlers.remove(id)
            byId.remove(id) != null
        } else {
            false
        }

    @Synchronized
    fun resolversFor(type: RelationType): Set<String> = byId.values.filter { type in it.relationTypes }.mapTo(linkedSetOf()) { it.id }

    fun primaryRule(
        candidate: ContextCandidate,
        policy: ContextPolicyState,
    ): ContextRuleState? {
        if (candidate.pinned) return policy.rules.filter { it.enabled && it.resolver == "explicit.pinnedFiles" }.maxByOrNull { it.priority }
        val resolverIds = candidate.relations.flatMapTo(linkedSetOf()) { resolversFor(it.type) }
        return policy.rules
            .filter {
                it.enabled && it.resolver in resolverIds
            }.maxWithOrNull(compareBy<ContextRuleState> { it.priority }.thenByDescending { it.id })
    }

    fun primaryResolver(
        candidate: ContextCandidate,
        policy: ContextPolicyState? = null,
    ): String {
        if (candidate.resolverId.isNotBlank()) return candidate.resolverId
        policy?.let { primaryRule(candidate, it)?.resolver }?.let { return it }
        if (candidate.pinned) return "explicit.pinnedFiles"
        return candidate.relations
            .asSequence()
            .flatMap { resolversFor(it.type).asSequence() }
            .firstOrNull() ?: "repository.references"
    }

    private fun metadata(
        id: String,
        displayName: String,
        description: String,
        category: ResolverCategory,
        vararg relationTypes: RelationType,
        strategy: ResolverStrategy = ResolverStrategy.TRAVERSAL,
    ) = ContextResolverMetadata(id, displayName, description, category, relationTypes.toSet(), strategy)
}
