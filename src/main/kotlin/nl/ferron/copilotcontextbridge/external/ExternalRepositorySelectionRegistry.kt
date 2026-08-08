package nl.ferron.copilotcontextbridge.external

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import nl.ferron.copilotcontextbridge.model.ContextCandidate
import nl.ferron.copilotcontextbridge.settings.ProjectSettings
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
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val batchExcluded = linkedSetOf<String>()
    private val sessionExcluded = linkedSetOf<String>()
    private val includeOnce = linkedSetOf<String>()

    fun register(result: ExternalRepositoryDropResolver.Result) {
        val combined = (sources.values + result.accepted).distinctBy { it.repository.root.toString() + "::" + it.relativePath }
        val rootsByName = combined.map { it.repository }.distinctBy { it.root }.groupBy { it.name }
        sources.clear()
        combined.forEach { source ->
            val repository =
                if (rootsByName.getValue(source.repository.name).size == 1) {
                    source.repository
                } else {
                    source.repository.copy(
                        id = "${source.repository.name}-${shortHash(
                            source.repository.root
                                .toString()
                                .lowercase(),
                        )}",
                    )
                }
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
        if (sources.isEmpty()) return
        sources.clear()
        batchExcluded.clear()
        includeOnce.clear()
        fireChanged()
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
        if (sessionExcluded.add(sourceKey)) fireChanged()
    }

    fun alwaysExclude(sourceKey: String) {
        includeOnce.remove(sourceKey)
        batchExcluded.remove(sourceKey)
        sessionExcluded.remove(sourceKey)
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
                sessionExcluded.remove(sourceKey) or
                persistentAlwaysExcluded().remove(sourceKey) or
                includeOnce.remove(sourceKey)
        if (changed) fireChanged()
    }

    fun excludedSourceKeys(): Set<String> = (batchExcluded + sessionExcluded + persistentAlwaysExcluded()).toSet() - includeOnce

    fun exclusionScope(sourceKey: String): String =
        when (sourceKey) {
            in persistentAlwaysExcluded() -> "project"
            in sessionExcluded -> "session"
            else -> "batch"
        }

    fun startNewSession() {
        val changed = sessionExcluded.isNotEmpty() || batchExcluded.isNotEmpty() || includeOnce.isNotEmpty()
        sessionExcluded.clear()
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

    private fun fireChanged() = listeners.forEach { it() }

    private fun shortHash(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .take(4)
            .joinToString("") { "%02x".format(it) }
}
