package nl.ferron.copilotcontextbridge.security

import java.nio.file.Files
import java.nio.file.Path

object PathSafety {
    fun normalizeRelative(path: String): String {
        require(path.isNotBlank()) { "Path is empty." }
        require(!Regex("^[A-Za-z]:").containsMatchIn(path)) { "Absolute Windows paths are not allowed." }
        require(!path.startsWith('/') && !path.startsWith('\\')) { "Absolute paths are not allowed." }
        val normalized = Path.of(path.replace('\\', '/')).normalize()
        require(!normalized.isAbsolute && normalized.none { it.toString() == ".." }) { "Path escapes the repository root." }
        val text = normalized.joinToString("/")
        require(text.isNotBlank() && text != ".") { "Path does not identify a file." }
        return text
    }

    fun resolveInside(
        root: Path,
        relative: String,
        mustExist: Boolean = true,
    ): Path {
        val rootReal = root.toRealPath()
        val normalized = normalizeRelative(relative)
        val candidate = rootReal.resolve(normalized).normalize()
        require(candidate.startsWith(rootReal)) { "Path escapes the repository root." }
        if (mustExist) {
            require(Files.exists(candidate)) { "Target does not exist: $normalized" }
            val real = candidate.toRealPath()
            require(real.startsWith(rootReal)) { "Path resolves through a symlink outside the repository." }
            return real
        }
        val existingParent =
            generateSequence(candidate.parent) { it.parent }.firstOrNull(Files::exists)
                ?: throw IllegalArgumentException("No safe parent exists for path: $normalized")
        require(existingParent.toRealPath().startsWith(rootReal)) { "Parent resolves outside the repository." }
        return candidate
    }
}
