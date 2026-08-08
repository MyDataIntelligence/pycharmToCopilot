package nl.ferron.copilotcontextbridge.settings

import junit.framework.TestCase

class SettingsStateTest : TestCase() {
    fun testProjectSettingsClampLimitsRestoreScoresAndPreserveExplicitEmptyGuidelines() {
        val service = ProjectSettings()
        val state =
            ProjectSettings.Data().apply {
                maximumUploadFiles = 999
                textualScanLimitBytes = 1
                scores.clear()
                enabledGuidelineSources.clear()
                guidelineSelectionConfigured = true
                clearActiveSelectionAfterExport = true
            }

        service.loadState(state)

        assertEquals(20, service.state.maximumUploadFiles)
        assertEquals(64L * 1024L, service.state.textualScanLimitBytes)
        assertEquals(ProjectSettings.defaultScores(), service.state.scores)
        assertTrue(service.state.guidelineSelectionConfigured)
        assertTrue(service.state.enabledGuidelineSources.isEmpty())
        assertFalse(service.state.clearActiveSelectionAfterExport)
    }

    fun testApplicationSettingsRepairEmptyPatternsAndRetention() {
        val service = AppSettings()
        val state =
            AppSettings.Data().apply {
                promptSkills.clear()
                ignorePatterns.clear()
                secretFilenamePatterns.clear()
                returnFileInstruction = ""
                combinedTextIntro = ""
                stagingRetentionDays = 0
            }

        service.loadState(state)

        assertTrue(service.state.promptSkills.isNotEmpty())
        assertTrue(service.state.ignorePatterns.isNotEmpty())
        assertTrue(service.state.secretFilenamePatterns.isNotEmpty())
        assertEquals(Defaults.RETURN_FILE_INSTRUCTION, service.state.returnFileInstruction)
        assertEquals(Defaults.COMBINED_TEXT_INTRO, service.state.combinedTextIntro)
        assertEquals(1, service.state.stagingRetentionDays)
    }
}
