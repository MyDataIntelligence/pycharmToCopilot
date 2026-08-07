package nl.ferron.copilotcontextbridge.staging

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object StagedFilenameService {
    private val reserved =
        setOf("CON", "PRN", "AUX", "NUL") +
            (1..9).flatMap { listOf("COM$it", "LPT$it") }

    fun namesFor(paths: Collection<String>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val used = hashSetOf<String>()
        paths.sorted().forEach { path ->
            val normalized = path.replace('\\', '/').trimStart('/')
            var name = normalized.replace("/", "__").replace(Regex("[^A-Za-z0-9._-]"), "_")
            if (name.substringBefore('.').uppercase() in reserved) name = "_$name"
            if (name.length > 180) {
                val extension = name.substringAfterLast('.', "")
                name = name.take(150) + "__" + shortHash(normalized) + if (extension.isBlank()) "" else ".$extension"
            }
            val lowered = name.lowercase()
            if (!used.add(lowered)) {
                val dot = name.lastIndexOf('.')
                name = if (dot > 0) name.take(dot) + "__" + shortHash(normalized) + name.drop(dot) else name + "__" + shortHash(normalized)
                used.add(name.lowercase())
            }
            result[path] = name
        }
        return result
    }

    private fun shortHash(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .take(4)
            .joinToString("") { "%02x".format(it) }
}
