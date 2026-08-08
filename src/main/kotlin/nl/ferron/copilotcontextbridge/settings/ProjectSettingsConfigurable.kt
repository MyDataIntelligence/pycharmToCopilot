package nl.ferron.copilotcontextbridge.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/** Complete project-scoped configuration for context selection and safe patch application. */
class ProjectSettingsConfigurable(
    private val project: Project,
) : Configurable {
    private val maximum = JSpinner(SpinnerNumberModel(20, 2, 20, 1))
    private val scanLimitKiB = JSpinner(SpinnerNumberModel(2048, 64, 20 * 1024, 64))
    private val automaticFill = JBCheckBox("Fill remaining slots with dependencies")
    private val directImports = JBCheckBox("Include direct imports")
    private val dependents = JBCheckBox("Include direct dependents")
    private val tests = JBCheckBox("Include related tests")
    private val configs = JBCheckBox("Include referenced configuration")
    private val secondLevel = JBCheckBox("Include second-level dependencies")
    private val packages = JBCheckBox("Include complete package folders")
    private val repositoryTree = JBCheckBox("Include filtered repository tree")
    private val mermaid = JBCheckBox("Generate Mermaid dependency map")
    private val absolutePath = JBCheckBox("Include absolute repository path")
    private val previous = JBCheckBox("Avoid files already prepared in earlier batches")
    private val openOnStartup = JBCheckBox("Open the Bridge when a project starts")
    private val detectSecrets = JBCheckBox("Detect likely secrets")
    private val blockSecrets = JBCheckBox("Block likely secrets by default")
    private val reformat = JBCheckBox("Reformat replacement functions when the IDE supports it safely")
    private val optimize = JBCheckBox("Optimize imports after replacement")
    private val undo = JBCheckBox("One Undo operation for all replacements")
    private val customIgnores = JBTextArea(12, 60)
    private val projectSecretPatterns = JBTextArea(10, 60)
    private val kickoffPromptOverride = JBTextArea(12, 70)
    private val scores = JBTextArea(16, 60)
    private val postApplyCommand = JBTextField()

    override fun getDisplayName() = "Copilot Context Bridge (Project)"

    override fun createComponent(): JComponent =
        JBTabbedPane().apply {
            addTab(
                "Context selection",
                scroll(
                    vertical(
                        row("Fallback physical attachment limit", maximum),
                        JLabel("Prompt Context Policy may override this limit; 00_REPO_CONTEXT.md always uses one attachment."),
                        automaticFill,
                        directImports,
                        dependents,
                        tests,
                        configs,
                        secondLevel,
                        packages,
                    ),
                ),
            )
            addTab(
                "Context output",
                scroll(
                    vertical(
                        repositoryTree,
                        mermaid,
                        absolutePath,
                        previous,
                        openOnStartup,
                        row("Maximum text scan size (KiB)", scanLimitKiB),
                    ),
                ),
            )
            addTab(
                "Copilot prompt",
                labeled(
                    "Project kickoff prompt override (leave blank to inherit global; placeholders: {sessionId}, {batchNumber}, {promptSkill})",
                    JBScrollPane(kickoffPromptOverride),
                ),
            )
            addTab(
                "Security & ignores",
                JPanel(BorderLayout(6, 6)).apply {
                    add(vertical(detectSecrets, blockSecrets), BorderLayout.NORTH)
                    add(
                        JPanel(GridLayout(2, 1, 6, 6)).apply {
                            add(labeled("Additional project ignore patterns (one per line)", JBScrollPane(customIgnores)))
                            add(labeled("Additional project secret filename patterns (one per line)", JBScrollPane(projectSecretPatterns)))
                        },
                        BorderLayout.CENTER,
                    )
                },
            )
            addTab(
                "Import changes",
                scroll(
                    vertical(
                        reformat,
                        optimize,
                        undo,
                        row("Optional post-apply command", postApplyCommand),
                        JLabel("The command runs only after at least one function was applied and reports its real exit status."),
                    ),
                ),
            )
            addTab("Dependency scores", labeled("Deterministic score weights (KEY=INTEGER)", JBScrollPane(scores)))
            reset()
        }

    override fun isModified(): Boolean = values() != snapshot(project.getService(ProjectSettings::class.java).state)

    override fun apply() {
        val parsedScores = parseScores(scores.text)
        val kickoffOverride = kickoffPromptOverride.text.trim()
        if (kickoffOverride.isNotBlank()) {
            val kickoffErrors = KickoffPromptTemplateRenderer.validationErrors(kickoffOverride)
            if (kickoffErrors.isNotEmpty()) throw ConfigurationException(kickoffErrors.joinToString("\n"))
        }
        project.getService(ProjectSettings::class.java).state.apply {
            maximumUploadFiles = maximum.value as Int
            automaticallyFillDependencies = automaticFill.isSelected
            includeDirectImports = directImports.isSelected
            includeDirectDependents = dependents.isSelected
            includeRelatedTests = tests.isSelected
            includeReferencedConfiguration = configs.isSelected
            includeSecondLevelDependencies = secondLevel.isSelected
            includePackageFolders = packages.isSelected
            includeRepositoryTree = repositoryTree.isSelected
            generateMermaid = mermaid.isSelected
            includeAbsoluteRepositoryPath = absolutePath.isSelected
            avoidPreviouslySentFiles = previous.isSelected
            openToolWindowOnStartup = openOnStartup.isSelected
            detectLikelySecrets = detectSecrets.isSelected
            blockLikelySecrets = blockSecrets.isSelected
            reformatReplacements = reformat.isSelected
            optimizeImports = optimize.isSelected
            oneUndoOperation = undo.isSelected
            textualScanLimitBytes = (scanLimitKiB.value as Int).toLong() * 1024L
            customIgnorePatterns = lines(customIgnores.text).toMutableList()
            projectSecretFilenamePatterns = lines(projectSecretPatterns.text).toMutableList()
            kickoffPromptTemplateOverride = kickoffOverride
            postApplyCommand = this@ProjectSettingsConfigurable.postApplyCommand.text.trim()
            this.scores = parsedScores.toMutableMap()
        }
    }

    override fun reset() {
        val state = project.getService(ProjectSettings::class.java).state
        maximum.value = state.maximumUploadFiles
        automaticFill.isSelected = state.automaticallyFillDependencies
        directImports.isSelected = state.includeDirectImports
        dependents.isSelected = state.includeDirectDependents
        tests.isSelected = state.includeRelatedTests
        configs.isSelected = state.includeReferencedConfiguration
        secondLevel.isSelected = state.includeSecondLevelDependencies
        packages.isSelected = state.includePackageFolders
        repositoryTree.isSelected = state.includeRepositoryTree
        mermaid.isSelected = state.generateMermaid
        absolutePath.isSelected = state.includeAbsoluteRepositoryPath
        previous.isSelected = state.avoidPreviouslySentFiles
        openOnStartup.isSelected = state.openToolWindowOnStartup
        detectSecrets.isSelected = state.detectLikelySecrets
        blockSecrets.isSelected = state.blockLikelySecrets
        reformat.isSelected = state.reformatReplacements
        optimize.isSelected = state.optimizeImports
        undo.isSelected = state.oneUndoOperation
        scanLimitKiB.value = (state.textualScanLimitBytes / 1024L).toInt().coerceIn(64, 20 * 1024)
        customIgnores.text = state.customIgnorePatterns.joinToString("\n")
        projectSecretPatterns.text = state.projectSecretFilenamePatterns.joinToString("\n")
        kickoffPromptOverride.text = state.kickoffPromptTemplateOverride
        postApplyCommand.text = state.postApplyCommand
        scores.text = state.scores.entries.joinToString("\n") { "${it.key}=${it.value}" }
    }

    private fun values(): List<Any> =
        listOf(
            maximum.value,
            automaticFill.isSelected,
            directImports.isSelected,
            dependents.isSelected,
            tests.isSelected,
            configs.isSelected,
            secondLevel.isSelected,
            packages.isSelected,
            repositoryTree.isSelected,
            mermaid.isSelected,
            absolutePath.isSelected,
            previous.isSelected,
            openOnStartup.isSelected,
            detectSecrets.isSelected,
            blockSecrets.isSelected,
            reformat.isSelected,
            optimize.isSelected,
            undo.isSelected,
            scanLimitKiB.value,
            lines(customIgnores.text),
            lines(projectSecretPatterns.text),
            kickoffPromptOverride.text.trim(),
            postApplyCommand.text.trim(),
            runCatching { parseScores(scores.text) }.getOrDefault(emptyMap()),
        )

    private fun snapshot(state: ProjectSettings.Data): List<Any> =
        listOf(
            state.maximumUploadFiles,
            state.automaticallyFillDependencies,
            state.includeDirectImports,
            state.includeDirectDependents,
            state.includeRelatedTests,
            state.includeReferencedConfiguration,
            state.includeSecondLevelDependencies,
            state.includePackageFolders,
            state.includeRepositoryTree,
            state.generateMermaid,
            state.includeAbsoluteRepositoryPath,
            state.avoidPreviouslySentFiles,
            state.openToolWindowOnStartup,
            state.detectLikelySecrets,
            state.blockLikelySecrets,
            state.reformatReplacements,
            state.optimizeImports,
            state.oneUndoOperation,
            (state.textualScanLimitBytes / 1024L).toInt(),
            state.customIgnorePatterns.toList(),
            state.projectSecretFilenamePatterns.toList(),
            state.kickoffPromptTemplateOverride,
            state.postApplyCommand,
            state.scores.toMap(),
        )

    private fun parseScores(text: String): Map<String, Int> {
        val parsed = linkedMapOf<String, Int>()
        lines(text).forEachIndexed { index, line ->
            val parts = line.split('=', limit = 2)
            if (parts.size != 2 || parts[0].isBlank()) throw ConfigurationException("Invalid score on line ${index + 1}: use KEY=INTEGER")
            val value = parts[1].trim().toIntOrNull() ?: throw ConfigurationException("Invalid integer on score line ${index + 1}")
            parsed[parts[0].trim()] = value
        }
        val missing = ProjectSettings.defaultScores().keys - parsed.keys
        if (missing.isNotEmpty()) throw ConfigurationException("Missing dependency score keys: ${missing.joinToString()}")
        return parsed
    }

    private fun lines(value: String): List<String> =
        value
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toList()

    private fun row(
        label: String,
        component: JComponent,
    ) = JPanel(BorderLayout(8, 0)).apply {
        add(JLabel(label), BorderLayout.WEST)
        add(component, BorderLayout.CENTER)
    }

    private fun vertical(vararg components: JComponent) =
        JPanel(GridLayout(0, 1, 5, 5)).apply {
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
            components.forEach(::add)
        }

    private fun labeled(
        title: String,
        component: JComponent,
    ) = JPanel(BorderLayout(5, 5)).apply {
        border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        add(JLabel(title), BorderLayout.NORTH)
        add(component, BorderLayout.CENTER)
    }

    private fun scroll(component: JComponent) = JBScrollPane(component).apply { border = null }
}
