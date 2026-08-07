package nl.ferron.copilotcontextbridge.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import nl.ferron.copilotcontextbridge.settings.AppSettings
import nl.ferron.copilotcontextbridge.settings.CopilotReturnMode
import nl.ferron.copilotcontextbridge.settings.ProjectSettings
import nl.ferron.copilotcontextbridge.settings.ReturnInstructionDefaults
import nl.ferron.copilotcontextbridge.settings.ReturnInstructions
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.SwingConstants

/** Editor for global -> project -> prompt-specific return-instruction inheritance. */
class ReturnInstructionsPanel(
    private val project: Project,
    private val onChanged: () -> Unit = {},
) : JPanel(BorderLayout(8, 8)) {
    private val app get() = AppSettings.getInstance().state
    private val projectState get() = project.getService(ProjectSettings::class.java).state

    private val prompt = JComboBox<String>()
    private val mode = JComboBox(CopilotReturnMode.entries.toTypedArray())
    private val useProjectOverride = JBCheckBox("Use project override instead of the global default")
    private val global = editor()
    private val projectOverride = editor()
    private val promptAddition = editor()
    private val effective = editor().apply { isEditable = false }
    private val validation = JLabel()
    private var loading = false

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        add(
            JPanel(GridLayout(2, 2, 8, 5)).apply {
                add(JLabel("Prompt Library entry"))
                add(prompt)
                add(JLabel("Return mode"))
                add(mode)
            },
            BorderLayout.NORTH,
        )
        val inheritedEditors =
            JSplitPane(
                JSplitPane.VERTICAL_SPLIT,
                labelled("1. Global default", global),
                labelled("2. Project override", projectOverride, useProjectOverride),
            ).apply {
                resizeWeight = 0.5
                isContinuousLayout = true
            }
        val promptAndEffective =
            JSplitPane(
                JSplitPane.VERTICAL_SPLIT,
                labelled("3. Prompt-specific additions", promptAddition),
                labelled("Effective instructions (read-only)", effective),
            ).apply {
                resizeWeight = 0.35
                isContinuousLayout = true
            }
        add(
            JSplitPane(JSplitPane.HORIZONTAL_SPLIT, inheritedEditors, promptAndEffective).apply {
                resizeWeight = 0.5
                isContinuousLayout = true
            },
            BorderLayout.CENTER,
        )
        add(
            JPanel(BorderLayout(6, 4)).apply {
                add(validation, BorderLayout.NORTH)
                add(
                    JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
                        add(JButton("Save").apply { addActionListener { save() } })
                        add(JButton("Copy effective").apply { addActionListener { UiSupport.copyText(effective.text) } })
                        add(JButton("Reset global").apply { addActionListener { resetGlobal() } })
                        add(JButton("Clear project override").apply { addActionListener { clearProjectOverride() } })
                        add(JButton("Clear prompt additions").apply { addActionListener { clearPromptAddition() } })
                        add(JButton("Restore safe defaults").apply { addActionListener { restoreSafeDefaults() } })
                    },
                    BorderLayout.CENTER,
                )
            },
            BorderLayout.SOUTH,
        )
        prompt.addActionListener { if (!loading) loadSelected() }
        mode.addActionListener { if (!loading) loadMode() }
        useProjectOverride.addActionListener {
            if (!loading) {
                projectOverride.isEnabled = useProjectOverride.isSelected
                refreshEffective()
            }
        }
        listOf(global, projectOverride, promptAddition).forEach { area ->
            area.document.addDocumentListener(SimpleDocumentListener { if (!loading) refreshEffective() })
        }
        refresh()
    }

    fun refresh() {
        loading = true
        val selectedId = selectedSkill()?.id ?: projectState.selectedPromptSkillId
        prompt.removeAllItems()
        app.promptSkills.forEach { prompt.addItem(it.id) }
        prompt.selectedItem = selectedId.takeIf { id -> app.promptSkills.any { it.id == id } } ?: app.promptSkills.firstOrNull()?.id
        loading = false
        loadSelected()
    }

    private fun loadSelected() {
        val skill = selectedSkill() ?: return
        loading = true
        mode.selectedItem = ReturnInstructions.mode(skill.contextPolicy)
        promptAddition.text = skill.returnInstructionsAddition
        loading = false
        loadMode()
    }

    private fun loadMode() {
        val selectedMode = selectedMode()
        loading = true
        global.text = app.returnInstructionsByMode[selectedMode.name] ?: ReturnInstructionDefaults.forMode(selectedMode)
        val override = projectState.returnInstructionOverrides[selectedMode.name].orEmpty()
        useProjectOverride.isSelected = override.isNotBlank()
        projectOverride.text = override
        projectOverride.isEnabled = useProjectOverride.isSelected
        promptAddition.text = selectedSkill()?.returnInstructionsAddition.orEmpty()
        loading = false
        refreshEffective()
    }

    private fun refreshEffective() {
        val base = if (useProjectOverride.isSelected && projectOverride.text.isNotBlank()) projectOverride.text else global.text
        effective.text = listOf(base.trim(), promptAddition.text.trim()).filter(String::isNotBlank).joinToString("\n\n")
        effective.caretPosition = 0
        val issues = ReturnInstructions.validate(selectedMode(), effective.text)
        validation.text =
            if (issues.isEmpty()) {
                "All required safeguards for ${selectedMode().name} are present."
            } else {
                "<html><b>Unsafe/incomplete instructions:</b> ${issues.joinToString(" &nbsp;|&nbsp; ") { it.message }}</html>"
            }
        validation.foreground = if (issues.isEmpty()) JBColor(0x237A3B, 0x64C77B) else JBColor(0xB42318, 0xFF7B72)
        validation.horizontalAlignment = SwingConstants.LEFT
    }

    private fun save() {
        val skill = selectedSkill() ?: return
        val selectedMode = selectedMode()
        app.returnInstructionsByMode[selectedMode.name] = global.text.trim().ifBlank { ReturnInstructionDefaults.forMode(selectedMode) }
        if (useProjectOverride.isSelected && projectOverride.text.isNotBlank()) {
            projectState.returnInstructionOverrides[selectedMode.name] = projectOverride.text.trim()
        } else {
            projectState.returnInstructionOverrides.remove(selectedMode.name)
        }
        skill.returnInstructionsAddition = promptAddition.text.trim()
        skill.contextPolicy.returnMode = selectedMode.name
        if (selectedMode ==
            CopilotReturnMode.COPILOT_PATCH_FILE
        ) {
            app.returnFileInstruction = app.returnInstructionsByMode.getValue(selectedMode.name)
        }
        onChanged()
        loadMode()
    }

    private fun resetGlobal() {
        global.text = ReturnInstructionDefaults.forMode(selectedMode())
        refreshEffective()
    }

    private fun clearProjectOverride() {
        useProjectOverride.isSelected = false
        projectOverride.text = ""
        projectOverride.isEnabled = false
        refreshEffective()
    }

    private fun clearPromptAddition() {
        promptAddition.text = ""
        refreshEffective()
    }

    private fun restoreSafeDefaults() {
        resetGlobal()
        clearProjectOverride()
        clearPromptAddition()
        save()
    }

    private fun selectedSkill(): AppSettings.PromptSkillState? = app.promptSkills.firstOrNull { it.id == prompt.selectedItem as? String }

    private fun selectedMode(): CopilotReturnMode = mode.selectedItem as? CopilotReturnMode ?: CopilotReturnMode.COPILOT_PATCH_FILE

    private fun editor() =
        JBTextArea(8, 42).apply {
            lineWrap = true
            wrapStyleWord = true
        }

    private fun labelled(
        title: String,
        area: JBTextArea,
        option: JBCheckBox? = null,
    ) = JPanel(BorderLayout(4, 4)).apply {
        add(
            JPanel(BorderLayout()).apply {
                add(JLabel(title), BorderLayout.WEST)
                option?.let { add(it, BorderLayout.EAST) }
            },
            BorderLayout.NORTH,
        )
        add(JBScrollPane(area), BorderLayout.CENTER)
    }
}
