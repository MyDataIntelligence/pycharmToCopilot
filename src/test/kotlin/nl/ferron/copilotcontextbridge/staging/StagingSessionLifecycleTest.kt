package nl.ferron.copilotcontextbridge.staging

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class StagingSessionLifecycleTest : BasePlatformTestCase() {
    fun testKeepMarkerAndSafeSessionDeletion() {
        val root = Path.of(System.getProperty("java.io.tmpdir"), "CopilotContextBridge")
        val session = root.resolve("test-${UUID.randomUUID()}")
        Files.createDirectories(session.resolve(".session"))
        Files.writeString(session.resolve("safe-copy.py"), "print('copy')\n")
        val service = StagingService(project)

        service.keepSession(session)
        assertTrue(Files.isRegularFile(session.resolve(".session/.keep")))

        service.deleteSession(session)
        assertFalse(Files.exists(session))
    }

    fun testDeletionRejectsPathsOutsideStagingRoot() {
        val outside = Path.of(System.getProperty("java.io.tmpdir"), "not-a-copilot-session")

        try {
            StagingService(project).deleteSession(outside)
            fail("Unsafe staging deletion should be rejected.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("outside the staging root"))
        }
    }
}
