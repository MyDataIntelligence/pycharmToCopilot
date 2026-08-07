package nl.ferron.copilotcontextbridge.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import nl.ferron.copilotcontextbridge.settings.ProjectSettings

class AddRepositoryStructureAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        event.project
            ?.getService(ProjectSettings::class.java)
            ?.state
            ?.includeRepositoryTree = true
        ActionSupport.open(event)
    }

    override fun update(event: AnActionEvent) = ActionSupport.update(event, requireSelection = false)
}
