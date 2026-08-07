package nl.ferron.copilotcontextbridge.staging

import java.nio.file.Files
import java.nio.file.Path

object TextFileSupport {
    private val directMicrosoft365Extensions =
        setOf("py", "txt", "md", "json", "csv", "yaml", "yml", "xml", "sql", "html", "htm", "js", "ts", "java", "kt")

    fun requiresMicrosoft365TextCopy(relativePath: String): Boolean {
        val extension = relativePath.substringAfterLast('.', "").lowercase()
        return extension !in directMicrosoft365Extensions
    }

    fun isLikelyText(path: Path): Boolean =
        runCatching {
            Files.newInputStream(path).use { input ->
                val bytes = input.readNBytes(16_384)
                if (bytes.isEmpty()) return@use true
                if (bytes.any { it == 0.toByte() }) return@use false
                val controls =
                    bytes.count { byte ->
                        val value = byte.toInt() and 0xff
                        value < 32 && value !in setOf(9, 10, 12, 13)
                    }
                controls.toDouble() / bytes.size <= 0.02
            }
        }.getOrDefault(false)
}
