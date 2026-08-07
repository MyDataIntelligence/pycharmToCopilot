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

    @Test fun `parses add file without qualified name`() {
        val json =
            """{"formatVersion":1,"repositoryId":"repo","sessionId":"session","replacements":[{"operation":"add_file","path":"src/new.py","replacement":"VALUE = 1\n"}]}"""

        val replacement = CopilotPatchParser().parseJson(json).replacements.single()

        assertEquals(FILE_OPERATION_QUALIFIED_NAME, replacement.qualifiedName)
        assertEquals("VALUE = 1\n", replacement.replacement)
        assertEquals(null, replacement.originalHash)
    }

    @Test fun `parses delete file with exact hash and no replacement`() {
        val json =
            """{"formatVersion":1,"repositoryId":"repo","sessionId":"session","replacements":[{"operation":"delete_file","path":"src/old.py","originalHash":"sha256:abc"}]}"""

        val replacement = CopilotPatchParser().parseJson(json).replacements.single()

        assertEquals(FILE_OPERATION_QUALIFIED_NAME, replacement.qualifiedName)
        assertEquals("sha256:abc", replacement.originalHash)
        assertEquals(null, replacement.replacement)
    }

    @Test fun `rejects delete file with replacement content`() {
        val json =
            """{"formatVersion":1,"repositoryId":"repo","sessionId":"session","replacements":[{"operation":"delete_file","path":"src/old.py","originalHash":"sha256:abc","replacement":"bad"}]}"""

        assertThrows(IllegalArgumentException::class.java) { CopilotPatchParser().parseJson(json) }
    }

    @Test fun `zip resolves add file content outside replacements directory`() {
        val changes =
            """{"formatVersion":1,"repositoryId":"repo","sessionId":"session","replacements":[{"operation":"add_file","path":"src/new.py","replacementFile":"files/new.py"}]}"""
        val bytes =
            ByteArrayOutputStream()
                .also { output ->
                    ZipOutputStream(output).use { zip ->
                        zip.putNextEntry(ZipEntry("changes.json"))
                        zip.write(changes.toByteArray())
                        zip.closeEntry()
                        zip.putNextEntry(ZipEntry("files/new.py"))
                        zip.write("VALUE = 2\n".toByteArray())
                    }
                }.toByteArray()

        assertEquals(
            "VALUE = 2\n",
            CopilotPatchParser()
                .parseZip(bytes)
                .replacements
                .single()
                .replacement,
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
