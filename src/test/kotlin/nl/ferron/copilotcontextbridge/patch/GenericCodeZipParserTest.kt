package nl.ferron.copilotcontextbridge.patch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GenericCodeZipParserTest {
    @Test fun `structured manifest is detected and never treated as generic`() {
        assertTrue(GenericCodeZipParser().hasStructuredManifest(zipOf("changes.json" to "{}")))
    }

    @Test fun `exact path wins and missing path becomes add file`() {
        val root = Files.createTempDirectory("ccb-generic-zip-")
        try {
            Files.createDirectories(root.resolve("src"))
            Files.writeString(root.resolve("src/main.py"), "OLD\n")
            val patch = GenericCodeZipParser().parse(zipOf("src/main.py" to "NEW\n", "src/new.py" to "ADD\n"), root, "repo")
            assertEquals(listOf("src/main.py", "src/new.py"), patch.replacements.map { it.path })
            assertEquals(listOf("replace_file", "add_file"), patch.replacements.map { it.operation })
            assertTrue(
                patch.replacements
                    .first()
                    .originalHash!!
                    .startsWith("sha256:"),
            )
        } finally {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test fun `unique basename is proposed but duplicate basename is rejected as ambiguous`() {
        val root = Files.createTempDirectory("ccb-generic-zip-")
        try {
            Files.createDirectories(root.resolve("actual"))
            Files.writeString(root.resolve("actual/main.py"), "OLD\n")
            val mapped = GenericCodeZipParser().parse(zipOf("copilot/main.py" to "NEW\n"), root, "repo")
            assertEquals("actual/main.py", mapped.replacements.single().path)
            assertEquals("archive:copilot/main.py", mapped.replacements.single().replacementFile)

            Files.createDirectories(root.resolve("other"))
            Files.writeString(root.resolve("other/main.py"), "OTHER\n")
            val error =
                assertThrows(IllegalArgumentException::class.java) {
                    GenericCodeZipParser().parse(zipOf("copilot/main.py" to "NEW\n"), root, "repo")
                }
            assertTrue(error.message!!.contains("Ambiguous"))
        } finally {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
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
