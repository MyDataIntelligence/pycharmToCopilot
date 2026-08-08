package nl.ferron.copilotcontextbridge.patch

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project

/** Keeps version-sensitive JetBrains Diff API usage isolated from the import UI. */
interface DiffFacade {
    fun showFunctionDiff(
        replacement: ValidatedReplacement,
        onSelectionChanged: (selected: Boolean, useCopilotForConflict: Boolean, proposedText: String) -> Unit,
    )

    fun showCombinedDiff(replacements: List<ValidatedReplacement>)
}

class JetBrainsDiffFacade(
    private val project: Project,
) : DiffFacade {
    override fun showFunctionDiff(
        replacement: ValidatedReplacement,
        onSelectionChanged: (Boolean, Boolean, String) -> Unit,
    ) {
        val request = createRequest(replacement, onSelectionChanged)
        DiffManager.getInstance().showDiff(project, request)
    }

    override fun showCombinedDiff(replacements: List<ValidatedReplacement>) {
        val factory = DiffContentFactory.getInstance()
        val current = combinedContent(replacements, useProposed = false)
        val proposed = combinedContent(replacements, useProposed = true)
        val plainText = FileTypeManager.getInstance().getFileTypeByFileName("combined.txt")
        DiffManager.getInstance().showDiff(
            project,
            SimpleDiffRequest(
                "Copilot changes (${replacements.size})",
                factory.create(project, current, plainText),
                factory.create(project, proposed, plainText),
                "CURRENT",
                "COPILOT PROPOSED",
            ),
        )
    }

    internal fun createRequest(
        replacement: ValidatedReplacement,
        onSelectionChanged: (Boolean, Boolean, String) -> Unit,
    ): SimpleDiffRequest {
        val contentFactory = DiffContentFactory.getInstance()
        // This document is deliberately not backed by a VirtualFile. Reviewers can refine the
        // proposed result in Diff without touching the project; its text is only returned when an
        // explicit include/use-Copilot action is chosen.
        val proposedDocument = EditorFactory.getInstance().createDocument(replacement.newText)
        val title = "${replacement.request.path} :: ${replacement.request.qualifiedName}"
        val fileType = fileTypeFor(replacement.request.path)
        val request =
            if (replacement.status == ReplacementStatus.CHANGED && replacement.baseText.isNotBlank()) {
                SimpleDiffRequest(
                    title,
                    contentFactory.create(project, replacement.baseText, fileType),
                    contentFactory.create(project, replacement.oldText, fileType),
                    contentFactory.create(project, proposedDocument, fileType),
                    "BASE (exported)",
                    "CURRENT (local)",
                    "PROPOSED (Copilot)",
                )
            } else {
                SimpleDiffRequest(
                    title,
                    contentFactory.create(project, replacement.oldText, fileType),
                    contentFactory.create(project, proposedDocument, fileType),
                    if (replacement.status == ReplacementStatus.NEW) "CURRENT (does not exist)" else "CURRENT",
                    "COPILOT PROPOSED",
                )
            }
        val actions =
            mutableListOf<AnAction>(
                selectionAction("Include proposed result in Apply") { onSelectionChanged(true, false, proposedDocument.text) },
                selectionAction("Exclude from Apply") { onSelectionChanged(false, false, proposedDocument.text) },
            )
        if (replacement.status == ReplacementStatus.CHANGED) {
            actions += selectionAction("Resolve with proposed result") { onSelectionChanged(true, true, proposedDocument.text) }
            actions += selectionAction("Keep current version") { onSelectionChanged(false, false, proposedDocument.text) }
        }
        request.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, actions)
        request.putUserData(
            DiffUserDataKeys.FORCE_READ_ONLY_CONTENTS,
            if (request.contents.size == 3) booleanArrayOf(true, true, false) else booleanArrayOf(true, false),
        )
        return request
    }

    internal fun proposedDocument(request: SimpleDiffRequest): Document =
        request.contents
            .last()
            .let { content -> (content as com.intellij.diff.contents.DocumentContent).document }

    private fun selectionAction(
        text: String,
        action: () -> Unit,
    ): AnAction =
        object : AnAction(text) {
            override fun actionPerformed(event: AnActionEvent) = action()
        }

    private fun fileTypeFor(path: String): FileType = FileTypeManager.getInstance().getFileTypeByFileName(path.substringAfterLast('/'))

    private fun combinedContent(
        replacements: List<ValidatedReplacement>,
        useProposed: Boolean,
    ): String =
        replacements.joinToString("\n\n") { replacement ->
            "# ${replacement.request.path} :: ${replacement.request.qualifiedName}\n" +
                if (useProposed) replacement.newText else replacement.oldText
        }
}
