package nl.ferron.copilotcontextbridge.settings

/** Pure editing operations shared by the policy dialog and regression tests. */
object ContextPolicyEditor {
    fun resetToPromptDefault(
        policy: ContextPolicyState,
        promptId: String,
    ) {
        val defaults = AppSettings.defaultPolicyForPrompt(promptId)
        policy.version = defaults.version
        policy.target = defaults.target
        policy.returnMode = defaults.returnMode
        policy.previousBatchMode = defaults.previousBatchMode
        policy.maxRepositoryFiles = defaults.maxRepositoryFiles
        policy.maxAttachments = defaults.maxAttachments
        policy.bundleAutomaticContext = defaults.bundleAutomaticContext
        policy.rules = defaults.rules.map(ContextRuleState::copyOf).toMutableList()
    }

    fun duplicateRule(
        rules: MutableList<ContextRuleState>,
        sourceIndex: Int,
    ): ContextRuleState {
        require(sourceIndex in rules.indices) { "Rule index is outside the policy." }
        val source = rules[sourceIndex]
        val existing = rules.mapTo(mutableSetOf()) { it.id }
        var suffix = 1
        var candidate = "${source.id}-copy"
        while (candidate in existing) {
            suffix++
            candidate = "${source.id}-copy-$suffix"
        }
        return source.copyOf().also {
            it.id = candidate
            rules += it
        }
    }
}
