package nl.ferron.copilotcontextbridge.external

import com.intellij.openapi.components.Service
import nl.ferron.copilotcontextbridge.model.ContextCandidate
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
class ExternalRepositorySelectionRegistry {
    data class RepositorySelection(
        val repository: ExternalRepositoryDropResolver.Repository,
        val pinnedFiles: List<ExternalRepositoryDropResolver.Source>,
        val discoveryDirectories: List<ExternalRepositoryDropResolver.Source>,
    )

    private val sources = linkedMapOf<String, ExternalRepositoryDropResolver.Source>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

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
        fireChanged()
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
                )
            }.sortedBy { it.repository.id }

    fun candidates(resolver: ExternalRepositoryDropResolver): List<ContextCandidate> =
        selections().flatMap { selection -> selection.pinnedFiles.map(resolver::toCandidate) }

    fun registeredSourceKeys(): Set<String> = sources.keys.toSet()

    fun allSources(): List<ExternalRepositoryDropResolver.Source> = sources.values.toList()

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    private fun fireChanged() = listeners.forEach { it() }

    private fun shortHash(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .take(4)
            .joinToString("") { "%02x".format(it) }
}
