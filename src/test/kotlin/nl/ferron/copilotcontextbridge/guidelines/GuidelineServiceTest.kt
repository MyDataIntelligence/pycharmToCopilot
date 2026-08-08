package nl.ferron.copilotcontextbridge.guidelines

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import nl.ferron.copilotcontextbridge.ProjectRoot
import nl.ferron.copilotcontextbridge.settings.ContextPolicyState
import java.nio.file.Files

class GuidelineServiceTest : BasePlatformTestCase() {
    fun testDetectsSourcesWithSafeDefaultsAndExtractsOnlyRelevantReadmeSections() {
        addRepositoryFile("AGENTS.md", "# Agent rules\n\nUse repository utilities.\n")
        addRepositoryFile(
            "README.md",
            "# Product\n\nMarketing text.\n\n## Development\n\nRun the focused tests.\n\n## Usage\n\nUser text.\n",
        )
        addRepositoryFile("pyproject.toml", "[project]\nname='demo'\n[tool.ruff]\nline-length = 100\n")

        val sources = GuidelineService(project).detect().associateBy { it.relativePath }

        assertTrue(sources.getValue("AGENTS.md").enabled)
        assertFalse(sources.getValue("README.md").enabled)
        assertFalse(sources.getValue("pyproject.toml").enabled)
        assertTrue(sources.getValue("README.md").content.contains("Run the focused tests."))
        assertFalse(sources.getValue("README.md").content.contains("Marketing text."))
        assertTrue(sources.getValue("pyproject.toml").content.contains("line-length = 100"))
        assertFalse(sources.getValue("pyproject.toml").content.contains("name='demo'"))
    }

    fun testRepositoryEditorLoadsCompleteSourceAndWritesOnlyOnExplicitSave() {
        val readme =
            addRepositoryFile(
                "README.md",
                "# Product\n\nKeep this introduction.\n\n## Development\n\nOld rule.\n",
            )
        val service = GuidelineService(project)

        val editorText = service.sourceText("README.md")
        assertTrue(editorText.contains("Keep this introduction."))
        assertEquals(editorText, readme.inputStream.bufferedReader().use { it.readText() })

        val replacement = editorText.replace("Old rule.", "New explicit rule.")
        assertEquals(editorText, readme.inputStream.bufferedReader().use { it.readText() })
        service.saveSource("README.md", replacement)

        FileDocumentManager.getInstance().getDocument(readme)?.let { assertEquals(replacement, it.text) }
        assertEquals(
            replacement,
            Files.readString(ProjectRoot.path(project).resolve("README.md")),
        )
    }

    fun testRepositoryEditorRejectsFilesOutsideDetectedGuidelineSources() {
        addRepositoryFile("src/service.py", "SECRET = 'do not edit'\n")
        val service = GuidelineService(project)

        val error =
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                service.saveSource("src/service.py", "changed\n")
            }

        assertTrue(error.message.orEmpty().contains("Not a detected repository guideline source"))
    }

    fun testPolicyCanDisableRepositoryInstructionSourcesIndependently() {
        addRepositoryFile("AGENTS.md", "# Agent rules\n\nUse repository utilities.\n")
        addRepositoryFile(".github/copilot-instructions.md", "# Copilot rules\n\nAdd tests.\n")
        val policy = ContextPolicyState.defaultFor("test")
        policy.rules.first { it.resolver == "guidelines.agents" }.enabled = false

        val merged = GuidelineService(project).merge("General task", "", policy)

        assertFalse(merged.sources.first { it.relativePath == "AGENTS.md" }.enabled)
        assertTrue(merged.sources.first { it.relativePath == ".github/copilot-instructions.md" }.enabled)
        assertFalse(merged.markdown.contains("Use repository utilities."))
        assertTrue(merged.markdown.contains("Add tests."))
    }

    fun testDetectsScopedAgentsFilesWhileIgnoringGeneratedTrees() {
        addRepositoryFile("AGENTS.md", "# Root rules\n")
        addRepositoryFile("src/AGENTS.md", "# Source rules\n")
        addRepositoryFile("build/AGENTS.md", "# Generated rules\n")
        val service = GuidelineService(project)

        val sources = service.detect().associateBy { it.relativePath }

        assertTrue(sources.containsKey("AGENTS.md"))
        assertTrue(sources.containsKey("src/AGENTS.md"))
        assertFalse(sources.containsKey("build/AGENTS.md"))
    }

    fun testEffectiveGuidelinesAlwaysIncludeHardPythonAuthoringRules() {
        val merged = GuidelineService(project).merge("Implement Python behavior", "")

        assertTrue(merged.markdown.contains("## Plugin Python authoring rules"))
        assertTrue(merged.markdown.contains("sphinxcontrib-napoleon.readthedocs.io/en/latest/example_google.html"))
        assertTrue(merged.markdown.contains("`Args:`"))
        assertTrue(merged.markdown.contains("clear leading verb"))
        assertTrue(merged.markdown.contains("Never record change history"))
    }

    private fun addRepositoryFile(
        relativePath: String,
        text: String,
    ): VirtualFile {
        val path = ProjectRoot.path(project).resolve(relativePath)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
        return requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
    }
}
