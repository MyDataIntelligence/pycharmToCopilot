package nl.ferron.copilotcontextbridge.settings

/** Deterministic Prompt Library mutations kept outside Swing for reliable testing. */
object PromptSkillLibraryEditor {
    fun add(
        skills: MutableList<AppSettings.PromptSkillState>,
        id: String,
    ): AppSettings.PromptSkillState {
        require(id.isNotBlank() && skills.none { it.id == id }) { "Prompt skill ID must be unique." }
        return AppSettings
            .PromptSkillState(
                id,
                "New prompt skill",
                "",
                "Ask the user to clarify the goal, then follow this skill.",
                "",
                ContextPolicyState.defaultFor(id),
                "Custom",
            ).also(skills::add)
    }

    fun duplicate(
        skills: MutableList<AppSettings.PromptSkillState>,
        sourceIndex: Int,
        id: String,
    ): AppSettings.PromptSkillState {
        require(sourceIndex in skills.indices) { "Prompt skill index is outside the library." }
        require(id.isNotBlank() && skills.none { it.id == id }) { "Prompt skill ID must be unique." }
        val source = skills[sourceIndex]
        return AppSettings
            .PromptSkillState(
                id,
                "${source.name} copy",
                source.description,
                source.prompt,
                source.guidelines,
                source.contextPolicy.copyOf().also { it.id = "$id-policy" },
                source.category,
            ).also {
                it.returnInstructionsAddition = source.returnInstructionsAddition
                skills += it
            }
    }

    fun remove(
        skills: MutableList<AppSettings.PromptSkillState>,
        index: Int,
    ): Boolean {
        if (skills.size <= 1 || index !in skills.indices) return false
        skills.removeAt(index)
        return true
    }
}
