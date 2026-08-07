package nl.ferron.copilotcontextbridge.patch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CopilotPatchParserTest {
    @Test fun `parses replacement and summary`() {
        val patch = CopilotPatchParser().parseJson(validJson())
        assertEquals(1, patch.formatVersion)
        assertEquals("replace_function", patch.replacements.single().operation)
        assertEquals("Updated behavior", patch.summary?.overview)
    }

    @Test fun `parses add function without original hash`() {
        val json = validJson().replace("\"replace_function\"", "\"add_function\"").replace(",\"originalHash\":\"sha256:abc\"", "")
        assertEquals(
            null,
            CopilotPatchParser()
                .parseJson(json)
                .replacements
                .single()
                .originalHash,
        )
    }

    @Test fun `rejects traversal zip entry`() {
        val bytes =
            ByteArrayOutputStream()
                .also { output ->
                    ZipOutputStream(output).use { zip ->
                        zip.putNextEntry(ZipEntry("../changes.json"))
                        zip.write(validJson().toByteArray())
                    }
                }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) { CopilotPatchParser().parseZip(bytes) }
    }

    @Test fun `zip resolves replacement file`() {
        val changes = validJson().replace("\"replacement\":\"def run():\\n    return 1\\n\"", "\"replacementFile\":\"replacements/001.py\"")
        val bytes =
            ByteArrayOutputStream()
                .also { output ->
                    ZipOutputStream(output).use { zip ->
                        zip.putNextEntry(ZipEntry("changes.json"))
                        zip.write(changes.toByteArray())
                        zip.closeEntry()
                        zip.putNextEntry(ZipEntry("replacements/001.py"))
                        zip.write("def run():\n    return 2\n".toByteArray())
                    }
                }.toByteArray()
        assertTrue(
            CopilotPatchParser()
                .parseZip(bytes)
                .replacements
                .single()
                .replacement!!
                .contains("return 2"),
        )
    }

    private fun validJson() =
        """{"formatVersion":1,"repositoryId":"repo","sessionId":"session","summary":{"overview":"Updated behavior","functions":[],"testsPerformed":["Not run"],"risks":[],"limitations":[]},"replacements":[{"operation":"replace_function","path":"src/a.py","qualifiedName":"run","originalHash":"sha256:abc","replacement":"def run():\n    return 1\n"}]}"""
}
