package nl.ferron.copilotcontextbridge.analysis

import nl.ferron.copilotcontextbridge.model.DependencyRelation
import nl.ferron.copilotcontextbridge.model.RelationType

/** Deterministic dependency-depth expansion independent of PSI discovery. */
object DependencyTraversal {
    data class Options(
        val directImports: Boolean,
        val directDependents: Boolean,
        val relatedTests: Boolean,
        val referencedConfiguration: Boolean,
        val secondLevel: Boolean,
    )

    fun collect(
        pinned: Set<String>,
        relations: Collection<DependencyRelation>,
        options: Options,
    ): Map<String, Int> {
        val depths = pinned.associateWithTo(linkedMapOf()) { 0 }

        fun addRelated(
            selected: Set<String>,
            depth: Int,
        ): Set<String> {
            val added = linkedSetOf<String>()
            relations.forEach { relation ->
                val outgoingEnabled =
                    when (relation.type) {
                        RelationType.DIRECT_IMPORT -> options.directImports
                        RelationType.RELATED_TEST -> options.relatedTests
                        RelationType.REFERENCED_CONFIGURATION, RelationType.TEXT_REFERENCE -> options.referencedConfiguration
                        else -> true
                    }
                val incomingEnabled =
                    options.directDependents &&
                        (relation.type != RelationType.RELATED_TEST || options.relatedTests)
                if (relation.from in selected && outgoingEnabled && relation.to !in pinned) added += relation.to
                if (relation.to in selected && incomingEnabled && relation.from !in pinned) added += relation.from
            }
            added.forEach { depths.merge(it, depth, ::minOf) }
            return added
        }

        val direct = addRelated(pinned, 1)
        if (options.secondLevel) addRelated(direct, 2)
        return depths
    }
}
