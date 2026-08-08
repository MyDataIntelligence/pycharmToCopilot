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
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import nl.ferron.copilotcontextbridge.ProjectRoot
import nl.ferron.copilotcontextbridge.context.ContextPackService
import nl.ferron.copilotcontextbridge.external.ExplorerRepositoryDropHandler
import nl.ferron.copilotcontextbridge.external.ExternalRepositoryDropResolver
import nl.ferron.copilotcontextbridge.external.ExternalRepositorySelectionRegistry
import nl.ferron.copilotcontextbridge.model.ContextCandidate
import nl.ferron.copilotcontextbridge.model.ContextPack
import nl.ferron.copilotcontextbridge.model.displayRepository
import nl.ferron.copilotcontextbridge.model.sourceKey
import nl.ferron.copilotcontextbridge.patch.CopilotPatchSniffer
import nl.ferron.copilotcontextbridge.settings.AppSettings
import nl.ferron.copilotcontextbridge.settings.ProjectSettings
import nl.ferron.copilotcontextbridge.settings.ProjectSettingsConfigurable
import nl.ferron.copilotcontextbridge.settings.ReturnInstructions
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
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
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
    private val externalRegistry = project.getService(ExternalRepositorySelectionRegistry::class.java)
    private val externalResolver =
        ExternalRepositoryDropResolver(
            ProjectRoot.path(project),
            AppSettings.getInstance().state.ignorePatterns,
            projectSettings.state.customIgnorePatterns,
            AppSettings.getInstance().state.secretFilenamePatterns,
            projectSettings.state.textualScanLimitBytes,
        )

    private val count = JLabel("0 repository files • 0 / 20 attachments")
    private val status = JLabel("Select files to begin")
    private val capacity =
        JProgressBar().apply {
            preferredSize = Dimension(JBUI.scale(160), JBUI.scale(10))
        }
    private val pinnedFiles = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val automaticFiles = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val preview = JBTextArea().apply { isEditable = false }
    private val historyText = JBTextArea().apply { isEditable = false }
    private val skillCombo = JComboBox<PromptSkillChoice>()
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
    private val newSessionButton =
        JButton("New session").apply {
            toolTipText = "Start a new Copilot conversation and reset previous-batch avoidance"
            addActionListener { startNewSession() }
        }
    private val detailTabs = createStableDetailTabs()
    private val contextFilesPanel = ContextFilesPanel(project)
    private val returnInstructionsPanel =
        ReturnInstructionsPanel(project) {
            invalidatePreparedBatch()
            recalculate()
        }
    private val promptSkillsPanel = PromptSkillsPanel(::promptLibraryChanged)
    private val importPanel = PatchImportPanel(project)
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
    private var refreshingHistory = false
    private val copyContextButtons = mutableListOf<JButton>()
    private val analysisGeneration = AtomicInteger()

    @Volatile private var activeAnalysis: ProgressIndicator? = null

    init {
        border = JBUI.Borders.empty(6)
        detailTabs.addTab("More Copilot actions", createMoreActionsPanel())
        detailTabs.addTab("Context files", contextFilesPanel)
        detailTabs.addTab("Context preview", createPreviewPanel())
        detailTabs.addTab(
            "Guidelines",
            GuidelinesPanel(project) {
                invalidatePreparedBatch()
                recalculate()
            },
        )
        detailTabs.addTab("Prompt skills", promptSkillsPanel)
        detailTabs.addTab("Return instructions", returnInstructionsPanel)
        detailsHost.add(detailTabs, DetailMode.MORE.name)
        detailsHost.add(importPanel, DetailMode.IMPORT.name)

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
            val selected = skillCombo.selectedItem as? PromptSkillChoice ?: return@addActionListener
            AppSettings.getInstance().state.promptSkills.firstOrNull { it.id == selected.id }?.let {
                if (projectSettings.state.selectedPromptSkillId != it.id) {
                    projectSettings.state.selectedPromptSkillId = it.id
                    returnInstructionsPanel.refresh()
                    invalidatePreparedBatch()
                    recalculate()
                }
            }
        }
        batchCombo.addActionListener {
            if (!refreshingHistory) showSelectedBatchDetails()
        }
        configureDragSource()
        selectionService.addListener {
            ApplicationManager.getApplication().invokeLater {
                refreshHistory()
                if (!calculating && !preparing && staged == null) recalculate()
            }
        }
        externalRegistry.addListener {
            ApplicationManager.getApplication().invokeLater {
                if (!preparing && staged == null) recalculate()
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
                    JButton("＋ Add files, folder or ZIP").apply { addActionListener { addFiles() } },
                    JButton("Clear", AllIcons.Actions.GC).apply { addActionListener { clearSelection() } },
                ),
            )
            add(createRepositoryDropZone())
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
            add(JLabel("00_REPO_CONTEXT.md is included automatically and indexes this batch.").apply { foreground = JBColor.GRAY })
            add(actionGrid(copyButton, copyAllTextButton, openButton, nextButton))
            add(createReturnDropZone())
            components.filterIsInstance<javax.swing.JComponent>().forEach { it.alignmentX = LEFT_ALIGNMENT }
        }

    private fun createRepositoryDropZone() =
        JLabel("Drop repository files, folders or ZIP archives", AllIcons.Actions.Download, SwingConstants.CENTER).apply {
            border = BorderFactory.createDashedBorder(JBColor.GRAY, 1f, 3f)
            preferredSize = Dimension(JBUI.scale(320), JBUI.scale(34))
            minimumSize = Dimension(0, JBUI.scale(34))
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(34))
            transferHandler =
                ExplorerRepositoryDropHandler(
                    resolver = externalResolver::resolve,
                    resultConsumer = ::acceptRepositoryDrop,
                    errorConsumer = { message ->
                        UiSupport.notify(project, "Repository drop failed", message, NotificationType.ERROR)
                    },
                )
        }

    private fun acceptRepositoryDrop(result: ExternalRepositoryDropResolver.Result) {
        val confirmed =
            if (result.confirmationRequired.isEmpty()) {
                emptyList()
            } else if (
                Messages.showYesNoDialog(
                    project,
                    "${result.confirmationRequired.size} dropped file(s) may contain credentials or secrets. " +
                        "Include them in this batch after explicit confirmation?\n\n" +
                        result.confirmationRequired.joinToString("\n") { "${it.repository.name}: ${it.relativePath}" },
                    "Confirm Sensitive Dropped Files",
                    null,
                ) == Messages.YES
            ) {
                result.confirmationRequired
            } else {
                emptyList()
            }
        val accepted = result.accepted + confirmed
        val current = accepted.filter { it.repository.current }
        val external = accepted.filterNot { it.repository.current }
        val currentVirtualFiles = current.mapNotNull { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it.absolutePath) }
        if (currentVirtualFiles.isNotEmpty()) selectionService.addSelection(currentVirtualFiles)
        if (external.isNotEmpty()) {
            externalRegistry.register(
                ExternalRepositoryDropResolver.Result(
                    external.map { it.repository }.distinctBy { it.id },
                    external,
                    emptyList(),
                    emptyList(),
                ),
            )
        }
        if (result.rejected.isNotEmpty()) {
            UiSupport.notify(
                project,
                "Some dropped paths were rejected",
                result.rejected.joinToString("<br>") { "${it.suppliedPath.fileName}: ${it.reason}" },
                NotificationType.WARNING,
            )
        }
        invalidatePreparedBatch()
        recalculate()
    }

    private fun createReturnDropZone() =
        JLabel("⇩  Drop Copilot return here", AllIcons.Actions.Download, SwingConstants.CENTER).apply {
            border = BorderFactory.createDashedBorder(JBColor.GRAY, 1f, 3f)
            toolTipText = "Accepts only a schema-matching .copilotpatch, JSON, or ZIP and opens Import automatically"
            preferredSize = Dimension(JBUI.scale(320), JBUI.scale(38))
            minimumSize = Dimension(0, JBUI.scale(38))
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(38))
            transferHandler =
                object : TransferHandler() {
                    override fun canImport(support: TransferSupport): Boolean =
                        support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)

                    override fun importData(support: TransferSupport): Boolean {
                        val files =
                            support.transferable
                                .getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as? List<*> ?: return false
                        val file = files.filterIsInstance<java.io.File>().singleOrNull() ?: return false
                        if (!CopilotPatchSniffer.matches(file.toPath())) {
                            UiSupport.notify(
                                project,
                                "Return file not recognized",
                                "The dropped file does not match the versioned Copilot patch schema.",
                                NotificationType.WARNING,
                            )
                            return false
                        }
                        showDetails(DetailMode.IMPORT)
                        importPanel.loadFile(file)
                        return true
                    }
                }
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
        add(
            JBScrollPane(content).apply {
                border = null
                horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            },
            BorderLayout.CENTER,
        )
    }

    private fun createMoreActionsPanel() =
        JPanel(BorderLayout(8, 8)).apply {
            border = JBUI.Borders.empty(10)
            val navigation =
                JPanel(GridLayout(0, 2, 8, 8)).apply {
                    border = BorderFactory.createTitledBorder("Manage context")
                    MoreWorkspaceModel.destinations.forEach { destination -> add(moreDestinationButton(destination)) }
                }
            val quickCopy =
                JPanel(GridLayout(1, 2, 8, 0)).apply {
                    border = BorderFactory.createTitledBorder("Quick copy")
                    add(contextCopyButton(MoreWorkspaceModel.quickActions[0]))
                    add(JButton(MoreWorkspaceModel.quickActions[1]).apply { addActionListener { copyReturnInstructions() } })
                }
            add(
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    add(navigation)
                    add(Box.createVerticalStrut(JBUI.scale(8)))
                    add(quickCopy)
                },
                BorderLayout.NORTH,
            )
            add(createHistoryPanel(), BorderLayout.CENTER)
        }

    private fun moreDestinationButton(destination: MoreWorkspaceModel.Destination): JButton {
        val icon =
            when (destination.title) {
                "Guidelines" -> AllIcons.General.InspectionsOK
                "Prompt skills" -> AllIcons.General.User
                "Return instructions" -> AllIcons.Actions.Copy
                "Settings" -> AllIcons.General.GearPlain
                else -> AllIcons.FileTypes.Text
            }
        return JButton(
            "<html><b>${destination.title}</b><br><font color='#888888'>${destination.subtitle}</font></html>",
            icon,
        ).apply {
            horizontalAlignment = SwingConstants.LEFT
            addActionListener {
                destination.tabIndex?.let { detailTabs.selectedIndex = it } ?: openSettings()
            }
        }
    }

    private fun contextCopyButton(text: String) =
        JButton(text).apply {
            addActionListener { copyCurrentContext() }
            copyContextButtons += this
        }

    private fun createHistoryPanel() =
        JPanel(BorderLayout(5, 5)).apply {
            border = BorderFactory.createTitledBorder("Batch history (recent)")
            add(JBScrollPane(historyText), BorderLayout.CENTER)
            add(
                JPanel(BorderLayout(0, 5)).apply {
                    add(batchCombo, BorderLayout.NORTH)
                    add(
                        JPanel(GridLayout(2, 2, 6, 4)).apply {
                            add(JButton("Restore").apply { addActionListener { selectedBatchId()?.let(selectionService::restoreBatch) } })
                            add(JButton("Keep staged files").apply { addActionListener { keepSelectedSession() } })
                            add(JButton("Delete staged files").apply { addActionListener { deleteSelectedSession() } })
                            add(JButton("Forget").apply { addActionListener { forgetSelectedBatch() } })
                        },
                        BorderLayout.CENTER,
                    )
                    add(newSessionButton, BorderLayout.SOUTH)
                },
                BorderLayout.SOUTH,
            )
        }

    private fun createPreviewPanel() =
        JPanel(BorderLayout(6, 6)).apply {
            border = JBUI.Borders.empty(8)
            add(
                actionRow(
                    contextCopyButton("Copy context only"),
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
            val choices =
                AppSettings
                    .getInstance()
                    .state.promptSkills
                    .map { PromptSkillChoice(it.id, skillLabel(it)) }
            choices.forEach(skillCombo::addItem)
            skillCombo.selectedItem = choices.firstOrNull { it.id == selectedId } ?: choices.firstOrNull()
        } finally {
            refreshingSkills = false
        }
    }

    private fun addFiles() {
        val descriptor = FileChooserDescriptor(true, true, false, false, false, true)
        val selected = FileChooser.chooseFiles(descriptor, project, null).toList()
        val archives = selected.filter { !it.isDirectory && it.extension.equals("zip", ignoreCase = true) }
        val regularSelection = selected - archives.toSet()
        if (regularSelection.isNotEmpty()) selectionService.addSelection(regularSelection)
        if (archives.isEmpty()) return
        AppExecutorUtil.getAppExecutorService().execute {
            runCatching { externalResolver.resolve(archives.map { Path.of(it.path) }) }
                .onSuccess { result -> ApplicationManager.getApplication().invokeLater { acceptRepositoryDrop(result) } }
                .onFailure { error ->
                    ApplicationManager.getApplication().invokeLater {
                        UiSupport.notify(
                            project,
                            "ZIP context import failed",
                            error.message ?: "The selected ZIP could not be processed safely.",
                            NotificationType.ERROR,
                        )
                    }
                }
        }
    }

    private fun skillLabel(skill: AppSettings.PromptSkillState): String = "${skill.category.ifBlank { "Custom" }}  ·  ${skill.name}"

    private fun recalculate() {
        val generation = analysisGeneration.incrementAndGet()
        activeAnalysis?.cancel()
        calculating = true
        pack = null
        refreshSkills()
        status.text = "Analysing repository…"
        preview.text = "Context preview is being recalculated…"
        updateControls()
        object : Task.Backgroundable(project, "Calculating Copilot context", true) {
            override fun run(indicator: ProgressIndicator) {
                activeAnalysis = indicator
                runCatching { project.getService(ContextPackService::class.java).build() }
                    .onSuccess { result ->
                        ApplicationManager.getApplication().invokeLater {
                            if (generation == analysisGeneration.get() && !indicator.isCanceled) showPack(result)
                        }
                    }.onFailure { error ->
                        ApplicationManager.getApplication().invokeLater {
                            if (generation == analysisGeneration.get() && !indicator.isCanceled) {
                                status.text = "Analysis failed — ${error.message ?: "unknown error"}"
                                UiSupport.notify(
                                    project,
                                    "Context generation failed",
                                    error.message ?: "Unknown error",
                                    NotificationType.ERROR,
                                )
                            }
                        }
                    }
                ApplicationManager.getApplication().invokeLater {
                    if (generation == analysisGeneration.get()) {
                        calculating = false
                        activeAnalysis = null
                        updateControls()
                    }
                }
            }
        }.queue()
    }

    private fun showPack(result: ContextPack) {
        pack = result
        staged = null
        val maximum =
            AppSettings
                .getInstance()
                .skill(result.promptSkillId)
                .contextPolicy.maxAttachments
                .coerceIn(2, 20)
        val included = result.attachmentPlan.attachmentCount
        capacity.maximum = maximum
        capacity.value = included.coerceAtMost(maximum)
        count.text = "${result.attachmentPlan.repositoryFileCount} repository files • $included / $maximum attachments"
        status.text =
            when {
                !result.selection.valid -> "⚠ ${result.selection.validationErrors.size} issue(s) must be resolved"
                result.selection.omitted.isNotEmpty() -> "${result.selection.omitted.size} candidates omitted • ${formatBytes(
                    result.estimatedBytes,
                )}"
                else -> "Safe selection ready • ${formatBytes(result.estimatedBytes)}"
            }
        renderFileList(pinnedFiles, result.selection.included.filter { it.pinned }, true)
        renderInvalidPinnedPaths()
        renderFileList(automaticFiles, result.selection.included.filterNot { it.pinned }, false)
        contextFilesPanel.show(result)
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
        candidates.forEach { candidate ->
            panel.add(
                JPanel(BorderLayout(3, 0)).apply {
                    isOpaque = false
                    add(
                        JLabel(candidateLabel(candidate, pinned)).apply {
                            toolTipText = candidateDetails(candidate, pinned)
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
                                    if (candidate.repositoryId.isBlank()) {
                                        selectionService.removePath(candidate.relativePath)
                                    } else {
                                        externalRegistry.remove(candidate.sourceKey)
                                        invalidatePreparedBatch()
                                        recalculate()
                                    }
                                } else {
                                    if (candidate.repositoryId.isBlank()) {
                                        selectionService.excludeForBatch(candidate.relativePath)
                                    } else {
                                        externalRegistry.remove(candidate.sourceKey)
                                        invalidatePreparedBatch()
                                        recalculate()
                                    }
                                }
                            }
                        },
                        BorderLayout.EAST,
                    )
                    if (!pinned) {
                        componentPopupMenu = exclusionMenu(candidate.relativePath)
                        components.forEach { (it as? javax.swing.JComponent)?.componentPopupMenu = componentPopupMenu }
                    }
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

    private fun renderInvalidPinnedPaths() {
        val invalid = selectionService.invalidPinnedPaths()
        if (invalid.isEmpty()) return
        if (pack?.selection?.included?.none { it.pinned } == true) pinnedFiles.removeAll()
        invalid.forEach { path ->
            pinnedFiles.add(
                JPanel(BorderLayout(3, 0)).apply {
                    isOpaque = false
                    add(
                        JLabel("⚠ $path — file moved or deleted").apply {
                            foreground = JBColor.RED
                            toolTipText = "Remove this invalid pin, then add the file again from its new location."
                        },
                        BorderLayout.CENTER,
                    )
                    add(
                        JButton(AllIcons.Actions.Close).apply {
                            isContentAreaFilled = false
                            isBorderPainted = false
                            toolTipText = "Remove invalid pinned path"
                            addActionListener { selectionService.removePath(path) }
                        },
                        BorderLayout.EAST,
                    )
                },
            )
        }
        pinnedFiles.revalidate()
        pinnedFiles.repaint()
    }

    private fun candidateLabel(
        candidate: ContextCandidate,
        pinned: Boolean,
    ): String {
        val path =
            if (candidate.repositoryId.isBlank()) {
                candidate.relativePath
            } else {
                "${candidate.displayRepository}: ${candidate.relativePath}"
            }
        if (pinned) return path
        val tag =
            when (candidate.relations.firstOrNull()?.type) {
                nl.ferron.copilotcontextbridge.model.RelationType.RELATED_TEST -> "test"
                nl.ferron.copilotcontextbridge.model.RelationType.TEST_FIXTURE -> "fixture"
                nl.ferron.copilotcontextbridge.model.RelationType.DIRECT_IMPORT -> "import"
                nl.ferron.copilotcontextbridge.model.RelationType.DIRECT_DEPENDENT -> "caller"
                nl.ferron.copilotcontextbridge.model.RelationType.REFERENCED_CONFIGURATION -> "config"
                nl.ferron.copilotcontextbridge.model.RelationType.BRANCH_CHANGE -> "diff"
                nl.ferron.copilotcontextbridge.model.RelationType.PACKAGE_INIT -> "base"
                nl.ferron.copilotcontextbridge.model.RelationType.PROJECT_CONFIGURATION -> "instructions"
                nl.ferron.copilotcontextbridge.model.RelationType.INSTRUCTION -> "instructions"
                nl.ferron.copilotcontextbridge.model.RelationType.TEMPLATE -> "template"
                nl.ferron.copilotcontextbridge.model.RelationType.SIMILAR_IMPLEMENTATION -> "example"
                else -> "dependency"
            }
        return "$path  [$tag]"
    }

    private fun candidateDetails(
        candidate: ContextCandidate,
        pinned: Boolean,
    ): String {
        val attachment = pack?.attachmentPlan?.repositoryToAttachment?.get(candidate.sourceKey) ?: "not packed"
        val reasons =
            candidate.relations
                .joinToString("<br>") { relation ->
                    "${relation.type}: ${relation.evidence.ifBlank { "${relation.from} → ${relation.to}" }}"
                }.ifBlank { if (pinned) "Manually selected" else "Automatic repository relation" }
        return "<html><b>${candidate.relativePath}</b><br>" +
            "SHA-256: ${candidate.sha256}<br>Priority score: ${candidate.score}<br>Depth: ${candidate.depth}<br>" +
            "Prepared attachment: $attachment<br><br>$reasons</html>"
    }

    private fun exclusionMenu(path: String) =
        JPopupMenu().apply {
            add(JMenuItem("Exclude this batch").apply { addActionListener { selectionService.excludeForBatch(path) } })
            add(JMenuItem("Exclude this session").apply { addActionListener { selectionService.excludeForSession(path) } })
            add(
                JMenuItem("Always exclude this path…").apply {
                    addActionListener {
                        if (
                            Messages.showYesNoDialog(
                                project,
                                "Always exclude $path from automatic context in this project?",
                                "Always Exclude Path",
                                null,
                            ) == Messages.YES
                        ) {
                            selectionService.alwaysExclude(path)
                        }
                    }
                },
            )
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
                UiSupport.notify(project, "Batch prepared", packingSummary(current))
            }.onFailure { UiSupport.notify(project, "Staging failed", it.message ?: "Unknown error", NotificationType.ERROR) }
            .getOrNull()
            .also { preparing = false }
    }

    private fun packingSummary(context: ContextPack): String {
        val pinned = context.selection.included.count { it.pinned }
        val automatic = context.selection.included.size - pinned
        val bundles =
            context.attachmentPlan.attachments.count {
                it.kind ==
                    nl.ferron.copilotcontextbridge.model.AttachmentKind.AUTOMATIC_BUNDLE
            }
        return buildString {
            append("${context.attachmentPlan.attachmentCount} physical attachments representing ")
            append("${context.attachmentPlan.repositoryFileCount} repository files.<br>")
            append("$pinned pinned files kept separate; $automatic automatic files packed into $bundles bundle(s).")
            if (context.selection.omitted.isNotEmpty()) {
                append("<br>${context.selection.omitted.size} relevant files were omitted; inspect them under More → Context files.")
            }
            if (context.selection.excluded.isNotEmpty()) {
                append("<br>${context.selection.excluded.size} files were excluded by user rules.")
            }
        }
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
        externalRegistry.clearManualSourcesKeepArchives()
        selectionService.clear()
        status.text = "Next batch ready; archive sources remain available and previously sent entries are avoided."
        dragLabel.text = "Drag becomes available after preparation"
        recalculate()
    }

    private fun startNewSession() {
        if (
            Messages.showYesNoDialog(
                project,
                "Start a new Copilot conversation session? Current selection is cleared and earlier batches remain only in history.",
                "Start New Session",
                null,
            ) != Messages.YES
        ) {
            return
        }
        invalidatePreparedBatch()
        externalRegistry.clear()
        selectionService.startNewSession()
        status.text = "New Copilot session started. Select files for batch 1."
        dragLabel.text = "Drag becomes available after preparation"
        recalculate()
    }

    private fun invalidatePreparedBatch() {
        staged = null
        pack = null
        updateControls()
    }

    private fun clearSelection() {
        invalidatePreparedBatch()
        externalRegistry.clear()
        selectionService.clear()
    }

    private fun copyReturnInstructions() {
        val app = AppSettings.getInstance()
        val skill = app.skill(projectSettings.state.selectedPromptSkillId)
        val effective = ReturnInstructions.resolve(app.state, projectSettings.state, skill)
        UiSupport.copyText(effective.effectiveText)
        UiSupport.notify(project, "Return instructions copied", "Copied the effective ${effective.mode.name} instructions.")
    }

    private fun copyCurrentContext() {
        val current = pack ?: return
        if (calculating || preparing) return
        UiSupport.copyText(current.markdown)
        UiSupport.notify(project, "Context copied", "Copied the current generated 00_REPO_CONTEXT.md preview.")
    }

    private fun promptLibraryChanged() {
        val settings = AppSettings.getInstance()
        if (settings.state.promptSkills.none { it.id == projectSettings.state.selectedPromptSkillId }) {
            projectSettings.state.selectedPromptSkillId =
                settings.state.promptSkills
                    .first()
                    .id
        }
        refreshSkills()
        returnInstructionsPanel.refresh()
        invalidatePreparedBatch()
        if (!calculating && !preparing) recalculate()
    }

    private fun openSettings() = ShowSettingsUtil.getInstance().showSettingsDialog(project, ProjectSettingsConfigurable::class.java)

    private fun selectedBatchId() = (batchCombo.selectedItem as? String)?.substringBefore(" - ")

    private fun forgetSelectedBatch() {
        val sessionId = selectedBatchId() ?: return
        if (
            Messages.showYesNoDialog(
                project,
                "Forget this batch from Bridge history? Staged files are not deleted.",
                "Forget Batch",
                null,
            ) != Messages.YES
        ) {
            return
        }
        selectionService.deleteBatch(sessionId)
    }

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
        val selected = selectedBatchId()
        val batches = selectionService.batches().asReversed()
        refreshingHistory = true
        try {
            batchCombo.removeAllItems()
            batches.forEach { batch -> batchCombo.addItem("${batch.sessionId} - ${batch.promptSkillName}") }
            val restoredIndex = batches.indexOfFirst { it.sessionId == selected }
            if (restoredIndex >= 0) batchCombo.selectedIndex = restoredIndex
        } finally {
            refreshingHistory = false
        }
        showSelectedBatchDetails()
    }

    private fun showSelectedBatchDetails() {
        val selectedId = selectedBatchId()
        val selected = selectionService.batches().firstOrNull { it.sessionId == selectedId }
        historyText.text =
            buildString {
                if (selected == null) {
                    appendLine("No prepared batches yet.")
                } else {
                    appendLine("${selected.promptSkillName} · ${selected.createdAt.take(16).replace('T', ' ')}")
                    appendLine(if (selected.status == "HANDED_OFF") "Sent/copy started" else "Prepared")
                    appendLine("00_REPO_CONTEXT.md")
                    selected.paths.forEach(::appendLine)
                }
            }
    }

    private fun updateControls() {
        val state = workflowControlState(pack?.selection?.valid == true, staged != null, calculating, preparing)
        prepareButton.isEnabled = state.canPrepare
        copyButton.isEnabled = state.canUsePreparedFiles
        copyAllTextButton.isEnabled = state.canUsePreparedFiles
        openButton.isEnabled = state.canUsePreparedFiles
        nextButton.isEnabled = state.canUsePreparedFiles
        copyContextButtons.forEach { it.isEnabled = state.canCopyContext }
        newSessionButton.isEnabled = state.canStartNewSession
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
