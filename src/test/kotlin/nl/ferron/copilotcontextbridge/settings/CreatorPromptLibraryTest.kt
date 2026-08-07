package nl.ferron.copilotcontextbridge.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CreatorPromptLibraryTest {
    @Test
    fun exposesAllCreatorSkillsWithPromptAndGuidelines() {
        val skills = CreatorPromptLibrary.skills()

        assertEquals(setOf("skill-creator", "slash-command-creator", "agents-md-creator"), skills.map { it.id }.toSet())
        assertTrue(skills.all { it.prompt.length > 1_000 })
        assertTrue(skills.all { it.guidelines.contains("complete Pythonfuncties") })
    }

    @Test
    fun upgradesExistingSettingsWithoutDuplicatingBuiltIns() {
        val settings = AppSettings()
        val existing =
            AppSettings.Data().apply {
                promptSkills = mutableListOf(AppSettings.defaultPromptSkills().first())
            }

        settings.loadState(existing)
        settings.loadState(settings.state)

        CreatorPromptLibrary.skills().forEach { builtIn ->
            assertEquals(1, settings.state.promptSkills.count { it.id == builtIn.id })
        }
    }
}
