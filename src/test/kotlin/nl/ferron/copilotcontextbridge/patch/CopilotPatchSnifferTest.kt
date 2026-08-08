package nl.ferron.copilotcontextbridge.patch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CopilotPatchSnifferTest {
    @Test
    fun acceptsOnlySchemaShapedJson() {
        assertTrue(
            CopilotPatchSniffer.matchesJson(
                """{"formatVersion":1,"repositoryId":"repo","sessionId":"batch","replacements":[{"operation":"replace_function","path":"src/a.py","qualifiedName":"run","originalHash":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","replacement":"def run():\n    return 1\n"}]}""",
            ),
        )
        assertFalse(CopilotPatchSniffer.matchesJson("""{"formatVersion":1,"items":[]}"""))
        assertFalse(
            CopilotPatchSniffer.matchesJson(
                """{"formatVersion":1,"repositoryId":"repo","sessionId":"batch","replacements":[{"operation":"replace_function"}]}""",
            ),
        )
        assertFalse(CopilotPatchSniffer.matchesJson("not json"))
    }

    @Test
    fun acceptsSafeSourceOnlyZipForManualImportReview() {
        val path = Files.createTempFile("ccb-source-only-", ".zip")
        try {
            Files.write(path, zipOf("src/main.py" to "def run():\n    return 1\n"))

            assertTrue(CopilotPatchSniffer.matches(path))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun rejectsTraversalSourceZipBeforeImport() {
        val path = Files.createTempFile("ccb-source-traversal-", ".zip")
        try {
            Files.write(path, zipOf("../outside.py" to "print('unsafe')\n"))

            assertFalse(CopilotPatchSniffer.matches(path))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray =
        ByteArrayOutputStream()
            .also { output ->
                ZipOutputStream(output).use { zip ->
                    entries.forEach { (name, content) ->
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(content.toByteArray())
                        zip.closeEntry()
                    }
                }
            }.toByteArray()
}
