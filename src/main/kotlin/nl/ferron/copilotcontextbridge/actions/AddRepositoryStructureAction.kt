package nl.ferron.copilotcontextbridge.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import nl.ferron.copilotcontextbridge.settings.ProjectSettings
import nl.ferron.copilotcontextbridge.state.ContextSelectionService

class AddRepositoryStructureAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        project.getService(ProjectSettings::class.java).state.includeRepositoryTree = true
        project.getService(ContextSelectionService::class.java).requestRecalculation()
        ActionSupport.open(event)
    }

    override fun update(event: AnActionEvent) = ActionSupport.update(event, requireSelection = false)
}
