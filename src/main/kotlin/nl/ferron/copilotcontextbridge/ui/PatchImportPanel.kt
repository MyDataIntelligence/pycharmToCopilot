package nl.ferron.copilotcontextbridge.ui

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
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
import nl.ferron.copilotcontextbridge.patch.PatchDiffFormatter
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
import java.awt.event.HierarchyEvent
import java.io.File
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.TransferHandler

/** Secure inbound workflow for complete Python-function replacements. */
class PatchImportPanel(
    private val project: Project,
) : JPanel(BorderLayout(6, 6)) {
    private val json = JBTextArea(6, 50)
    private val rows = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val diff = JBTextArea(14, 55).apply { isEditable = false }
    private val diffTitle = JLabel("Function diff")
    private val validation = JLabel("Schema  ·  Paths  ·  Functions  ·  Hashes")
    private val changesTitle = JLabel("3. Changes found")
    private val applyButton =
        JButton("Apply selected functions").apply {
            putClientProperty("JButton.buttonType", "default")
            addActionListener { applySelected() }
        }
    private val inputCards = JPanel(CardLayout())
    private var current: PatchValidator.Result? = null
    private val selections = mutableMapOf<String, JCheckBox>()
    private val forces = mutableMapOf<String, JCheckBox>()

    init {
        border = JBUI.Borders.empty(8)
        val content =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(stepTitle("1. Drop .copilotpatch / JSON / ZIP"))
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
                "<html><center><b>Drop file here</b><br><font color='#888888'>or click to open</font></center></html>",
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
            val diffPanel =
                JPanel(BorderLayout(4, 4)).apply {
                    add(
                        JPanel(BorderLayout()).apply {
                            add(diffTitle, BorderLayout.CENTER)
                            add(JButton("View combined diff").apply { addActionListener { showCombinedDiff() } }, BorderLayout.EAST)
                        },
                        BorderLayout.NORTH,
                    )
                    add(JBScrollPane(diff), BorderLayout.CENTER)
                }
            val split =
                JSplitPane(JSplitPane.VERTICAL_SPLIT, JBScrollPane(rows), diffPanel).apply {
                    resizeWeight = 0.5
                    isOneTouchExpandable = true
                    isContinuousLayout = true
                    border = null
                    addHierarchyListener { event ->
                        if (
                            event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L &&
                            isShowing &&
                            getClientProperty("initialDividerSet") != true
                        ) {
                            putClientProperty("initialDividerSet", true)
                            SwingUtilities.invokeLater { setDividerLocation(0.5) }
                        }
                    }
                }
            add(split, BorderLayout.CENTER)
            add(
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    add(
                        JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
                            add(JButton("Select safe").apply { addActionListener { selectSafe() } })
                            add(JButton("Deselect conflicts").apply { addActionListener { deselectConflicts() } })
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

    private fun loadFile(file: File) {
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
        validation.text = "Validating schema, paths, functions and hashes…"
        object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                val outcome = runCatching(loader)
                ApplicationManager.getApplication().invokeLater {
                    outcome
                        .onSuccess(::showResult)
                        .onFailure { showError(it.message ?: "Patch could not be loaded.") }
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
        changesTitle.text = "3. Changes found (${result.targets.size} functions)"
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
                ?.let { "Diff — ${it.request.path}::${it.request.qualifiedName}" }
                ?: "Function diff"
        applyButton.isEnabled = validSchema && selections.values.any { it.isSelected }
        updateApplyCaption()
    }

    private fun addTargetRow(item: nl.ferron.copilotcontextbridge.patch.ValidatedReplacement) {
        val key = "${item.request.path}::${item.request.qualifiedName}"
        val safe = item.status in setOf(ReplacementStatus.MATCH, ReplacementStatus.NEW)
        val selectable = safe || item.status == ReplacementStatus.CHANGED
        val selected =
            JCheckBox("${item.request.qualifiedName}()", item.selected && safe).apply {
                isEnabled = selectable
                toolTipText = item.request.path
                addActionListener {
                    diff.text = item.unifiedDiff
                    diff.caretPosition = 0
                    diffTitle.text = "Diff — ${item.request.path}::${item.request.qualifiedName}"
                    updateApplyCaption()
                }
            }
        selections[key] = selected
        val force =
            JCheckBox("Force replace", false).apply {
                isVisible = item.status == ReplacementStatus.CHANGED
                toolTipText = "Explicitly overwrite a function that changed locally after export"
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
            },
        )
    }

    private fun selectSafe() {
        val result = current ?: return
        result.targets.forEach { target ->
            val item = target.validated
            val key = "${item.request.path}::${item.request.qualifiedName}"
            selections[key]?.isSelected = item.status in setOf(ReplacementStatus.MATCH, ReplacementStatus.NEW)
        }
        updateApplyCaption()
    }

    private fun showCombinedDiff() {
        val targets = current?.targets.orEmpty()
        diff.text = PatchDiffFormatter.combined(targets)
        diff.caretPosition = 0
        diffTitle.text = "Combined diff — ${targets.size} replacement(s)"
    }

    private fun deselectConflicts() {
        forces.keys.forEach { key ->
            selections[key]?.isSelected = false
            forces[key]?.isSelected = false
        }
        updateApplyCaption()
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
            showError("Select Force replace for each local conflict, or deselect the conflicting function.")
            return
        }
        if (forced.isNotEmpty() &&
            Messages.showYesNoDialog(
                project,
                "Force-replace ${forced.size} locally changed function(s)?",
                "Confirm Force Replace",
                null,
            ) != Messages.YES
        ) {
            return
        }
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
}
