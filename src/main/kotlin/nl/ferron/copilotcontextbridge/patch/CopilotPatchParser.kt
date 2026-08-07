package nl.ferron.copilotcontextbridge.patch

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

class CopilotPatchParser {
    fun parse(path: Path): CopilotPatch {
        val size = Files.size(path)
        require(size <= MAX_ARCHIVE_BYTES) { "Patch exceeds the ${MAX_ARCHIVE_BYTES / 1024 / 1024} MB limit." }
        return if (path.fileName.toString().lowercase().endsWith(
                ".zip",
            )
        ) {
            parseZip(Files.readAllBytes(path))
        } else {
            parseJson(Files.readString(path))
        }
    }

    fun parseJson(json: String): CopilotPatch = parseObject(JsonParser.parseString(json).asJsonObject, emptyMap())

    fun parseZip(bytes: ByteArray): CopilotPatch {
        val entries = linkedMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory) { "ZIP directories are not accepted as patch entries." }
                val name = entry.name.replace('\\', '/')
                require(
                    !name.startsWith('/') && !name.contains("../") && !Regex("^[A-Za-z]:").containsMatchIn(name),
                ) { "Unsafe ZIP entry: $name" }
                require(entries.size < MAX_ENTRIES) { "ZIP contains too many entries." }
                val data = zip.readNBytes(MAX_ENTRY_BYTES + 1)
                require(data.size <= MAX_ENTRY_BYTES) { "ZIP entry is too large: $name" }
                total += data.size
                require(total <= MAX_UNCOMPRESSED_BYTES) { "ZIP expands beyond the safe size limit." }
                entries[name] = data
            }
        }
        val changes = entries["changes.json"] ?: error("ZIP must contain changes.json at its root.")
        val snippets =
            entries
                .filterKeys { it != "changes.json" }
                .mapValues { (_, value) -> value.toString(StandardCharsets.UTF_8) }
        return parseObject(JsonParser.parseString(changes.toString(StandardCharsets.UTF_8)).asJsonObject, snippets)
    }

    private fun parseObject(
        root: JsonObject,
        snippets: Map<String, String>,
    ): CopilotPatch {
        val version = root.requiredInt("formatVersion")
        require(version == 1) { "Unsupported formatVersion: $version" }
        val repositoryId = root.requiredString("repositoryId")
        val sessionId = root.requiredString("sessionId")
        val array = root.getAsJsonArray("replacements") ?: error("replacements must be an array.")
        require(array.size() in 1..MAX_REPLACEMENTS) { "Patch must contain 1..$MAX_REPLACEMENTS replacements." }
        val replacements =
            array.mapIndexed { index, element ->
                require(element.isJsonObject) { "Replacement $index must be an object." }
                val item = element.asJsonObject
                val operation = item.requiredString("operation")
                require(operation in setOf("replace_function", "add_function", "add_file", "delete_file")) {
                    "Unsupported operation: $operation"
                }
                val embedded = item.optionalString("replacement")
                val reference = item.optionalString("replacementFile")
                val requiresContent = operation != "delete_file"
                if (requiresContent) {
                    require((embedded == null) xor (reference == null)) {
                        "Replacement $index must contain exactly one of replacement or replacementFile."
                    }
                } else {
                    require(embedded == null && reference == null) {
                        "delete_file replacement $index must not contain replacement content."
                    }
                }
                val replacementText =
                    if (requiresContent) {
                        embedded ?: snippets[reference] ?: error("Missing ZIP content: $reference")
                    } else {
                        null
                    }
                require(replacementText == null || replacementText.toByteArray(StandardCharsets.UTF_8).size <= MAX_ENTRY_BYTES) {
                    "Replacement $index is too large."
                }
                FunctionReplacement(
                    operation,
                    item.requiredString("path"),
                    if (operation.endsWith(
                            "_file",
                        )
                    ) {
                        item.optionalString("qualifiedName") ?: FILE_OPERATION_QUALIFIED_NAME
                    } else {
                        item.requiredString("qualifiedName")
                    },
                    if (operation in setOf("replace_function", "delete_file")) {
                        item.requiredString("originalHash")
                    } else {
                        item.optionalString("originalHash")
                    },
                    replacementText,
                    reference,
                    item.get("allowAsyncChange")?.asBoolean ?: false,
                    item.get("allowDecoratorKindChange")?.asBoolean ?: false,
                    item.optionalString("parentQualifiedName"),
                    item.optionalString("insertAfterQualifiedName"),
                )
            }
        val summary =
            root.getAsJsonObject("summary")?.let { item ->
                PatchSummary(
                    item.optionalString("overview").orEmpty(),
                    item
                        .getAsJsonArray("functions")
                        ?.map { function ->
                            val value = function.asJsonObject
                            PatchSummaryItem(
                                value.requiredString("path"),
                                value.requiredString("qualifiedName"),
                                value.requiredString("change"),
                                value.requiredString("reason"),
                            )
                        }.orEmpty(),
                    item.stringList("testsPerformed"),
                    item.stringList("risks"),
                    item.stringList("limitations"),
                )
            }
        return CopilotPatch(version, repositoryId, sessionId, replacements, summary)
    }

    private fun JsonObject.requiredString(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            ?: error("Missing or empty string: $name")

    private fun JsonObject.optionalString(name: String): String? = get(name)?.takeIf { !it.isJsonNull }?.asString

    private fun JsonObject.requiredInt(name: String): Int =
        get(name)?.takeIf { it.isJsonPrimitive }?.asInt ?: error("Missing integer: $name")

    private fun JsonObject.stringList(name: String): List<String> =
        getAsJsonArray(name)
            ?.mapNotNull {
                it.takeIf { value -> value.isJsonPrimitive }?.asString
            }.orEmpty()

    companion object {
        const val MAX_ARCHIVE_BYTES = 20L * 1024L * 1024L
        const val MAX_UNCOMPRESSED_BYTES = 50L * 1024L * 1024L
        const val MAX_ENTRY_BYTES = 10 * 1024 * 1024
        const val MAX_ENTRIES = 100
        const val MAX_REPLACEMENTS = 50
    }
}
