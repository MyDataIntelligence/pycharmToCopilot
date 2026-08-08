package nl.ferron.copilotcontextbridge.patch

import nl.ferron.copilotcontextbridge.security.PathSafety
import nl.ferron.copilotcontextbridge.security.ZipMetadataSafety
import nl.ferron.copilotcontextbridge.staging.TextFileSupport
import java.io.ByteArrayInputStream
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.zip.ZipInputStream

class GenericCodeZipParser {
    fun hasStructuredManifest(bytes: ByteArray): Boolean =
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.any { !it.isDirectory && it.name == "changes.json" }
        }

    fun parse(
        bytes: ByteArray,
        repositoryRoot: Path,
        repositoryId: String,
    ): CopilotPatch {
        require(bytes.size <= CopilotPatchParser.MAX_ARCHIVE_BYTES) { "ZIP exceeds the 20 MB compressed limit." }
        val root = repositoryRoot.toRealPath()
        val entries = linkedMapOf<String, String>()
        val specialEntries = ZipMetadataSafety.specialEntryNames(bytes)
        val folded = mutableSetOf<String>()
        var expanded = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || entry.name.endsWith('/')) continue
                require(entries.size < CopilotPatchParser.MAX_ENTRIES) { "ZIP contains too many entries." }
                val supplied = entry.name.replace('\\', '/')
                require(entry.name !in specialEntries) { "ZIP contains a symlink or special entry: ${entry.name}" }
                val path = PathSafety.normalizeRelative(supplied)
                require(path == supplied) { "ZIP entry path is not canonical: ${entry.name}" }
                require(folded.add(path.lowercase())) { "ZIP contains a duplicate or case-ambiguous entry: $path" }
                val data = zip.readNBytes(CopilotPatchParser.MAX_ENTRY_BYTES + 1)
                require(data.size <= CopilotPatchParser.MAX_ENTRY_BYTES) { "ZIP entry is too large: $path" }
                expanded += data.size
                require(expanded <= CopilotPatchParser.MAX_UNCOMPRESSED_BYTES) { "ZIP expands beyond the safe size limit." }
                require(TextFileSupport.isLikelyText(data)) { "ZIP entry is binary: $path" }
                entries[path] = decodeUtf8(path, data)
            }
        }
        require(entries.isNotEmpty()) { "ZIP contains no code files." }
        require("changes.json" !in entries) { "Structured ZIP must be parsed through changes.json." }

        val repositoryByBasename = repositoryFilesByBasename(root)
        val mappedTargets = mutableSetOf<String>()
        val replacements =
            entries.map { (archivePath, content) ->
                val exact = root.resolve(archivePath).normalize()
                require(exact.startsWith(root)) { "ZIP entry escapes the repository: $archivePath" }
                val targetRelative =
                    if (Files.isRegularFile(exact, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(exact)) {
                        archivePath
                    } else {
                        val matches = repositoryByBasename[archivePath.substringAfterLast('/').lowercase()].orEmpty()
                        require(matches.size <= 1) {
                            "Ambiguous ZIP entry '${archivePath.substringAfterLast(
                                '/',
                            )}': ${matches.size} repository files share that basename."
                        }
                        matches.singleOrNull() ?: archivePath
                    }
                require(mappedTargets.add(targetRelative.lowercase())) {
                    "Multiple ZIP entries map to the same repository file: $targetRelative"
                }
                val target = root.resolve(targetRelative).normalize()
                val exists = Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(target)
                FunctionReplacement(
                    operation = if (exists) "replace_file" else "add_file",
                    path = targetRelative,
                    qualifiedName = FILE_OPERATION_QUALIFIED_NAME,
                    originalHash = if (exists) FileContentHasher.hash(target) else null,
                    replacement = content,
                    replacementFile = "archive:$archivePath",
                )
            }
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
                .take(12)
        return CopilotPatch(1, repositoryId, "generic-zip-$digest", replacements)
    }

    private fun repositoryFilesByBasename(root: Path): Map<String, List<String>> {
        val files = mutableListOf<String>()
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (dir != root && (Files.isSymbolicLink(dir) || dir.fileName.toString().lowercase() in PRUNED_DIRECTORIES)) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (attrs.isRegularFile && !Files.isSymbolicLink(file)) {
                        require(files.size < MAX_REPOSITORY_FILES) {
                            "Repository is too large for safe basename fallback; use exact paths or changes.json."
                        }
                        files += root.relativize(file).joinToString("/")
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return files.groupBy { it.substringAfterLast('/').lowercase() }
    }

    private fun decodeUtf8(
        path: String,
        bytes: ByteArray,
    ): String =
        runCatching {
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        }.getOrElse {
            throw IllegalArgumentException("ZIP entry is not valid UTF-8 text: $path", it)
        }

    companion object {
        private const val MAX_REPOSITORY_FILES = 50_000
        private val PRUNED_DIRECTORIES =
            setOf(
                ".git",
                ".idea",
                ".venv",
                "venv",
                "node_modules",
                "build",
                "dist",
                "out",
                "target",
                "__pycache__",
            )
    }
}
