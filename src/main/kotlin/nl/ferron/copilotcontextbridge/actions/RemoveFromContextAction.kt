package nl.ferron.copilotcontextbridge.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class RemoveFromContextAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        ActionSupport.selection(event)?.removeFiles(ActionSupport.files(event))
        ActionSupport.open(event)
    }

    override fun update(event: AnActionEvent) = ActionSupport.update(event)
}
