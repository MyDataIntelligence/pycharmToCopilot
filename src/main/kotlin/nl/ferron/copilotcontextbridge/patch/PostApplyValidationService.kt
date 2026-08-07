package nl.ferron.copilotcontextbridge.patch

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import nl.ferron.copilotcontextbridge.ProjectRoot
import nl.ferron.copilotcontextbridge.ui.UiSupport

/** Runs an explicitly configured validation command without exposing its output in normal notifications. */
class PostApplyValidationService(
    private val project: Project,
) {
    fun run(command: String) {
        if (command.isBlank()) return
        object : Task.Backgroundable(project, "Validating Copilot changes", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Running configured post-apply validation"
                val result =
                    runCatching {
                        val commandLine =
                            if (SystemInfo.isWindows) {
                                GeneralCommandLine("cmd.exe", "/d", "/s", "/c", command)
                            } else {
                                GeneralCommandLine("/bin/sh", "-lc", command)
                            }
                        commandLine.withWorkDirectory(ProjectRoot.path(project).toFile())
                        CapturingProcessHandler(commandLine).runProcess(120_000)
                    }
                ApplicationManager.getApplication().invokeLater {
                    result
                        .onSuccess { output ->
                            when {
                                output.isTimeout ->
                                    UiSupport.notify(
                                        project,
                                        "Post-apply validation timed out",
                                        "The configured command exceeded 120 seconds. No success is claimed.",
                                        NotificationType.WARNING,
                                    )
                                output.exitCode == 0 ->
                                    UiSupport.notify(
                                        project,
                                        "Post-apply validation passed",
                                        "The configured command completed successfully (exit code 0).",
                                    )
                                else ->
                                    UiSupport.notify(
                                        project,
                                        "Post-apply validation failed",
                                        "The configured command returned exit code ${output.exitCode}. Open the Run tool window for diagnostics.",
                                        NotificationType.WARNING,
                                    )
                            }
                        }.onFailure { error ->
                            UiSupport.notify(
                                project,
                                "Post-apply validation could not start",
                                error.message ?: "Unknown process error",
                                NotificationType.ERROR,
                            )
                        }
                }
            }
        }.queue()
    }
}
