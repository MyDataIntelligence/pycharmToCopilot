package nl.ferron.copilotcontextbridge.external

import junit.framework.TestCase
import nl.ferron.copilotcontextbridge.settings.Defaults
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipContextSourceExtractorTest : TestCase() {
    private lateinit var root: Path

    override fun setUp() {
        root = Files.createTempDirectory("ccb-zip-source-")
    }

    override fun tearDown() {
        Files.walk(root).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    fun testPreservesArchivePathsAndFiltersIgnoredSecretAndBinaryEntries() {
        val archive = root.resolve("copilot-context.zip")
        Files.write(
            archive,
            zipOf(
                "project/src/main.py" to "print('safe')\n".toByteArray(),
                "project/build/output.py" to "print('ignored')\n".toByteArray(),
                "project/.env" to "TOKEN=value\n".toByteArray(),
                "project/image.bin" to byteArrayOf(0, 1, 2),
            ),
        )
        val result =
            ZipContextSourceExtractor(Defaults.ignorePatterns, emptyList(), Defaults.secretPatterns, root.resolve("cache"))
                .extract(archive)

        assertEquals(listOf("project/src/main.py"), result.entries.map { it.archivePath })
        assertEquals(4, result.discoveredCount)
        assertEquals(3, result.excluded.size)
        assertEquals("print('safe')\n", Files.readString(result.entries.single().extractedPath))
        assertTrue(result.extractionRoot.startsWith(root.resolve("cache")))
    }

    fun testRejectsTraversalAndCaseAmbiguousDuplicates() {
        val extractor = ZipContextSourceExtractor(Defaults.ignorePatterns, emptyList(), Defaults.secretPatterns, root.resolve("cache"))
        val traversal = root.resolve("traversal.zip")
        Files.write(traversal, zipOf("../escape.py" to "x=1".toByteArray()))
        assertTrue(runCatching { extractor.extract(traversal) }.exceptionOrNull()!!.message!!.contains("repository root"))

        val duplicate = root.resolve("duplicate.zip")
        Files.write(duplicate, zipOf("src/Main.py" to "x=1".toByteArray(), "src/main.py" to "x=2".toByteArray()))
        assertTrue(runCatching { extractor.extract(duplicate) }.exceptionOrNull()!!.message!!.contains("case-ambiguous"))
    }

    fun testConfiguredContextExtensionIsExcludedFromArchiveEntries() {
        val archive = root.resolve("extension-filter.zip")
        Files.write(
            archive,
            zipOf(
                "src/main.py" to "print('safe')\n".toByteArray(),
                "docs/manual.pdf" to "plain text but excluded by policy\n".toByteArray(),
            ),
        )

        val result =
            ZipContextSourceExtractor(
                Defaults.ignorePatterns,
                emptyList(),
                Defaults.secretPatterns,
                root.resolve("cache"),
                excludedContextExtensions = listOf("pdf"),
            ).extract(archive)

        assertEquals(listOf("src/main.py"), result.entries.map { it.archivePath })
        assertEquals("file extension excluded by plugin settings", result.excluded.single().reason)
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream()
            .also { output ->
                ZipOutputStream(output).use { zip ->
                    entries.forEach { (name, content) ->
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(content)
                        zip.closeEntry()
                    }
                }
            }.toByteArray()
}
