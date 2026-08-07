package nl.ferron.copilotcontextbridge.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class OpenToolWindowAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) = ActionSupport.open(event)

    override fun update(event: AnActionEvent) = ActionSupport.update(event, requireSelection = false)
}
