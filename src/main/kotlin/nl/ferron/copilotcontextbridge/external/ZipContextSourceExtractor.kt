package nl.ferron.copilotcontextbridge.external

import nl.ferron.copilotcontextbridge.security.IgnoreMatcher
import nl.ferron.copilotcontextbridge.security.PathSafety
import nl.ferron.copilotcontextbridge.security.SecretDetector
import nl.ferron.copilotcontextbridge.security.ZipMetadataSafety
import nl.ferron.copilotcontextbridge.staging.TextFileSupport
import java.io.ByteArrayInputStream
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.zip.ZipInputStream

class ZipContextSourceExtractor(
    private val globalIgnorePatterns: Collection<String>,
    private val customIgnorePatterns: Collection<String>,
    secretFilenamePatterns: Collection<String>,
    private val cacheRoot: Path = Path.of(System.getProperty("java.io.tmpdir"), "CopilotContextBridgeArchiveSources"),
    private val maximumEntryBytes: Int = MAX_ENTRY_BYTES,
    excludedContextExtensions: Collection<String> = emptyList(),
) {
    data class Entry(
        val archivePath: String,
        val extractedPath: Path,
    )

    data class Excluded(
        val archivePath: String,
        val reason: String,
    )

    data class Result(
        val archiveName: String,
        val repositoryId: String,
        val extractionRoot: Path,
        val entries: List<Entry>,
        val excluded: List<Excluded>,
        val discoveredCount: Int,
    )

    private val secretDetector = SecretDetector(secretFilenamePatterns)
    private val excludedExtensions =
        excludedContextExtensions
            .map { it.removePrefix(".").trim().lowercase() }
            .filter(String::isNotBlank)
            .toSet()

    fun extract(archive: Path): Result {
        require(Files.isRegularFile(archive) && !Files.isSymbolicLink(archive)) { "ZIP source must be a regular file." }
        val compressedSize = Files.size(archive)
        require(compressedSize <= MAX_COMPRESSED_BYTES) { "ZIP exceeds the 20 MB compressed limit." }
        val bytes = Files.readAllBytes(archive)
        val specialEntries = ZipMetadataSafety.specialEntryNames(bytes)
        val hash = sha256(bytes)
        val archiveName = archive.fileName.toString()
        val repositoryId = sanitize(archiveName.removeSuffix(".zip")) + "-" + hash.take(8)
        val extractionRoot = cacheRoot.resolve(hash).resolve(archiveName)
        cleanup()

        val rawEntries = linkedMapOf<String, ByteArray>()
        val foldedNames = mutableSetOf<String>()
        var total = 0L
        var discovered = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                discovered++
                require(discovered <= MAX_ENTRIES) { "ZIP contains more than $MAX_ENTRIES entries." }
                if (entry.isDirectory || entry.name.endsWith('/')) continue
                val supplied = entry.name.replace('\\', '/')
                require(entry.name !in specialEntries) { "ZIP contains a symlink or special entry: ${entry.name}" }
                val normalized = PathSafety.normalizeRelative(supplied)
                require(normalized == supplied) { "ZIP entry path is not canonical: ${entry.name}" }
                require(foldedNames.add(normalized.lowercase())) { "ZIP has a duplicate or case-ambiguous path: $normalized" }
                val entryLimit = maximumEntryBytes.coerceIn(1, MAX_ENTRY_BYTES)
                val content = zip.readNBytes(entryLimit + 1)
                require(content.size <= entryLimit) { "ZIP entry exceeds the configured text-file size limit: $normalized" }
                total += content.size
                require(total <= MAX_EXPANDED_BYTES) { "ZIP expands beyond the 50 MB limit." }
                rawEntries[normalized] = content
            }
        }
        require(rawEntries.isNotEmpty()) { "ZIP contains no files." }

        val archiveIgnorePatterns =
            rawEntries.filterKeys { it == ".gitignore" || it == ".ignore" }.values.flatMap { bytesValue ->
                decodeUtf8(bytesValue)?.lineSequence()?.toList().orEmpty()
            }
        val ignoreMatcher = IgnoreMatcher(globalIgnorePatterns + customIgnorePatterns + archiveIgnorePatterns)
        val accepted = mutableListOf<Pair<String, ByteArray>>()
        val excluded = mutableListOf<Excluded>()
        rawEntries.forEach { (path, content) ->
            val text = decodeUtf8(content)
            val reason =
                when {
                    ignoreMatcher.isIgnored(path) -> "ignored by repository or plugin rules"
                    isExcludedExtension(path) -> "file extension excluded by plugin settings"
                    text == null || !TextFileSupport.isLikelyText(content) -> "binary or non-UTF-8 content"
                    secretDetector.suspiciousFilename(path) -> "suspicious secret filename"
                    secretDetector.scanText(text).isNotEmpty() -> "secret-like content"
                    else -> null
                }
            if (reason == null) accepted += path to content else excluded += Excluded(path, reason)
        }
        require(accepted.isNotEmpty()) { "ZIP contains no safe text files after filtering." }

        val marker = extractionRoot.resolve(".complete")
        if (!Files.isRegularFile(marker)) {
            val temporary = Files.createTempDirectory(cacheRoot.also(Files::createDirectories), "extract-")
            try {
                accepted.forEach { (path, content) ->
                    val target = temporary.resolve(path).normalize()
                    require(target.startsWith(temporary)) { "ZIP entry escapes the extraction cache: $path" }
                    Files.createDirectories(target.parent)
                    Files.write(target, content)
                }
                Files.writeString(temporary.resolve(".complete"), hash)
                Files.createDirectories(extractionRoot.parent)
                if (!Files.exists(extractionRoot)) Files.move(temporary, extractionRoot, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                if (Files.exists(temporary)) Files.walk(temporary).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
        return Result(
            archiveName,
            repositoryId,
            extractionRoot,
            accepted.map { (path, _) -> Entry(path, extractionRoot.resolve(path)) },
            excluded,
            discovered,
        )
    }

    private fun cleanup() {
        if (!Files.isDirectory(cacheRoot)) return
        val cutoff = Instant.now().minus(7, ChronoUnit.DAYS)
        Files.list(cacheRoot).use { roots ->
            roots
                .filter { Files.getLastModifiedTime(it).toInstant().isBefore(cutoff) }
                .forEach { stale ->
                    runCatching {
                        Files
                            .walk(stale)
                            .sorted(Comparator.reverseOrder())
                            .forEach(Files::deleteIfExists)
                    }
                }
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String? =
        runCatching {
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sanitize(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "-").trim('-').ifBlank { "archive" }

    private fun isExcludedExtension(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in excludedExtensions

    companion object {
        const val MAX_COMPRESSED_BYTES = 20L * 1024 * 1024
        const val MAX_EXPANDED_BYTES = 50L * 1024 * 1024
        const val MAX_ENTRY_BYTES = 10 * 1024 * 1024
        const val MAX_ENTRIES = 500
    }
}
