package nl.ferron.copilotcontextbridge.staging

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

object SessionCleanupService {
    fun cleanup(
        root: Path,
        cutoff: Instant,
    ): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()
        val deleted = mutableListOf<Path>()
        Files.list(root).use { stream ->
            stream
                .filter { Files.isDirectory(it) && !Files.exists(it.resolve(".session/.keep")) }
                .filter { runCatching { Files.getLastModifiedTime(it).toInstant().isBefore(cutoff) }.getOrDefault(false) }
                .forEach { old ->
                    runCatching {
                        Files.walk(old).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
                        deleted.add(old)
                    }
                }
        }
        return deleted
    }
}
