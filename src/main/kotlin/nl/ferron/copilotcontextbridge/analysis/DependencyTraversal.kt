package nl.ferron.copilotcontextbridge.analysis

import nl.ferron.copilotcontextbridge.model.DependencyRelation
import nl.ferron.copilotcontextbridge.model.RelationType

/** Deterministic dependency-depth expansion independent of PSI discovery. */
object DependencyTraversal {
    data class Options(
        val directImports: Boolean,
        val directCallees: Boolean,
        val directDependents: Boolean,
        val relatedTests: Boolean,
        val nearbyTests: Boolean,
        val referencedConfiguration: Boolean,
        val maximumDepth: Int = 1,
        val resolverLimits: Map<String, Int> = emptyMap(),
    )

    fun collect(
        pinned: Set<String>,
        relations: Collection<DependencyRelation>,
        options: Options,
    ): Map<String, Int> {
        val depths = pinned.associateWithTo(linkedMapOf()) { 0 }

        val counters = mutableMapOf<String, Int>()
        val sortedRelations = relations.sortedWith(compareBy({ it.from }, { it.to }, { it.type.name }))

        fun include(
            path: String,
            depth: Int,
            resolver: String,
            added: MutableSet<String>,
        ) {
            if (path in pinned) return
            val maximum = options.resolverLimits[resolver] ?: Int.MAX_VALUE
            if ((counters[resolver] ?: 0) >= maximum && path !in depths) return
            if (path !in depths) counters[resolver] = (counters[resolver] ?: 0) + 1
            depths.merge(path, depth, ::minOf)
            added += path
        }

        val direct = linkedSetOf<String>()
        sortedRelations.forEach { relation ->
            if (relation.from in pinned) {
                when (relation.type) {
                    RelationType.DIRECT_IMPORT -> if (options.directImports) include(relation.to, 1, "python.directImports", direct)
                    RelationType.DIRECT_CALLEE -> if (options.directCallees) include(relation.to, 1, "python.directCallees", direct)
                    RelationType.RELATED_TEST -> if (options.relatedTests) include(relation.to, 1, "python.matchingTests", direct)
                    RelationType.NEARBY_TEST -> if (options.nearbyTests) include(relation.to, 1, "tests.nearby", direct)
                    RelationType.REFERENCED_CONFIGURATION, RelationType.TEXT_REFERENCE ->
                        if (options.referencedConfiguration) {
                            include(relation.to, 1, "text.referencedConfiguration", direct)
                        }
                    else -> Unit
                }
            }
            if (relation.to in pinned) {
                when (relation.type) {
                    RelationType.DIRECT_IMPORT, RelationType.DIRECT_CALLEE ->
                        if (options.directDependents) include(relation.from, 1, "python.directCallers", direct)
                    RelationType.RELATED_TEST -> if (options.relatedTests) include(relation.from, 1, "python.matchingTests", direct)
                    RelationType.NEARBY_TEST -> if (options.nearbyTests) include(relation.from, 1, "tests.nearby", direct)
                    else -> Unit
                }
            }
        }

        var frontier: Set<String> = direct
        for (depth in 2..options.maximumDepth.coerceAtLeast(1)) {
            val next = linkedSetOf<String>()
            sortedRelations.forEach { relation ->
                if (relation.from !in frontier) return@forEach
                when (relation.type) {
                    RelationType.DIRECT_IMPORT ->
                        if (options.directImports) include(relation.to, depth, "python.transitiveImports", next)
                    RelationType.DIRECT_CALLEE ->
                        if (options.directCallees) include(relation.to, depth, "python.transitiveImports", next)
                    else -> Unit
                }
            }
            frontier = next
            if (frontier.isEmpty()) break
        }
        return depths
    }
}
