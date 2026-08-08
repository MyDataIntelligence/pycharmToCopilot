package nl.ferron.copilotcontextbridge.staging

import nl.ferron.copilotcontextbridge.model.StagedFile
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class CombinedContextTextBuilderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun includesMetadataPathsAndExactTextContent() {
        val source = temporaryFolder.newFile("src__service.py").toPath()
        Files.writeString(source, "def run():\n    return 'ok'\n")
        val file = StagedFile("src/service.py", "src__service.py", source, "sha256:abc", "DIRECT_IMPORT", true)

        val result = CombinedContextTextBuilder.build("Batch instructions", listOf(file))

        assertTrue(result.contains("Original path: `src/service.py`"))
        assertTrue(result.contains("Staged filename: `src__service.py`"))
        assertTrue(result.contains("SHA-256: `sha256:abc`"))
        assertTrue(result.contains("```python\ndef run():\n    return 'ok'\n```"))
    }

    @Test
    fun placesKickoffPromptBeforeFileContents() {
        val source = temporaryFolder.newFile("src__service.py").toPath()
        Files.writeString(source, "def run():\n    return 'ok'\n")
        val file = StagedFile("src/service.py", "src__service.py", source, "sha256:abc", "PINNED", true)

        val result = CombinedContextTextBuilder.build("Batch instructions", listOf(file), "Read the context index first.")

        assertTrue(result.indexOf("## Prompt to paste into Copilot") < result.indexOf("## 1. src/service.py"))
        assertTrue(result.contains("Read the context index first."))
    }
}
