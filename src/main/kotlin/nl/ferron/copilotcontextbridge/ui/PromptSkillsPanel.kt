package nl.ferron.copilotcontextbridge.ui

import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import nl.ferron.copilotcontextbridge.settings.AppSettings
import nl.ferron.copilotcontextbridge.settings.PromptSkillLibraryCodec
import nl.ferron.copilotcontextbridge.settings.PromptSkillLibraryEditor
import java.awt.BorderLayout
import java.awt.GridLayout
import java.nio.file.Files
import java.util.UUID
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.ListSelectionModel

class PromptSkillsPanel(
    private val onLibraryChanged: () -> Unit = {},
) : JPanel(BorderLayout(8, 8)) {
    private val model = DefaultListModel<String>()
    private val list = JBList(model)
    private val name = JBTextField()
    private val category = JBTextField()
    private val description = JBTextField()
    private val prompt =
        JBTextArea(8, 60).apply {
            lineWrap = true
            wrapStyleWord = true
        }
    private val guidelines =
        JBTextArea(10, 60).apply {
            lineWrap = true
            wrapStyleWord = true
        }

    init {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.addListSelectionListener { if (!it.valueIsAdjusting) loadSelected() }
        val editor =
            JPanel(BorderLayout(6, 6)).apply {
                add(
                    JPanel(GridLayout(3, 2, 6, 6)).apply {
                        add(JLabel("Name"))
                        add(this@PromptSkillsPanel.name)
                        add(JLabel("Category"))
                        add(category)
                        add(JLabel("Description"))
                        add(description)
                    },
                    BorderLayout.NORTH,
                )
                add(
                    JSplitPane(
                        JSplitPane.VERTICAL_SPLIT,
                        labeled("Prompt", JBScrollPane(prompt)),
                        labeled("Skill guidelines", JBScrollPane(guidelines)),
                    ).apply {
                        resizeWeight = 0.5
                        isOneTouchExpandable = true
                        isContinuousLayout = true
                    },
                    BorderLayout.CENTER,
                )
                add(
                    JPanel(GridLayout(2, 4, 6, 4)).apply {
                        add(JButton("Save skill").apply { addActionListener { saveSelected() } })
                        add(JButton("Context Policy").apply { addActionListener { editPolicy() } })
                        add(JButton("Add").apply { addActionListener { addSkill() } })
                        add(
                            JButton("Duplicate").apply {
                                toolTipText = "Create an independent copy of this prompt skill and its Context Policy"
                                addActionListener { duplicateSkill() }
                            },
                        )
                        add(JButton("Delete").apply { addActionListener { deleteSkill() } })
                        add(JButton("Import").apply { addActionListener { importSkills() } })
                        add(JButton("Export").apply { addActionListener { exportSkills() } })
                    },
                    BorderLayout.SOUTH,
                )
            }
        add(JLabel("Prompt Skills — each skill carries its own prompt and guidelines"), BorderLayout.NORTH)
        add(
            JSplitPane(JSplitPane.VERTICAL_SPLIT, JBScrollPane(list), editor).apply {
                resizeWeight = 0.22
                dividerLocation = 190
                isOneTouchExpandable = true
                isContinuousLayout = true
            },
            BorderLayout.CENTER,
        )
        refresh()
    }

    fun refresh(preferredIndex: Int = list.selectedIndex) {
        model.clear()
        AppSettings
            .getInstance()
            .state.promptSkills
            .forEach { model.addElement("${it.category.ifBlank { "Custom" }}  ·  ${it.name}") }
        list.selectedIndex = PromptSkillLibraryEditor.selectionAfterRefresh(preferredIndex, model.size)
    }

    private fun loadSelected() {
        val skill =
            AppSettings
                .getInstance()
                .state.promptSkills
                .getOrNull(list.selectedIndex) ?: return
        name.text = skill.name
        category.text = skill.category
        description.text = skill.description
        prompt.text = skill.prompt
        guidelines.text = skill.guidelines
    }

    private fun saveSelected() {
        val selectedIndex = list.selectedIndex
        val skill =
            AppSettings
                .getInstance()
                .state.promptSkills
                .getOrNull(list.selectedIndex) ?: return
        if (name.text.isBlank() || prompt.text.isBlank()) {
            showError("Prompt skill cannot be saved", "Name and prompt are required. Guidelines may be empty.")
            return
        }
        skill.name = name.text.trim()
        skill.category = category.text.trim().ifBlank { "Custom" }
        skill.description = description.text.trim()
        skill.prompt = prompt.text.trim()
        skill.guidelines = guidelines.text.trim()
        refresh(selectedIndex)
        onLibraryChanged()
    }

    private fun addSkill() {
        val id = "custom-${UUID.randomUUID().toString().take(8)}"
        PromptSkillLibraryEditor.add(AppSettings.getInstance().state.promptSkills, id)
        refresh(model.size)
        list.selectedIndex = model.size - 1
        onLibraryChanged()
    }

    private fun duplicateSkill() {
        val source =
            AppSettings
                .getInstance()
                .state.promptSkills
                .getOrNull(list.selectedIndex) ?: return
        PromptSkillLibraryEditor.duplicate(
            AppSettings.getInstance().state.promptSkills,
            list.selectedIndex,
            "custom-${UUID.randomUUID().toString().take(8)}",
        )
        refresh()
        list.selectedIndex = model.size - 1
        onLibraryChanged()
    }

    private fun deleteSkill() {
        val skills = AppSettings.getInstance().state.promptSkills
        if (skills.size <= 1 || list.selectedIndex !in skills.indices) return
        if (PromptSkillLibraryEditor.isBuiltIn(skills[list.selectedIndex])) {
            com.intellij.openapi.ui.Messages.showInfoMessage(
                this,
                "Built-in prompt skills can be edited or duplicated, but only custom prompt skills can be deleted.",
                "Built-in Prompt Skill",
            )
            return
        }
        if (
            com.intellij.openapi.ui.Messages.showYesNoDialog(
                this,
                "Delete prompt skill '${skills[list.selectedIndex].name}'?",
                "Delete Prompt Skill",
                null,
            ) != com.intellij.openapi.ui.Messages.YES
        ) {
            return
        }
        val removedIndex = list.selectedIndex
        PromptSkillLibraryEditor.remove(skills, removedIndex)
        refresh(PromptSkillLibraryEditor.selectionAfterRemoval(removedIndex, skills.size))
        onLibraryChanged()
    }

    private fun editPolicy() {
        val skill =
            AppSettings
                .getInstance()
                .state.promptSkills
                .getOrNull(list.selectedIndex) ?: return
        val selectedIndex = list.selectedIndex
        if (
            ContextPolicyDialog(skill.id, skill.name, skill.contextPolicy) { duplicatedPolicy ->
                duplicateSkillWithPolicy(selectedIndex, duplicatedPolicy)
            }.showAndGet()
        ) {
            refresh(selectedIndex)
            onLibraryChanged()
        }
    }

    private fun duplicateSkillWithPolicy(
        sourceIndex: Int,
        policy: nl.ferron.copilotcontextbridge.settings.ContextPolicyState,
    ) {
        val skills = AppSettings.getInstance().state.promptSkills
        if (sourceIndex !in skills.indices) return
        val duplicate =
            PromptSkillLibraryEditor.duplicate(
                skills,
                sourceIndex,
                "custom-${UUID.randomUUID().toString().take(8)}",
            )
        duplicate.contextPolicy = policy.copyOf()
        refresh(skills.indexOf(duplicate))
        onLibraryChanged()
    }

    private fun exportSkills() {
        val chooser = JFileChooser().apply { selectedFile = java.io.File("copilot-prompt-skills.json") }
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            runCatching {
                Files.writeString(
                    chooser.selectedFile.toPath(),
                    PromptSkillLibraryCodec.encode(AppSettings.getInstance().state.promptSkills),
                )
            }.onFailure { showError("Skill export failed", it.message ?: "Unknown error") }
        }
    }

    private fun importSkills() {
        val chooser = JFileChooser()
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            runCatching {
                val imported = PromptSkillLibraryCodec.decode(Files.readString(chooser.selectedFile.toPath()))
                AppSettings.getInstance().state.promptSkills = PromptSkillLibraryEditor.mergeImported(imported)
                refresh(0)
                onLibraryChanged()
            }.onFailure { showError("Skill import rejected", it.message ?: "Invalid skill library") }
        }
    }

    private fun showError(
        title: String,
        message: String,
    ) = JOptionPane.showMessageDialog(this, message, title, JOptionPane.ERROR_MESSAGE)

    private fun labeled(
        title: String,
        component: java.awt.Component,
    ) = JPanel(BorderLayout()).apply {
        add(JLabel(title), BorderLayout.NORTH)
        add(component, BorderLayout.CENTER)
    }
}
