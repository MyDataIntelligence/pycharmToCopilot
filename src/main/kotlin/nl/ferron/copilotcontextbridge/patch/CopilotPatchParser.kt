package nl.ferron.copilotcontextbridge.patch

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import nl.ferron.copilotcontextbridge.security.PathSafety
import nl.ferron.copilotcontextbridge.security.ZipMetadataSafety
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

    fun parseJson(json: String): CopilotPatch = parseObject(JsonParser.parseString(json.removePrefix("\uFEFF")).asJsonObject, emptyMap())

    fun parseZip(bytes: ByteArray): CopilotPatch {
        require(bytes.size <= MAX_ARCHIVE_BYTES) { "ZIP exceeds the ${MAX_ARCHIVE_BYTES / 1024 / 1024} MB compressed limit." }
        val entries = linkedMapOf<String, ByteArray>()
        val foldedNames = mutableSetOf<String>()
        val specialEntries = ZipMetadataSafety.specialEntryNames(bytes)
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory) { "ZIP directories are not accepted as patch entries." }
                val suppliedName = entry.name.replace('\\', '/')
                val name = PathSafety.normalizeRelative(suppliedName)
                require(entries.size < MAX_ENTRIES) { "ZIP contains too many entries." }
                require(name !in entries) { "ZIP contains a duplicate entry: $name" }
                require(foldedNames.add(name.lowercase())) { "ZIP contains a duplicate or case-ambiguous entry: $name" }
                require(entry.name !in specialEntries) { "ZIP contains a symlink or special entry: ${entry.name}" }
                require(name == suppliedName) { "ZIP entry must use a canonical repository-relative path: $suppliedName" }
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
                .mapValues { (_, value) -> value.toString(StandardCharsets.UTF_8).removePrefix("\uFEFF") }
        return parseObject(
            JsonParser.parseString(changes.toString(StandardCharsets.UTF_8).removePrefix("\uFEFF")).asJsonObject,
            snippets,
        )
    }

    private fun parseObject(
        root: JsonObject,
        snippets: Map<String, String>,
    ): CopilotPatch {
        val version = root.requiredInt("formatVersion")
        require(version == 1) { "Unsupported formatVersion: $version" }
        val repositoryId = root.requiredString("repositoryId")
        val sessionId = root.requiredString("sessionId")
        require(repositoryId.length <= MAX_ID_LENGTH) { "repositoryId is too long." }
        require(sessionId.length <= MAX_ID_LENGTH) { "sessionId is too long." }
        val array = root.getAsJsonArray("replacements") ?: error("replacements must be an array.")
        require(array.size() in 1..MAX_REPLACEMENTS) { "Patch must contain 1..$MAX_REPLACEMENTS replacements." }
        val replacements =
            array.mapIndexed { index, element ->
                require(element.isJsonObject) { "Replacement $index must be an object." }
                val item = element.asJsonObject
                val operation = item.requiredString("operation")
                require(operation in setOf("replace_function", "add_function", "add_file", "replace_file", "delete_file")) {
                    "Unsupported operation: $operation"
                }
                val suppliedPath = item.requiredString("path")
                val path = PathSafety.normalizeRelative(suppliedPath)
                require(path == suppliedPath.replace('\\', '/')) {
                    "Replacement $index path must already be canonical: $suppliedPath"
                }
                if (operation.endsWith("_function")) {
                    require(path.endsWith(".py", ignoreCase = true)) {
                        "Replacement $index function target must be a Python file."
                    }
                }
                require(path.length <= MAX_PATH_LENGTH) { "Replacement $index path is too long." }
                val embedded = item.optionalString("replacement")
                val reference = item.optionalString("replacementFile")
                if (reference != null) {
                    val normalizedReference = PathSafety.normalizeRelative(reference)
                    require(reference.replace('\\', '/') == normalizedReference && normalizedReference.startsWith("replacements/")) {
                        "replacementFile must be a canonical path below replacements/: $reference"
                    }
                }
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
                        (embedded ?: snippets[reference] ?: error("Missing ZIP content: $reference"))
                            .replace("\r\n", "\n")
                            .replace('\r', '\n')
                    } else {
                        null
                    }
                require(replacementText == null || replacementText.toByteArray(StandardCharsets.UTF_8).size <= MAX_ENTRY_BYTES) {
                    "Replacement $index is too large."
                }
                val qualifiedName =
                    if (operation.endsWith("_file")) {
                        item.optionalString("qualifiedName")?.also {
                            require(it == FILE_OPERATION_QUALIFIED_NAME) {
                                "$operation qualifiedName must be omitted or '$FILE_OPERATION_QUALIFIED_NAME'."
                            }
                        } ?: FILE_OPERATION_QUALIFIED_NAME
                    } else {
                        item.requiredString("qualifiedName")
                    }
                val originalHash =
                    if (operation in setOf("replace_function", "replace_file", "delete_file")) {
                        item.requiredString("originalHash").also(::requireSha256)
                    } else {
                        item.optionalString("originalHash")?.also {
                            error("$operation must not contain originalHash.")
                        }
                    }
                val parentQualifiedName =
                    if (operation == "add_function") {
                        item.requiredStringAllowEmpty("parentQualifiedName")
                    } else {
                        item.optionalString("parentQualifiedName")
                    }
                FunctionReplacement(
                    operation,
                    path,
                    qualifiedName,
                    originalHash,
                    replacementText,
                    reference,
                    item.optionalBoolean("allowAsyncChange") ?: false,
                    item.optionalBoolean("allowDecoratorKindChange") ?: false,
                    parentQualifiedName,
                    item.optionalString("insertAfterQualifiedName"),
                )
            }
        val duplicateTargets =
            replacements
                .groupBy { "${it.path}::${it.qualifiedName}" }
                .filterValues { it.size > 1 }
                .keys
        require(duplicateTargets.isEmpty()) {
            "Patch contains duplicate target operations: ${duplicateTargets.sorted().joinToString()}."
        }
        val summary =
            root.getAsJsonObject("summary")?.let { item ->
                PatchSummary(
                    item.requiredStringAllowEmpty("overview"),
                    item.requiredArray("functions").map { function ->
                        val value = function.asJsonObject
                        PatchSummaryItem(
                            value.requiredString("path"),
                            value.requiredString("qualifiedName"),
                            value.requiredString("change"),
                            value.requiredString("reason"),
                        )
                    },
                    item.requiredStringList("testsPerformed"),
                    item.requiredStringList("risks"),
                    item.requiredStringList("limitations"),
                )
            }
        return CopilotPatch(version, repositoryId, sessionId, replacements, summary)
    }

    private fun JsonObject.requiredString(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf { it.isNotBlank() }
            ?: error("Missing or empty string: $name")

    private fun JsonObject.requiredStringAllowEmpty(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?: error("Missing string: $name")

    private fun JsonObject.optionalString(name: String): String? {
        val value = get(name) ?: return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$name must be a string." }
        return value.asString
    }

    private fun JsonObject.optionalBoolean(name: String): Boolean? {
        val value = get(name) ?: return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) { "$name must be a boolean." }
        return value.asBoolean
    }

    private fun JsonObject.requiredInt(name: String): Int =
        get(name)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber && INTEGER_PATTERN.matches(it.asString) }
            ?.asInt ?: error("Missing integer: $name")

    private fun JsonObject.requiredArray(name: String) = get(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: error("Missing array: $name")

    private fun JsonObject.requiredStringList(name: String): List<String> =
        requiredArray(name).mapIndexed { index, value ->
            require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$name[$index] must be a string." }
            value.asString
        }

    companion object {
        const val MAX_ARCHIVE_BYTES = 20L * 1024L * 1024L
        const val MAX_UNCOMPRESSED_BYTES = 50L * 1024L * 1024L
        const val MAX_ENTRY_BYTES = 10 * 1024 * 1024
        const val MAX_ENTRIES = 100
        const val MAX_REPLACEMENTS = 50
        const val MAX_ID_LENGTH = 256
        const val MAX_PATH_LENGTH = 4096

        private val SHA256_PATTERN = Regex("^sha256:[0-9a-f]{64}$")
        private val INTEGER_PATTERN = Regex("^-?[0-9]+$")

        private fun requireSha256(value: String) {
            require(SHA256_PATTERN.matches(value)) { "originalHash must be a lowercase SHA-256 value." }
        }
    }
}
