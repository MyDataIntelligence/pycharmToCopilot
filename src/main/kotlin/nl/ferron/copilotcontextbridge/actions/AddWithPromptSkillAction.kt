package nl.ferron.copilotcontextbridge.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import nl.ferron.copilotcontextbridge.settings.AppSettings
import nl.ferron.copilotcontextbridge.settings.ProjectSettings
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class AddWithPromptSkillAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val skills = AppSettings.getInstance().state.promptSkills
        // The built-in library normally guarantees at least one entry, but an imported or
        // externally edited application state can still be empty.  Never let a context-menu
        // action crash with an IndexOutOfBoundsException in that state.
        if (skills.isEmpty()) {
            ActionSupport.open(event)
            return
        }
        val dialog = PromptSkillDialog(project, skills.map { it.name })
        if (!dialog.showAndGet()) return
        val selectedSkill = skills.getOrNull(dialog.selectedIndex) ?: return
        project.getService(ProjectSettings::class.java).state.selectedPromptSkillId = selectedSkill.id
        ActionSupport.selection(event)?.addSelection(ActionSupport.files(event))
        ActionSupport.open(event)
    }

    override fun update(event: AnActionEvent) = ActionSupport.update(event)

    private class PromptSkillDialog(
        project: Project,
        names: List<String>,
    ) : DialogWrapper(project) {
        private val combo = JComboBox(names.toTypedArray())
        val selectedIndex: Int get() = combo.selectedIndex

        init {
            title = "Add with Prompt Skill"
            init()
        }

        override fun createCenterPanel(): JComponent =
            JPanel().apply {
                add(JLabel("Prompt and skill guidelines:"))
                add(combo)
            }
    }
}
