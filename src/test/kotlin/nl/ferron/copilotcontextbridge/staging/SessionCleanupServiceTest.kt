package nl.ferron.copilotcontextbridge.staging

import junit.framework.TestCase
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant

class SessionCleanupServiceTest : TestCase() {
    fun testDeletesOnlyExpiredUnkeptSessions() {
        val root = Files.createTempDirectory("ccb-cleanup-test")
        val old = session(root, "old")
        val kept = session(root, "kept")
        val recent = session(root, "recent")
        Files.writeString(kept.resolve(".session/.keep"), "keep\n")
        val oldTime = FileTime.from(Instant.parse("2026-01-01T00:00:00Z"))
        Files.setLastModifiedTime(old, oldTime)
        Files.setLastModifiedTime(kept, oldTime)

        val deleted = SessionCleanupService.cleanup(root, Instant.parse("2026-02-01T00:00:00Z"))

        assertEquals(listOf(old), deleted)
        assertFalse(Files.exists(old))
        assertTrue(Files.isDirectory(kept))
        assertTrue(Files.isDirectory(recent))
    }

    private fun session(
        root: java.nio.file.Path,
        name: String,
    ): java.nio.file.Path {
        val directory = root.resolve(name)
        Files.createDirectories(directory.resolve(".session"))
        Files.writeString(directory.resolve("file.py"), "pass\n")
        return directory
    }
}
