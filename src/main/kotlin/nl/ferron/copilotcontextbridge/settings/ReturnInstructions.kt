package nl.ferron.copilotcontextbridge.settings

/** Mode-specific defaults and deterministic instruction inheritance. */
object ReturnInstructionDefaults {
    fun forMode(mode: CopilotReturnMode): String =
        when (mode) {
            CopilotReturnMode.COPILOT_PATCH_FILE -> PATCH_FILE
            CopilotReturnMode.CODE_TOOL_FILES -> CODE_TOOL_FILES
            CopilotReturnMode.TEXT_ONLY -> TEXT_ONLY
            CopilotReturnMode.DIRECT_REPOSITORY_EDIT -> DIRECT_EDIT
        }

    fun all(): Map<String, String> = CopilotReturnMode.entries.associate { it.name to forMode(it) }

    private val PATCH_FILE =
        """
        Return one versioned Copilot Context Bridge change set as a real downloadable `copilot-result.copilotpatch` JSON file, created with the available code/file tool. If you package the result as ZIP instead, `changes.json` at the ZIP root is mandatory and must describe every included change; never use a loose source-only ZIP as the primary structured response. If file creation is unavailable, return one fenced JSON block and explicitly state that this fallback was necessary.

        Use schema version field `formatVersion` with value `1`. For every changed Python function include `operation`, the original repository-relative `path`, fully `qualifiedName`, exported `originalHash`, and `replacement` containing the complete function source including decorators, signature, type hints, docstring, and full body. Return only changed functions and put all replacements in the same change set. Never use line numbers or partial source as identity.

        When a genuinely new Python file is required, use `operation: add_file`, its repository-relative `path`, and `replacement` containing the complete syntactically valid file. When the user explicitly requested removal of a complete Python file, use `operation: delete_file`, its repository-relative `path`, and the exported exact-file `originalHash`; never propose an unrelated or implicit deletion. Use function operations for changes inside existing files instead of returning whole-file replacements.

        Also include `summary` with: overview, changed paths and qualified functions, reasons, tests actually performed, risks, assumptions, and limitations. Do not claim a validation ran when it did not run.
        """.trimIndent()

    private val CODE_TOOL_FILES =
        """
        Use the available code/file-creation tool to return one real downloadable ZIP, not ordinary chat-only code blocks. A versioned `changes.json` manifest (`schemaVersion`: 1) at the ZIP root is mandatory. It must preserve every original repository-relative path and reference the complete source for each new or changed file. Do not return a loose source-only ZIP. For changed Python functions, record the fully `qualifiedName` and exported `originalHash`; never return a partial function.

        Include `CHANGE_SUMMARY.md` with: overview, changed paths, qualified functions, reasons, tests actually performed, risks, assumptions, and limitations. If the interface cannot create files, state that limitation and use clearly labelled full-source code blocks as a fallback.
        """.trimIndent()

    private val TEXT_ONLY =
        """
        Return the requested result directly in the chat as structured text. Identify referenced or proposed files by their original repository-relative path. When the task requests source code, provide complete source for every new file and complete functions for changed Python behavior; never use partial snippets or “the rest stays the same”.

        End with a concise summary containing: result, paths considered, assumptions, validation actually performed, risks, and limitations. Do not emit a `.copilotpatch` schema unless the user explicitly changes the requested return mode.
        """.trimIndent()

    private val DIRECT_EDIT =
        """
        Apply the requested changes directly to the repository workspace available to GitHub Copilot. Modify only original repository-relative paths that are in scope. Write complete, syntactically valid source and preserve unrelated code. For Python changes, identify the affected fully qualified functions and use the exported original hashes as conflict evidence before overwriting changed local code.

        After editing, report: overview, changed paths and qualified functions, reasons, tests and validation actually performed, risks, assumptions, and limitations. Do not return or invent a `.copilotpatch` attachment for this direct-edit mode.
        """.trimIndent()
}

data class ReturnInstructionIssue(
    val requirement: String,
    val message: String,
)

data class EffectiveReturnInstructions(
    val mode: CopilotReturnMode,
    val globalDefault: String,
    val projectOverride: String?,
    val promptAddition: String,
    val effectiveText: String,
    val issues: List<ReturnInstructionIssue>,
)

object ReturnInstructions {
    fun mode(policy: ContextPolicyState): CopilotReturnMode =
        runCatching { CopilotReturnMode.valueOf(policy.returnMode) }.getOrDefault(CopilotReturnMode.COPILOT_PATCH_FILE)

