package nl.ferron.copilotcontextbridge.external

import junit.framework.TestCase
import java.nio.file.Files

class ExternalRepositorySelectionRegistryTest : TestCase() {
    fun testClearRemovesBatchExclusionsEvenWhenSourcesWereAlreadyRemoved() {
        val root = Files.createTempDirectory("ccb-external-registry")
        try {
            val file = root.resolve("src/service.py")
            Files.createDirectories(file.parent)
            Files.writeString(file, "def service():\n    return True\n")
            val repository = ExternalRepositoryDropResolver.Repository("external", "external", root, false)
            val source =
                ExternalRepositoryDropResolver.Source(
                    repository,
                    "src/service.py",
                    file,
                    ExternalRepositoryDropResolver.Kind.PINNED_FILE,
                )
            val registry = ExternalRepositorySelectionRegistry()
            registry.registerConfirmed(listOf(source))
            registry.excludeForBatch(source.key)
            registry.remove(source.key)

            // A clear after the source was removed must still reset the batch decision. Otherwise
            // a later drop of the same repository path unexpectedly remains excluded.
            registry.clear()
            registry.registerConfirmed(listOf(source))
            assertFalse(source.key in registry.excludedSourceKeys())
        } finally {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    fun testSameNamedRepositoryGetsNewStableIdWithoutRenamingExistingSource() {
        val firstRoot = Files.createTempDirectory("ccb-external-api-")
        val secondRoot = Files.createTempDirectory("ccb-external-api-")
        try {
            val firstFile = firstRoot.resolve("src/service.py")
            val secondFile = secondRoot.resolve("src/service.py")
            Files.createDirectories(firstFile.parent)
            Files.createDirectories(secondFile.parent)
            Files.writeString(firstFile, "def first():\n    return True\n")
            Files.writeString(secondFile, "def second():\n    return True\n")
            val first = source(firstRoot, firstFile)
            val second = source(secondRoot, secondFile)
            val registry = ExternalRepositorySelectionRegistry()

            registry.registerConfirmed(listOf(first))
            val firstId =
                registry
                    .selections()
                    .single()
                    .repository.id
            registry.excludeForSession("$firstId::src/service.py")
            registry.registerConfirmed(listOf(second))

            val ids = registry.selections().map { it.repository.id }
            assertTrue(firstId in ids)
            assertEquals(2, ids.toSet().size)
            assertTrue("$firstId::src/service.py" in registry.excludedSourceKeys())
        } finally {
            Files.walk(firstRoot).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            Files.walk(secondRoot).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun source(
        root: java.nio.file.Path,
        file: java.nio.file.Path,
    ): ExternalRepositoryDropResolver.Source =
        ExternalRepositoryDropResolver.Source(
            ExternalRepositoryDropResolver.Repository("api", "api", root, false),
            root.relativize(file).toString().replace('\\', '/'),
            file,
            ExternalRepositoryDropResolver.Kind.PINNED_FILE,
        )
}
