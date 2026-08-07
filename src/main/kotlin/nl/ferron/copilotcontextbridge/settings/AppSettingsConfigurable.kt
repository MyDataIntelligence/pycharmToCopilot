package nl.ferron.copilotcontextbridge.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.GridLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class AppSettingsConfigurable : Configurable {
    private val question = JBTextField()
    private val guidelines = JBTextArea(22, 80)
    private val returnInstruction = JBTextArea(10, 80)
    private val combinedTextIntro = JBTextArea(8, 80)
    private val ignorePatterns = JBTextArea(18, 70)
    private val secretPatterns = JBTextArea(18, 70)
    private val retention = JSpinner(SpinnerNumberModel(7, 1, 365, 1))

    override fun getDisplayName() = "Copilot Context Bridge"

    override fun createComponent(): JComponent =
        JBTabbedPane().apply {
            addTab(
                "General",
                JPanel(GridLayout(2, 2, 8, 8)).apply {
                    add(JLabel("Mandatory first-response question"))
                    add(question)
                    add(JLabel("Staging retention days"))
                    add(retention)
                },
            )
            addTab(
                "Copilot output",
                JPanel(GridLayout(2, 1, 8, 8)).apply {
                    add(editor("Return-file instruction", returnInstruction))
                    add(editor("Complete-pack text introduction", combinedTextIntro))
                },
            )
            addTab("Global guidelines", editor("Global guidelines (Markdown)", guidelines))
            addTab("Repository ignores", editor("Global ignore patterns (one per line)", ignorePatterns))
            addTab("Secret filenames", editor("Likely-secret filename patterns (one per line)", secretPatterns))
            reset()
        }

    override fun isModified(): Boolean {
        val state = AppSettings.getInstance().state
        return question.text != state.mandatoryFirstQuestion ||
            guidelines.text != state.globalGuidelines ||
            returnInstruction.text != state.returnFileInstruction ||
            combinedTextIntro.text != state.combinedTextIntro ||
            lines(ignorePatterns.text) != state.ignorePatterns ||
            lines(secretPatterns.text) != state.secretFilenamePatterns ||
            retention.value != state.stagingRetentionDays
    }

    override fun apply() {
        if (question.text.isBlank()) throw ConfigurationException("Mandatory question cannot be empty.")
        if (returnInstruction.text.isBlank()) throw ConfigurationException("Return-file instruction cannot be empty.")
        val ignores = lines(ignorePatterns.text)
        val secrets = lines(secretPatterns.text)
        if (ignores.isEmpty()) throw ConfigurationException("At least one repository ignore pattern is required.")
        if (secrets.isEmpty()) throw ConfigurationException("At least one likely-secret filename pattern is required.")
        AppSettings.getInstance().state.apply {
            mandatoryFirstQuestion = question.text.trim()
            globalGuidelines = guidelines.text
            returnFileInstruction = returnInstruction.text.trim()
            returnInstructionsByMode[CopilotReturnMode.COPILOT_PATCH_FILE.name] = returnInstruction.text.trim()
            combinedTextIntro = this@AppSettingsConfigurable.combinedTextIntro.text.trim()
            ignorePatterns = ignores.toMutableList()
            secretFilenamePatterns = secrets.toMutableList()
            stagingRetentionDays = retention.value as Int
        }
    }

    override fun reset() {
        val state = AppSettings.getInstance().state
        question.text = state.mandatoryFirstQuestion
        guidelines.text = state.globalGuidelines
        returnInstruction.text =
            state.returnInstructionsByMode[CopilotReturnMode.COPILOT_PATCH_FILE.name]
                ?: state.returnFileInstruction
        combinedTextIntro.text = state.combinedTextIntro
        ignorePatterns.text = state.ignorePatterns.joinToString("\n")
        secretPatterns.text = state.secretFilenamePatterns.joinToString("\n")
        retention.value = state.stagingRetentionDays
    }

    private fun lines(value: String): List<String> =
        value
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toList()

    private fun editor(
        title: String,
        area: JBTextArea,
    ) = JPanel(BorderLayout()).apply {
        add(JLabel(title), BorderLayout.NORTH)
        add(JBScrollPane(area), BorderLayout.CENTER)
    }
}
