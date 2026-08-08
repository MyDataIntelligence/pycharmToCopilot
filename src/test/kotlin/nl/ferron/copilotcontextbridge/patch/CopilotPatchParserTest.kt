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
        val json =
            validJson()
                .replace("\"replace_function\"", "\"add_function\"")
                .replace(",\"originalHash\":\"$VALID_HASH\"", "")
                .replace("\"replacement\":", "\"parentQualifiedName\":\"\",\"replacement\":")
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
            """{"formatVersion":1,"repositoryId":"repo","sessionId":"session","replacements":[{"operation":"delete_file","path":"src/old.py","originalHash":"$VALID_HASH"}]}"""

        val replacement = CopilotPatchParser().parseJson(json).replacements.single()

        assertEquals(FILE_OPERATION_QUALIFIED_NAME, replacement.qualifiedName)
        assertEquals(VALID_HASH, replacement.originalHash)
        assertEquals(null, replacement.replacement)
    }

    @Test fun `parses structured whole file replacement with exact hash`() {
        val json =
            """{"formatVersion":1,"repositoryId":"repo","sessionId":"session","replacements":[{"operation":"replace_file","path":"src/existing.py","originalHash":"$VALID_HASH","replacement":"VALUE = 2\n"}]}"""

        val replacement = CopilotPatchParser().parseJson(json).replacements.single()

        assertEquals("replace_file", replacement.operation)
        assertEquals(FILE_OPERATION_QUALIFIED_NAME, replacement.qualifiedName)
        assertEquals(VALID_HASH, replacement.originalHash)
        assertEquals("VALUE = 2\n", replacement.replacement)
    }

    @Test fun `utf8 bom is accepted for structured json and zip snippet`() {
        val changes =
            "\uFEFF" +
                """{"formatVersion":1,"repositoryId":"repo","sessionId":"session","replacements":[{"operation":"replace_file","path":"src/existing.py","originalHash":"$VALID_HASH","replacementFile":"replacements/existing.py"}]}"""
        val bytes =
            ByteArrayOutputStream()
                .also { output ->
                    ZipOutputStream(output).use { zip ->
                        zip.putNextEntry(ZipEntry("changes.json"))
                        zip.write(changes.toByteArray())
                        zip.closeEntry()
                        zip.putNextEntry(ZipEntry("replacements/existing.py"))
                        zip.write("\uFEFFVALUE = 2\n".toByteArray())
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

    @Test fun `rejects delete file with replacement content`() {
        val json =
            """{"formatVersion":1,"repositoryId":"repo","sessionId":"session","replacements":[{"operation":"delete_file","path":"src/old.py","originalHash":"$VALID_HASH","replacement":"bad"}]}"""

        assertThrows(IllegalArgumentException::class.java) { CopilotPatchParser().parseJson(json) }
    }

    @Test fun `zip resolves add file content from replacements directory`() {
        val changes =
            """{"formatVersion":1,"repositoryId":"repo","sessionId":"session","replacements":[{"operation":"add_file","path":"src/new.py","replacementFile":"replacements/new.py"}]}"""
        val bytes =
            ByteArrayOutputStream()
                .also { output ->
                    ZipOutputStream(output).use { zip ->
                        zip.putNextEntry(ZipEntry("changes.json"))
                        zip.write(changes.toByteArray())
                        zip.closeEntry()
                        zip.putNextEntry(ZipEntry("replacements/new.py"))
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

    @Test fun `rejects invalid hash duplicate target and noncanonical path`() {
        val invalidHash = validJson().replace(VALID_HASH, "sha256:abc")
        val duplicate =
            validJson().replace(
                "]}",
                ",{" +
                    "\"operation\":\"replace_function\",\"path\":\"src/a.py\",\"qualifiedName\":\"run\"," +
                    "\"originalHash\":\"$VALID_HASH\",\"replacement\":\"def run():\\n    return 2\\n\"}]}",
            )
        val noncanonical = validJson().replace("src/a.py", "src/./a.py")

        assertThrows(IllegalArgumentException::class.java) { CopilotPatchParser().parseJson(invalidHash) }
        assertThrows(IllegalArgumentException::class.java) { CopilotPatchParser().parseJson(duplicate) }
        assertThrows(IllegalArgumentException::class.java) { CopilotPatchParser().parseJson(noncanonical) }
    }

    @Test fun `rejects duplicate ZIP entries and snippet outside replacements directory`() {
        val outside = validJson().replace("\"replacement\":\"def run():\\n    return 1\\n\"", "\"replacementFile\":\"files/run.py\"")
        val outsideBytes = zipOf("changes.json" to outside, "files/run.py" to "def run():\n    return 2\n")
        assertThrows(IllegalArgumentException::class.java) { CopilotPatchParser().parseZip(outsideBytes) }

        val changes = validJson().replace("\"replacement\":\"def run():\\n    return 1\\n\"", "\"replacementFile\":\"replacements/run.py\"")
        val duplicateBytes =
            zipOf(
                "changes.json" to changes,
                "replacements/run.py" to "def run():\n    return 1\n",
                "replacements/./run.py" to "def run():\n    return 2\n",
            )
        assertThrows(IllegalArgumentException::class.java) { CopilotPatchParser().parseZip(duplicateBytes) }
    }

    @Test fun `rejects case ambiguous ZIP entries before import`() {
        val bytes =
            zipOf(
                "changes.json" to validJson(),
                "replacements/run.py" to "def run():\n    return 2\n",
                "replacements/RUN.py" to "def run():\n    return 3\n",
            )

        val error = assertThrows(IllegalArgumentException::class.java) { CopilotPatchParser().parseZip(bytes) }

        assertTrue(error.message.orEmpty().contains("case-ambiguous"))
    }

    @Test fun `rejects known schema fields with wrong JSON types or incomplete summary`() {
        val fractionalVersion = validJson().replace("\"formatVersion\":1", "\"formatVersion\":1.5")
        val stringBoolean = validJson().replace("\"replacement\":", "\"allowAsyncChange\":\"true\",\"replacement\":")
        val incompleteSummary = validJson().replace(",\"testsPerformed\":[\"Not run\"]", "")

        assertThrows(IllegalStateException::class.java) { CopilotPatchParser().parseJson(fractionalVersion) }
        assertThrows(IllegalArgumentException::class.java) { CopilotPatchParser().parseJson(stringBoolean) }
        assertThrows(IllegalStateException::class.java) { CopilotPatchParser().parseJson(incompleteSummary) }
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

    private fun validJson() =
        """{"formatVersion":1,"repositoryId":"repo","sessionId":"session","summary":{"overview":"Updated behavior","functions":[],"testsPerformed":["Not run"],"risks":[],"limitations":[]},"replacements":[{"operation":"replace_function","path":"src/a.py","qualifiedName":"run","originalHash":"$VALID_HASH","replacement":"def run():\n    return 1\n"}]}"""

    companion object {
        private const val VALID_HASH = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
