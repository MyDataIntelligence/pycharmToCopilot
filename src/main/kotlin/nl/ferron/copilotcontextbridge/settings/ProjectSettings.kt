package nl.ferron.copilotcontextbridge.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(name = "CopilotContextBridgeProject", storages = [Storage(".idea/copilot-context-bridge.xml")])
class ProjectSettings : PersistentStateComponent<ProjectSettings.Data> {
    class Data {
        @JvmField var maximumUploadFiles: Int = 20

        @JvmField var automaticallyFillDependencies: Boolean = true

        @JvmField var includeDirectImports: Boolean = true

        @JvmField var includeDirectDependents: Boolean = true

        @JvmField var includeRelatedTests: Boolean = true

        @JvmField var includeReferencedConfiguration: Boolean = true

        @JvmField var includeSecondLevelDependencies: Boolean = false

        @JvmField var includePackageFolders: Boolean = false

        @JvmField var generateMermaid: Boolean = true

        @JvmField var includeAbsoluteRepositoryPath: Boolean = false

        @JvmField var detectLikelySecrets: Boolean = true

        @JvmField var blockLikelySecrets: Boolean = true

        @JvmField var reformatReplacements: Boolean = true

        @JvmField var optimizeImports: Boolean = false

        @JvmField var oneUndoOperation: Boolean = true

        @JvmField var textualScanLimitBytes: Long = 2L * 1024L * 1024L

        @JvmField var selectedPromptSkillId: String = "general-change"

        @JvmField var includeRepositoryTree: Boolean = true

        @JvmField var avoidPreviouslySentFiles: Boolean = true

        /** Legacy persisted option. Preparing a batch never clears pins; Next batch is the explicit clearing action. */
        @JvmField var clearActiveSelectionAfterExport: Boolean = false

        @JvmField var openToolWindowOnStartup: Boolean = true

        @JvmField var enabledGuidelineSources: MutableList<String> = mutableListOf()

        @JvmField var guidelineSelectionConfigured: Boolean = false

        @JvmField var customIgnorePatterns: MutableList<String> = mutableListOf()

        @JvmField var projectSecretFilenamePatterns: MutableList<String> = mutableListOf()

        /** Blank inherits the editable application-level kickoff prompt template. */
        @JvmField var kickoffPromptTemplateOverride: String = ""

        @JvmField var externalAlwaysExcludedSourceKeys: MutableList<String> = mutableListOf()

        @JvmField var postApplyCommand: String = ""

        /** Blank or absent values inherit the application-level default for that return mode. */
        @JvmField var returnInstructionOverrides: MutableMap<String, String> = mutableMapOf()

        @JvmField var scores: MutableMap<String, Int> = defaultScores().toMutableMap()
    }

    private var data = Data()

    override fun getState(): Data = data

    override fun loadState(state: Data) {
        data = state
        defaultScores().forEach { (key, value) -> data.scores.putIfAbsent(key, value) }
        data.maximumUploadFiles = data.maximumUploadFiles.coerceIn(2, 20)
        data.textualScanLimitBytes = data.textualScanLimitBytes.coerceIn(64 * 1024L, 20L * 1024L * 1024L)
        data.clearActiveSelectionAfterExport = false
    }

    companion object {
        fun defaultScores(): Map<String, Int> =
            linkedMapOf(
                "PINNED" to 1000,
                "DIRECT_IMPORT" to 800,
                "DIRECT_CALLEE" to 775,
                "DIRECT_DEPENDENT" to 700,
                "RELATED_TEST" to 650,
                "NEARBY_TEST" to 600,
                "REFERENCED_CONFIGURATION" to 550,
                "PACKAGE_INIT" to 450,
                "PROJECT_CONFIGURATION" to 400,
                "SECOND_LEVEL" to 300,
                "SAME_PACKAGE" to 200,
                "TEXT_REFERENCE" to 100,
                "GENERATED_PENALTY" to -500,
                "EXCLUDED_PENALTY" to -1000,
            )
    }
}
