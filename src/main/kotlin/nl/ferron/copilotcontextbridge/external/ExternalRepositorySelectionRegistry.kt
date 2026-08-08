package nl.ferron.copilotcontextbridge.external

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import nl.ferron.copilotcontextbridge.model.ContextCandidate
import nl.ferron.copilotcontextbridge.settings.ProjectSettings
import nl.ferron.copilotcontextbridge.state.ContextSelectionService
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Repository-separated selection state behind the Explorer drop UI.
 *
 * A caller must explicitly confirm every item in [ExternalRepositoryDropResolver.Result.confirmationRequired] and
 * resolve it again with that source key before it can be registered.
 */
@Service(Service.Level.PROJECT)
class ExternalRepositorySelectionRegistry(
    private val project: Project? = null,
) {
    data class RepositorySelection(
        val repository: ExternalRepositoryDropResolver.Repository,
        val pinnedFiles: List<ExternalRepositoryDropResolver.Source>,
        val discoveryDirectories: List<ExternalRepositoryDropResolver.Source>,
        val archiveFiles: List<ExternalRepositoryDropResolver.Source> = emptyList(),
    )

    private val sources = linkedMapOf<String, ExternalRepositoryDropResolver.Source>()

    /** Stable repository IDs prevent exclusion keys changing when another same-named repo is dropped. */
    private val fallbackRepositoryIds = linkedMapOf<String, String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val batchExcluded = linkedSetOf<String>()
    private val includeOnce = linkedSetOf<String>()

    fun register(result: ExternalRepositoryDropResolver.Result) {
        val combined =
            (sources.values + result.accepted)
                .distinctBy { it.repository.root.toString() + "::" + it.relativePath }
        val assignedIds = linkedMapOf<String, String>()
        combined
            .map { it.repository }
            .distinctBy {
                it.root
                    .toAbsolutePath()
                    .normalize()
                    .toString()
            }.sortedBy { it.root.toString().lowercase() }
            .forEach { repository ->
                val rootKey =
                    repository.root
                        .toAbsolutePath()
                        .normalize()
                        .toString()
                val stableIds = repositoryIdsByRoot()
                val existing = stableIds[rootKey]
                if (existing != null) {
                    assignedIds[rootKey] = existing
                    return@forEach
                }
                val used = (stableIds.values + assignedIds.values).toSet()
                val requested = repository.id.ifBlank { repository.name }
                val candidate =
                    if (requested !in used) {
                        requested
                    } else {
                        "${repository.name}-${shortHash(rootKey.lowercase())}"
                    }
                val unique =
                    generateSequence(candidate) { value ->
                        "$value-${shortHash(rootKey.lowercase()).take(4)}"
                    }.first { it !in used }
                stableIds[rootKey] = unique
                assignedIds[rootKey] = unique
            }
        sources.clear()
        combined.forEach { source ->
            val rootKey =
                source.repository.root
                    .toAbsolutePath()
                    .normalize()
                    .toString()
            val repository =
                source.repository.copy(
                    id = assignedIds.getValue(rootKey),
                )
            val canonical = source.copy(repository = repository)
            sources[canonical.key] = canonical
        }
        fireChanged()
    }

    fun registerConfirmed(confirmed: Collection<ExternalRepositoryDropResolver.Source>) {
        register(ExternalRepositoryDropResolver.Result(emptyList(), confirmed.toList(), emptyList(), emptyList()))
    }

    fun remove(sourceKey: String) {
        if (sources.remove(sourceKey) != null) fireChanged()
    }

    fun clear() {
        val changed =
            sources.isNotEmpty() ||
                batchExcluded.isNotEmpty() ||
                includeOnce.isNotEmpty()
        sources.clear()
        batchExcluded.clear()
        includeOnce.clear()
        if (changed) fireChanged()
    }

    /** Keeps archive discovery sources available so a following batch can select the next unsent entries. */
    fun clearManualSourcesKeepArchives() {
        val changed = sources.entries.removeIf { it.value.kind != ExternalRepositoryDropResolver.Kind.ARCHIVE_FILE }
        val exclusionsChanged = batchExcluded.isNotEmpty() || includeOnce.isNotEmpty()
        batchExcluded.clear()
        includeOnce.clear()
        if (changed || exclusionsChanged) fireChanged()
    }

    fun excludeForBatch(sourceKey: String) {
        includeOnce.remove(sourceKey)
        if (batchExcluded.add(sourceKey)) fireChanged()
    }

    fun excludeForSession(sourceKey: String) {
        includeOnce.remove(sourceKey)
        batchExcluded.remove(sourceKey)
        val excluded = sessionExcluded()
        if (excluded.add(sourceKey)) fireChanged()
    }

    fun alwaysExclude(sourceKey: String) {
        includeOnce.remove(sourceKey)
        batchExcluded.remove(sourceKey)
        sessionExcluded().remove(sourceKey)
        val persisted = persistentAlwaysExcluded()
        if (sourceKey !in persisted) {
            persisted.add(sourceKey)
            fireChanged()
        }
    }

    fun includeOnce(sourceKey: String) {
        if (includeOnce.add(sourceKey)) fireChanged()
    }

    fun removeExclusion(sourceKey: String) {
        val changed =
            batchExcluded.remove(sourceKey) or
                sessionExcluded().remove(sourceKey) or
                persistentAlwaysExcluded().remove(sourceKey) or
                includeOnce.remove(sourceKey)
        if (changed) fireChanged()
    }

    fun excludedSourceKeys(): Set<String> = (batchExcluded + sessionExcluded() + persistentAlwaysExcluded()).toSet() - includeOnce

    fun exclusionScope(sourceKey: String): String =
        when (sourceKey) {
            in persistentAlwaysExcluded() -> "project"
            in sessionExcluded() -> "session"
            else -> "batch"
        }

    fun startNewSession() {
        val excluded = sessionExcluded()
        val changed = excluded.isNotEmpty() || batchExcluded.isNotEmpty() || includeOnce.isNotEmpty()
        excluded.clear()
        batchExcluded.clear()
        includeOnce.clear()
        if (changed) fireChanged()
    }

    fun selections(): List<RepositorySelection> =
        sources.values
            .groupBy { it.repository.id }
            .values
            .map { values ->
                RepositorySelection(
                    values.first().repository,
                    values.filter { it.kind == ExternalRepositoryDropResolver.Kind.PINNED_FILE }.sortedBy { it.relativePath },
                    values.filter { it.kind == ExternalRepositoryDropResolver.Kind.DISCOVERY_DIRECTORY }.sortedBy { it.relativePath },
                    values.filter { it.kind == ExternalRepositoryDropResolver.Kind.ARCHIVE_FILE }.sortedBy { it.relativePath },
                )
            }.sortedBy { it.repository.id }

    fun candidates(resolver: ExternalRepositoryDropResolver): List<ContextCandidate> =
        selections().flatMap { selection -> (selection.pinnedFiles + selection.archiveFiles).map(resolver::toCandidate) }

    fun registeredSourceKeys(): Set<String> = sources.keys.toSet()

    fun allSources(): List<ExternalRepositoryDropResolver.Source> = sources.values.toList()

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    private fun persistentAlwaysExcluded(): MutableList<String> =
        project?.getService(ProjectSettings::class.java)?.state?.externalAlwaysExcludedSourceKeys ?: mutableListOf()

    private fun repositoryIdsByRoot(): MutableMap<String, String> =
        project?.getService(ProjectSettings::class.java)?.state?.externalRepositoryIdsByRoot ?: fallbackRepositoryIds

    /**
     * Session exclusions are looked up lazily from the active conversation session.  This is
     * important because the session selector changes ContextSelectionService state before this
     * registry is asked to recalculate; a single in-memory set would leak an exclusion into the
     * newly selected conversation.
     */
    private fun sessionExcluded(): MutableSet<String> {
        val settings = project?.getService(ProjectSettings::class.java)
        val sessionId = project?.getService(ContextSelectionService::class.java)?.activeConversationSessionId() ?: DEFAULT_SESSION
        if (settings == null) return fallbackSessionExcluded
        settings.state.externalSessionExcludedSourceKeys.getOrPut(sessionId) { mutableListOf() }
        return MutableListSetView(settings.state.externalSessionExcludedSourceKeys, sessionId)
    }

    /** Mutable set view over the persisted list, avoiding a second source of truth. */
    private class MutableListSetView(
        private val map: MutableMap<String, MutableList<String>>,
        private val key: String,
    ) : AbstractMutableSet<String>() {
        private val list: MutableList<String> get() = map.getOrPut(key) { mutableListOf() }

        override val size: Int get() = list.distinct().size

        override fun add(element: String): Boolean {
            if (element in list) return false
            list += element
            return true
        }

        override fun iterator(): MutableIterator<String> {
            val snapshot = list.distinct().toMutableList()
            var current: String? = null
            return object : MutableIterator<String> {
                private val delegate = snapshot.iterator()

                override fun hasNext(): Boolean = delegate.hasNext()

                override fun next(): String = delegate.next().also { current = it }

                override fun remove() {
                    val value = current ?: throw IllegalStateException("next() must be called before remove()")
                    list.remove(value)
                    current = null
                }
            }
        }
    }

    private val fallbackSessionExcluded = linkedSetOf<String>()

    private companion object {
        const val DEFAULT_SESSION = "__default__"
    }

    private fun fireChanged() = listeners.forEach { it() }

    private fun shortHash(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .take(4)
            .joinToString("") { "%02x".format(it) }
}
