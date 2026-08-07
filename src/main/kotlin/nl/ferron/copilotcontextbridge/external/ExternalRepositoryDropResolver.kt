package nl.ferron.copilotcontextbridge.external

import nl.ferron.copilotcontextbridge.model.ContextCandidate
import nl.ferron.copilotcontextbridge.model.DependencyRelation
import nl.ferron.copilotcontextbridge.model.RelationConfidence
import nl.ferron.copilotcontextbridge.model.RelationType
import nl.ferron.copilotcontextbridge.security.IgnoreMatcher
import nl.ferron.copilotcontextbridge.security.SecretDetector
import nl.ferron.copilotcontextbridge.staging.TextFileSupport
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Resolves files dropped from the operating-system file manager without trusting their supplied path.
 *
 * External inputs must belong to a Git work tree. The open project root is also accepted when it is not a Git
 * repository. Symbolic links are rejected rather than followed, and repository ignore/security rules are applied
 * before a file can become pinned or a directory can become a discovery root.
 */
class ExternalRepositoryDropResolver(
    currentRepositoryRoot: Path,
    private val globalIgnorePatterns: Collection<String>,
    private val customIgnorePatterns: Collection<String> = emptyList(),
    secretFilenamePatterns: Collection<String>,
    private val textualScanLimitBytes: Long = 2L * 1024 * 1024,
) {
    data class Repository(
        val id: String,
        val name: String,
        val root: Path,
        val current: Boolean,
    )

    data class Source(
        val repository: Repository,
        val relativePath: String,
        val absolutePath: Path,
        val kind: Kind,
        val secretWarning: String? = null,
    ) {
        val key: String get() = "${repository.id}::$relativePath"
    }

    enum class Kind { PINNED_FILE, DISCOVERY_DIRECTORY }

    data class Rejected(
        val suppliedPath: Path,
        val reason: String,
    )

    data class Result(
        val repositories: List<Repository>,
        val accepted: List<Source>,
        val confirmationRequired: List<Source>,
        val rejected: List<Rejected>,
    ) {
        val safe: Boolean get() = rejected.isEmpty() && confirmationRequired.isEmpty()
    }

    private val currentRoot = currentRepositoryRoot.toRealPath()
    private val secretDetector = SecretDetector(secretFilenamePatterns)

    fun resolve(
        suppliedPaths: Collection<Path>,
        confirmedSensitiveKeys: Set<String> = emptySet(),
    ): Result {
        val raw = mutableListOf<RawSource>()
        val rejected = mutableListOf<Rejected>()
        suppliedPaths
            .distinctBy {
                it
                    .toAbsolutePath()
                    .normalize()
                    .toString()
                    .lowercase()
            }.forEach { supplied ->
                resolveOne(supplied).fold(
                    onSuccess = raw::add,
                    onFailure = { rejected += Rejected(supplied, it.message ?: "The dropped path is not safe.") },
                )
            }

        val repositories = repositoriesFor(raw.map { it.repositoryRoot }.toSet())
        val repositoryByRoot = repositories.associateBy { it.root }
        val accepted = mutableListOf<Source>()
        val pending = mutableListOf<Source>()
        raw.sortedWith(compareBy({ it.repositoryRoot.toString().lowercase() }, { it.relativePath.lowercase() })).forEach { item ->
            val repository = repositoryByRoot.getValue(item.repositoryRoot)
            val source = Source(repository, item.relativePath, item.absolutePath, item.kind, item.secretWarning)
            if (item.secretWarning != null && source.key !in confirmedSensitiveKeys) pending += source else accepted += source
        }
        return Result(repositories, accepted, pending, rejected)
    }

    /** Expands discovery roots for analysis while still applying ignore, secret, symlink and size rules. */
    fun discoverFiles(
        source: Source,
        maximumFiles: Int = 500,
    ): Result {
        require(source.kind == Kind.DISCOVERY_DIRECTORY) { "Only a discovery directory can be expanded." }
        val root = source.repository.root
        val directory = safeExisting(root, source.relativePath.ifBlank { "." })
        val paths =
            Files
                .walk(directory)
                .use { stream ->
                    stream
                        .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }
                        .limit(maximumFiles.toLong().coerceAtLeast(1))
                        .toList()
                }
        return resolve(paths)
    }

    fun toCandidate(source: Source): ContextCandidate {
        require(source.kind == Kind.PINNED_FILE) { "Discovery directories are not upload candidates." }
        val size = Files.size(source.absolutePath)
        return ContextCandidate(
            relativePath = source.relativePath,
            absolutePath = source.absolutePath,
            score = 1_000,
            depth = 0,
            confidence = RelationConfidence.CONFIRMED,
            relations =
                listOf(
                    DependencyRelation(
                        source.relativePath,
                        source.relativePath,
                        RelationType.PINNED,
                        RelationConfidence.CONFIRMED,
                        depth = 0,
                        evidence = "manually dropped from the operating-system file manager",
                    ),
                ),
            pinned = true,
            secretWarning = source.secretWarning,
            size = size,
            sha256 = sha256(source.absolutePath),
            repositoryId = source.repository.id,
            repositoryRoot = source.repository.root,
            repositoryName = source.repository.name,
        )
    }

    private fun resolveOne(supplied: Path): kotlin.Result<RawSource> =
        runCatching {
            val absolute = supplied.toAbsolutePath().normalize()
            require(Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) { "Dropped path does not exist." }
            require(!Files.isSymbolicLink(absolute)) { "Symbolic-link drops are not accepted." }
            val real = absolute.toRealPath(LinkOption.NOFOLLOW_LINKS)
            val isDirectory = Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)
            require(
                isDirectory || Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS),
            ) { "Only regular files and directories are accepted." }
            val repositoryRoot = findRepositoryRoot(real, isDirectory)
            val relative = repositoryRoot.relativize(real).joinToString("/") { it.toString() }
            require(!relative.split('/').contains("..")) { "Dropped path escapes its repository root." }
            val matcher = ignoreMatcher(repositoryRoot)
            require(!matcher.isIgnored(relative, isDirectory)) { "Path is excluded by repository or plugin ignore rules." }
            if (isDirectory) {
                RawSource(repositoryRoot, relative, real, Kind.DISCOVERY_DIRECTORY, null)
            } else {
                require(TextFileSupport.isLikelyText(real)) { "Only text-based repository files can be added to Copilot context." }
                val warning = inspectSecret(real, relative)
                RawSource(repositoryRoot, relative, real, Kind.PINNED_FILE, warning)
            }
        }

    private fun findRepositoryRoot(
        path: Path,
        directory: Boolean,
    ): Path {
        var cursor: Path? = if (directory) path else path.parent
        while (cursor != null) {
            val marker = cursor.resolve(".git")
            if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                require(!Files.isSymbolicLink(marker)) { "Repository .git marker may not be a symbolic link." }
                val root = cursor.toRealPath(LinkOption.NOFOLLOW_LINKS)
                require(path.startsWith(root)) { "Dropped path escapes the detected repository root." }
                return root
            }
            cursor = cursor.parent
        }
        if (path.startsWith(currentRoot)) return currentRoot
        throw IllegalArgumentException("External path is not inside a Git repository (no .git marker found).")
    }

    private fun repositoriesFor(roots: Set<Path>): List<Repository> {
        val grouped = roots.groupBy { sanitizeRepositoryName(it.fileName?.toString().orEmpty()) }
        return roots
            .sortedBy { it.toString().lowercase() }
            .map { root ->
                val name = sanitizeRepositoryName(root.fileName?.toString().orEmpty())
                val id = if (grouped.getValue(name).size == 1) name else "$name-${shortHash(root.toString().lowercase())}"
                Repository(
                    id,
                    root.fileName
                        ?.toString()
                        .orEmpty()
                        .ifBlank { name },
                    root,
                    root == currentRoot,
                )
            }
    }

    private fun ignoreMatcher(repositoryRoot: Path): IgnoreMatcher {
        val repositoryPatterns =
            listOf(".gitignore", ".ignore").flatMap { filename ->
                val path = repositoryRoot.resolve(filename)
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(path) &&
                    Files.size(path) <= 1024 * 1024
                ) {
                    Files.readAllLines(path, StandardCharsets.UTF_8)
                } else {
                    emptyList()
                }
            }
        return IgnoreMatcher(globalIgnorePatterns + customIgnorePatterns + repositoryPatterns)
    }

    private fun inspectSecret(
        path: Path,
        relativePath: String,
    ): String? {
        if (secretDetector.suspiciousFilename(relativePath)) return "suspicious filename"
        if (Files.size(path) > textualScanLimitBytes) return null
        val text = runCatching { Files.readString(path, StandardCharsets.UTF_8) }.getOrNull() ?: return null
        return secretDetector.scanText(text).firstOrNull()?.let { "${it.rule} near line ${it.line}" }
    }

    private fun safeExisting(
        root: Path,
        relative: String,
    ): Path {
        val path = root.resolve(relative).normalize()
        require(path.startsWith(root)) { "Discovery path escapes its repository root." }
        require(!Files.isSymbolicLink(path)) { "Symbolic-link discovery roots are not accepted." }
        val real = path.toRealPath(LinkOption.NOFOLLOW_LINKS)
        require(real.startsWith(root)) { "Discovery path resolves outside its repository root." }
        return real
    }

    private fun sanitizeRepositoryName(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "-").trim('-').ifBlank { "repository" }

    private fun shortHash(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray())
            .take(4)
            .joinToString("") { "%02x".format(it) }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return "sha256:" + digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class RawSource(
        val repositoryRoot: Path,
        val relativePath: String,
        val absolutePath: Path,
        val kind: Kind,
        val secretWarning: String?,
    )
}
