package nl.ferron.copilotcontextbridge.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SecurityTest {
    @Test fun `ignore matcher supports globstar and negation`() {
        val matcher = IgnoreMatcher(listOf("build/", "**/*.pyc", "*.env", "!safe.env"))
        assertTrue(matcher.isIgnored("build/output.txt"))
        assertTrue(matcher.isIgnored("src/cache/x.pyc"))
        assertTrue(matcher.isIgnored("prod.env"))
        assertFalse(matcher.isIgnored("safe.env"))
    }

    @Test fun `path traversal and absolute paths are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { PathSafety.normalizeRelative("../../outside.py") }
        assertThrows(IllegalArgumentException::class.java) { PathSafety.normalizeRelative("C:\\Windows\\x.py") }
        assertThrows(IllegalArgumentException::class.java) { PathSafety.normalizeRelative("/etc/passwd") }
    }

    @Test fun `safe path resolves inside root`() {
        val root = Files.createTempDirectory("ccb-safe")
        val target = root.resolve("src/test.py")
        Files.createDirectories(target.parent)
        Files.writeString(target, "pass")
        assertTrue(PathSafety.resolveInside(root, "src/test.py").startsWith(root))
    }

    @Test fun `secret detector reports rule without value`() {
        val detector = SecretDetector(listOf(".env", "*.pem"))
        assertTrue(detector.suspiciousFilename(".env"))
        val finding = detector.scanText("client_secret = \"abcdefghijklmnopqrstuvwxyz\"").single()
        assertEquals("secret-assignment", finding.rule)
    }
}
