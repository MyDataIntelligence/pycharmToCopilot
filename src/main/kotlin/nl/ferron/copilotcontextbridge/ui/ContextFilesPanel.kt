package nl.ferron.copilotcontextbridge.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import nl.ferron.copilotcontextbridge.external.ExternalRepositoryDropResolver
import nl.ferron.copilotcontextbridge.external.ExternalRepositorySelectionRegistry
import nl.ferron.copilotcontextbridge.model.ContextCandidate
import nl.ferron.copilotcontextbridge.model.ContextPack
import nl.ferron.copilotcontextbridge.model.displayRepository
import nl.ferron.copilotcontextbridge.model.sourceKey
import nl.ferron.copilotcontextbridge.settings.AppSettings
import nl.ferron.copilotcontextbridge.state.ContextSelectionService
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

/** Exact inspectable view of represented, omitted, and user-excluded repository files. */
class ContextFilesPanel(
    private val project: Project,
) : JPanel(BorderLayout()) {
    private val selection = project.getService(ContextSelectionService::class.java)
    private val externalSelection = project.getService(ExternalRepositorySelectionRegistry::class.java)
    private val included = rowsPanel()
    private val omitted = rowsPanel()
    private val excluded = rowsPanel()
    private val tabs = JBTabbedPane()

    init {
        border = JBUI.Borders.empty(8)
        tabs.addTab("Included", JBScrollPane(included))
        tabs.addTab("Omitted", JBScrollPane(omitted))
        tabs.addTab("Excluded", JBScrollPane(excluded))
        add(
            JBLabel("Every represented repository file and its prepared Copilot attachment.").apply {
                foreground = JBColor.GRAY
                border = JBUI.Borders.emptyBottom(6)
            },
            BorderLayout.NORTH,
        )
        add(tabs, BorderLayout.CENTER)
    }

    fun show(pack: ContextPack) {
        replaceRows(
            included,
            pack.selection.included,
        ) { candidate ->
            val attachment = pack.attachmentPlan.repositoryToAttachment[candidate.sourceKey] ?: "not packed"
            candidateRow(
                candidate,
                "${ContextSelectionLabels.category(candidate)} · ${ContextSelectionLabels.detail(candidate)} · attachment: $attachment",
            )
        }
        replaceRows(
            omitted,
            pack.selection.omitted,
        ) { candidate ->
            val maximumRepositoryFiles =
                AppSettings
                    .getInstance()
                    .skill(pack.promptSkillId)
                    .contextPolicy.maxRepositoryFiles
            val displaced =
                pack.selection.included
                    .filterNot { it.pinned }
                    .minWithOrNull(compareBy<ContextCandidate> { it.score }.thenByDescending { it.relativePath })
                    ?.takeIf { pack.selection.included.size >= maximumRepositoryFiles }
            candidateRow(
                candidate,
                "Considered as ${primaryReason(candidate)} · omitted: " +
                    (candidate.ignoredReason ?: "lower priority than included context"),
                JButton("Pin").apply {
                    toolTipText = "Guarantee inclusion; lower-priority automatic context may be displaced"
                    addActionListener {
                        if (candidate.repositoryId.isBlank()) {
                            selection.addRelativePaths(listOf(candidate.relativePath))
                        } else {
                            val repository =
                                ExternalRepositoryDropResolver.Repository(
                                    candidate.repositoryId,
                                    candidate.displayRepository,
                                    candidate.repositoryRoot ?: candidate.absolutePath.parent,
                                    false,
                                )
                            externalSelection.registerConfirmed(
                                listOf(
                                    ExternalRepositoryDropResolver.Source(
                                        repository,
                                        candidate.relativePath,
                                        candidate.absolutePath,
                                        ExternalRepositoryDropResolver.Kind.PINNED_FILE,
                                        candidate.secretWarning,
                                    ),
                                ),
                            )
                        }
                        displaced?.let {
                            UiSupport.notify(
                                project,
                                "Pinned file added",
                                "${candidate.relativePath} is guaranteed; lower-priority automatic file ${it.relativePath} is displaced.",
                            )
                        }
                    }
                },
            )
        }
        replaceRows(
            excluded,
            pack.selection.excluded,
        ) { candidate ->
            val scope =
                if (candidate.repositoryId.isNotBlank()) {
                    externalSelection.exclusionScope(candidate.sourceKey)
                } else {
                    when (candidate.relativePath) {
                        in selection.alwaysExcludedPaths() -> "project"
                        in selection.sessionExcludedPaths() -> "session"
                        else -> "batch"
                    }
                }
            candidateRow(
                candidate,
                "Excluded for this $scope · originally considered as ${primaryReason(candidate)}",
                JButton("Include once").apply {
                    addActionListener {
                        if (candidate.repositoryId.isNotBlank()) {
                            externalSelection.includeOnce(candidate.sourceKey)
                        } else {
                            selection.includeOnce(candidate.relativePath)
                        }
                    }
                },
                JButton("Remove exclusion").apply {
                    addActionListener {
                        if (candidate.repositoryId.isNotBlank()) {
                            externalSelection.removeExclusion(candidate.sourceKey)
                        } else {
                            selection.removePermanentExclusion(candidate.relativePath)
                        }
                    }
                },
            )
        }
        tabs.setTitleAt(0, "Included (${pack.selection.included.size})")
        tabs.setTitleAt(1, "Omitted (${pack.selection.omitted.size})")
        tabs.setTitleAt(2, "Excluded (${pack.selection.excluded.size})")
    }

    private fun candidateRow(
        candidate: ContextCandidate,
        detail: String,
        vararg actions: JButton,
    ) = JPanel(BorderLayout(8, 3)).apply {
        border =
            BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                JBUI.Borders.empty(7, 5),
            )
        add(
            JPanel(BorderLayout(0, 2)).apply {
                isOpaque = false
                add(
                    JLabel(
                        if (candidate.repositoryId.isBlank()) {
                            candidate.relativePath
                        } else {
                            "${candidate.displayRepository}: ${candidate.relativePath}"
                        },
                    ),
                    BorderLayout.NORTH,
                )
                add(
                    JLabel(
                        "<html><font color='#888888'>$detail · priority ${candidate.score} · depth ${candidate.depth}<br>" +
                            "resolver ${candidate.resolverId.ifBlank { "n/a" }} · " +
                            "policy ${candidate.policyRuleId.ifBlank { "n/a" }}<br>${candidate.sha256}</font></html>",
                    ),
                    BorderLayout.CENTER,
                )
            },
            BorderLayout.CENTER,
        )
        if (actions.isNotEmpty()) {
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { actions.forEach(::add) }, BorderLayout.EAST)
        }
    }

    private fun replaceRows(
        panel: JPanel,
        candidates: List<ContextCandidate>,
        row: (ContextCandidate) -> JPanel,
    ) {
        panel.removeAll()
        if (candidates.isEmpty()) {
            panel.add(JLabel("No files in this category.").apply { foreground = JBColor.GRAY })
        } else {
            candidates.forEach { panel.add(row(it)) }
        }
        panel.revalidate()
        panel.repaint()
    }

    private fun primaryReason(candidate: ContextCandidate): String =
        candidate.relations
            .firstOrNull()
            ?.type
            ?.name
            ?.lowercase()
            ?.replace('_', ' ') ?: "repository relation"

    private fun rowsPanel() = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
}
