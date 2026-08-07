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
}
