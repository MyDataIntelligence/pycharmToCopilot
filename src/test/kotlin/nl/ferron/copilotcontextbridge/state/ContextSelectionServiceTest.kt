package nl.ferron.copilotcontextbridge.state

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import nl.ferron.copilotcontextbridge.ProjectRoot
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
