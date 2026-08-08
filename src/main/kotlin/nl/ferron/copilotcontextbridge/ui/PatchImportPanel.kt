package nl.ferron.copilotcontextbridge.ui

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import nl.ferron.copilotcontextbridge.patch.JetBrainsDiffFacade
import nl.ferron.copilotcontextbridge.patch.PatchImportService
import nl.ferron.copilotcontextbridge.patch.PatchValidator
import nl.ferron.copilotcontextbridge.patch.PostApplyValidationService
import nl.ferron.copilotcontextbridge.patch.PythonFunctionReplacementService
import nl.ferron.copilotcontextbridge.patch.ReplacementStatus
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.TransferHandler

/** Secure inbound workflow for complete Python-function replacements. */
class PatchImportPanel(
    private val project: Project,
) : JPanel(BorderLayout(6, 6)) {
    private val json = JBTextArea(6, 50)
    private val rows = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

    // Retained as internal state only for compatibility with existing rendering paths; no textual diff widget is shown.
    private val diff = JBTextArea().apply { isEditable = false }
    private val diffTitle = JLabel()
    private val validation = JLabel("Schema  ·  Paths  ·  Functions  ·  Hashes")
    private val changesTitle = JLabel("3. Changes found")
    private val applyButton =
        JButton("Apply selected changes").apply {
            putClientProperty("JButton.buttonType", "default")
            addActionListener { applySelected() }
        }
    private val inputCards = JPanel(CardLayout())
    private var current: PatchValidator.Result? = null
    private val selections = mutableMapOf<String, JCheckBox>()
    private val forces = mutableMapOf<String, JCheckBox>()
    private val diffFacade = JetBrainsDiffFacade(project)
    private val validationGeneration = AtomicLong()

    init {
        border = JBUI.Borders.empty(8)
        val content =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(stepTitle("1. Drop .copilotpatch / JSON / ZIP"))
                add(JLabel("Structured changes.json is preferred; a plain code ZIP is reviewed as a safe fallback."))
                add(createInputPanel())
                add(Box.createVerticalStrut(JBUI.scale(9)))
                add(stepTitle("2. Validation"))
                add(validationCard())
                add(Box.createVerticalStrut(JBUI.scale(9)))
                add(changesTitle.apply { font = font.deriveFont(Font.BOLD, font.size2D + 1f) })
                add(createChangesPanel())
            }
        add(content, BorderLayout.CENTER)
        applyButton.isEnabled = false
    }

    private fun createInputPanel(): JPanel {
        val drop =
            JLabel(
                "<html><center><b>Drop Copilot result here</b><br><font color='#888888'>changes.json preferred · plain ZIP supported</font></center></html>",
                AllIcons.Nodes.Folder,
                SwingConstants.CENTER,
            ).apply {
                border = BorderFactory.createDashedBorder(JBColor.GRAY, 2f, 4f)
                preferredSize = Dimension(JBUI.scale(360), JBUI.scale(94))
                transferHandler =
                    object : TransferHandler() {
                        override fun canImport(support: TransferSupport) = support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)

                        override fun importData(support: TransferSupport): Boolean {
                            val files = support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*> ?: return false
                            val file = files.filterIsInstance<File>().singleOrNull() ?: return false
                            loadFile(file)
                            return true
                        }
                    }
                addMouseListener(
                    object : java.awt.event.MouseAdapter() {
                        override fun mouseClicked(event: java.awt.event.MouseEvent) = chooseFile()
                    },
                )
            }
        val paste =
            JPanel(BorderLayout(4, 4)).apply {
                add(JBScrollPane(json), BorderLayout.CENTER)
                add(JButton("Validate pasted JSON").apply { addActionListener { validateJson() } }, BorderLayout.SOUTH)
            }
        inputCards.add(drop, "drop")
        inputCards.add(paste, "paste")
        return JPanel(BorderLayout(4, 4)).apply {
            border = cardBorder()
            add(inputCards, BorderLayout.CENTER)
            add(
                JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
                    add(JButton("Open patch file").apply { addActionListener { chooseFile() } })
                    add(JButton("Paste JSON").apply { addActionListener { pasteJsonFromClipboard() } })
                },
                BorderLayout.SOUTH,
            )
        }
    }

    private fun validationCard() =
        JPanel(BorderLayout()).apply {
            border = cardBorder()
            add(validation, BorderLayout.CENTER)
        }

    private fun createChangesPanel() =
        JPanel(BorderLayout(5, 5)).apply {
            border = cardBorder()
            add(
                JPanel(BorderLayout()).apply {
                    add(JLabel("Review changes in PyCharm's native Diff viewer."), BorderLayout.CENTER)
                    add(JButton("View combined diff").apply { addActionListener { showCombinedDiff() } }, BorderLayout.EAST)
                },
                BorderLayout.NORTH,
            )
            add(JBScrollPane(rows), BorderLayout.CENTER)
            add(
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    add(
                        JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
                            add(JButton("Select all").apply { addActionListener { selectAll() } })
                            add(JButton("Select safe").apply { addActionListener { selectSafe() } })
                            add(JButton("Deselect conflicts").apply { addActionListener { deselectConflicts() } })
                            add(JButton("Clear").apply { addActionListener { clearImport() } })
                        },
                    )
                    add(applyButton.apply { maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height) })
                    add(
                        JLabel("All applied changes are Undoable (Ctrl+Z)").apply {
                            foreground = JBColor.GRAY
                            alignmentX = CENTER_ALIGNMENT
                        },
                    )
                },
                BorderLayout.SOUTH,
            )
        }

    private fun chooseFile() {
        val descriptor =
            FileChooserDescriptor(true, false, false, false, false, false).withFileFilter {
                it.extension?.lowercase() in setOf("copilotpatch", "json", "zip")
            }
        FileChooser.chooseFile(descriptor, project, null)?.let { loadFile(File(it.path)) }
    }

    fun loadFile(file: File) {
        loadInBackground("Validating Copilot patch") { PatchImportService(project).load(file.toPath()) }
    }

    private fun validateJson() {
        val suppliedJson = json.text
        loadInBackground("Validating pasted Copilot patch") { PatchImportService(project).loadJson(suppliedJson) }
    }

    private fun pasteJsonFromClipboard() {
        (inputCards.layout as CardLayout).show(inputCards, "paste")
        val clipboardText = CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor) as? String
        if (!clipboardText.isNullOrBlank()) {
            json.text = clipboardText
            json.caretPosition = 0
            validation.text = "Ready to validate pasted JSON"
        }
        json.requestFocusInWindow()
    }

    private fun loadInBackground(
        title: String,
        loader: () -> PatchValidator.Result,
    ) {
        val generation = validationGeneration.incrementAndGet()
        validation.text = "Validating schema, paths, functions and hashes…"
        object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                val outcome = runCatching(loader)
                ApplicationManager.getApplication().invokeLater {
                    if (generation != validationGeneration.get()) return@invokeLater
                    outcome
                        .onSuccess(::showResult)
                        .onFailure { showLoadError(it.message ?: "Patch could not be loaded.") }
                }
            }
        }.queue()
    }

    private fun showResult(result: PatchValidator.Result) {
        current = result
        rows.removeAll()
        selections.clear()
        forces.clear()
        val validSchema = result.validation.errors.isEmpty()
        validation.text =
            if (validSchema) {
                "✓ Schema    ✓ Paths    ✓ Functions    ✓ Hashes"
            } else {
                "⚠ Validation failed — ${result.validation.errors.size} issue(s)"
            }
        validation.foreground = if (validSchema) JBColor(0x258A4A, 0x61C879) else JBColor(0xB45309, 0xE6A34A)
        changesTitle.text = "3. Changes found (${result.targets.size})"
        result.validation.patch?.summary?.let { summary ->
            if (summary.overview.isNotBlank()) rows.add(JLabel("Summary: ${summary.overview}"))
        }
        result.targets.forEach { target -> addTargetRow(target.validated) }
        result.validation.errors.forEach { rows.add(JLabel("⚠ $it").apply { foreground = JBColor.RED }) }
        rows.revalidate()
        rows.repaint()
        diff.text =
            result.targets
                .firstOrNull()
                ?.validated
                ?.unifiedDiff
                .orEmpty()
        diffTitle.text =
            result.targets
                .firstOrNull()
                ?.validated
                ?.let(::diffTitleFor)
                ?: "Function diff"
        applyButton.isEnabled = validSchema && selections.values.any { it.isSelected }
        updateApplyCaption()
    }

    private fun addTargetRow(item: nl.ferron.copilotcontextbridge.patch.ValidatedReplacement) {
        val key = "${item.request.path}::${item.request.qualifiedName}"
        val safe = item.status in setOf(ReplacementStatus.MATCH, ReplacementStatus.NEW)
        val selectable = safe || item.status == ReplacementStatus.CHANGED
        val displayName =
            if (item.request.operation.endsWith("_file")) {
                when (item.request.operation) {
                    "add_file" -> "Add file"
                    "replace_file" -> "Replace file"
                    "delete_file" -> "Delete file"
                    else -> "File change"
                }
            } else {
                "${item.request.qualifiedName}()"
            }
        val selected =
            JCheckBox(displayName, item.selected && selectable).apply {
                isEnabled = selectable
                toolTipText = item.request.path
                addActionListener {
                    diff.text = item.unifiedDiff
                    diff.caretPosition = 0
                    diffTitle.text = diffTitleFor(item)
                    updateApplyCaption()
                }
            }
        selections[key] = selected
        val force =
            JCheckBox("Use Copilot version", false).apply {
                isVisible = item.status == ReplacementStatus.CHANGED
                toolTipText = "Explicitly choose the Copilot proposal after reviewing the 3-way diff"
                addActionListener { updateApplyCaption() }
            }
        forces[key] = force
        val badgeText =
            when (item.status) {
                ReplacementStatus.MATCH, ReplacementStatus.NEW -> "Safe"
                ReplacementStatus.CHANGED -> "Conflict"
                else ->
                    item.status.name
                        .lowercase()
                        .replaceFirstChar(Char::uppercase)
            }
        val badge =
            JLabel(badgeText).apply {
                foreground = if (safe) JBColor(0x258A4A, 0x61C879) else JBColor(0xB45309, 0xE6A34A)
                font = font.deriveFont(Font.BOLD)
            }
        rows.add(
            JPanel(BorderLayout(5, 2)).apply {
                border = JBUI.Borders.empty(4, 1)
                add(
                    JPanel(BorderLayout()).apply {
                        isOpaque = false
                        add(selected, BorderLayout.CENTER)
                        add(badge, BorderLayout.EAST)
                    },
                    BorderLayout.NORTH,
                )
                add(
                    JPanel().apply {
                        layout = BoxLayout(this, BoxLayout.Y_AXIS)
                        isOpaque = false
                        add(
                            JLabel("${item.request.path}  •  ${item.oldLineCount} → ${item.newLineCount} lines").apply {
                                foreground = JBColor.GRAY
                            },
                        )
                        add(JLabel("<html>${escapeHtml(item.message)}</html>").apply { foreground = JBColor.GRAY })
                    },
                    BorderLayout.CENTER,
                )
                if (force.isVisible) add(force, BorderLayout.SOUTH)
                add(
                    JButton(if (item.status == ReplacementStatus.CHANGED) "Open 3-way diff" else "Open diff").apply {
                        addActionListener {
                            diffFacade.showFunctionDiff(item) { include, useCopilot ->
                                ApplicationManager.getApplication().invokeLater {
                                    selected.isSelected = include
                                    force.isSelected = useCopilot
                                    updateApplyCaption()
                                }
                            }
                        }
                    },
                    BorderLayout.EAST,
                )
            },
        )
    }

    private fun diffTitleFor(item: nl.ferron.copilotcontextbridge.patch.ValidatedReplacement): String =
        if (item.request.operation.endsWith("_file")) {
            "Diff — ${item.request.path}"
        } else {
            "Diff — ${item.request.path}::${item.request.qualifiedName}"
        }

    private fun selectAll() {
        selections.values.filter { it.isEnabled }.forEach { it.isSelected = true }
        updateApplyCaption()
    }

    private fun selectSafe() {
        val result = current ?: return
        result.targets.forEach { target ->
            val item = target.validated
            val key = "${item.request.path}::${item.request.qualifiedName}"
            selections[key]?.isSelected = item.status in setOf(ReplacementStatus.MATCH, ReplacementStatus.NEW)
            if (item.status == ReplacementStatus.CHANGED) forces[key]?.isSelected = false
        }
        updateApplyCaption()
    }

    private fun showCombinedDiff() {
        val selectedKeys = selections.filterValues { it.isSelected }.keys
        val targets =
            current
                ?.targets
                .orEmpty()
                .filter { "${it.validated.request.path}::${it.validated.request.qualifiedName}" in selectedKeys }
        if (targets.isEmpty()) {
            UiSupport.notify(
                project,
                "No changes selected",
                "Select at least one validated operation before opening the combined diff.",
                NotificationType.WARNING,
            )
            return
        }
        diffFacade.showCombinedDiff(targets.map { it.validated })
        diffTitle.text = "Combined diff — ${targets.size} replacement(s)"
    }

    private fun deselectConflicts() {
        forces.filterValues { it.isVisible }.keys.forEach { key ->
            selections[key]?.isSelected = false
            forces[key]?.isSelected = false
        }
        updateApplyCaption()
    }

    fun clearImport() {
        validationGeneration.incrementAndGet()
        current = null
        json.text = ""
        rows.removeAll()
        selections.clear()
        forces.clear()
        diff.text = ""
        diffTitle.text = ""
        validation.text = "Schema  ·  Paths  ·  Functions  ·  Hashes"
        changesTitle.text = "3. Changes found"
        applyButton.isEnabled = false
        (inputCards.layout as CardLayout).show(inputCards, "drop")
        rows.revalidate()
        rows.repaint()
    }

    private fun updateApplyCaption() {
        val amount = selections.values.count { it.isSelected }
        applyButton.text = "Apply selected ($amount)"
        applyButton.isEnabled = current?.validation?.errors?.isEmpty() == true && amount > 0
    }

    private fun applySelected() {
        val result = current ?: return
        if (result.validation.errors.isNotEmpty()) {
            showError(result.validation.errors.joinToString("\n"))
            return
        }
        val selected = selections.filterValues { it.isSelected }.keys
        val forced = forces.filterValues { it.isSelected }.keys
        val conflictsWithoutForce = selected.filter { key -> forces[key]?.isVisible == true && forces[key]?.isSelected != true }
        if (conflictsWithoutForce.isNotEmpty()) {
            val details =
                conflictsWithoutForce.joinToString("\n\n") { key ->
                    val target =
                        result.targets
                            .first { "${it.validated.request.path}::${it.validated.request.qualifiedName}" == key }
                            .validated
                    val targetKind = if (target.request.operation.endsWith("_file")) "file" else "function"
                    "WHAT: The local $targetKind changed after export.\n" +
                        "WHERE: $key\n" +
                        "WHY: The Copilot proposal was generated against an older $targetKind hash.\n" +
                        "EXPECTED: ${target.request.originalHash}\n" +
                        "CURRENT: ${if (target.request.operation.endsWith(
                                "_file",
                            )
                        ) {
                            nl.ferron.copilotcontextbridge.patch.FileContentHasher.hash(
                                target.oldText,
                            )
                        } else {
                            nl.ferron.copilotcontextbridge.analysis.FunctionHasher
                                .hash(target.oldText)
                        }}\n" +
                        "HOW TO RESOLVE: Open the 3-way diff, then choose Use Copilot version or deselect the change."
                }
            UiSupport.notify(project, "Resolve import conflicts", details.replace("\n", "<br>"), NotificationType.WARNING)
            return
        }
        revalidateBeforeApply(result, selected, forced)
    }

    private fun revalidateBeforeApply(
        reviewed: PatchValidator.Result,
        selected: Set<String>,
        forced: Set<String>,
    ) {
        val patch = reviewed.validation.patch ?: return
        val generation = validationGeneration.incrementAndGet()
        validation.text = "Revalidating paths, PSI targets and hashes before Apply…"
        object : Task.Backgroundable(project, "Revalidating Copilot changes", true) {
            override fun run(indicator: ProgressIndicator) {
                val refreshed =
                    ReadAction
                        .nonBlocking<PatchValidator.Result> {
                            PatchValidator(project).validate(patch)
                        }.executeSynchronously()
                ApplicationManager.getApplication().invokeLater {
                    if (generation != validationGeneration.get()) return@invokeLater
                    val reviewedState = validationFingerprint(reviewed)
                    val refreshedState = validationFingerprint(refreshed)
                    if (reviewedState != refreshedState || refreshed.validation.errors.isNotEmpty()) {
                        showResult(refreshed)
                        UiSupport.notify(
                            project,
                            "Import changed during review",
                            "Files, functions or hashes changed after validation. The import was refreshed; review the native diffs again.",
                            NotificationType.WARNING,
                        )
                        return@invokeLater
                    }
                    if (
                        Messages.showYesNoDialog(
                            project,
                            applyConfirmationMessage(refreshed, selected, forced),
                            "Confirm Copilot Function Replacements",
                            "Apply selected",
                            "Cancel",
                            null,
                        ) != Messages.YES
                    ) {
                        validation.text = "Apply cancelled; reviewed changes remain loaded."
                        return@invokeLater
                    }
                    val deleteCount =
                        refreshed.targets.count { target ->
                            target.validated.request.operation == "delete_file" &&
                                "${target.validated.request.path}::${target.validated.request.qualifiedName}" in selected
                        }
                    if (
                        deleteCount > 0 &&
                        Messages.showYesNoDialog(
                            project,
                            "This will delete $deleteCount project file(s). The deletion is Undoable in PyCharm, but only continue after reviewing each deletion diff.",
                            "Confirm File Deletion",
                            "Delete selected files",
                            "Cancel",
                            Messages.getWarningIcon(),
                        ) != Messages.YES
                    ) {
                        validation.text = "File deletion cancelled; reviewed changes remain loaded."
                        return@invokeLater
                    }
                    applyValidated(refreshed, selected, forced)
                }
            }
        }.queue()
    }

    private fun validationFingerprint(result: PatchValidator.Result): List<String> =
        result.targets.map { target ->
            val item = target.validated
            "${item.request.path}::${item.request.qualifiedName}:${item.status}:" +
                nl.ferron.copilotcontextbridge.analysis.FunctionHasher
                    .hash(item.oldText)
        }

    private fun applyConfirmationMessage(
        result: PatchValidator.Result,
        selected: Set<String>,
        forced: Set<String>,
    ): String {
        val operations =
            result.targets.filter { target ->
                "${target.validated.request.path}::${target.validated.request.qualifiedName}" in selected
            }
        val fileOperations =
            operations.count {
                it.validated.request.operation
                    .endsWith("_file")
            }
        val functionOperations = operations.size - fileOperations
        val conflicts = operations.count { it.validated.status == ReplacementStatus.CHANGED }
        return buildString {
            append("Apply ${operations.size} selected change(s) as one Undoable PyCharm operation?")
            append("\n\nFunctions: $functionOperations; whole files: $fileOperations; conflicts: $conflicts.")
            if (forced.isNotEmpty()) append("\n${forced.size} conflict decision(s) will use the Copilot version.")
        }
    }

    private fun applyValidated(
        result: PatchValidator.Result,
        selected: Set<String>,
        forced: Set<String>,
    ) {
        val applied = PythonFunctionReplacementService(project).apply(result, selected, forced)
        UiSupport.notify(
            project,
            "Copilot replacements",
            "Applied: ${applied.applied.size}; skipped: ${applied.skipped.size}; failed: ${applied.failures.size}.",
            if (applied.failures.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING,
        )
        if (applied.applied.isNotEmpty() && applied.failures.isEmpty()) {
            val command = project.getService(nl.ferron.copilotcontextbridge.settings.ProjectSettings::class.java).state.postApplyCommand
            PostApplyValidationService(project).run(command)
        }
    }

    private fun stepTitle(text: String) = JLabel(text).apply { font = font.deriveFont(Font.BOLD, font.size2D + 1f) }

    private fun cardBorder() = BorderFactory.createCompoundBorder(JBUI.Borders.customLine(JBColor.border(), 1), JBUI.Borders.empty(7))

    private fun escapeHtml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun showError(message: String) {
        current = null
        validation.text = "⚠ Validation failed — $message"
        changesTitle.text = "3. Changes found"
        rows.removeAll()
        diff.text = ""
        selections.clear()
        forces.clear()
        applyButton.isEnabled = false
        rows.revalidate()
        rows.repaint()
        UiSupport.notify(project, "Patch rejected", message, NotificationType.ERROR)
    }

    private fun showLoadError(message: String) {
        if (current == null) {
            showError(message)
        } else {
            UiSupport.notify(
                project,
                "Patch rejected; current review preserved",
                message,
                NotificationType.ERROR,
            )
        }
    }
}
