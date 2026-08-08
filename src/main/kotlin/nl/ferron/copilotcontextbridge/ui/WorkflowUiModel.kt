package nl.ferron.copilotcontextbridge.ui

/** Stable identity for a Prompt Library choice; labels are presentation only and may be duplicated. */
internal data class PromptSkillChoice(
    val id: String,
    val label: String,
) {
    override fun toString(): String = label
}

/** Pure state projection shared by the workflow buttons and their headless tests. */
internal data class WorkflowControlState(
    val canPrepare: Boolean,
    val canUsePreparedFiles: Boolean,
    val canCopyContext: Boolean,
    val canStartNewSession: Boolean,
)

internal fun workflowControlState(
    hasValidPack: Boolean,
    hasStagedPack: Boolean,
    calculating: Boolean,
    preparing: Boolean,
): WorkflowControlState =
    WorkflowControlState(
        canPrepare = hasValidPack && !hasStagedPack && !calculating && !preparing,
        canUsePreparedFiles = hasStagedPack && !calculating && !preparing,
        canCopyContext = hasValidPack && !calculating && !preparing,
        canStartNewSession = !calculating && !preparing,
    )

/** Fixed top-level order; modelled separately so labels cannot drift from the rendered navigation. */
internal object TopLevelNavigationModel {
    val destinations = listOf("Batch", "Import", "Preview", "More")
}

/** The Preview workspace keeps both projections of the same outgoing pack together. */
internal object PreviewWorkspaceModel {
    val sections = listOf("Context preview", "Context files")
}

/** Fixed More-workspace ordering. Keeping this as data prevents refreshes from reordering controls. */
internal object MoreWorkspaceModel {
    data class Destination(
        val title: String,
        val subtitle: String,
        val tabIndex: Int?,
    )

    val destinations =
        listOf(
            Destination("Guidelines", "Repository and global instructions", 1),
            Destination("Prompt skills", "Prompts with their own guidelines", 2),
            Destination("Return instructions", "Effective Copilot output contract", 3),
            Destination("Settings", "Limits, exclusions and behaviour", null),
        )

    val quickActions = listOf("Copy context", "Copy return instructions")
    val historyActions = listOf("Restore", "Keep staged files", "Delete staged files", "Forget", "New session")
}
