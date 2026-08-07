package nl.ferron.copilotcontextbridge.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(name = "CopilotContextBridgeApplication", storages = [Storage("copilot-context-bridge.xml")])
class AppSettings : PersistentStateComponent<AppSettings.Data> {
    class PromptSkillState {
        @JvmField var id: String = ""

        @JvmField var name: String = ""

        @JvmField var description: String = ""

        @JvmField var prompt: String = ""

        @JvmField var guidelines: String = ""

        constructor()

        constructor(id: String, name: String, description: String, prompt: String, guidelines: String = "") {
            this.id = id
            this.name = name
            this.description = description
            this.prompt = prompt
            this.guidelines = guidelines
        }
    }

    class Data {
        @JvmField var mandatoryFirstQuestion: String = Defaults.FIRST_QUESTION

        @JvmField var globalGuidelines: String = Defaults.globalGuidelines

        @JvmField var ignorePatterns: MutableList<String> = Defaults.ignorePatterns.toMutableList()

        @JvmField var secretFilenamePatterns: MutableList<String> = Defaults.secretPatterns.toMutableList()

        @JvmField var stagingRetentionDays: Int = 7

        @JvmField var returnFileInstruction: String = Defaults.RETURN_FILE_INSTRUCTION

        @JvmField var combinedTextIntro: String = Defaults.COMBINED_TEXT_INTRO

        @JvmField var promptSkills: MutableList<PromptSkillState> = defaultPromptSkills().toMutableList()
    }

    private var data = Data()

    override fun getState(): Data = data

    override fun loadState(state: Data) {
        data = state
        if (data.promptSkills.isEmpty()) data.promptSkills.addAll(defaultPromptSkills())
        DevelopmentPromptLibrary.skills().forEach { builtIn ->
            if (data.promptSkills.none { it.id == builtIn.id }) data.promptSkills.add(builtIn)
        }
        val prioritizedDevelopmentSkills =
            DevelopmentPromptLibrary.skills().mapNotNull { builtIn -> data.promptSkills.firstOrNull { it.id == builtIn.id } }
        data.promptSkills.removeAll { candidate -> prioritizedDevelopmentSkills.any { it.id == candidate.id } }
        data.promptSkills.addAll(minOf(1, data.promptSkills.size), prioritizedDevelopmentSkills)
        val review = data.promptSkills.firstOrNull { it.id == RepositoryReviewPrompt.ID } ?: RepositoryReviewPrompt.skill()
        data.promptSkills.removeAll { it.id == RepositoryReviewPrompt.ID }
        data.promptSkills.add(minOf(1 + prioritizedDevelopmentSkills.size, data.promptSkills.size), review)
        RepositoryReviewPrompt.upgradeLegacy(data.promptSkills)
        val refactor = data.promptSkills.firstOrNull { it.id == RefactorPrompt.ID } ?: RefactorPrompt.skill()
        data.promptSkills.removeAll { it.id == RefactorPrompt.ID }
        val reviewIndex = data.promptSkills.indexOfFirst { it.id == RepositoryReviewPrompt.ID }
        data.promptSkills.add(if (reviewIndex >= 0) reviewIndex + 1 else minOf(5, data.promptSkills.size), refactor)
        CreatorPromptLibrary.skills().forEach { builtIn ->
            if (data.promptSkills.none { it.id == builtIn.id }) data.promptSkills.add(builtIn)
        }
        if (data.returnFileInstruction.isBlank()) data.returnFileInstruction = Defaults.RETURN_FILE_INSTRUCTION
        if (data.combinedTextIntro.isBlank()) data.combinedTextIntro = Defaults.COMBINED_TEXT_INTRO
        if (data.ignorePatterns.isEmpty()) data.ignorePatterns.addAll(Defaults.ignorePatterns)
        if (data.secretFilenamePatterns.isEmpty()) data.secretFilenamePatterns.addAll(Defaults.secretPatterns)
        data.stagingRetentionDays = data.stagingRetentionDays.coerceIn(1, 365)
    }

    fun resetGuidelines() {
        data.globalGuidelines = Defaults.globalGuidelines
    }

    fun skill(id: String): PromptSkillState = data.promptSkills.firstOrNull { it.id == id } ?: data.promptSkills.first()

    companion object {
        fun getInstance(): AppSettings = ApplicationManager.getApplication().getService(AppSettings::class.java)

        fun defaultPromptSkills(): List<PromptSkillState> =
            listOf(
                PromptSkillState(
                    "general-change",
                    "General change",
                    "Make a focused code change after clarifying the goal.",
                    "After the user explains the goal, make the smallest safe change and return only modified functions in the required patch format.",
                    "Preserve unrelated behavior and follow repository conventions. Validate callers, tests and configuration before changing public behavior.",
                ),
            ) +
                DevelopmentPromptLibrary.skills() +
                listOf(
                    RepositoryReviewPrompt.skill(),
                    RefactorPrompt.skill(),
                    PromptSkillState(
                        "create-documentation",
                        "Create documentation",
                        "Create or improve repository documentation from the supplied context.",
                        "After the user explains the intended audience and documentation goal, create accurate documentation grounded only in supplied files. Clearly mark information that requires an omitted file. Return function patches only when Python functions were actually changed; otherwise return the requested Markdown content with repository-relative destination paths.",
                        "Use clear headings, concrete examples and repository-relative links. Do not document behavior that cannot be confirmed from supplied files. Preserve the repository's terminology and documentation style.",
                    ),
                    PromptSkillState(
                        "write-tests",
                        "Write tests",
                        "Add focused regression coverage.",
                        "After clarification, design and implement focused tests for the requested behavior. Reuse existing test conventions and mock external boundaries.",
                        "Cover happy path, failure path, boundary cases and regressions without depending on live external services.",
                    ),
                    PromptSkillState(
                        "explain-architecture",
                        "Explain architecture",
                        "Explain structure and data flow from the supplied repository context.",
                        "After clarification, explain the architecture, ownership boundaries and data flow using only supplied content. Distinguish confirmed facts from inferences.",
                        "Prefer concise diagrams and repository-relative references. Explicitly identify omitted dependencies that limit certainty.",
                    ),
                ) + CreatorPromptLibrary.skills()
    }
}
