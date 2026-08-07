package nl.ferron.copilotcontextbridge.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class AddToContextAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        ActionSupport.selection(event)?.addSelection(ActionSupport.files(event))
        ActionSupport.open(event)
    }

    override fun update(event: AnActionEvent) = ActionSupport.update(event)
}
