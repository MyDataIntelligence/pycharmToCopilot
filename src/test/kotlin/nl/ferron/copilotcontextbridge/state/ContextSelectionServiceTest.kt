package nl.ferron.copilotcontextbridge.state

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import nl.ferron.copilotcontextbridge.ProjectRoot
import nl.ferron.copilotcontextbridge.external.ExternalRepositoryDropResolver
import nl.ferron.copilotcontextbridge.external.ExternalRepositorySelectionRegistry
import java.nio.file.Files

class ContextSelectionServiceTest : BasePlatformTestCase() {
    fun testArchiveSourceKeysRemainDistinctAcrossBatches() {
        val service = project.getService(ContextSelectionService::class.java)
        service.markExported(
            "archive-batch",
            "General change",
            listOf("src/main.py"),
            false,
            listOf("first-archive::src/main.py"),
        )

        assertTrue("first-archive::src/main.py" in service.sentSourceKeys())
        assertFalse("second-archive::src/main.py" in service.sentSourceKeys())
        assertEquals(listOf("src/main.py"), service.currentSessionBatches().first { it.sessionId == "archive-batch" }.paths)
    }

    fun testRestoringHistoricalExternalBatchUsesSourceKeyInsteadOfCurrentProjectPath() {
        val externalRoot = Files.createTempDirectory("ccb-historical-external-")
        try {
            val externalFile = externalRoot.resolve("src/service.py")
            Files.createDirectories(externalFile.parent)
            Files.writeString(externalFile, "def service():\n    return True\n")
            val source =
                ExternalRepositoryDropResolver.Source(
                    ExternalRepositoryDropResolver.Repository("api", "api", externalRoot, false),
                    "src/service.py",
                    externalFile,
                    ExternalRepositoryDropResolver.Kind.PINNED_FILE,
                )
            val registry = project.getService(ExternalRepositorySelectionRegistry::class.java)
            project.getService(ContextSelectionService::class.java).clear()
            registry.clear()
            registry.registerConfirmed(listOf(source))
            val sourceKey = registry.registeredSourceKeys().single()
            val selection = project.getService(ContextSelectionService::class.java)
            selection.markExported("external-batch", "General change", listOf("src/service.py"), false, listOf(sourceKey))
            registry.clear()

            val restored = selection.restoreBatch("external-batch")

            assertEquals(listOf(sourceKey), restored.restoredExternalSourceKeys)
            assertEmpty(restored.unresolvedExternalSourceKeys)
            assertEquals(listOf(sourceKey), registry.registeredSourceKeys().toList())
            // The external path must not be interpreted as a current-project pin.
            assertEmpty(selection.pinnedPaths())
        } finally {
            Files.walk(externalRoot).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    fun testMissingHistoricalExternalSourceIsReportedAndNeverPinnedLocally() {
        val selection = project.getService(ContextSelectionService::class.java)
        selection.clear()
        project.getService(ExternalRepositorySelectionRegistry::class.java).clear()
        selection.markExported(
            "missing-external-batch",
            "General change",
            listOf("src/foreign.py"),
            false,
            listOf("gone-repository::src/foreign.py"),
        )

        val restored = selection.restoreBatch("missing-external-batch")

        assertEmpty(restored.restoredExternalSourceKeys)
        assertEquals(listOf("gone-repository::src/foreign.py"), restored.unresolvedExternalSourceKeys)
        assertEmpty(selection.pinnedPaths())
    }

    fun testSettingsOnlyActionCanRequestImmediateRecalculation() {
        val service = project.getService(ContextSelectionService::class.java)
        var changes = 0
        service.addListener { changes++ }

        service.requestRecalculation()

        assertEquals(1, changes)
    }

    fun testAutomaticExclusionsAreBatchScopedAndClearable() {
        val service = project.getService(ContextSelectionService::class.java)

        service.excludeAutomaticPath("src/generated_dependency.py")

        assertEquals(setOf("src/generated_dependency.py"), service.excludedAutomaticPaths())
        service.clearAutomaticExclusions()
        assertEmpty(service.excludedAutomaticPaths())
    }

    fun testExternalSessionExclusionsFollowConversationSessionSwitch() {
        val root = ProjectRoot.path(project)
        val path = root.resolve("external/src/service.py")
        Files.createDirectories(path.parent)
        Files.writeString(path, "def service():\n    return True\n")
        val repository = ExternalRepositoryDropResolver.Repository("external", "external", root, true)
        val source =
            ExternalRepositoryDropResolver.Source(
                repository,
                "external/src/service.py",
                path,
                ExternalRepositoryDropResolver.Kind.PINNED_FILE,
            )
        val registry = project.getService(ExternalRepositorySelectionRegistry::class.java)
        registry.registerConfirmed(listOf(source))
        val selection = project.getService(ContextSelectionService::class.java)
        val originalSession = selection.activeConversationSessionId()

        registry.excludeForSession(source.key)
        assertTrue(source.key in registry.excludedSourceKeys())

        selection.startNewSession()
        registry.startNewSession()
        assertFalse(source.key in registry.excludedSourceKeys())
        assertTrue(selection.switchConversationSession(originalSession))
        assertTrue(source.key in registry.excludedSourceKeys())
    }

    fun testClearRemovesPinnedFilesDiscoveryRootsAndAutomaticExclusions() {
        val file = myFixture.addFileToProject("src/main.py", "print('ok')\n").virtualFile
        val directory = file.parent
        val service = project.getService(ContextSelectionService::class.java)
        service.addSelection(listOf(file, directory))
        service.excludeAutomaticPath("tests/test_main.py")

        service.clear()

        assertEmpty(service.pinnedPaths())
        assertEmpty(service.discoveryRoots())
        assertEmpty(service.excludedAutomaticPaths())
    }

    fun testMultiSelectionAcrossFilesAndDirectoryPublishesOneChange() {
        val root = ProjectRoot.path(project)
        val firstPath = root.resolve("src/first.py")
        val secondPath = root.resolve("tests/test_first.py")
        Files.createDirectories(firstPath.parent)
        Files.createDirectories(secondPath.parent)
        Files.writeString(firstPath, "FIRST = 1\n")
        Files.writeString(secondPath, "def test_first(): pass\n")
        val first = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(firstPath))
        val second = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(secondPath))
        val directory = first.parent
        val service = project.getService(ContextSelectionService::class.java)
        service.loadState(ContextSelectionService.Data())
        var notifications = 0
        service.addListener { notifications++ }

        service.addSelection(listOf(first, second, directory))

        assertEquals(listOf("src/first.py", "tests/test_first.py"), service.pinnedPaths())
        assertEquals(listOf("src"), service.discoveryRoots())
        assertEquals(1, notifications)
    }