    fun resolve(
        app: AppSettings.Data,
        project: ProjectSettings.Data,
        skill: AppSettings.PromptSkillState,
        selectedMode: CopilotReturnMode = mode(skill.contextPolicy),
    ): EffectiveReturnInstructions {
        val global = app.returnInstructionsByMode[selectedMode.name].orEmpty().ifBlank { ReturnInstructionDefaults.forMode(selectedMode) }
        val override = project.returnInstructionOverrides[selectedMode.name]?.trim()?.takeIf(String::isNotBlank)
        val addition = skill.returnInstructionsAddition.trim()
        val effective = listOfNotNull(override ?: global, addition.takeIf(String::isNotBlank)).joinToString("\n\n")
        return EffectiveReturnInstructions(selectedMode, global, override, addition, effective, validate(selectedMode, effective))
    }

    fun validate(
        mode: CopilotReturnMode,
        text: String,
    ): List<ReturnInstructionIssue> {
        val value = text.lowercase()
        val requirements =
            when (mode) {
                CopilotReturnMode.COPILOT_PATCH_FILE ->
                    listOf(
                        requirement("schemaVersion", "Mention schema version 1 (`formatVersion` or `schemaVersion`).") {
                            ("formatversion" in it || "schemaversion" in it) &&
                                Regex("(?:format|schema)version.{0,30}1").containsMatchIn(it)
                        },
                        requirement("originalPath", "Require the original repository-relative path for every change.") {
                            "repository-relative" in it && "path" in it
                        },
                        requirement("originalHash", "Require `originalHash` so locally changed functions are not silently overwritten.") {
                            "originalhash" in it
                        },
                        requirement("qualifiedFunction", "Require the fully qualified Python function name (`qualifiedName`).") {
                            "qualifiedname" in it || ("qualified" in it && "function" in it)
                        },
                        requirement("completeSource", "Require complete replacement source, including decorators, signature and body.") {
                            "complete" in it && "decorator" in it && "signature" in it && "body" in it
                        },
                    )
                CopilotReturnMode.CODE_TOOL_FILES ->
                    listOf(
                        requirement("schemaVersion", "Require a versioned file manifest (`schemaVersion`: 1).") {
                            "schemaversion" in it && Regex("schemaversion.{0,30}1").containsMatchIn(it)
                        },
                        requirement("originalPath", "Require original repository-relative paths in the file manifest.") {
                            "repository-relative" in it && "path" in it
                        },
                        requirement(
                            "originalHash",
                            "Require exported `originalHash` for changed Python functions.",
                        ) { "originalhash" in it },
                        requirement("qualifiedFunction", "Require fully qualified Python function names.") {
                            "qualifiedname" in it || ("qualified" in it && "function" in it)
                        },
                        requirement("completeSource", "Require complete file/function source instead of partial snippets.") {
                            "complete source" in it && ("partial" in it || "complete functions" in it)
                        },
                    )
                CopilotReturnMode.TEXT_ONLY ->
                    listOf(
                        requirement("originalPath", "Require original repository-relative paths for referenced output.") {
                            "repository-relative" in it && "path" in it
                        },
                        requirement("completeSource", "For code tasks, require complete source and reject partial snippets.") {
                            "complete source" in it && "partial" in it
                        },
                    )
                CopilotReturnMode.DIRECT_REPOSITORY_EDIT ->
                    listOf(
                        requirement("originalPath", "Limit direct edits to original repository-relative paths.") {
                            "repository-relative" in it && "path" in it
                        },
                        requirement("originalHash", "Use exported original hashes as conflict evidence before overwriting.") {
                            "original hash" in it || "originalhash" in it || "original hashes" in it
                        },
                        requirement("qualifiedFunction", "Report fully qualified functions affected by direct edits.") {
                            "qualified function" in it || "qualified functions" in it
                        },
                        requirement("completeSource", "Require complete, syntactically valid source.") {
                            "complete" in it && "source" in it
                        },
                    )
            }
        return requirements.mapNotNull { it.issue(value) }
    }

    private fun requirement(
        id: String,
        message: String,
        predicate: (String) -> Boolean,
    ) = Requirement(id, message, predicate)

    private data class Requirement(
        val id: String,
        val message: String,
        val predicate: (String) -> Boolean,
    ) {
        fun issue(text: String): ReturnInstructionIssue? = if (predicate(text)) null else ReturnInstructionIssue(id, message)
    }
}
