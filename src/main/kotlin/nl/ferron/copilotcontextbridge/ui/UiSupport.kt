package nl.ferron.copilotcontextbridge.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

object UiSupport {
    fun notify(
        project: Project,
        title: String,
        content: String,
        type: NotificationType = NotificationType.INFORMATION,
    ) {
        NotificationGroupManager
            .getInstance()
            .getNotificationGroup("Copilot Context Bridge")
            .createNotification(title, content, type)
            .notify(project)
    }

    fun copyText(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}
