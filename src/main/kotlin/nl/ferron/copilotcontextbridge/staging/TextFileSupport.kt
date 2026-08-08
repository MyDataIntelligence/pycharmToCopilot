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
                isLikelyText(bytes)
            }
        }.getOrDefault(false)

    fun isLikelyText(bytes: ByteArray): Boolean {
        val sample = if (bytes.size <= 16_384) bytes else bytes.copyOf(16_384)
        if (sample.isEmpty()) return true
        if (sample.any { it == 0.toByte() }) return false
        val controls =
            sample.count { byte ->
                val value = byte.toInt() and 0xff
                value < 32 && value !in setOf(9, 10, 12, 13)
            }
        return controls.toDouble() / sample.size <= 0.02
    }
}
