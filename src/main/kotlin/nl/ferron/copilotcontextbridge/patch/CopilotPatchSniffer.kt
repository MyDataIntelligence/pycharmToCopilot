package nl.ferron.copilotcontextbridge.patch

import com.google.gson.JsonParser
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
            val root = JsonParser.parseString(text).asJsonObject
            root.get("formatVersion")?.asInt == 1 &&
                root.get("repositoryId")?.asString?.isNotBlank() == true &&
                root.get("sessionId")?.asString?.isNotBlank() == true &&
                root.getAsJsonArray("replacements")?.size()?.let { it in 1..CopilotPatchParser.MAX_REPLACEMENTS } == true
        }.getOrDefault(false)

    private fun matchesZip(path: Path): Boolean {
        ZipInputStream(Files.newInputStream(path)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return false
                if (!entry.isDirectory && entry.name.replace('\\', '/') == "changes.json") {
                    val bytes = zip.readNBytes(CopilotPatchParser.MAX_ENTRY_BYTES + 1)
                    if (bytes.size > CopilotPatchParser.MAX_ENTRY_BYTES) return false
                    return matchesJson(bytes.toString(StandardCharsets.UTF_8))
                }
            }
        }
    }
}