    fun testPreparedBatchIsMarkedHandedOffAfterSendAction() {
        val service = project.getService(ContextSelectionService::class.java)
        service.markExported("session-1", "General change", listOf("src/main.py"), false)

        assertEquals("PREPARED", service.batches().first { it.sessionId == "session-1" }.status)
        service.markHandedOff("session-1")
        assertEquals("HANDED_OFF", service.batches().first { it.sessionId == "session-1" }.status)
    }

    fun testUnsafeRelativePathsAreNeverPersisted() {
        val service = project.getService(ContextSelectionService::class.java)
        service.clear()

        service.addRelativePaths(listOf("../../outside.py", "C:\\Windows\\system.py", "/etc/passwd", "src/safe.py"))

        assertEquals(listOf("src/safe.py"), service.pinnedPaths())
    }

    fun testNewSessionDoesNotExposeEarlierBatchesOrSentFileAvoidance() {
        val service = project.getService(ContextSelectionService::class.java)
        service.loadState(ContextSelectionService.Data())
        val firstConversation = service.activeConversationSessionId()
        service.markExported("batch-1", "General change", listOf("src/first.py"), false)

        assertEquals(setOf("src/first.py"), service.sentPaths())
        assertEquals(1, service.currentSessionBatches().size)

        service.startNewSession()

        assertFalse(firstConversation == service.activeConversationSessionId())
        assertEmpty(service.sentPaths())
        assertEquals(setOf("src/first.py"), service.allSentPaths())
        assertEmpty(service.currentSessionBatches())
        assertEquals(1, service.batches().size)
        service.markExported("batch-2", "Fix issue", listOf("src/second.py"), false)
        assertEquals(setOf("src/second.py"), service.sentPaths())
        assertEquals(listOf("batch-2"), service.currentSessionBatches().map { it.sessionId })
    }

