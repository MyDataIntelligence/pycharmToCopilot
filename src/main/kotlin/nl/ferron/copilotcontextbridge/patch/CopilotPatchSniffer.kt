package nl.ferron.copilotcontextbridge.patch

import nl.ferron.copilotcontextbridge.security.PathSafety
import nl.ferron.copilotcontextbridge.security.ZipMetadataSafety
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

/** Cheap schema sniffing for drag targets; full parsing and security validation still happen afterwards. */
object CopilotPatchSniffer {
    fun matches(path: Path): Boolean =
        runCatching {
            if (!Files.isRegularFile(path) || Files.size(path) > CopilotPatchParser.MAX_ARCHIVE_BYTES) return false
            when (
                path.fileName
                    .toString()
                    .substringAfterLast('.', "")
                    .lowercase()
            ) {
                "copilotpatch", "json" -> matchesJson(Files.readString(path))
                "zip" -> matchesZip(path)
                else -> false
            }
        }.getOrDefault(false)

    fun matchesJson(text: String): Boolean =
        runCatching {
            CopilotPatchParser().parseJson(text)
            true
        }.getOrDefault(false)

    private fun matchesZip(path: Path): Boolean {
        val bytes = Files.readAllBytes(path)
        val specialEntries = ZipMetadataSafety.specialEntryNames(bytes)
        var hasFiles = false
        var expanded = 0L
        val foldedNames = mutableSetOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val suppliedName = entry.name.replace('\\', '/')
                val normalized = PathSafety.normalizeRelative(suppliedName)
                if (normalized != suppliedName || entry.name in specialEntries) return false
                if (!foldedNames.add(normalized.lowercase())) return false
                hasFiles = true
                val entryBytes = zip.readNBytes(CopilotPatchParser.MAX_ENTRY_BYTES + 1)
                if (entryBytes.size > CopilotPatchParser.MAX_ENTRY_BYTES) return false
                expanded += entryBytes.size
                if (expanded > CopilotPatchParser.MAX_UNCOMPRESSED_BYTES) return false
                if (normalized == "changes.json") {
                    if (!matchesJson(entryBytes.toString(StandardCharsets.UTF_8))) return false
                }
            }
        }
        // A source-only ZIP is intentionally accepted here; the import review will
        // map basenames and leave every fallback replacement unselected until reviewed.
        return hasFiles
    }
}
