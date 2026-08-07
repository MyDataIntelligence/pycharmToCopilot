package nl.ferron.copilotcontextbridge.ui

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import nl.ferron.copilotcontextbridge.ProjectRoot
import nl.ferron.copilotcontextbridge.guidelines.GuidelineService
import nl.ferron.copilotcontextbridge.settings.AppSettings
import nl.ferron.copilotcontextbridge.settings.ProjectSettings
import java.awt.BorderLayout
import java.nio.file.Files
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSplitPane

class GuidelinesPanel(
    private val project: Project,
) : JPanel(BorderLayout(6, 6)) {
    private val global = JBTextArea(18, 60)
    private val sourcesModel = DefaultListModel<String>()
    private val sources = JBList(sourcesModel)
    private var detected = emptyList<GuidelineService.Source>()

    init {
        val left =
            JPanel(BorderLayout()).apply {
                add(JLabel("Detected repository guideline sources"), BorderLayout.NORTH)
                add(JBScrollPane(sources), BorderLayout.CENTER)
                add(
                    JPanel().apply {
                        add(JButton("Toggle source").apply { addActionListener { toggleSource() } })
                        add(JButton("Open source").apply { addActionListener { openSource() } })
                        add(JButton("Reload").apply { addActionListener { reload() } })
                        add(JButton("Create structure").apply { addActionListener { createStructure() } })
                    },
                    BorderLayout.SOUTH,
                )
            }
        val right =
            JPanel(BorderLayout()).apply {
                add(JLabel("Global personal guidelines (Markdown)"), BorderLayout.NORTH)
                add(JBScrollPane(global), BorderLayout.CENTER)
                add(
                    JPanel().apply {
                        add(
                            JButton("Save global").apply {
                                addActionListener {
                                    AppSettings.getInstance().state.globalGuidelines =
                                        global.text
                                    UiSupport.notify(project, "Guidelines saved", "Global personal guidelines were saved.")
                                }
                            },
                        )
                        add(
                            JButton("Reset defaults").apply {
                                addActionListener {
                                    AppSettings.getInstance().resetGuidelines()
                                    global.text =
                                        AppSettings.getInstance().state.globalGuidelines
                                }
                            },
                        )
                        add(JButton("Export global").apply { addActionListener { exportGlobal() } })
                        add(JButton("Import global").apply { addActionListener { importGlobal() } })
                    },
                    BorderLayout.SOUTH,
                )
            }
        add(
            JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right).apply { dividerLocation = 300 },
            BorderLayout.CENTER,
        )
        reload()
    }

    private fun reload() {
        global.text = AppSettings.getInstance().state.globalGuidelines
        detected = GuidelineService(project).detect()
        sourcesModel.clear()
        detected.forEach {
            sourcesModel.addElement("${if (it.enabled) "✓" else "○"} ${it.relativePath}")
        }
    }

    private fun toggleSource() {
        val source = detected.getOrNull(sources.selectedIndex) ?: return
        val state = project.getService(ProjectSettings::class.java).state
        val enabled = detected.filter { it.enabled }.mapTo(linkedSetOf()) { it.relativePath }
        if (source.relativePath in enabled) enabled.remove(source.relativePath) else enabled.add(source.relativePath)
        state.enabledGuidelineSources = enabled.toMutableList()
        state.guidelineSelectionConfigured = true
        reload()
    }

    private fun openSource() {
        val source = detected.getOrNull(sources.selectedIndex) ?: return
        val root = ProjectRoot.virtualFile(project)
        root.findFileByRelativePath(source.relativePath)?.let { OpenFileDescriptor(project, it).navigate(true) }
    }

    private fun createStructure() {
        val root = ProjectRoot.path(project)
        val target = root.resolve(".github/skills/code-guidelines/SKILL.md")
        if (Files.exists(target)) {
            Messages.showInfoMessage(project, "The guideline structure already exists.", "Copilot Context Bridge")
            return
        }
        if (Messages.showYesNoDialog(project, "Create .github/skills/code-guidelines/SKILL.md?", "Create Guideline Structure", null) ==
            Messages.YES
        ) {
            Files.createDirectories(target.parent.resolve("references"))
            Files.writeString(target, "# Repository code guidelines\n\nDescribe repository-specific conventions here.\n")
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)
            reload()
        }
    }

    private fun exportGlobal() {
        val chooser = JFileChooser().apply { selectedFile = java.io.File("copilot-global-guidelines.md") }
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            runCatching { Files.writeString(chooser.selectedFile.toPath(), global.text) }
                .onSuccess { UiSupport.notify(project, "Guidelines exported", chooser.selectedFile.name) }
                .onFailure {
                    UiSupport.notify(
                        project,
                        "Guideline export failed",
                        it.message ?: "Unknown error",
                        com.intellij.notification.NotificationType.ERROR,
                    )
                }
        }
    }

    private fun importGlobal() {
        val chooser = JFileChooser()
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            runCatching { Files.readString(chooser.selectedFile.toPath()) }
                .onSuccess {
                    global.text = it
                    AppSettings.getInstance().state.globalGuidelines = it
                    UiSupport.notify(project, "Guidelines imported", chooser.selectedFile.name)
                }.onFailure {
                    UiSupport.notify(
                        project,
                        "Guideline import failed",
                        it.message ?: "Unknown error",
                        com.intellij.notification.NotificationType.ERROR,
                    )
                }
        }
    }
}