    fun testConversationSessionsCanBeSwitchedFromHistory() {
        val service = project.getService(ContextSelectionService::class.java)
        service.loadState(ContextSelectionService.Data())
        val first = service.activeConversationSessionId()
        service.markExported("batch-1", "General change", listOf("src/first.py"), false)
        service.startNewSession()
        val second = service.activeConversationSessionId()
        service.markExported("batch-2", "Fix issue", listOf("src/second.py"), false)

        assertEquals(setOf(first, second), service.conversationSessions().map { it.id }.toSet())
        assertTrue(service.switchConversationSession(first))
        assertEquals(first, service.activeConversationSessionId())
        assertEquals(listOf("batch-1"), service.currentSessionBatches().map { it.sessionId })
        assertFalse(service.switchConversationSession("missing-session"))
    }

    fun testSwitchingSessionsRestoresEachSessionDraftSelection() {
        val service = project.getService(ContextSelectionService::class.java)
        service.loadState(ContextSelectionService.Data())
        val first = service.activeConversationSessionId()
        service.addRelativePaths(listOf("src/first.py"))
        service.excludeForSession("tests/first.py")
        service.markExported("batch-first", "General change", listOf("src/first.py"), false)

        service.startNewSession()
        val second = service.activeConversationSessionId()
        service.addRelativePaths(listOf("src/second.py"))
        service.excludeForBatch("tests/second.py")
        service.markExported("batch-second", "General change", listOf("src/second.py"), false)

        assertTrue(service.switchConversationSession(first))
        assertEquals(listOf("src/first.py"), service.pinnedPaths())
        assertTrue("tests/first.py" in service.excludedAutomaticPaths())
        assertFalse("tests/second.py" in service.excludedAutomaticPaths())

        assertTrue(service.switchConversationSession(second))
        assertEquals(listOf("src/second.py"), service.pinnedPaths())
        assertTrue("tests/second.py" in service.excludedAutomaticPaths())
        assertFalse("tests/first.py" in service.excludedAutomaticPaths())
    }

    fun testExclusionScopesHaveDistinctLifetimesAndIncludeOnceOverridesThem() {
        val service = project.getService(ContextSelectionService::class.java)
        service.loadState(ContextSelectionService.Data())
        service.excludeForBatch("src/batch.py")
        service.excludeForSession("src/session.py")
        service.alwaysExclude("src/permanent.py")

        assertTrue("src/batch.py" in service.excludedAutomaticPaths())
        assertTrue("src/session.py" in service.excludedAutomaticPaths())
        assertTrue("src/permanent.py" in service.excludedAutomaticPaths())

        service.includeOnce("src/permanent.py")
        assertFalse("src/permanent.py" in service.excludedAutomaticPaths())

        service.clear()
        assertFalse("src/batch.py" in service.excludedAutomaticPaths())
        assertTrue("src/session.py" in service.excludedAutomaticPaths())
        assertTrue("src/permanent.py" in service.excludedAutomaticPaths())

        service.startNewSession()
        assertFalse("src/session.py" in service.excludedAutomaticPaths())
        assertTrue("src/permanent.py" in service.excludedAutomaticPaths())
    }
}
