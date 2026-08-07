package nl.ferron.copilotcontextbridge.context

import com.intellij.openapi.util.io.FileUtil
import junit.framework.TestCase
import java.nio.file.Files

class GitBranchContextResolverTest : TestCase() {
    fun testBuildsFactualBranchContextAndHonorsSelectedChangedPaths() {
        val root = Files.createTempDirectory("ccb-git-context")
        try {
            git(root, "init", "-b", "main")
            git(root, "config", "user.email", "test@example.invalid")
            git(root, "config", "user.name", "Test User")
            Files.writeString(root.resolve("one.py"), "VALUE = 1\n")
            Files.writeString(root.resolve("two.py"), "VALUE = 2\n")
            git(root, "add", ".")
            git(root, "commit", "-m", "Initial")
            git(root, "checkout", "-b", "feature/context")
            Files.writeString(root.resolve("one.py"), "VALUE = 10\n")
            Files.writeString(root.resolve("two.py"), "VALUE = 20\n")

            val result = GitBranchContextResolver(root).resolve(setOf("one.py"))!!

            assertEquals("feature/context", result.branch)
            assertEquals("main", result.baseBranch)
            assertEquals(listOf("one.py"), result.changedPaths)
            assertTrue(result.markdown.contains("`one.py`"))
            assertTrue(result.markdown.contains("VALUE = 10"))
            assertFalse(result.markdown.contains("VALUE = 20"))
            assertTrue(result.markdown.contains("### Reviewer focus"))
        } finally {
            FileUtil.delete(root.toFile())
        }
    }

    fun testReturnsNullOutsideGitRepository() {
        val root = Files.createTempDirectory("ccb-not-git")
        try {
            assertNull(GitBranchContextResolver(root).resolve())
        } finally {
            Files.deleteIfExists(root)
        }
    }

    private fun git(
        root: java.nio.file.Path,
        vararg arguments: String,
    ) {
        val process = ProcessBuilder(listOf("git", "-C", root.toString()) + arguments).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals("git ${arguments.joinToString(" ")} failed: $output", 0, process.waitFor())
    }
}
