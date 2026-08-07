package nl.ferron.copilotcontextbridge.analysis

import nl.ferron.copilotcontextbridge.security.IgnoreMatcher
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

class RepositoryScanner(
    private val root: Path,
    defaultPatterns: Collection<String>,
    customPatterns: Collection<String>,
) {
    data class Entry(
        val relativePath: String,
        val path: Path,
        val directory: Boolean,
        val size: Long,
    )

    data class Snapshot(
        val entries: List<Entry>,
        val ignoredPatterns: List<String>,
    ) {
        val files: List<Entry> get() = entries.filterNot { it.directory }
    }

    private val basePatterns = (defaultPatterns + customPatterns).toMutableList()

    fun scan(): Snapshot {
        val ignoreFilePatterns =
            listOf(".gitignore", ".ignore").flatMap { name ->
                val file = root.resolve(name)
                if (Files.isRegularFile(file)) Files.readAllLines(file, StandardCharsets.UTF_8) else emptyList()
            }
        val patterns = basePatterns + ignoreFilePatterns
        val matcher = IgnoreMatcher(patterns)
        val entries = mutableListOf<Entry>()
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (dir != root) {
                        val relative = relative(dir)
                        if (Files.isSymbolicLink(dir) || matcher.isIgnored(relative, true)) return FileVisitResult.SKIP_SUBTREE
                        entries += Entry(relative, dir, true, 0)
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    val relative = relative(file)
                    if (!Files.isSymbolicLink(file) && !matcher.isIgnored(relative, false)) {
                        entries += Entry(relative, file, false, attrs.size())
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return Snapshot(entries.sortedBy { it.relativePath.lowercase() }, patterns)
    }

    fun renderTree(
        snapshot: Snapshot,
        repositoryName: String,
    ): String {
        class Node(
            val name: String,
            var file: Boolean = false,
        ) {
            val children = sortedMapOf<String, Node>(String.CASE_INSENSITIVE_ORDER)
        }
        val rootNode = Node(repositoryName)
        snapshot.entries.forEach { entry ->
            var node = rootNode
            entry.relativePath.split('/').forEachIndexed { index, segment ->
                node = node.children.getOrPut(segment) { Node(segment) }
                if (index == entry.relativePath.count { it == '/' }) node.file = !entry.directory
            }
        }
        val lines = mutableListOf("${rootNode.name}/")

        fun append(
            node: Node,
            prefix: String,
        ) {
            val children = node.children.values.toList()
            children.forEachIndexed { index, child ->
                val last = index == children.lastIndex
                lines += prefix + (if (last) "└── " else "├── ") + child.name + if (child.file) "" else "/"
                if (!child.file) append(child, prefix + if (last) "    " else "│   ")
            }
        }
        append(rootNode, "")
        return lines.joinToString("\n")
    }

    private fun relative(path: Path): String = root.relativize(path).joinToString("/")

    companion object {
        val textExtensions =
            setOf(
                "py",
                "pyi",
                "json",
                "yaml",
                "yml",
                "toml",
                "sql",
                "csv",
                "tsv",
                "md",
                "txt",
                "xml",
                "ini",
                "cfg",
                "conf",
                "sh",
                "ps1",
                "bat",
                "ipynb",
                "properties",
            )

        fun isSupportedText(path: Path): Boolean =
            path.fileName
                .toString()
                .substringAfterLast('.', "")
                .lowercase() in textExtensions
    }
}
