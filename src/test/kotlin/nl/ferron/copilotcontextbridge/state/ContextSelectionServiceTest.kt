package nl.ferron.copilotcontextbridge.state

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ContextSelectionServiceTest : BasePlatformTestCase() {
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

    fun testPreparedBatchIsMarkedHandedOffAfterSendAction() {
        val service = project.getService(ContextSelectionService::class.java)
        service.markExported("session-1", "General change", listOf("src/main.py"), false)

        assertEquals("PREPARED", service.batches().single().status)
        service.markHandedOff("session-1")
        assertEquals("HANDED_OFF", service.batches().single().status)
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
