package nl.ferron.copilotcontextbridge.external

import junit.framework.TestCase
import nl.ferron.copilotcontextbridge.settings.Defaults
import java.nio.file.Files
import java.nio.file.Path

class ExternalRepositoryDropResolverTest : TestCase() {
    private lateinit var temporaryRoot: Path

    override fun setUp() {
        temporaryRoot = Files.createTempDirectory("ccb-external-drop-")
    }

    override fun tearDown() {
        Files.walk(temporaryRoot).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
    }

    fun testExplorerFilesAreSeparatedByDetectedGitRepository() {
        val current = repository("current")
        val api = repository("api-service")
        val robot = repository("robot-tests")
        val apiFile = write(api, "src/client.py", "def get():\n    return 1\n")
        val robotFile = write(robot, "libraries/orders.py", "def load():\n    return []\n")
        val resolver = resolver(current)

        val result = resolver.resolve(listOf(apiFile, robotFile))

        assertTrue(result.rejected.isEmpty())
        assertTrue(result.confirmationRequired.isEmpty())
        assertEquals(listOf("api-service", "robot-tests"), result.repositories.map { it.id })
        assertEquals(setOf("src/client.py", "libraries/orders.py"), result.accepted.map { it.relativePath }.toSet())
        assertTrue(result.accepted.all { it.kind == ExternalRepositoryDropResolver.Kind.PINNED_FILE })
        assertTrue(result.accepted.none { it.repository.current })
    }

    fun testDirectoryIsDiscoveryInstructionAndNotUploadCandidate() {
        val current = repository("current")
        val external = repository("api")
        val directory = Files.createDirectories(external.resolve("src/functions"))
        write(external, "src/functions/main.py", "print('safe')\n")
        val resolver = resolver(current)

        val result = resolver.resolve(listOf(directory))

        assertEquals(1, result.accepted.size)
        assertEquals(ExternalRepositoryDropResolver.Kind.DISCOVERY_DIRECTORY, result.accepted.single().kind)
        assertFailsWithMessage("Discovery directories") { resolver.toCandidate(result.accepted.single()) }
        val discovered = resolver.discoverFiles(result.accepted.single())
        assertEquals(listOf("src/functions/main.py"), discovered.accepted.map { it.relativePath })
    }

    fun testRepositoryAndPluginIgnoreRulesAreApplied() {
        val current = repository("current")
        val external = repository("api")
        write(external, ".gitignore", "private/\n*.generated.py\n")
        val ignoredByGit = write(external, "private/data.py", "print('hidden')")
        val ignoredByPlugin = write(external, "build/output.py", "print('build')")
        val ignoredCustom = write(external, "scratch/tmp.py", "print('tmp')")
        val resolver = resolver(current, listOf("scratch/"))

        val result = resolver.resolve(listOf(ignoredByGit, ignoredByPlugin, ignoredCustom))

        assertTrue(result.accepted.isEmpty())
        assertEquals(3, result.rejected.size)
        assertTrue(result.rejected.all { "ignore" in it.reason })
    }

    fun testLikelySecretRequiresExactExplicitConfirmation() {
        val current = repository("current")
        val external = repository("api")
        val secret = write(external, ".env", "CLIENT_SECRET='this-is-a-real-looking-secret-value'\n")
        val resolver = resolver(current)

        val first = resolver.resolve(listOf(secret))
        assertTrue(first.accepted.isEmpty())
        assertEquals("suspicious filename", first.confirmationRequired.single().secretWarning)

        val confirmed = resolver.resolve(listOf(secret), setOf(first.confirmationRequired.single().key))
        assertEquals(1, confirmed.accepted.size)
        assertTrue(confirmed.confirmationRequired.isEmpty())
    }

    fun testExternalNonGitPathIsRejectedButCurrentProjectPathIsAccepted() {
        val current = Files.createDirectories(temporaryRoot.resolve("plain-current"))
        val currentFile = write(current, "src/current.py", "print('current')")
        val unrelated = Files.createDirectories(temporaryRoot.resolve("unrelated"))
        val externalFile = write(unrelated, "outside.py", "print('outside')")
        val resolver = resolver(current)

        val result = resolver.resolve(listOf(currentFile, externalFile))

        assertEquals(listOf("src/current.py"), result.accepted.map { it.relativePath })
        assertTrue(
            result.accepted
                .single()
                .repository.current,
        )
        assertEquals(1, result.rejected.size)
        assertTrue(
            result.rejected
                .single()
                .reason
                .contains("no .git marker"),
        )
    }

    fun testEqualRepositoryNamesReceiveStableDistinctIdsAndRegistryKeepsBothPaths() {
        val current = repository("current")
        val firstRoot = repositoryAt(temporaryRoot.resolve("one/shared"))
        val secondRoot = repositoryAt(temporaryRoot.resolve("two/shared"))
        val first = write(firstRoot, "src/config.py", "VALUE = 1\n")
        val second = write(secondRoot, "src/config.py", "VALUE = 2\n")
        val resolver = resolver(current)

        val firstResult = resolver.resolve(listOf(first, second))
        val secondResult = resolver.resolve(listOf(second, first))
        assertEquals(
            firstResult.repositories.map { it.id },
            secondResult.repositories.map { it.id },
        )
        assertEquals(
            2,
            firstResult.repositories
                .map { it.id }
                .distinct()
                .size,
        )

        val registry = ExternalRepositorySelectionRegistry()
        registry.register(resolver.resolve(listOf(first)))
        registry.register(resolver.resolve(listOf(second)))
        assertEquals(2, registry.selections().size)
        assertEquals(2, registry.registeredSourceKeys().size)
        assertEquals(
            2,
            registry
                .candidates(resolver)
                .map { it.repositoryId }
                .distinct()
                .size,
        )
    }

    fun testRegistrySupportsSessionAndIncludeOnceExclusions() {
        val current = repository("current-exclusions")
        val external = repository("external-exclusions")
        val source = write(external, "src/client.py", "def client():\n    return 1\n")
        val registry = ExternalRepositorySelectionRegistry()
        val resolver = resolver(current)
        registry.register(resolver.resolve(listOf(source)))
        val sourceKey = registry.registeredSourceKeys().single()

        registry.excludeForSession(sourceKey)
        assertTrue(sourceKey in registry.excludedSourceKeys())
        assertEquals("session", registry.exclusionScope(sourceKey))

        registry.includeOnce(sourceKey)
        assertFalse(sourceKey in registry.excludedSourceKeys())

        registry.startNewSession()
        assertFalse(sourceKey in registry.excludedSourceKeys())
    }

    private fun resolver(
        current: Path,
        custom: List<String> = emptyList(),
    ) = ExternalRepositoryDropResolver(current, Defaults.ignorePatterns, custom, Defaults.secretPatterns)

    private fun repository(name: String): Path = repositoryAt(temporaryRoot.resolve(name))

    private fun repositoryAt(root: Path): Path {
        Files.createDirectories(root.resolve(".git"))
        return root
    }

    private fun write(
        root: Path,
        relative: String,
        content: String,
    ): Path {
        val path = root.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
        return path
    }

    private fun assertFailsWithMessage(
        part: String,
        action: () -> Unit,
    ) {
        val error = runCatching(action).exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message.orEmpty().contains(part))
    }
}
