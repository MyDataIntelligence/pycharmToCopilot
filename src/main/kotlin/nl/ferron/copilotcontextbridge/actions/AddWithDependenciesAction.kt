package nl.ferron.copilotcontextbridge.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import nl.ferron.copilotcontextbridge.settings.ProjectSettings

class AddWithDependenciesAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        project.getService(ProjectSettings::class.java).state.automaticallyFillDependencies = true
        ActionSupport.selection(event)?.addSelection(ActionSupport.files(event))
        ActionSupport.open(event)
    }

    override fun update(event: AnActionEvent) = ActionSupport.update(event)
}
