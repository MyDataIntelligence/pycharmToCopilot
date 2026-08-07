package nl.ferron.copilotcontextbridge.ui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import nl.ferron.copilotcontextbridge.settings.ProjectSettings
import javax.swing.Timer

class OpenOnStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val enabled = project.getService(ProjectSettings::class.java).state.openToolWindowOnStartup
        LOG.info("Open-on-startup activity for ${project.name}: enabled=$enabled")
        if (!enabled) return
        val manager = ToolWindowManager.getInstance(project)
        manager.invokeLater {
            var attempts = 0
            val timer = Timer(500, null)
            timer.addActionListener {
                attempts++
                val toolWindow = manager.getToolWindow("Copilot Context Bridge")
                when {
                    project.isDisposed -> timer.stop()
                    toolWindow != null -> {
                        timer.stop()
                        toolWindow.show()
                    }
                    attempts >= 20 -> {
                        timer.stop()
                        LOG.warn("Copilot Context Bridge tool window was not registered after project startup")
                    }
                }
            }
            timer.initialDelay = 0
            timer.start()
        }
    }

    companion object {
        private val LOG = Logger.getInstance(OpenOnStartupActivity::class.java)
    }
}
