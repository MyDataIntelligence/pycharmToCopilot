package nl.ferron.copilotcontextbridge.ui

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
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

/** Editable policy for the currently selected prompt library entry. */
class ContextPolicyDialog(
    private val promptName: String,
    private val policy: ContextPolicyState,
) : DialogWrapper(true) {
    private val model = RuleTableModel(policy.rules)
    private val table = JTable(model)
    private val target = JComboBox(CopilotTarget.entries.map { it.name }.toTypedArray())
    private val returnMode = JComboBox(CopilotReturnMode.entries.map { it.name }.toTypedArray())
    private val previous = JComboBox(PreviousBatchMode.entries.map { it.name }.toTypedArray())
    private val repositoryFiles = JSpinner(SpinnerNumberModel(policy.maxRepositoryFiles.coerceIn(1, 500), 1, 500, 1))
    private val attachmentLimit = JSpinner(SpinnerNumberModel(policy.maxAttachments.coerceIn(2, 20), 2, 20, 1))
    private val bundle = JBCheckBox("Bundle automatic context", policy.bundleAutomaticContext)

    init {
        title = "Context Policy · $promptName"
        target.selectedItem = policy.target
        returnMode.selectedItem = policy.returnMode
        previous.selectedItem = policy.previousBatchMode
        table.autoCreateRowSorter = true
        table.fillsViewportHeight = true
        init()
    }

    override fun createCenterPanel(): JComponent =
        JPanel(BorderLayout(8, 8)).apply {
            preferredSize = java.awt.Dimension(950, 560)
            add(
                JPanel(java.awt.GridLayout(2, 4, 8, 6)).apply {
                    add(JLabel("Target"))
                    add(target)
                    add(JLabel("Return mode"))
                    add(returnMode)
                    add(JLabel("Previous batches"))
                    add(previous)
                    add(JLabel("Max repository files"))
                    add(repositoryFiles)
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
                            add(JButton("Reset").apply { addActionListener { resetPolicy() } })
                        },
                        BorderLayout.EAST,
                    )
                },
                BorderLayout.SOUTH,
            )
        }

    override fun doOKAction() {
        policy.target = target.selectedItem as String
        policy.returnMode = returnMode.selectedItem as String
        policy.previousBatchMode = previous.selectedItem as String
        policy.maxRepositoryFiles = repositoryFiles.value as Int
        policy.maxAttachments = attachmentLimit.value as Int
        policy.bundleAutomaticContext = bundle.isSelected
        policy.rules = model.rules.map(ContextRuleState::copyOf).toMutableList()
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
        val copy = model.rules[table.convertRowIndexToModel(row)].copyOf()
        copy.id += "-copy"
        model.rules += copy
        model.fireTableDataChanged()
    }

    private fun resetPolicy() {
        val defaults = ContextPolicyState.defaultFor(policy.id)
        policy.rules = defaults.rules
        policy.target = defaults.target
        policy.returnMode = defaults.returnMode
        policy.previousBatchMode = defaults.previousBatchMode
        policy.maxRepositoryFiles = defaults.maxRepositoryFiles
        policy.maxAttachments = defaults.maxAttachments
        policy.bundleAutomaticContext = defaults.bundleAutomaticContext
        target.selectedItem = policy.target
        returnMode.selectedItem = policy.returnMode
        previous.selectedItem = policy.previousBatchMode
        repositoryFiles.value = policy.maxRepositoryFiles
        attachmentLimit.value = policy.maxAttachments
        bundle.isSelected = policy.bundleAutomaticContext
        model.rules = policy.rules.map(ContextRuleState::copyOf).toMutableList()
        model.fireTableDataChanged()
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
            if (columnIndex in
                setOf(0, 4, 8)
            ) {
                Boolean::class.java
            } else {
                String::class.java
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
                3 -> rule.priority.toString()
                4 -> rule.required
                5 -> rule.maxDepth.toString()
                6 -> rule.maxFiles.toString()
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
}
