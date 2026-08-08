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
import java.util.UUID
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

        @JvmField var conversationSessionId: String = ""

        @JvmField var batchNumber: Int = 0
    }

    class Data {
        @JvmField var pinnedPaths: MutableList<String> = mutableListOf()

        @JvmField var discoveryRoots: MutableList<String> = mutableListOf()

        @JvmField var batches: MutableList<BatchState> = mutableListOf()

        @JvmField var invalidPinnedPaths: MutableList<String> = mutableListOf()

        @JvmField var excludedAutomaticPaths: MutableList<String> = mutableListOf()

        @JvmField var excludedThisBatchPaths: MutableList<String> = mutableListOf()

        @JvmField var excludedThisSessionPaths: MutableList<String> = mutableListOf()

        @JvmField var alwaysExcludedPaths: MutableList<String> = mutableListOf()

        @JvmField var includeOncePaths: MutableList<String> = mutableListOf()

        @JvmField var activeConversationSessionId: String = UUID.randomUUID().toString()

        @JvmField var nextBatchNumber: Int = 1
    }

    private var data = Data()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    override fun getState(): Data = data

    override fun loadState(state: Data) {
        data = state
        if (data.excludedThisBatchPaths.isEmpty() && data.excludedAutomaticPaths.isNotEmpty()) {
            data.excludedThisBatchPaths.addAll(data.excludedAutomaticPaths)
            data.excludedAutomaticPaths.clear()
        }
        if (data.activeConversationSessionId.isBlank()) data.activeConversationSessionId = UUID.randomUUID().toString()
        data.nextBatchNumber = data.nextBatchNumber.coerceAtLeast(1)
        data.batches.forEachIndexed { index, batch ->
            if (batch.conversationSessionId.isBlank()) batch.conversationSessionId = data.activeConversationSessionId
            if (batch.batchNumber <= 0) batch.batchNumber = index + 1
        }
        validatePaths()
    }

    fun pinnedPaths(): List<String> = data.pinnedPaths.distinct()

    fun invalidPinnedPaths(): List<String> = data.invalidPinnedPaths.toList()

    fun discoveryRoots(): List<String> = data.discoveryRoots.distinct()

    fun excludedAutomaticPaths(): Set<String> =
        (data.alwaysExcludedPaths + data.excludedThisSessionPaths + data.excludedThisBatchPaths)
            .toSet() - data.includeOncePaths.toSet()

    fun batchExcludedPaths(): Set<String> = data.excludedThisBatchPaths.toSet()

    fun sessionExcludedPaths(): Set<String> = data.excludedThisSessionPaths.toSet()

    fun alwaysExcludedPaths(): Set<String> = data.alwaysExcludedPaths.toSet()

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
        files.filter { it.isInLocalFileSystem && VfsUtilCore.isAncestor(root, it, false) }.forEach { selected ->
            val path =
                selected.path
                    .removePrefix(root.path)
                    .trimStart('/')
                    .replace('\\', '/')
            if (selected.isDirectory) {
                if (path !in data.discoveryRoots) data.discoveryRoots.add(path)
            } else if (path.isNotBlank() && path !in data.pinnedPaths) {
                data.pinnedPaths.add(path)
            }
        }
        validatePaths()
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

    fun excludeAutomaticPath(path: String) = excludeForBatch(path)

    fun excludeForBatch(path: String) {
        data.includeOncePaths.remove(path)
        if (path !in data.excludedThisBatchPaths) data.excludedThisBatchPaths.add(path)
        fireChanged()
    }

    fun excludeForSession(path: String) {
        data.includeOncePaths.remove(path)
        data.excludedThisBatchPaths.remove(path)
        if (path !in data.excludedThisSessionPaths) data.excludedThisSessionPaths.add(path)
        fireChanged()
    }

    fun alwaysExclude(path: String) {
        data.includeOncePaths.remove(path)
        data.excludedThisBatchPaths.remove(path)
        data.excludedThisSessionPaths.remove(path)
        if (path !in data.alwaysExcludedPaths) data.alwaysExcludedPaths.add(path)
        fireChanged()
    }

    fun includeOnce(path: String) {
        if (path !in data.includeOncePaths) data.includeOncePaths.add(path)
        fireChanged()
    }

    fun removePermanentExclusion(path: String) {
        data.alwaysExcludedPaths.remove(path)
        data.excludedThisSessionPaths.remove(path)
        data.excludedThisBatchPaths.remove(path)
        data.includeOncePaths.remove(path)
        fireChanged()
    }

    fun clearAutomaticExclusions() {
        if (data.excludedThisBatchPaths.isEmpty() && data.excludedThisSessionPaths.isEmpty() && data.alwaysExcludedPaths.isEmpty()) return
        data.excludedThisBatchPaths.clear()
        data.excludedThisSessionPaths.clear()
        data.alwaysExcludedPaths.clear()
        data.includeOncePaths.clear()
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
        data.excludedThisBatchPaths.clear()
        data.includeOncePaths.clear()
        fireChanged()
    }

    fun sentPaths(): Set<String> =
        data.batches
            .filter { it.conversationSessionId == data.activeConversationSessionId }
            .flatMapTo(linkedSetOf()) { it.paths }

    fun allSentPaths(): Set<String> = data.batches.flatMapTo(linkedSetOf()) { it.paths }

    fun batches(): List<BatchSummary> =
        data.batches.map {
            BatchSummary(it.sessionId, it.createdAt, it.promptSkillName, it.paths.toList(), it.status)
        }

    fun currentSessionBatches(): List<BatchSummary> =
        data.batches
            .filter { it.conversationSessionId == data.activeConversationSessionId }
            .map { BatchSummary(it.sessionId, it.createdAt, it.promptSkillName, it.paths.toList(), it.status) }

    fun activeConversationSessionId(): String = data.activeConversationSessionId

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
                conversationSessionId = data.activeConversationSessionId
                batchNumber = data.nextBatchNumber
            }
        data.batches.add(batch)
        data.nextBatchNumber++
        if (clearActive) {
            data.pinnedPaths.removeAll(paths.toSet())
            data.invalidPinnedPaths.removeAll(paths.toSet())
        }
        fireChanged()
    }

    fun startNewSession() {
        data.activeConversationSessionId = UUID.randomUUID().toString()
        data.nextBatchNumber = 1
        data.excludedThisSessionPaths.clear()
        clear()
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

    /** Invalidates any current preview after settings-only actions that do not otherwise mutate selection state. */
    fun requestRecalculation() {
        validatePaths()
        fireChanged()
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
