package nl.ferron.copilotcontextbridge.state

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import nl.ferron.copilotcontextbridge.ProjectRoot
import nl.ferron.copilotcontextbridge.model.BatchSummary
import nl.ferron.copilotcontextbridge.security.PathSafety
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

@State(name = "CopilotContextSelection", storages = [Storage(".idea/copilot-context-selection.xml")])
class ContextSelectionService(
    private val project: Project,
) : PersistentStateComponent<ContextSelectionService.Data> {
    class BatchState {
        @JvmField var sessionId: String = ""

        @JvmField var createdAt: String = ""

        @JvmField var promptSkillName: String = ""

        @JvmField var paths: MutableList<String> = mutableListOf()

        @JvmField var status: String = "PREPARED"
    }

    class Data {
        @JvmField var pinnedPaths: MutableList<String> = mutableListOf()

        @JvmField var discoveryRoots: MutableList<String> = mutableListOf()

        @JvmField var batches: MutableList<BatchState> = mutableListOf()

        @JvmField var invalidPinnedPaths: MutableList<String> = mutableListOf()

        @JvmField var excludedAutomaticPaths: MutableList<String> = mutableListOf()
    }

    private var data = Data()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    override fun getState(): Data = data

    override fun loadState(state: Data) {
        data = state
        validatePaths()
    }

    fun pinnedPaths(): List<String> = data.pinnedPaths.distinct()

    fun invalidPinnedPaths(): List<String> = data.invalidPinnedPaths.toList()

    fun discoveryRoots(): List<String> = data.discoveryRoots.distinct()

    fun excludedAutomaticPaths(): Set<String> = data.excludedAutomaticPaths.toSet()

    fun addFiles(files: Collection<VirtualFile>) {
        val root = ProjectRoot.virtualFile(project)
        files.filter { !it.isDirectory && it.isInLocalFileSystem && VfsUtilCore.isAncestor(root, it, false) }.forEach { file ->
            val path =
                file.path
                    .removePrefix(root.path)
                    .trimStart('/')
                    .replace('\\', '/')
            if (path.isNotBlank() && path !in data.pinnedPaths) data.pinnedPaths.add(path)
        }
        validatePaths()
        fireChanged()
    }

    fun addSelection(files: Collection<VirtualFile>) {
        val root = ProjectRoot.virtualFile(project)
        addFiles(files.filterNot { it.isDirectory })
        files.filter { it.isDirectory && it.isInLocalFileSystem && VfsUtilCore.isAncestor(root, it, false) }.forEach { directory ->
            val path =
                directory.path
                    .removePrefix(root.path)
                    .trimStart('/')
                    .replace('\\', '/')
            if (path !in data.discoveryRoots) data.discoveryRoots.add(path)
        }
        fireChanged()
    }

    fun addRelativePaths(paths: Collection<String>) {
        paths
            .mapNotNull { runCatching { PathSafety.normalizeRelative(it) }.getOrNull() }
            .filter { it.isNotBlank() && it !in data.pinnedPaths }
            .forEach(data.pinnedPaths::add)
        validatePaths()
        fireChanged()
    }

    fun excludeAutomaticPath(path: String) {
        if (path !in data.excludedAutomaticPaths) data.excludedAutomaticPaths.add(path)
        fireChanged()
    }

    fun clearAutomaticExclusions() {
        if (data.excludedAutomaticPaths.isEmpty()) return
        data.excludedAutomaticPaths.clear()
        fireChanged()
    }

    fun removeFiles(files: Collection<VirtualFile>) {
        val root = ProjectRoot.virtualFile(project)
        val paths =
            files
                .map {
                    it.path
                        .removePrefix(root.path)
                        .trimStart('/')
                        .replace('\\', '/')
                }.toSet()
        data.pinnedPaths.removeAll(paths)
        data.invalidPinnedPaths.removeAll(paths)
        data.discoveryRoots.removeAll(paths)
        fireChanged()
    }

    fun removePath(path: String) {
        data.pinnedPaths.remove(path)
        data.invalidPinnedPaths.remove(path)
        fireChanged()
    }

    fun clear() {
        data.pinnedPaths.clear()
        data.discoveryRoots.clear()
        data.invalidPinnedPaths.clear()
        data.excludedAutomaticPaths.clear()
        fireChanged()
    }

    fun sentPaths(): Set<String> = data.batches.flatMapTo(linkedSetOf()) { it.paths }

    fun batches(): List<BatchSummary> =
        data.batches.map {
            BatchSummary(it.sessionId, it.createdAt, it.promptSkillName, it.paths.toList(), it.status)
        }

    fun markExported(
        sessionId: String,
        promptSkillName: String,
        paths: List<String>,
        clearActive: Boolean,
    ) {
        val batch =
            BatchState().apply {
                this.sessionId = sessionId
                createdAt = Instant.now().toString()
                this.promptSkillName = promptSkillName
                this.paths.addAll(paths.distinct())
            }
        data.batches.add(batch)
        if (clearActive) {
            data.pinnedPaths.removeAll(paths.toSet())
            data.invalidPinnedPaths.removeAll(paths.toSet())
        }
        fireChanged()
    }

    fun restoreBatch(sessionId: String) {
        val batch = data.batches.firstOrNull { it.sessionId == sessionId } ?: return
        addRelativePaths(batch.paths)
    }

    fun markHandedOff(sessionId: String) {
        val batch = data.batches.firstOrNull { it.sessionId == sessionId } ?: return
        if (batch.status == "HANDED_OFF") return
        batch.status = "HANDED_OFF"
        fireChanged()
    }

    fun deleteBatch(sessionId: String) {
        data.batches.removeIf { it.sessionId == sessionId }
        fireChanged()
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun validatePaths() {
        val root = runCatching { ProjectRoot.virtualFile(project) }.getOrNull()
        data.invalidPinnedPaths.clear()
        if (root == null) {
            data.invalidPinnedPaths.addAll(data.pinnedPaths)
            return
        }
        data.pinnedPaths.filterTo(data.invalidPinnedPaths) { root.findFileByRelativePath(it) == null }
    }

    private fun fireChanged() = listeners.forEach { it.invoke() }
}
