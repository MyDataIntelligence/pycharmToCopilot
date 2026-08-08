package nl.ferron.copilotcontextbridge.settings

/**
 * A versioned, data-driven context collection policy owned by a Prompt Library entry.
 * Resolver IDs deliberately remain strings so new resolvers can be registered without
 * changing persisted policy schema.
 */
enum class CopilotTarget { MICROSOFT_365, GITHUB_COPILOT }

enum class CopilotReturnMode { COPILOT_PATCH_FILE, CODE_TOOL_FILES, TEXT_ONLY, DIRECT_REPOSITORY_EDIT }

enum class PreviousBatchMode { SAME_SESSION_ONLY, NEVER, ALWAYS }

class ContextRuleState {
    @JvmField var id: String = ""

    @JvmField var enabled: Boolean = true

    @JvmField var resolver: String = ""

    @JvmField var priority: Int = 50

    @JvmField var required: Boolean = false

    @JvmField var maxDepth: Int = 1

    @JvmField var maxFiles: Int = 20

    @JvmField var bundleGroup: String = ""

    @JvmField var keepSeparate: Boolean = false

    @JvmField var parameters: MutableMap<String, String> = mutableMapOf()

    constructor()

    constructor(
        id: String,
        resolver: String,
        priority: Int,
        enabled: Boolean = true,
        required: Boolean = false,
        maxDepth: Int = 1,
        maxFiles: Int = 20,
        bundleGroup: String = "",
        keepSeparate: Boolean = false,
        parameters: Map<String, String> = emptyMap(),
    ) {
        this.id = id
        this.resolver = resolver
        this.priority = priority
        this.enabled = enabled
        this.required = required
        this.maxDepth = maxDepth
        this.maxFiles = maxFiles
        this.bundleGroup = bundleGroup
        this.keepSeparate = keepSeparate
        this.parameters = parameters.toMutableMap()
    }

    fun copyOf() = ContextRuleState(id, resolver, priority, enabled, required, maxDepth, maxFiles, bundleGroup, keepSeparate, parameters)
}

class ContextPolicyState {
    @JvmField var id: String = "general-change-default"

    @JvmField var version: Int = 1

    @JvmField var target: String = CopilotTarget.MICROSOFT_365.name

    @JvmField var returnMode: String = CopilotReturnMode.COPILOT_PATCH_FILE.name

    @JvmField var previousBatchMode: String = PreviousBatchMode.SAME_SESSION_ONLY.name

    @JvmField var maxRepositoryFiles: Int = 50

    @JvmField var maxAttachments: Int = 20

    @JvmField var maxBundleCharacters: Int = 80_000

    @JvmField var estimatedMaxBundleTokens: Int = 20_000

    @JvmField var bundleAutomaticContext: Boolean = true

    @JvmField var rules: MutableList<ContextRuleState> = defaultRules().toMutableList()

    constructor()

    constructor(id: String, rules: Collection<ContextRuleState> = defaultRules()) {
        this.id = id
        this.rules = rules.map(ContextRuleState::copyOf).toMutableList()
    }

    fun rule(id: String): ContextRuleState? = rules.firstOrNull { it.id == id }

    fun isEnabled(resolver: String): Boolean = rules.any { it.resolver == resolver && it.enabled }

    fun priorityFor(
        resolver: String,
        fallback: Int,
    ): Int = rules.filter { it.resolver == resolver && it.enabled }.maxOfOrNull { it.priority } ?: fallback

    fun maxDepthFor(
        resolver: String,
        fallback: Int,
    ): Int = rules.filter { it.resolver == resolver && it.enabled }.maxOfOrNull { it.maxDepth } ?: fallback

    fun copyOf() =
        ContextPolicyState(id, rules).also {
            it.version = version
            it.target = target
            it.returnMode = returnMode
            it.previousBatchMode = previousBatchMode
            it.maxRepositoryFiles = maxRepositoryFiles
            it.maxAttachments = maxAttachments
            it.maxBundleCharacters = maxBundleCharacters
            it.estimatedMaxBundleTokens = estimatedMaxBundleTokens
            it.bundleAutomaticContext = bundleAutomaticContext
        }

    companion object {
        fun defaultFor(promptId: String) = ContextPolicyState("$promptId-policy")

        fun defaultRules(): List<ContextRuleState> =
            listOf(
                ContextRuleState("pinned-files", "explicit.pinnedFiles", 100, required = true, keepSeparate = true),
                ContextRuleState("matching-tests", "python.matchingTests", 100, required = true, bundleGroup = "tests"),
                ContextRuleState("test-fixtures", "tests.fixtures", 80, bundleGroup = "tests"),
                ContextRuleState("nearby-tests", "tests.nearby", 60, enabled = false, bundleGroup = "tests"),
                ContextRuleState("direct-imports", "python.directImports", 70, bundleGroup = "dependencies"),
                ContextRuleState("direct-callees", "python.directCallees", 70, bundleGroup = "dependencies"),
                ContextRuleState("direct-callers", "python.directCallers", 65, enabled = false, bundleGroup = "dependencies"),
                ContextRuleState(
                    "transitive-imports",
                    "python.transitiveImports",
                    55,
                    enabled = false,
                    maxDepth = 2,
                    bundleGroup = "dependencies",
                ),
                ContextRuleState("referenced-config", "text.referencedConfiguration", 65, bundleGroup = "configuration"),
                ContextRuleState("agents", "guidelines.agents", 100, required = true, bundleGroup = "instructions"),
                ContextRuleState(
                    "copilot-instructions",
                    "guidelines.copilotInstructions",
                    100,
                    required = true,
                    bundleGroup = "instructions",
                ),
                ContextRuleState("project-guidelines", "guidelines.project", 95, bundleGroup = "instructions"),
                ContextRuleState("similar-implementations", "repository.similarImplementations", 45, enabled = false),
                ContextRuleState("templates", "repository.templates", 40, enabled = false),
                ContextRuleState("branch-changes", "git.branchChanges", 35, enabled = false),
            )
    }
}
