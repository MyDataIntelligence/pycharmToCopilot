package nl.ferron.copilotcontextbridge.settings

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken

/** Validates prompt-skill library imports before application settings are mutated. */
object PromptSkillLibraryCodec {
    fun encode(skills: List<AppSettings.PromptSkillState>): String = GsonBuilder().setPrettyPrinting().create().toJson(skills)

    fun decode(json: String): List<AppSettings.PromptSkillState> {
        try {
            val type = object : TypeToken<List<AppSettings.PromptSkillState>>() {}.type
            val imported =
                GsonBuilder().create().fromJson<List<AppSettings.PromptSkillState>>(json, type) ?: error("Skill library is null.")
            require(imported.isNotEmpty()) { "A skill library must contain at least one skill." }
            require(imported.size <= 100) { "A skill library may contain at most 100 skills." }
            require(imported.all { it.id.isNotBlank() && it.name.isNotBlank() && it.prompt.isNotBlank() }) {
                "Every skill requires a non-empty id, name and prompt."
            }
            require(imported.map { it.id }.distinct().size == imported.size) { "Skill IDs must be unique." }
            require(imported.map { it.name.trim().lowercase() }.distinct().size == imported.size) { "Skill names must be unique." }
            require(
                imported.all {
                    it.id.length <= 120 &&
                        it.name.length <= 200 &&
                        it.description.length <= 2_000 &&
                        it.category.length <= 200 &&
                        it.prompt.length <= 200_000 &&
                        it.guidelines.length <= 200_000 &&
                        it.returnInstructionsAddition.length <= 200_000
                },
            ) {
                "A skill exceeds the supported text length."
            }
            imported.forEach(::validateAndRepairPolicy)
            return imported
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid Prompt Library JSON: ${error.message}", error)
        }
    }

    private fun validateAndRepairPolicy(skill: AppSettings.PromptSkillState) {
        val policy = skill.contextPolicy
        if (policy.id.isBlank() || (policy.id == "general-change-policy" && skill.id != "general-change")) {
            skill.contextPolicy = ContextPolicyState.defaultFor(skill.id)
        }
        val effective = skill.contextPolicy
        require(effective.version in 1..100) { "Context Policy version is unsupported." }
        require(CopilotTarget.entries.any { it.name == effective.target }) { "Context Policy target is invalid." }
        require(CopilotReturnMode.entries.any { it.name == effective.returnMode }) { "Context Policy return mode is invalid." }
        require(PreviousBatchMode.entries.any { it.name == effective.previousBatchMode }) {
            "Context Policy previous-batch mode is invalid."
        }
        require(effective.maxRepositoryFiles in 1..500) { "Context Policy repository-file limit must be 1..500." }
        require(effective.maxAttachments in 2..20) { "Context Policy attachment limit must be 2..20." }
        require(effective.rules.isNotEmpty()) { "Context Policy must contain at least one rule." }
        require(
            effective.rules
                .map { it.id }
                .distinct()
                .size == effective.rules.size,
        ) { "Context Policy rule IDs must be unique." }
        effective.rules.forEach { rule ->
            require(rule.id.isNotBlank() && rule.id.length <= 120) { "Context Policy rule ID is invalid." }
            require(rule.resolver.isNotBlank() && rule.resolver.length <= 200) { "Context Policy resolver is invalid." }
            require(rule.priority in 0..1_000) { "Context Policy priority must be 0..1000." }
            require(rule.maxDepth in 0..10) { "Context Policy depth must be 0..10." }
            require(rule.maxFiles in 1..500) { "Context Policy rule file limit must be 1..500." }
            require(rule.bundleGroup.length <= 120) { "Context Policy bundle group is too long." }
            require(rule.parameters.size <= 100 && rule.parameters.all { (key, value) -> key.length <= 120 && value.length <= 2_000 }) {
                "Context Policy rule parameters are too large."
            }
        }
    }
}
