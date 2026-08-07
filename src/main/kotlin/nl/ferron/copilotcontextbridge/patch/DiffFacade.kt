package nl.ferron.copilotcontextbridge.patch

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.jetbrains.python.PythonFileType

/** Keeps version-sensitive JetBrains Diff API usage isolated from the import UI. */
interface DiffFacade {
    fun showFunctionDiff(
        replacement: ValidatedReplacement,
        onSelectionChanged: (selected: Boolean, useCopilotForConflict: Boolean) -> Unit,
    )

    fun showCombinedDiff(replacements: List<ValidatedReplacement>)
}

class JetBrainsDiffFacade(
    private val project: Project,
) : DiffFacade {
    override fun showFunctionDiff(
        replacement: ValidatedReplacement,
        onSelectionChanged: (Boolean, Boolean) -> Unit,
    ) {
        val request = createRequest(replacement, onSelectionChanged)
        DiffManager.getInstance().showDiff(project, request)
    }

    override fun showCombinedDiff(replacements: List<ValidatedReplacement>) {
        val factory = DiffContentFactory.getInstance()
        val current = combinedContent(replacements, useProposed = false)
        val proposed = combinedContent(replacements, useProposed = true)
        DiffManager.getInstance().showDiff(
            project,
            SimpleDiffRequest(
                "Copilot changes (${replacements.size})",
                factory.create(project, current, PythonFileType.INSTANCE),
                factory.create(project, proposed, PythonFileType.INSTANCE),
                "CURRENT",
                "COPILOT PROPOSED",
            ),
        )
    }

    internal fun createRequest(
        replacement: ValidatedReplacement,
        onSelectionChanged: (Boolean, Boolean) -> Unit,
    ): SimpleDiffRequest {
        val contentFactory = DiffContentFactory.getInstance()
        val title = "${replacement.request.path} :: ${replacement.request.qualifiedName}"
        val request =
            if (replacement.status == ReplacementStatus.CHANGED && replacement.baseText.isNotBlank()) {
                SimpleDiffRequest(
                    title,
                    contentFactory.create(project, replacement.baseText, PythonFileType.INSTANCE),
                    contentFactory.create(project, replacement.oldText, PythonFileType.INSTANCE),
                    contentFactory.create(project, replacement.newText, PythonFileType.INSTANCE),
                    "BASE (exported)",
                    "CURRENT (local)",
                    "PROPOSED (Copilot)",
                )
            } else {
                SimpleDiffRequest(
                    title,
                    contentFactory.create(project, replacement.oldText, PythonFileType.INSTANCE),
                    contentFactory.create(project, replacement.newText, PythonFileType.INSTANCE),
                    if (replacement.status == ReplacementStatus.NEW) "CURRENT (does not exist)" else "CURRENT",
                    "COPILOT PROPOSED",
                )
            }
        val actions =
            mutableListOf<AnAction>(
                selectionAction("Include in Apply") { onSelectionChanged(true, false) },
                selectionAction("Exclude from Apply") { onSelectionChanged(false, false) },
            )
        if (replacement.status == ReplacementStatus.CHANGED) {
            actions += selectionAction("Use Copilot version for this conflict") { onSelectionChanged(true, true) }
            actions += selectionAction("Keep current version") { onSelectionChanged(false, false) }
        }
        request.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, actions)
        request.putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
        return request
    }

    private fun selectionAction(
        text: String,
        action: () -> Unit,
    ): AnAction =
        object : AnAction(text) {
            override fun actionPerformed(event: AnActionEvent) = action()
        }

    private fun combinedContent(
        replacements: List<ValidatedReplacement>,
        useProposed: Boolean,
    ): String =
        replacements.joinToString("\n\n") { replacement ->
            "# ${replacement.request.path} :: ${replacement.request.qualifiedName}\n" +
                if (useProposed) replacement.newText else replacement.oldText
        }
}
