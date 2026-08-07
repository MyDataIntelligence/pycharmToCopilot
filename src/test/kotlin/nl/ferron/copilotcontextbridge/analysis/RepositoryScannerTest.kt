package nl.ferron.copilotcontextbridge.analysis

import junit.framework.TestCase
import java.nio.file.Files

class RepositoryScannerTest : TestCase() {
    fun testGitignoreCustomIgnoresAndTreeFiltering() {
        val root = Files.createTempDirectory("ccb-repository-scan")
        Files.createDirectories(root.resolve("src"))
        Files.createDirectories(root.resolve("build"))
        Files.createDirectories(root.resolve("private"))
        Files.writeString(root.resolve("src/main.py"), "print('ok')\n")
        Files.writeString(root.resolve("src/cache.pyc"), "noise")
        Files.writeString(root.resolve("build/output.txt"), "noise")
        Files.writeString(root.resolve("private/hidden.py"), "pass\n")
        Files.writeString(root.resolve(".gitignore"), "build/\n*.pyc\n")

        val scanner = RepositoryScanner(root, emptyList(), listOf("private/"))
        val snapshot = scanner.scan()
        val tree = scanner.renderTree(snapshot, "repo")

        assertTrue(snapshot.files.any { it.relativePath == "src/main.py" })
        assertFalse(snapshot.entries.any { it.relativePath.startsWith("build") })
        assertFalse(snapshot.entries.any { it.relativePath.endsWith(".pyc") })
        assertFalse(snapshot.entries.any { it.relativePath.startsWith("private") })
        assertTrue(tree.contains("main.py"))
        assertFalse(tree.contains("output.txt"))
    }
}
