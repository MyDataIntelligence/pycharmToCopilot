package nl.ferron.copilotcontextbridge.settings

import junit.framework.TestCase

class ReturnInstructionsTest : TestCase() {
    fun testEveryBuiltInModePassesItsOwnSafetyContract() {
        CopilotReturnMode.entries.forEach { mode ->
            assertEquals(
                "$mode should be safe by default",
                emptyList<ReturnInstructionIssue>(),
                ReturnInstructions.validate(mode, ReturnInstructionDefaults.forMode(mode)),
            )
        }
    }

    fun testFileBasedReturnModesRequireStructuredZipManifest() {
        val patch = ReturnInstructionDefaults.forMode(CopilotReturnMode.COPILOT_PATCH_FILE)
        val files = ReturnInstructionDefaults.forMode(CopilotReturnMode.CODE_TOOL_FILES)

        assertTrue(patch.contains("`changes.json` at the ZIP root is mandatory"))
        assertTrue(files.contains("`changes.json` manifest"))
        assertTrue(files.contains("Do not return a loose source-only ZIP"))
        assertTrue(files.contains("`formatVersion`: 1"))
        assertTrue(files.contains("`replace_file`"))
        assertTrue(files.contains("exact-file `originalHash`"))
    }

    fun testEffectiveHierarchyUsesProjectOverrideAndThenPromptAddition() {
        val app = AppSettings.Data()
        val project = ProjectSettings.Data()
        val skill = AppSettings.PromptSkillState("skill", "Skill", "", "Prompt")
        skill.contextPolicy.returnMode = CopilotReturnMode.TEXT_ONLY.name
        skill.returnInstructionsAddition = "PROMPT ADDITION"
        app.returnInstructionsByMode[CopilotReturnMode.TEXT_ONLY.name] = "GLOBAL"
        project.returnInstructionOverrides[CopilotReturnMode.TEXT_ONLY.name] = "PROJECT"

        val result = ReturnInstructions.resolve(app, project, skill)

        assertEquals(CopilotReturnMode.TEXT_ONLY, result.mode)
        assertEquals("GLOBAL", result.globalDefault)
        assertEquals("PROJECT", result.projectOverride)
        assertEquals("PROMPT ADDITION", result.promptAddition)
        assertEquals("PROJECT\n\nPROMPT ADDITION", result.effectiveText)
    }

    fun testBlankProjectOverrideInheritsGlobalDefault() {
        val app = AppSettings.Data()
        val project = ProjectSettings.Data()
        val skill = AppSettings.PromptSkillState("skill", "Skill", "", "Prompt")
        app.returnInstructionsByMode[CopilotReturnMode.COPILOT_PATCH_FILE.name] = "GLOBAL PATCH"
        project.returnInstructionOverrides[CopilotReturnMode.COPILOT_PATCH_FILE.name] = "   "

        assertEquals("GLOBAL PATCH", ReturnInstructions.resolve(app, project, skill).effectiveText)
    }

    fun testPatchValidationReportsEveryMissingIdentityAndSourceSafeguard() {
        val issues = ReturnInstructions.validate(CopilotReturnMode.COPILOT_PATCH_FILE, "Return a patch.")

        assertEquals(
            setOf("schemaVersion", "originalPath", "originalHash", "qualifiedFunction", "completeSource"),
            issues.map { it.requirement }.toSet(),
        )
        assertTrue(issues.all { it.message.isNotBlank() })
    }

    fun testTextOnlyModeDoesNotRequirePatchSchemaOrFunctionHash() {
        val issues = ReturnInstructions.validate(CopilotReturnMode.TEXT_ONLY, "Incomplete")

        assertEquals(setOf("originalPath", "completeSource"), issues.map { it.requirement }.toSet())
    }

    fun testEachWarningCanRestoreOnlyItsOwnRequirement() {
        CopilotReturnMode.entries.forEach { mode ->
            val initial = ReturnInstructions.validate(mode, "Custom instructions")
            initial.forEach { issue ->
                val restored = ReturnInstructions.restoreRequirement(mode, "Custom instructions", issue.requirement)
                val remaining = ReturnInstructions.validate(mode, restored)

                assertFalse("$mode should restore ${issue.requirement}", remaining.any { it.requirement == issue.requirement })
                if (initial.size > 1) {
                    assertTrue("Restoring one warning must not silently reset all instructions", remaining.isNotEmpty())
                }
            }
        }
    }

    fun testUnknownPersistedReturnModeFallsBackSafely() {
        val policy = ContextPolicyState().apply { returnMode = "REMOVED_MODE" }

        assertEquals(CopilotReturnMode.COPILOT_PATCH_FILE, ReturnInstructions.mode(policy))
    }
}
