package nl.ferron.copilotcontextbridge.ui

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import nl.ferron.copilotcontextbridge.ProjectRoot
import nl.ferron.copilotcontextbridge.context.ContextPackService
import nl.ferron.copilotcontextbridge.model.ContextCandidate
import nl.ferron.copilotcontextbridge.model.ContextPack
import nl.ferron.copilotcontextbridge.settings.AppSettings
import nl.ferron.copilotcontextbridge.settings.ProjectSettings
import nl.ferron.copilotcontextbridge.staging.CombinedContextTextBuilder
import nl.ferron.copilotcontextbridge.staging.FileListTransferable
import nl.ferron.copilotcontextbridge.staging.StagingService
import nl.ferron.copilotcontextbridge.state.ContextSelectionService
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.awt.datatransfer.Transferable
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JSplitPane
import javax.swing.SwingConstants
import javax.swing.TransferHandler

/** The primary three-step workflow and its supporting detail workspace. */
class CopilotContextPanel(
    private val project: Project,
) : JPanel(BorderLayout()) {
    private val selectionService = project.getService(ContextSelectionService::class.java)
    private val projectSettings = project.getService(ProjectSettings::class.java)

    private val count = JLabel("0 / 20")
    private val status = JLabel("Select files to begin")
    private val capacity =
        JProgressBar().apply {
            preferredSize = Dimension(JBUI.scale(160), JBUI.scale(10))
        }
    private val pinnedFiles = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val automaticFiles = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val preview = JBTextArea().apply { isEditable = false }
    private val historyText = JBTextArea().apply { isEditable = false }
    private val skillCombo = JComboBox<String>()
    private val batchCombo = JComboBox<String>()
    private val dragLabel = JLabel("Drag becomes available after preparation", AllIcons.Actions.Upload, SwingConstants.CENTER)
    private val prepareButton = greenActionButton("Prepare for Copilot") { prepareBatch() }
    private val copyButton = JButton("Copy files", AllIcons.Actions.Copy).apply { addActionListener { copyFiles() } }
    private val copyAllTextButton = JButton("Copy text", AllIcons.Actions.Copy).apply { addActionListener { copyCompletePackAsText() } }
    private val openButton =
        JButton("Open folder", AllIcons.Nodes.Folder).apply {
            toolTipText = "Open the temporary folder containing the safe copied batch"
            addActionListener { openStaging() }
        }
    private val nextButton =
        JButton("Next batch").apply {
            toolTipText = "Keep this batch in history and start a clean selection"
            addActionListener { startNextBatch() }
        }
    private val detailTabs = JBTabbedPane()
    private val detailsLayout = CardLayout()
    private val detailsHost = JPanel(detailsLayout)
    private val workspace = JPanel(BorderLayout())
    private lateinit var primaryPanel: JPanel
    private lateinit var wideSplit: JSplitPane
    private var detailRequested = false
    private var detailMode = DetailMode.MORE
    private var pack: ContextPack? = null
    private var staged: StagingService.StagingResult? = null
    private var calculating = false
    private var preparing = false
    private var refreshingSkills = false

    init {
        border = JBUI.Borders.empty(6)
        detailTabs.addTab("More Copilot actions", createMoreActionsPanel())
        detailTabs.addTab("Context preview", createPreviewPanel())
        detailTabs.addTab("Guidelines", GuidelinesPanel(project))
        detailTabs.addTab("Prompt skills", PromptSkillsPanel())
        detailsHost.add(detailTabs, DetailMode.MORE.name)
        detailsHost.add(PatchImportPanel(project), DetailMode.IMPORT.name)

        primaryPanel = createPrimaryPanel()
        wideSplit =
            JSplitPane(JSplitPane.HORIZONTAL_SPLIT, primaryPanel, detailsHost).apply {
                resizeWeight = 0.43
                dividerLocation = 500
                dividerSize = JBUI.scale(3)
                border = null
            }
        add(createPrimaryToolbar(), BorderLayout.NORTH)
        add(workspace, BorderLayout.CENTER)
        addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(event: ComponentEvent) = updateResponsiveWorkspace()
            },
        )
        updateResponsiveWorkspace()

        skillCombo.addActionListener {
            if (refreshingSkills) return@addActionListener
            val selected = skillCombo.selectedItem as? String ?: return@addActionListener
            AppSettings.getInstance().state.promptSkills.firstOrNull { it.name == selected }?.let {
                if (projectSettings.state.selectedPromptSkillId != it.id) {
                    projectSettings.state.selectedPromptSkillId = it.id
                    invalidatePreparedBatch()
                    recalculate()
                }
            }
        }
        configureDragSource()
        selectionService.addListener {
            ApplicationManager.getApplication().invokeLater {
                refreshHistory()
                if (!calculating && !preparing && staged == null) recalculate()
            }
        }
        refreshSkills()
        refreshHistory()
        updateControls()
        recalculate()
    }

    private fun createPrimaryPanel(): JPanel =
        JPanel(BorderLayout(0, 7)).apply {
            minimumSize = Dimension(0, 0)
            preferredSize = Dimension(JBUI.scale(460), 0)
            add(
                JBScrollPane(createWorkflow()).apply {
                    border = null
                    horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                },
                BorderLayout.CENTER,
            )
            add(createFooter(), BorderLayout.SOUTH)
        }

    private fun createPrimaryToolbar() =
        JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2, 2, 7, 2)
            add(
                actionRow(
                    primaryButton("Batch") {
                        detailRequested = false
                        updateResponsiveWorkspace()
                    },
                    JButton("Import", AllIcons.ToolbarDecorator.Import).apply {
                        addActionListener {
                            showDetails(DetailMode.IMPORT)
                        }
                    },
                    JButton("More ▾").apply {
                        addActionListener {
                            detailTabs.selectedIndex = 0
                            showDetails(DetailMode.MORE)
                        }
                    },
                ),
                BorderLayout.WEST,
            )
            add(
                JButton(AllIcons.General.GearPlain).apply {
                    toolTipText = "Copilot Context Bridge settings"
                    addActionListener { openSettings() }
                },
                BorderLayout.EAST,
            )
        }

    private fun createWorkflow() =
        verticalPanel().apply {
            border = JBUI.Borders.empty(2, 4, 10, 7)
            add(JBLabel("Prepare code for Copilot").apply { font = font.deriveFont(Font.BOLD, font.size2D + 3f) })
            add(JBLabel("Build one safe batch, then copy or drag it.").apply { foreground = JBColor.GRAY })
            add(Box.createVerticalStrut(JBUI.scale(7)))
            add(
                JPanel(BorderLayout(8, 0)).apply {
                    add(capacity, BorderLayout.CENTER)
                    add(count, BorderLayout.EAST)
                    maximumSize = Dimension(Int.MAX_VALUE, maxOf(capacity.preferredSize.height, count.preferredSize.height))
                },
            )
            add(Box.createVerticalStrut(JBUI.scale(12)))
            add(stepTitle("1.  Files in this batch"))
            add(Box.createVerticalStrut(JBUI.scale(5)))
            add(
                JPanel(GridLayout(1, 2, 8, 0)).apply {
                    add(fileCard("PINNED", pinnedFiles))
                    add(fileCard("AUTOMATIC", automaticFiles))
                    maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(170))
                },
            )
            add(
                actionGrid(
                    JButton("＋ Add files").apply { addActionListener { addFiles() } },
                    JButton("Clear", AllIcons.Actions.GC).apply { addActionListener { selectionService.clear() } },
                ),
            )
            add(Box.createVerticalStrut(JBUI.scale(10)))
            add(stepTitle("2.  Task / Prompt skill"))
            add(
                JPanel(BorderLayout(7, 0)).apply {
                    border = JBUI.Borders.empty(5, 0)
                    maximumSize = Dimension(Int.MAX_VALUE, skillCombo.preferredSize.height + JBUI.scale(30))
                    add(JLabel("Prompt skill"), BorderLayout.NORTH)
                    add(skillCombo, BorderLayout.CENTER)
                    add(
                        JLabel(AllIcons.General.Information).apply {
                            toolTipText = "Prompt skills can include task instructions and guidelines"
                        },
                        BorderLayout.EAST,
                    )
                },
            )
            add(JLabel("●  Dependencies fill remaining places.").apply { foreground = JBColor(0x38A169, 0x58B87A) })
            add(Box.createVerticalStrut(JBUI.scale(11)))
            add(stepTitle("3.  Prepare for Copilot"))
            add(Box.createVerticalStrut(JBUI.scale(5)))
            add(
                prepareButton.apply {
                    toolTipText = "Create a safe temporary pack containing 00_REPO_CONTEXT.md and selected files"
                    maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                },
            )
            add(Box.createVerticalStrut(JBUI.scale(6)))
            add(dragLabel)
            add(actionGrid(copyButton, copyAllTextButton, openButton, nextButton))
            components.filterIsInstance<javax.swing.JComponent>().forEach { it.alignmentX = LEFT_ALIGNMENT }
        }

    private fun createFooter() =
        JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0), JBUI.Borders.empty(5, 5))
            add(status, BorderLayout.CENTER)
            add(JLabel("00_REPO_CONTEXT.md  ✓"), BorderLayout.EAST)
        }

    private fun fileCard(
        title: String,
        content: JPanel,
    ) = JPanel(BorderLayout()).apply {
        border =
            BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(7),
            )
        add(JLabel(title).apply { font = font.deriveFont(Font.BOLD) }, BorderLayout.NORTH)
        add(content, BorderLayout.CENTER)
    }

    private fun createMoreActionsPanel() =
        JPanel(BorderLayout(8, 8)).apply {
            border = JBUI.Borders.empty(10)
            val navigation =
                JPanel(GridLayout(2, 2, 8, 8)).apply {
                    border = BorderFactory.createTitledBorder("Manage context")
                    add(navigationButton("Context preview", "Inspect the complete outgoing pack", AllIcons.FileTypes.Text, 1))
                    add(navigationButton("Guidelines", "Repository and global instructions", AllIcons.General.InspectionsOK, 2))
                    add(navigationButton("Prompt skills", "Prompts with their own guidelines", AllIcons.General.User, 3))
                    add(
                        JButton(
                            "<html><b>Settings</b><br><font color='#888888'>Limits, exclusions and behaviour</font></html>",
                            AllIcons.General.GearPlain,
                        ).apply {
                            horizontalAlignment = SwingConstants.LEFT
                            addActionListener { openSettings() }
                        },
                    )
                }
            val quickCopy =
                JPanel(GridLayout(1, 2, 8, 0)).apply {
                    border = BorderFactory.createTitledBorder("Quick copy")
                    add(JButton("Copy context").apply { addActionListener { UiSupport.copyText(preview.text) } })
                    add(JButton("Copy return instructions").apply { addActionListener { copyReturnInstructions() } })
                }
            add(
                JPanel(BorderLayout(0, 8)).apply {
                    add(navigation, BorderLayout.CENTER)
                    add(quickCopy, BorderLayout.SOUTH)
                },
                BorderLayout.NORTH,
            )
            add(createHistoryPanel(), BorderLayout.CENTER)
        }

    private fun navigationButton(
        title: String,
        subtitle: String,
        icon: javax.swing.Icon,
        tab: Int,
    ) = JButton("<html><b>$title</b><br><font color='#888888'>$subtitle</font></html>", icon).apply {
        horizontalAlignment = SwingConstants.LEFT
        addActionListener { detailTabs.selectedIndex = tab }
    }

    private fun createHistoryPanel() =
        JPanel(BorderLayout(5, 5)).apply {
            border = BorderFactory.createTitledBorder("Batch history (recent)")
            add(JBScrollPane(historyText), BorderLayout.CENTER)
            add(
                actionRow(
                    batchCombo,
                    JButton("Restore").apply { addActionListener { selectedBatchId()?.let(selectionService::restoreBatch) } },
                    JButton("Keep staged files").apply { addActionListener { keepSelectedSession() } },
                    JButton("Delete staged files").apply { addActionListener { deleteSelectedSession() } },
                    JButton("Forget").apply { addActionListener { selectedBatchId()?.let(selectionService::deleteBatch) } },
                ),
                BorderLayout.SOUTH,
            )
        }

    private fun createPreviewPanel() =
        JPanel(BorderLayout(6, 6)).apply {
            border = JBUI.Borders.empty(8)
            add(
                actionRow(
                    JButton("Copy context only").apply { addActionListener { UiSupport.copyText(preview.text) } },
                    JButton("Copy return instructions").apply { addActionListener { copyReturnInstructions() } },
                ),
                BorderLayout.NORTH,
            )
            add(JBScrollPane(preview), BorderLayout.CENTER)
        }

    private fun stepTitle(text: String) = JLabel(text).apply { font = font.deriveFont(Font.BOLD, font.size2D + 1f) }

    private fun verticalPanel() = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

    private fun actionRow(vararg components: Component) =
        JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            components.forEach(::add)
            alignmentX = LEFT_ALIGNMENT
        }

    private fun actionGrid(vararg components: Component) =
        JPanel(GridLayout(0, 2, 6, 4)).apply {
            border = JBUI.Borders.empty(4, 0)
            components.forEach(::add)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            alignmentX = LEFT_ALIGNMENT
        }

    private fun updateResponsiveWorkspace() {
        if (!::primaryPanel.isInitialized || !::wideSplit.isInitialized) return
        workspace.removeAll()
        if (width >= JBUI.scale(980)) {
            wideSplit.leftComponent = primaryPanel
            wideSplit.rightComponent = detailsHost
            workspace.add(wideSplit, BorderLayout.CENTER)
        } else if (detailRequested) {
            workspace.add(detailsHost, BorderLayout.CENTER)
        } else {
            workspace.add(primaryPanel, BorderLayout.CENTER)
        }
        workspace.revalidate()
        workspace.repaint()
    }

    private fun showDetails(mode: DetailMode) {
        detailMode = mode
        detailRequested = true
        detailsLayout.show(detailsHost, mode.name)
        updateResponsiveWorkspace()
    }

    private fun primaryButton(
        text: String,
        action: () -> Unit,
    ) = JButton(text).apply {
        putClientProperty("JButton.buttonType", "default")
        addActionListener { action() }
    }

    private fun greenActionButton(
        text: String,
        action: () -> Unit,
    ) = JButton(text).apply {
        background = JBColor(0x2EAD5F, 0x319C59)
        foreground = JBColor.WHITE
        isOpaque = true
        isBorderPainted = false
        font = font.deriveFont(Font.BOLD)
        addActionListener { action() }
    }

    private fun configureDragSource() {
        dragLabel.border = BorderFactory.createDashedBorder(JBColor.GRAY, 2f, 4f)
        dragLabel.preferredSize = Dimension(JBUI.scale(320), JBUI.scale(58))
        dragLabel.minimumSize = Dimension(0, JBUI.scale(58))
        dragLabel.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(58))
        dragLabel.transferHandler =
            StagedFilesTransferHandler(
                provider = { staged },
                onHandOff = { markCurrentBatchHandedOff() },
            )
        dragLabel.addMouseMotionListener(
            object : MouseMotionAdapter() {
                override fun mouseDragged(event: MouseEvent) {
                    if (staged != null) dragLabel.transferHandler.exportAsDrag(dragLabel, event, TransferHandler.COPY)
                }
            },
        )
    }

    private fun refreshSkills() {
        refreshingSkills = true
        try {
            val selectedId = projectSettings.state.selectedPromptSkillId
            skillCombo.removeAllItems()
            AppSettings
                .getInstance()
                .state.promptSkills
                .forEach { skillCombo.addItem(it.name) }
            skillCombo.selectedItem = AppSettings
                .getInstance()
                .state.promptSkills
                .firstOrNull { it.id == selectedId }
                ?.name
                ?: AppSettings
                    .getInstance()
                    .state.promptSkills
                    .firstOrNull()
                    ?.name
        } finally {
            refreshingSkills = false
        }
    }

    private fun addFiles() {
        val descriptor = FileChooserDescriptor(true, true, false, false, false, true)
        selectionService.addSelection(FileChooser.chooseFiles(descriptor, project, null).toList())
    }

    private fun recalculate() {
        if (calculating) return
        calculating = true
        refreshSkills()
        status.text = "Analysing repository…"
        object : Task.Backgroundable(project, "Calculating Copilot context", true) {
            override fun run(indicator: ProgressIndicator) {
                runCatching { project.getService(ContextPackService::class.java).build() }
                    .onSuccess { result -> ApplicationManager.getApplication().invokeLater { showPack(result) } }
                    .onFailure { error ->
                        ApplicationManager.getApplication().invokeLater {
                            status.text = "Analysis failed — ${error.message ?: "unknown error"}"
                            UiSupport.notify(project, "Context generation failed", error.message ?: "Unknown error", NotificationType.ERROR)
                        }
                    }
                calculating = false
            }
        }.queue()
    }

    private fun showPack(result: ContextPack) {
        pack = result
        staged = null
        val maximum = projectSettings.state.maximumUploadFiles
        val included = result.selection.included.size + 1
        capacity.maximum = maximum
        capacity.value = included.coerceAtMost(maximum)
        count.text = "$included / $maximum"
        status.text =
            when {
                !result.selection.valid -> "⚠ ${result.selection.validationErrors.size} issue(s) must be resolved"
                result.selection.omitted.isNotEmpty() -> "${result.selection.omitted.size} candidates omitted • ${formatBytes(
                    result.estimatedBytes,
                )}"
                else -> "Safe selection ready • ${formatBytes(result.estimatedBytes)}"
            }
        renderFileList(pinnedFiles, result.selection.included.filter { it.pinned }, true)
        renderFileList(automaticFiles, result.selection.included.filterNot { it.pinned }, false)
        preview.text = result.markdown
        preview.caretPosition = 0
        dragLabel.text = "Drag becomes available after preparation"
        updateControls()
    }

    private fun renderFileList(
        panel: JPanel,
        candidates: List<ContextCandidate>,
        pinned: Boolean,
    ) {
        panel.removeAll()
        candidates.take(4).forEach { candidate ->
            panel.add(
                JPanel(BorderLayout(3, 0)).apply {
                    isOpaque = false
                    add(
                        JLabel(candidate.relativePath).apply {
                            toolTipText = if (pinned) "Manually selected" else "Automatic dependency — score ${candidate.score}"
                        },
                        BorderLayout.CENTER,
                    )
                    add(
                        JButton(AllIcons.Actions.Close).apply {
                            isContentAreaFilled = false
                            isBorderPainted = false
                            toolTipText = if (pinned) "Remove pinned file" else "Exclude automatic file from this batch"
                            addActionListener {
                                if (pinned) {
                                    selectionService.removePath(candidate.relativePath)
                                } else {
                                    selectionService.excludeAutomaticPath(candidate.relativePath)
                                }
                            }
                        },
                        BorderLayout.EAST,
                    )
                },
            )
        }
        if (candidates.size > 4) {
            panel.add(
                JButton("+ ${candidates.size - 4} more").apply {
                    isContentAreaFilled = false
                    isBorderPainted = false
                    horizontalAlignment = SwingConstants.LEFT
                    addActionListener { detailTabs.selectedIndex = 1 }
                },
            )
        }
        if (candidates.isEmpty()) {
            panel.add(
                JLabel(if (pinned) "No pinned files" else "No automatic files").apply {
                    foreground =
                        JBColor.GRAY
                },
            )
        }
        panel.revalidate()
        panel.repaint()
    }

    private fun prepareBatch(): StagingService.StagingResult? {
        staged?.let { return it }
        var current = pack ?: return null
        val secretErrors = current.selection.validationErrors.filter { it.contains("requires explicit secret confirmation") }
        val otherErrors = current.selection.validationErrors - secretErrors.toSet()
        if (otherErrors.isNotEmpty()) {
            UiSupport.notify(project, "Cannot prepare batch", otherErrors.joinToString("<br>"), NotificationType.ERROR)
            return null
        }
        if (secretErrors.isNotEmpty() &&
            Messages.showYesNoDialog(
                project,
                "Likely secrets were detected:\n\n${secretErrors.joinToString("\n")}\n\nInclude these manually pinned files?",
                "Confirm Sensitive Files",
                null,
            ) != Messages.YES
        ) {
            return null
        }
        if (secretErrors.isNotEmpty()) current = current.copy(selection = current.selection.copy(validationErrors = emptyList()))
        preparing = true
        return runCatching { StagingService(project).stage(current) }
            .onSuccess {
                staged = it
                dragLabel.text = "Drag these ${it.files.size} files to Copilot"
                status.text = "✓ Safe pack ready • 00_REPO_CONTEXT.md included"
                refreshHistory()
                updateControls()
                UiSupport.notify(project, "Batch ready", "${it.files.size} files are ready to drag or paste.")
            }.onFailure { UiSupport.notify(project, "Staging failed", it.message ?: "Unknown error", NotificationType.ERROR) }
            .getOrNull()
            .also { preparing = false }
    }

    private fun copyFiles() {
        val result = staged ?: return
        val transferable =
            FileListTransferable(result.files.map { it.stagedPath.toFile() }, result.files.joinToString("\n") { it.relativePath })
        java.awt.Toolkit
            .getDefaultToolkit()
            .systemClipboard
            .setContents(transferable, null)
        markCurrentBatchHandedOff()
        UiSupport.notify(project, "Files copied", "${result.files.size} files are ready to paste into Copilot.")
    }

    private fun copyCompletePackAsText() {
        val result = staged ?: return
        UiSupport.copyText(CombinedContextTextBuilder.build(AppSettings.getInstance().state.combinedTextIntro, result.files))
        markCurrentBatchHandedOff()
        UiSupport.notify(project, "Complete pack copied", "Metadata, paths and ${result.files.size} file contents were copied as text.")
    }

    private fun openStaging() {
        val directory = staged?.directory ?: return
        runCatching { Desktop.getDesktop().open(directory.toFile()) }
            .onFailure { UiSupport.notify(project, "Cannot open staging folder", directory.toString(), NotificationType.ERROR) }
    }

    private fun startNextBatch() {
        invalidatePreparedBatch()
        selectionService.clear()
        status.text = "Select files for the next batch; previous files remain visible in history."
        dragLabel.text = "Drag becomes available after preparation"
        recalculate()
    }

    private fun invalidatePreparedBatch() {
        staged = null
        pack = null
        updateControls()
    }

    private fun copyReturnInstructions() {
        val marker = "## WHEN RETURNING CODE CHANGES"
        val text = preview.text.substringAfter(marker, preview.text)
        UiSupport.copyText("$marker\n$text")
    }

    private fun openSettings() = ShowSettingsUtil.getInstance().showSettingsDialog(project, "Copilot Context Bridge")

    private fun selectedBatchId() = (batchCombo.selectedItem as? String)?.substringBefore(" - ")

    private fun selectedSessionDirectory(): Path? {
        val sessionId = selectedBatchId() ?: return null
        staged?.takeIf { pack?.sessionId == sessionId }?.let { return it.directory }
        val repositoryId =
            ProjectRoot
                .path(project)
                .fileName
                .toString()
                .replace(Regex("[^A-Za-z0-9._-]"), "-")
        return Path.of(System.getProperty("java.io.tmpdir"), "CopilotContextBridge", "${repositoryId}_$sessionId")
    }

    private fun keepSelectedSession() {
        val directory = selectedSessionDirectory() ?: return
        if (!Files.isDirectory(directory)) {
            UiSupport.notify(project, "Staged files not found", "This batch no longer has a staging folder.", NotificationType.WARNING)
            return
        }
        runCatching { StagingService(project).keepSession(directory) }
            .onSuccess { UiSupport.notify(project, "Session kept", "Automatic cleanup will skip this staging folder.") }
            .onFailure { UiSupport.notify(project, "Cannot keep session", it.message ?: "Unknown error", NotificationType.ERROR) }
    }

    private fun deleteSelectedSession() {
        val directory = selectedSessionDirectory() ?: return
        if (!Files.isDirectory(directory)) {
            UiSupport.notify(project, "Staged files not found", "This batch no longer has a staging folder.", NotificationType.WARNING)
            return
        }
        if (
            Messages.showYesNoDialog(
                project,
                "Delete the safe copies for this batch? Repository files are never deleted.",
                "Delete Staged Files",
                null,
            ) != Messages.YES
        ) {
            return
        }
        runCatching { StagingService(project).deleteSession(directory) }
            .onSuccess {
                if (staged?.directory == directory) {
                    staged = null
                    updateControls()
                    dragLabel.text = "Drag becomes available after preparation"
                }
                UiSupport.notify(project, "Staged files deleted", "Only temporary safe copies were removed.")
            }.onFailure { UiSupport.notify(project, "Cannot delete staged files", it.message ?: "Unknown error", NotificationType.ERROR) }
    }

    private fun markCurrentBatchHandedOff() {
        val sessionId = pack?.sessionId ?: return
        selectionService.markHandedOff(sessionId)
        status.text = "✓ Batch handed off • ${staged?.files?.size ?: 0} files recorded in history"
    }

    private fun refreshHistory() {
        val batches = selectionService.batches().asReversed()
        batchCombo.removeAllItems()
        batches.forEach { batch -> batchCombo.addItem("${batch.sessionId} - ${batch.promptSkillName}") }
        historyText.text =
            buildString {
                batches.take(6).forEachIndexed { index, batch ->
                    val state = if (batch.status == "HANDED_OFF") "✓ Sent/copy started" else "✓ Prepared"
                    appendLine(
                        "#${batches.size - index}   ${batch.createdAt.take(
                            16,
                        ).replace('T', ' ')}   ${batch.paths.size + 1} files   $state",
                    )
                }
                if (batches.isEmpty()) appendLine("No prepared batches yet.")
            }
    }

    private fun updateControls() {
        prepareButton.isEnabled = pack?.selection?.valid == true && staged == null && !preparing
        copyButton.isEnabled = staged != null
        copyAllTextButton.isEnabled = staged != null
        openButton.isEnabled = staged != null
        nextButton.isEnabled = staged != null
    }

    private fun formatBytes(bytes: Long) = if (bytes < 1024 * 1024) "${bytes / 1024} KiB" else "%.1f MiB".format(bytes / 1024.0 / 1024.0)

    private class StagedFilesTransferHandler(
        private val provider: () -> StagingService.StagingResult?,
        private val onHandOff: () -> Unit,
    ) : TransferHandler() {
        override fun createTransferable(component: javax.swing.JComponent): Transferable? =
            provider()?.let { result ->
                onHandOff()
                FileListTransferable(result.files.map { it.stagedPath.toFile() }, result.files.joinToString("\n") { it.relativePath })
            }

        override fun getSourceActions(component: javax.swing.JComponent): Int = COPY
    }

    private enum class DetailMode {
        MORE,
        IMPORT,
    }
}
