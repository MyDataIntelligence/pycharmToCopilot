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

        @JvmField var category: String = "Code"

        @JvmField var prompt: String = ""

        @JvmField var guidelines: String = ""

        @JvmField var returnInstructionsAddition: String = ""

        @JvmField var contextPolicy: ContextPolicyState = ContextPolicyState.defaultFor("general-change")

        constructor()

        constructor(
            id: String,
            name: String,
            description: String,
            prompt: String,
            guidelines: String = "",
            contextPolicy: ContextPolicyState = ContextPolicyState.defaultFor(id),
            category: String = "Code",
        ) {
            this.id = id
            this.name = name
            this.description = description
            this.prompt = prompt
            this.guidelines = guidelines
            this.contextPolicy = contextPolicy
            this.category = category
        }
    }

    class Data {
        @JvmField var mandatoryFirstQuestion: String = Defaults.FIRST_QUESTION

        @JvmField var globalGuidelines: String = Defaults.globalGuidelines

        @JvmField var ignorePatterns: MutableList<String> = Defaults.ignorePatterns.toMutableList()

        @JvmField var secretFilenamePatterns: MutableList<String> = Defaults.secretPatterns.toMutableList()

        @JvmField var stagingRetentionDays: Int = 7

        @JvmField var returnFileInstruction: String = Defaults.RETURN_FILE_INSTRUCTION

        @JvmField var returnInstructionsByMode: MutableMap<String, String> = ReturnInstructionDefaults.all().toMutableMap()

        @JvmField var combinedTextIntro: String = Defaults.COMBINED_TEXT_INTRO

        @JvmField var kickoffPromptTemplate: String = Defaults.KICKOFF_PROMPT_TEMPLATE

        @JvmField var promptSkills: MutableList<PromptSkillState> = defaultPromptSkills().toMutableList()
    }

    private var data = Data()

    override fun getState(): Data = data

    override fun loadState(state: Data) {
        data = state
        val builtIns = defaultPromptSkills()
        builtIns.forEach { builtIn ->
            if (data.promptSkills.none { it.id == builtIn.id }) data.promptSkills.add(builtIn)
        }
        val builtInById = builtIns.associateBy { it.id }
        data.promptSkills.forEach { skill ->
            if (skill.contextPolicy.id.isBlank() || (skill.contextPolicy.id == "general-change-policy" && skill.id != "general-change")) {
                skill.contextPolicy = builtInById[skill.id]?.contextPolicy?.copyOf() ?: ContextPolicyState.defaultFor(skill.id)
            }
            if (skill.contextPolicy.rules.isEmpty()) skill.contextPolicy.rules = ContextPolicyState.defaultRules().toMutableList()
            val expectedCategory = builtInById[skill.id]?.category
            if (skill.category.isBlank() || (skill.category == "Code" && expectedCategory != null && expectedCategory != "Code")) {
                skill.category = expectedCategory ?: "Code"
            }
        }
        ReturnInstructionDefaults.all().forEach { (mode, defaultText) -> data.returnInstructionsByMode.putIfAbsent(mode, defaultText) }
        if (
            data.returnFileInstruction.isNotBlank() &&
            data.returnFileInstruction != Defaults.RETURN_FILE_INSTRUCTION &&
            data.returnInstructionsByMode[CopilotReturnMode.COPILOT_PATCH_FILE.name] ==
            ReturnInstructionDefaults.forMode(
                CopilotReturnMode.COPILOT_PATCH_FILE,
            )
        ) {
            data.returnInstructionsByMode[CopilotReturnMode.COPILOT_PATCH_FILE.name] = data.returnFileInstruction
        }
        data.promptSkills.firstOrNull { it.id == DevelopmentPromptLibrary.FIX_ISSUE_ID && it.name == "Fix issue" }?.name =
            "Debug problem"
        data.promptSkills.firstOrNull { it.id == "write-tests" && it.name == "Write tests" }?.name = "Generate tests"
        data.promptSkills.firstOrNull { it.id == RepositoryReviewPrompt.ID && it.name == "Review selected code" }?.name =
            "Review code"
        RepositoryReviewPrompt.upgradeLegacy(data.promptSkills)
        val orderedBuiltIns = builtIns.mapNotNull { builtIn -> data.promptSkills.firstOrNull { it.id == builtIn.id } }
        val customSkills = data.promptSkills.filter { it.id !in builtInById }
        data.promptSkills = (orderedBuiltIns + customSkills).toMutableList()
        if (data.returnFileInstruction.isBlank()) data.returnFileInstruction = Defaults.RETURN_FILE_INSTRUCTION
        if (data.combinedTextIntro.isBlank()) data.combinedTextIntro = Defaults.COMBINED_TEXT_INTRO
        if (KickoffPromptTemplateRenderer.validationErrors(data.kickoffPromptTemplate).isNotEmpty()) {
            data.kickoffPromptTemplate = Defaults.KICKOFF_PROMPT_TEMPLATE
        }
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

        fun defaultPolicyForPrompt(promptId: String): ContextPolicyState =
            defaultPromptSkills().firstOrNull { it.id == promptId }?.contextPolicy?.copyOf()
                ?: ContextPolicyState.defaultFor(promptId)

        fun defaultPromptSkills(): List<PromptSkillState> =
            buildList {
                add(
                    PromptSkillState(
                        "general-change",
                        "General change",
                        "Make a focused code change after clarifying the goal.",
                        """After the user explains the goal, make the smallest safe change and return only modified functions in the required patch format.

${Defaults.PYTHON_AUTHORING_RULES}""",
                        """Preserve unrelated behavior and follow repository conventions. Validate callers, tests and configuration before changing public behavior.

${Defaults.PYTHON_AUTHORING_RULES}""",
                    ),
                )
                add(DevelopmentPromptLibrary.skills().first { it.id == DevelopmentPromptLibrary.FIX_ISSUE_ID })
                add(
                    PromptSkillState(
                        "write-tests",
                        "Generate tests",
                        "Add focused regression coverage.",
                        """After clarification, design and implement focused tests for the requested behavior. Reuse existing test conventions and mock external boundaries.

${Defaults.PYTHON_AUTHORING_RULES}""",
                        """Cover happy path, failure path, boundary cases and regressions without depending on live external services.

${Defaults.PYTHON_AUTHORING_RULES}""",
                    ),
                )
                add(RepositoryReviewPrompt.skill())

                // Existing secondary Code workflows remain available after the four primary entries.
                add(RefactorPrompt.skill())
                add(DevelopmentPromptLibrary.skills().first { it.id == DevelopmentPromptLibrary.NEW_CODE_ID })
                add(
                    PromptSkillState(
                        "create-documentation",
                        "Create documentation",
                        "Create or improve repository documentation from the supplied context.",
                        "After the user explains the intended audience and documentation goal, create accurate documentation grounded only in supplied files. Clearly mark information that requires an omitted file. Return function patches only when Python functions were actually changed; otherwise return the requested Markdown content with repository-relative destination paths.",
                        "Use clear headings, concrete examples and repository-relative links. Do not document behavior that cannot be confirmed from supplied files. Preserve the repository's terminology and documentation style.",
                    ),
                )
                add(
                    PromptSkillState(
                        "explain-architecture",
                        "Explain architecture",
                        "Explain structure and data flow from the supplied repository context.",
                        "After clarification, explain the architecture, ownership boundaries and data flow using only supplied content. Distinguish confirmed facts from inferences.",
                        "Prefer concise diagrams and repository-relative references. Explicitly identify omitted dependencies that limit certainty.",
                    ),
                )

                add(DeltaPromptLibrary.skills().first { it.id == DeltaPromptLibrary.PREPARE_PR_ID })
                add(DeltaPromptLibrary.skills().first { it.id == DeltaPromptLibrary.ANALYZE_STORY_ID })
                add(DevelopmentPromptLibrary.skills().first { it.id == DevelopmentPromptLibrary.USER_STORY_ID })
                addAll(CreatorPromptLibrary.skills())
            }
    }
}
