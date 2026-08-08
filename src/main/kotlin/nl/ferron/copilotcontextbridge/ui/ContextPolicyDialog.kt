package nl.ferron.copilotcontextbridge.ui

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import nl.ferron.copilotcontextbridge.settings.ContextPolicyEditor
import nl.ferron.copilotcontextbridge.settings.ContextPolicyState
import nl.ferron.copilotcontextbridge.settings.ContextRuleState
import nl.ferron.copilotcontextbridge.settings.CopilotReturnMode
import nl.ferron.copilotcontextbridge.settings.CopilotTarget
import nl.ferron.copilotcontextbridge.settings.PreviousBatchMode
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JTable
import javax.swing.SpinnerNumberModel
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/** Editable policy for the currently selected prompt library entry. */
class ContextPolicyDialog(
    private val promptId: String,
    private val promptName: String,
    private val policy: ContextPolicyState,
    private val onDuplicatePolicy: (ContextPolicyState) -> Unit = {},
) : DialogWrapper(true) {
    private val workingPolicy = policy.copyOf()
    private val model = RuleTableModel(workingPolicy.rules)
    private val table = JTable(model)
    private val target = JComboBox(CopilotTarget.entries.map { it.name }.toTypedArray())
    private val returnMode = JComboBox(CopilotReturnMode.entries.map { it.name }.toTypedArray())
    private val previous = JComboBox(PreviousBatchMode.entries.map { it.name }.toTypedArray())
    private val repositoryFiles = JSpinner(SpinnerNumberModel(workingPolicy.maxRepositoryFiles.coerceIn(1, 500), 1, 500, 1))
    private val attachmentLimit = JSpinner(SpinnerNumberModel(workingPolicy.maxAttachments.coerceIn(2, 20), 2, 20, 1))
    private val bundleCharacters =
        JSpinner(SpinnerNumberModel(workingPolicy.maxBundleCharacters.coerceIn(10_000, 1_000_000), 10_000, 1_000_000, 5_000))
    private val bundleTokens =
        JSpinner(SpinnerNumberModel(workingPolicy.estimatedMaxBundleTokens.coerceIn(2_500, 250_000), 2_500, 250_000, 1_000))
    private val branchScope = JComboBox(arrayOf("All branch changes", "Selected changed files"))
    private val bundle = JBCheckBox("Bundle automatic context", workingPolicy.bundleAutomaticContext)

    init {
        title = "Context Policy · $promptName"
        target.selectedItem = workingPolicy.target
        returnMode.selectedItem = workingPolicy.returnMode
        previous.selectedItem = workingPolicy.previousBatchMode
        val branchRule = workingPolicy.rules.firstOrNull { it.resolver == "git.branchChanges" }
        branchScope.isEnabled = branchRule != null
        branchScope.selectedIndex = if (branchRule?.parameters?.get("scope") in setOf("selected", "selected-changed")) 1 else 0
        table.autoCreateRowSorter = true
        table.fillsViewportHeight = true
        // Keep long resolver and bundle identifiers inspectable instead of silently replacing
        // them with ellipses.  The policy editor is intentionally horizontally scrollable.
        table.autoResizeMode = JTable.AUTO_RESIZE_OFF
        val columnWidths = intArrayOf(72, 170, 210, 82, 82, 72, 90, 150, 82)
        columnWidths.forEachIndexed { index, width ->
            table.columnModel.getColumn(index).preferredWidth = width
        }
        table.columnModel.getColumn(1).cellRenderer = TooltipCellRenderer()
        table.columnModel.getColumn(2).cellRenderer = TooltipCellRenderer()
        table.columnModel.getColumn(7).cellRenderer = TooltipCellRenderer()
        init()
    }

    override fun createCenterPanel(): JComponent =
        JPanel(BorderLayout(8, 8)).apply {
            preferredSize = java.awt.Dimension(950, 560)
            add(
                JPanel(java.awt.GridLayout(0, 4, 8, 6)).apply {
                    add(JLabel("Target"))
                    add(target)
                    add(JLabel("Return mode"))
                    add(returnMode)
                    add(JLabel("Previous batches"))
                    add(previous)
                    add(JLabel("Max repository files"))
                    add(repositoryFiles)
                    add(JLabel("Max bundle characters"))
                    add(bundleCharacters)
                    add(JLabel("Estimated max bundle tokens"))
                    add(bundleTokens)
                    add(JLabel("Branch change scope"))
                    add(branchScope)
                },
                BorderLayout.NORTH,
            )
            add(JBScrollPane(table), BorderLayout.CENTER)
            add(
                JPanel(BorderLayout()).apply {
                    add(
                        JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                            add(bundle)
                            add(JLabel("Attachment limit"))
                            add(attachmentLimit)
                        },
                        BorderLayout.WEST,
                    )
                    add(
                        JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                            add(JButton("Configure rule…").apply { addActionListener { configureSelected() } })
                            add(JButton("Duplicate rule").apply { addActionListener { duplicateSelected() } })
                            add(
                                JButton("Duplicate policy").apply {
                                    toolTipText = "Create a new prompt skill with this policy as an independent copy"
                                    addActionListener { onDuplicatePolicy(workingPolicy.copyOf()) }
                                },
                            )
                            add(JButton("Reset").apply { addActionListener { resetPolicy() } })
                        },
                        BorderLayout.EAST,
                    )
                },
                BorderLayout.SOUTH,
            )
        }

    override fun doOKAction() {
        workingPolicy.target = target.selectedItem as String
        workingPolicy.returnMode = returnMode.selectedItem as String
        workingPolicy.previousBatchMode = previous.selectedItem as String
        workingPolicy.maxRepositoryFiles = repositoryFiles.value as Int
        workingPolicy.maxAttachments = attachmentLimit.value as Int
        workingPolicy.maxBundleCharacters = bundleCharacters.value as Int
        workingPolicy.estimatedMaxBundleTokens = bundleTokens.value as Int
        workingPolicy.bundleAutomaticContext = bundle.isSelected
        workingPolicy.rules = model.rules.map(ContextRuleState::copyOf).toMutableList()
        workingPolicy.rules.firstOrNull { it.resolver == "git.branchChanges" }?.let { rule ->
            if (branchScope.selectedIndex == 1) {
                rule.parameters["scope"] = "selected"
            } else {
                rule.parameters.remove("scope")
            }
        }
        ContextPolicyEditor.replaceWith(policy, workingPolicy)
        super.doOKAction()
    }

    private fun configureSelected() {
        val row = table.selectedRow.takeIf { it >= 0 } ?: return
        val rule = model.rules[table.convertRowIndexToModel(row)]
        val text = JBTextField(rule.parameters.entries.joinToString("; ") { "${it.key}=${it.value}" })
        if (com.intellij.openapi.ui.Messages.showOkCancelDialog(
                text,
                "Parameters: key=value; key2=value2",
                "Configure ${rule.id}",
                "Save",
                "Cancel",
                null,
            ) ==
            com.intellij.openapi.ui.Messages.OK
        ) {
            rule.parameters =
                text.text
                    .split(';')
                    .mapNotNull { entry ->
                        entry
                            .split(
                                '=',
                                limit = 2,
                            ).takeIf { it.size == 2 && it[0].trim().isNotEmpty() }
                            ?.let { it[0].trim() to it[1].trim() }
                    }.toMap()
                    .toMutableMap()
            model.fireTableRowsUpdated(row, row)
        }
    }

    private fun duplicateSelected() {
        val row = table.selectedRow.takeIf { it >= 0 } ?: return
        ContextPolicyEditor.duplicateRule(model.rules, table.convertRowIndexToModel(row))
        model.fireTableDataChanged()
    }

    private fun resetPolicy() {
        ContextPolicyEditor.resetToPromptDefault(workingPolicy, promptId)
        target.selectedItem = workingPolicy.target
        returnMode.selectedItem = workingPolicy.returnMode
        previous.selectedItem = workingPolicy.previousBatchMode
        repositoryFiles.value = workingPolicy.maxRepositoryFiles
        attachmentLimit.value = workingPolicy.maxAttachments
        bundleCharacters.value = workingPolicy.maxBundleCharacters
        bundleTokens.value = workingPolicy.estimatedMaxBundleTokens
        bundle.isSelected = workingPolicy.bundleAutomaticContext
        model.rules = workingPolicy.rules.map(ContextRuleState::copyOf).toMutableList()
        model.fireTableDataChanged()
        val branchRule = workingPolicy.rules.firstOrNull { it.resolver == "git.branchChanges" }
        branchScope.isEnabled = branchRule != null
        branchScope.selectedIndex = if (branchRule?.parameters?.get("scope") in setOf("selected", "selected-changed")) 1 else 0
    }

    private class RuleTableModel(
        rules: Collection<ContextRuleState>,
    ) : AbstractTableModel() {
        var rules: MutableList<ContextRuleState> = rules.map(ContextRuleState::copyOf).toMutableList()
        private val columns = listOf("Enabled", "Rule", "Resolver", "Priority", "Required", "Depth", "Max files", "Bundle", "Separate")

        override fun getRowCount() = rules.size

        override fun getColumnCount() = columns.size

        override fun getColumnName(column: Int) = columns[column]

        override fun getColumnClass(columnIndex: Int): Class<*> =
            when (columnIndex) {
                0, 4, 8 -> Boolean::class.java
                3, 5, 6 -> Int::class.java
                else -> String::class.java
            }

        override fun isCellEditable(
            rowIndex: Int,
            columnIndex: Int,
        ) = columnIndex != 1 && columnIndex != 2

        override fun getValueAt(
            rowIndex: Int,
            columnIndex: Int,
        ): Any {
            val rule = rules[rowIndex]
            return when (columnIndex) {
                0 -> rule.enabled
                1 -> rule.id
                2 -> rule.resolver
                3 -> rule.priority
                4 -> rule.required
                5 -> rule.maxDepth
                6 -> rule.maxFiles
                7 -> rule.bundleGroup
                else -> rule.keepSeparate
            }
        }

        override fun setValueAt(
            value: Any?,
            rowIndex: Int,
            columnIndex: Int,
        ) {
            val rule = rules[rowIndex]
            when (columnIndex) {
                0 -> rule.enabled = value as Boolean
                3 -> rule.priority = value.toString().toIntOrNull()?.coerceIn(0, 1_000) ?: rule.priority
                4 -> rule.required = value as Boolean
                5 -> rule.maxDepth = value.toString().toIntOrNull()?.coerceIn(0, 10) ?: rule.maxDepth
                6 -> rule.maxFiles = value.toString().toIntOrNull()?.coerceIn(1, 500) ?: rule.maxFiles
                7 -> rule.bundleGroup = value.toString()
                8 -> rule.keepSeparate = value as Boolean
            }
            fireTableCellUpdated(rowIndex, columnIndex)
        }
    }

    private class TooltipCellRenderer : DefaultTableCellRenderer() {
        override fun setValue(value: Any?) {
            super.setValue(value)
            toolTipText = value?.toString()
        }
    }
}
