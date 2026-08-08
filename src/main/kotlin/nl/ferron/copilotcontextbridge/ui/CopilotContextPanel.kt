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
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JProgressBar
import javax.swing.JSplitPane
import javax.swing.JToggleButton
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.TransferHandler
import javax.swing.UIManager

/** The primary three-step workflow and its supporting detail workspace. */
class CopilotContextPanel(
    private val project: Project,
) : JPanel(BorderLayout()) {
    private val selectionService = project.getService(ContextSelectionService::class.java)
    private val projectSettings = project.getService(ProjectSettings::class.java)
    private val externalRegistry = project.getService(ExternalRepositorySelectionRegistry::class.java)
    private val count = JLabel("0 repository files • 0 / 20 attachments")
    private val status = JLabel("Select files to begin")
    private val capacity =
        JProgressBar().apply {
            preferredSize = Dimension(JBUI.scale(160), JBUI.scale(10))
        }

    /** Compact selector: the closed value is the category; the popup contains every file in that category. */
    private val batchFileCategory =
        JComboBox<BatchFileDropdownItem>().apply {
            renderer = BatchFileDropdownRenderer()
            // Keep the page compact while making the popup itself scrollable for large packs.
            maximumRowCount = 12
        }
    private val batchFiles = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val preview = JBTextArea().apply { isEditable = false }
    private val historyText = JBTextArea().apply { isEditable = false }
    private val skillCombo = JComboBox<PromptSkillChoice>()
    private val batchCombo = JComboBox<String>()
    private val sessionCombo = JComboBox<SessionChoice>()
    private var refreshingSessions = false
    private val dragLabel = JLabel("Drag becomes available after preparation", AllIcons.Actions.Upload, SwingConstants.CENTER)
    private val kickoffPrompt =
        JBTextArea(5, 36).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            text = "Prepare the batch to generate the Copilot prompt."
        }
    private val copyPromptButton = JButton("Copy prompt", AllIcons.Actions.Copy).apply { addActionListener { copyKickoffPrompt() } }
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
    private val batchNavigation = JToggleButton(TopLevelNavigationModel.destinations[0]).apply { isSelected = true }
    private val importNavigation = JToggleButton(TopLevelNavigationModel.destinations[1], AllIcons.ToolbarDecorator.Import)
    private val previewNavigation = JToggleButton(TopLevelNavigationModel.destinations[2])
    private val moreNavigation = JToggleButton(TopLevelNavigationModel.destinations[3])
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
    private var refreshingBatchCategory = false
    private var selectedBatchCategory: BatchFileCategory? = null
    private var pinnedCandidates: List<ContextCandidate> = emptyList()
    private var automaticCandidates: List<ContextCandidate> = emptyList()
    private val copyContextButtons = mutableListOf<JButton>()
    private val analysisGeneration = AtomicInteger()
    private val recalculationTimer = Timer(140) { runRecalculation() }.apply { isRepeats = false }

    @Volatile private var activeAnalysis: ProgressIndicator? = null

    init {
        border = JBUI.Borders.empty(6)
        detailTabs.addTab("More Copilot actions", createMoreActionsPanel())
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
        detailsHost.add(createPreviewPage(), DetailMode.PREVIEW.name)

        primaryPanel = createPrimaryPanel()
        wideSplit =
            JSplitPane(JSplitPane.HORIZONTAL_SPLIT, primaryPanel, detailsHost).apply {
                resizeWeight = 0.43
                dividerLocation = 500
                // Keep the Batch workflow usable when a tool-window divider was previously
                // dragged too far left.  The detail pane still remains resizable, but neither
                // side is allowed to collapse into clipped labels and unusable controls.
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
        batchFileCategory.addActionListener {
            if (refreshingBatchCategory) return@addActionListener
            val item = batchFileCategory.selectedItem as? BatchFileDropdownItem ?: return@addActionListener
            // File rows are read-only inspection entries. Restore the compact category label after a click.
            if (!item.isCategory) {
                val category = item.category
                SwingUtilities.invokeLater {
                    refreshingBatchCategory = true
                    try {
                        selectedBatchCategory = category
                        refreshBatchFileCategories()
                    } finally {
                        refreshingBatchCategory = false
                    }
                }
            } else {
                selectedBatchCategory = item.category
                // Rebuild the popup so the newly selected category becomes the closed value and its
                // complete file set is immediately available on the next open.
                refreshBatchFileCategories()
            }
        }
        batchCombo.addActionListener {
            if (!refreshingHistory) showSelectedBatchDetails()
        }
        configureDragSource()
        selectionService.addListener {
            ApplicationManager.getApplication().invokeLater {
                refreshHistory()
                if (!preparing) {
                    invalidatePreparedBatch()
                    recalculate()
                }
            }
        }
        externalRegistry.addListener {
            ApplicationManager.getApplication().invokeLater {
                if (!preparing) {
                    invalidatePreparedBatch()
                    recalculate()
                }
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
            ButtonGroup().apply {
                add(batchNavigation)
                add(importNavigation)
                add(previewNavigation)
                add(moreNavigation)
            }
            batchNavigation.addActionListener {
                selectNavigation(batchNavigation)
                detailRequested = false
                updateResponsiveWorkspace()
            }
            importNavigation.addActionListener { showDetails(DetailMode.IMPORT) }
            previewNavigation.addActionListener { showDetails(DetailMode.PREVIEW) }
            moreNavigation.addActionListener {
                detailTabs.selectedIndex = 0
                showDetails(DetailMode.MORE)
            }
            selectNavigation(batchNavigation)
            add(
                JPanel(GridLayout(1, 4, 6, 0)).apply {
                    isOpaque = false
                    listOf(batchNavigation, importNavigation, previewNavigation, moreNavigation).forEach { button ->
                        // GridLayout must be allowed to shrink each button equally in narrow tool windows;
                        // otherwise the final More button is clipped beside the settings/details area.
                        button.minimumSize = Dimension(0, button.minimumSize.height)
                        add(button)
                    }
                },
                BorderLayout.CENTER,
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
            add(
                JPanel(BorderLayout(6, 0)).apply {
                    add(JLabel("Session"), BorderLayout.WEST)
                    sessionCombo.toolTipText = "Switch between saved Copilot conversation sessions"
                    sessionCombo.addActionListener {
                        if (!refreshingSessions) {
                            (sessionCombo.selectedItem as? SessionChoice)?.let { choice ->
                                if (selectionService.switchConversationSession(choice.id)) recalculate()
                            }
                        }
                    }
                    add(sessionCombo, BorderLayout.CENTER)
                    add(JButton("New session").apply { addActionListener { startNewSession() } }, BorderLayout.EAST)
                },
            )
            add(Box.createVerticalStrut(JBUI.scale(6)))
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
            add(createBatchFilesPanel())
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
            add(createKickoffPromptPanel())
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
                    resolver = { paths -> externalResolver().resolve(paths) },
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

    private fun createBatchFilesPanel() =
        JPanel(BorderLayout(0, 5)).apply {
            border =
                BorderFactory.createCompoundBorder(
                    JBUI.Borders.customLine(JBColor.border(), 1),
                    JBUI.Borders.empty(7),
                )
            add(
                JPanel(BorderLayout(7, 0)).apply {
                    isOpaque = false
                    add(JLabel("Show"), BorderLayout.WEST)
                    add(batchFileCategory, BorderLayout.CENTER)
                },
                BorderLayout.NORTH,
            )
            // The selector popup owns the file rows. Keeping the page itself compact means the complete
            // three-step batch flow fits without a second vertical scrollbar.
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(58))
        }

    private fun createKickoffPromptPanel() =
        JPanel(BorderLayout(0, 5)).apply {
            border = JBUI.Borders.emptyTop(5)
            add(
                JPanel(BorderLayout()).apply {
                    isOpaque = false
                    add(JLabel("Prompt for Copilot").apply { font = font.deriveFont(Font.BOLD) }, BorderLayout.WEST)
                    add(
                        JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                            isOpaque = false
                            add(copyPromptButton)
                            add(
                                JButton("Copy return text").apply {
                                    toolTipText = "Copy the effective return-format instructions when Copilot responds incorrectly"
                                    font = font.deriveFont(font.size2D - 1f)
                                    addActionListener { copyReturnInstructions() }
                                },
                            )
                        },
                        BorderLayout.EAST,
                    )
                },
                BorderLayout.NORTH,
            )
            add(
                JBScrollPane(kickoffPrompt).apply { horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER },
                BorderLayout.CENTER,
            )
            add(
                JLabel("00_REPO_CONTEXT.md and file details remain available under Preview.").apply { foreground = JBColor.GRAY },
                BorderLayout.SOUTH,
            )
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(145))
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

    private fun createPreviewPage() =
        JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(8)
            add(
                JPanel(BorderLayout()).apply {
                    add(JLabel("Preview").apply { font = font.deriveFont(Font.BOLD, font.size2D + 2f) }, BorderLayout.WEST)
                    add(
                        JLabel("Complete outgoing context and file allocation").apply { foreground = JBColor.GRAY },
                        BorderLayout.EAST,
                    )
                },
                BorderLayout.NORTH,
            )
            add(
                JSplitPane(
                    JSplitPane.VERTICAL_SPLIT,
                    createContextTextPreviewPanel(),
                    JPanel(BorderLayout()).apply {
                        border = BorderFactory.createTitledBorder(PreviewWorkspaceModel.sections[1])
                        add(contextFilesPanel, BorderLayout.CENTER)
                    },
                ).apply {
                    resizeWeight = 0.52
                    dividerLocation = JBUI.scale(360)
                    dividerSize = JBUI.scale(4)
                    border = null
                    topComponent.minimumSize = Dimension(0, JBUI.scale(180))
                    bottomComponent.minimumSize = Dimension(0, JBUI.scale(180))
                },
                BorderLayout.CENTER,
            )
        }

    private fun createContextTextPreviewPanel() =
        JPanel(BorderLayout(6, 6)).apply {
            border = BorderFactory.createTitledBorder(PreviewWorkspaceModel.sections[0])
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
        JPanel(GridLayout(0, if (components.size >= 4) components.size else 2, 6, 4)).apply {
            border = JBUI.Borders.empty(4, 0)
            components.forEach { component ->
                (component as? JComponent)?.apply {
                    minimumSize = Dimension(0, preferredSize.height)
                    if (components.size >= 4) {
                        font = font.deriveFont((font.size2D - 2f).coerceAtLeast(10f))
                    }
                }
                add(component)
            }
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            alignmentX = LEFT_ALIGNMENT
        }

    private fun updateResponsiveWorkspace() {
        if (!::primaryPanel.isInitialized || !::wideSplit.isInitialized) return
        workspace.removeAll()
        if (width >= JBUI.scale(980)) {
            val minimumPrimaryWidth = JBUI.scale(420)
            val minimumDetailsWidth = JBUI.scale(500)
            val maximumDividerLocation = (width - minimumDetailsWidth).coerceAtLeast(minimumPrimaryWidth)
            if (wideSplit.dividerLocation !in minimumPrimaryWidth..maximumDividerLocation) {
                wideSplit.dividerLocation = JBUI.scale(500).coerceIn(minimumPrimaryWidth, maximumDividerLocation)
            }
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
        when (mode) {
            DetailMode.IMPORT -> selectNavigation(importNavigation)
            DetailMode.PREVIEW -> selectNavigation(previewNavigation)
            DetailMode.MORE -> selectNavigation(moreNavigation)
        }
        detailsLayout.show(detailsHost, mode.name)
        updateResponsiveWorkspace()
    }

    private fun selectNavigation(selected: JToggleButton) {
        listOf(batchNavigation, importNavigation, previewNavigation, moreNavigation).forEach { button ->
            val active = button === selected
            button.isSelected = active
            button.isOpaque = active
            button.background = if (active) JBColor(0xDCEBFF, 0x31547D) else UIManager.getColor("Button.background")
            button.foreground = if (active) JBColor(0x174EA6, 0xFFFFFF) else UIManager.getColor("Button.foreground")
            button.font = button.font.deriveFont(if (active) Font.BOLD else Font.PLAIN)
        }
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
            runCatching { externalResolver().resolve(archives.map { Path.of(it.path) }) }
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

    /** Build from current application and project settings so exclusion edits apply immediately. */
    private fun externalResolver(): ExternalRepositoryDropResolver =
        ExternalRepositoryDropResolver(
            ProjectRoot.path(project),
            AppSettings.getInstance().state.ignorePatterns,
            projectSettings.state.customIgnorePatterns,
            AppSettings.getInstance().state.secretFilenamePatterns + projectSettings.state.projectSecretFilenamePatterns,
            projectSettings.state.textualScanLimitBytes,
            AppSettings.getInstance().state.excludedContextExtensions,
        )

    private fun recalculate() {
        if (!preparing) recalculationTimer.restart()
    }

    private fun runRecalculation() {
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
        // Keep over-limit pinned candidates visible in the Batch view.  They are present in the
        // omitted list with a validation error, but hiding them here would make a user believe
        // that Bridge silently removed an explicit selection.
        pinnedCandidates = BatchFileCategoryModel.pinnedCandidatesForDisplay(result.selection)
        automaticCandidates = result.selection.included.filterNot { it.pinned }
        refreshBatchFileCategories()
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
                                        externalRegistry.excludeForBatch(candidate.sourceKey)
                                    }
                                }
                            }
                        },
                        BorderLayout.EAST,
                    )
                    if (!pinned) {
                        componentPopupMenu =
                            if (candidate.repositoryId.isBlank()) {
                                exclusionMenu(candidate.relativePath)
                            } else {
                                externalExclusionMenu(candidate)
                            }
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

    private fun refreshBatchFileCategories() {
        val invalidCount = selectionService.invalidPinnedPaths().size
        val pinnedCount = pinnedCandidates.size + invalidCount
        val previous = selectedBatchCategory
        selectedBatchCategory = BatchFileCategoryModel.selectedCategory(previous, pinnedCount, automaticCandidates.size)
        val category = selectedBatchCategory ?: BatchFileCategory.PINNED
        refreshingBatchCategory = true
        try {
            batchFileCategory.removeAllItems()
            BatchFileCategoryModel
                .dropdownItems(
                    category,
                    pinnedCandidates,
                    automaticCandidates,
                    selectionService.invalidPinnedPaths(),
                ).forEach { item -> batchFileCategory.addItem(item) }
            batchFileCategory.selectedIndex = 0
            // A category row is rendered as the closed value; the popup includes both category switching
            // and every full repository-relative path in the active category.
            batchFileCategory.toolTipText =
                "Open to inspect all " +
                "${if (selectedBatchCategory == BatchFileCategory.PINNED) pinnedCount else automaticCandidates.size} files; " +
                "each row shows its relationship reason."
        } finally {
            refreshingBatchCategory = false
        }
        renderSelectedBatchCategory()
    }

    private fun renderSelectedBatchCategory() {
        when (selectedBatchCategory) {
            BatchFileCategory.AUTOMATIC -> renderFileList(batchFiles, automaticCandidates, false)
            else -> {
                renderFileList(batchFiles, pinnedCandidates, true)
                renderInvalidPinnedPaths(batchFiles)
            }
        }
    }

    private fun renderInvalidPinnedPaths(panel: JPanel) {
        val invalid = selectionService.invalidPinnedPaths()
        if (invalid.isEmpty()) return
        if (pinnedCandidates.isEmpty()) panel.removeAll()
        invalid.forEach { path ->
            panel.add(
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
        panel.revalidate()
        panel.repaint()
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
        val repository = candidate.displayRepository.ifBlank { pack?.repositoryId.orEmpty() }
        val resolver = candidate.resolverId.ifBlank { if (pinned) "explicit.pinnedFiles" else "not resolved" }
        val policyRule = candidate.policyRuleId.ifBlank { if (pinned) "pinned-files" else "not resolved" }
        return "<html><b>${candidate.relativePath}</b><br>Repository: $repository<br>" +
            "SHA-256: ${candidate.sha256}<br>Priority score: ${candidate.score}<br>Depth: ${candidate.depth}<br>" +
            "Resolver: $resolver<br>Policy rule: $policyRule<br>Prepared attachment: $attachment<br><br>$reasons</html>"
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

    private fun externalExclusionMenu(candidate: ContextCandidate) =
        JPopupMenu().apply {
            add(JMenuItem("Exclude this batch").apply { addActionListener { externalRegistry.excludeForBatch(candidate.sourceKey) } })
            add(JMenuItem("Exclude this session").apply { addActionListener { externalRegistry.excludeForSession(candidate.sourceKey) } })
            add(
                JMenuItem("Always exclude this source…").apply {
                    addActionListener {
                        if (
                            Messages.showYesNoDialog(
                                project,
                                "Always exclude ${candidate.displayRepository}: ${candidate.relativePath} from automatic context?",
                                "Always Exclude External Source",
                                null,
                            ) == Messages.YES
                        ) {
                            externalRegistry.alwaysExclude(candidate.sourceKey)
                        }
                    }
                },
            )
        }

    private fun prepareBatch() {
        if (staged != null || preparing) return
        var current = pack ?: return
        val secretErrors = current.selection.validationErrors.filter { it.contains("requires explicit secret confirmation") }
        val otherErrors = current.selection.validationErrors - secretErrors.toSet()
        if (otherErrors.isNotEmpty()) {
            UiSupport.notify(project, "Cannot prepare batch", otherErrors.joinToString("<br>"), NotificationType.ERROR)
            return
        }
        if (secretErrors.isNotEmpty() &&
            Messages.showYesNoDialog(
                project,
                "Likely secrets were detected:\n\n${secretErrors.joinToString("\n")}\n\nInclude these manually pinned files?",
                "Confirm Sensitive Files",
                null,
            ) != Messages.YES
        ) {
            return
        }
        if (secretErrors.isNotEmpty()) current = current.copy(selection = current.selection.copy(validationErrors = emptyList()))
        preparing = true
        updateControls()
        val context = current
        object : Task.Backgroundable(project, "Preparing Copilot batch", true) {
            override fun run(indicator: ProgressIndicator) {
                val result = runCatching { StagingService(project).stage(context) }
                ApplicationManager.getApplication().invokeLater {
                    preparing = false
                    result
                        .onSuccess {
                            if (pack?.sessionId == context.sessionId) {
                                staged = it
                                dragLabel.text = "Drag these ${it.files.size} files to Copilot"
                                kickoffPrompt.text = currentKickoffPrompt(context)
                                kickoffPrompt.caretPosition = 0
                                status.text = "✓ Safe pack ready • 00_REPO_CONTEXT.md included"
                                refreshHistory()
                                UiSupport.notify(project, "Batch prepared", packingSummary(context))
                            } else {
                                runCatching { StagingService(project).deleteSession(it.directory) }
                                UiSupport.notify(
                                    project,
                                    "Batch changed during preparation",
                                    "The obsolete temporary pack was removed. Prepare the current selection again.",
                                    NotificationType.WARNING,
                                )
                            }
                        }.onFailure {
                            UiSupport.notify(project, "Staging failed", it.message ?: "Unknown error", NotificationType.ERROR)
                        }
                    updateControls()
                }
            }
        }.queue()
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
            context.attachmentPlan.categorySummary().filterNot { it.bundleGroup == "pinned" }.forEach { category ->
                append(
                    "<br>${category.bundleGroup}: ${category.repositoryFileCount} repository files in " +
                        "${category.attachmentCount} attachment(s).",
                )
            }
            if (context.selection.omitted.isNotEmpty()) {
                append("<br>${context.selection.omitted.size} relevant files were omitted; inspect them under Preview → Context files.")
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
        UiSupport.copyText(
            CombinedContextTextBuilder.build(
                AppSettings.getInstance().state.combinedTextIntro,
                result.files,
                kickoffPrompt.text,
            ),
        )
        markCurrentBatchHandedOff()
        UiSupport.notify(project, "Complete pack copied", "Metadata, paths and ${result.files.size} file contents were copied as text.")
    }

    private fun copyKickoffPrompt() {
        if (staged == null || kickoffPrompt.text.isBlank()) return
        UiSupport.copyText(kickoffPrompt.text)
        UiSupport.notify(project, "Copilot prompt copied", "Paste this prompt after uploading the prepared files.")
    }

    private fun currentKickoffPrompt(context: ContextPack): String {
        val skill = AppSettings.getInstance().skill(context.promptSkillId)
        val template =
            projectSettings.state.kickoffPromptTemplateOverride.ifBlank {
                AppSettings.getInstance().state.kickoffPromptTemplate
            }
        return BatchKickoffPromptBuilder.build(
            template,
            context.sessionId,
            selectionService.batchNumber(context.sessionId) ?: selectionService.nextBatchNumber().coerceAtLeast(1),
            skill,
        )
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
        // Switch the project session before clearing the registry's session-scoped external
        // exclusions.  The registry resolves its persisted exclusion set from the active session;
        // clearing it first would erase the old session's exclusions and make them leak or vanish
        // when the user later switches back.
        externalRegistry.startNewSession()
        status.text = "New Copilot session started. Select files for batch 1."
        dragLabel.text = "Drag becomes available after preparation"
        recalculate()
    }

    private fun invalidatePreparedBatch() {
        staged = null
        pack = null
        kickoffPrompt.text = "Prepare the batch to generate the Copilot prompt."
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

    private fun openSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, ProjectSettingsConfigurable::class.java)
        invalidatePreparedBatch()
        recalculate()
    }

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
        refreshSessions()
        val selected = selectedBatchId()
        // History controls operate on the selected conversation only.  Showing batches from a
        // different session here made the session switch appear ineffective and allowed Restore
        // to pull an old conversation's files into the current draft.
        val batches = selectionService.currentSessionBatches().asReversed()
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

    private fun refreshSessions() {
        val active = selectionService.activeConversationSessionId()
        val summaries = selectionService.conversationSessions()
        val ids = (summaries.map { it.id } + active).distinct()
        refreshingSessions = true
        try {
            sessionCombo.removeAllItems()
            ids.forEach { id ->
                val summary = summaries.firstOrNull { it.id == id }
                sessionCombo.addItem(SessionChoice(id, summary?.batchCount ?: 0))
            }
            sessionCombo.selectedIndex = ids.indexOf(active).coerceAtLeast(0)
        } finally {
            refreshingSessions = false
        }
    }

    private data class SessionChoice(
        val id: String,
        val batchCount: Int,
    ) {
        override fun toString(): String = "${id.take(8)} · $batchCount batch${if (batchCount == 1) "" else "es"}"
    }

    private fun showSelectedBatchDetails() {
        val selectedId = selectedBatchId()
        val selected = selectionService.currentSessionBatches().firstOrNull { it.sessionId == selectedId }
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
        copyPromptButton.isEnabled = state.canUsePreparedFiles
        kickoffPrompt.isEnabled = state.canUsePreparedFiles
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
        PREVIEW,
    }
}
