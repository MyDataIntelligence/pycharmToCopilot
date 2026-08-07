package nl.ferron.copilotcontextbridge.context

import nl.ferron.copilotcontextbridge.model.RelationType

/** Resolver identities associated with a discovered relationship. */
internal fun RelationType.contextResolvers(): Set<String> =
    when (this) {
        RelationType.PINNED -> setOf("explicit.pinnedFiles")
        RelationType.RELATED_TEST -> setOf("python.matchingTests")
        RelationType.NEARBY_TEST -> setOf("tests.nearby")
        RelationType.TEST_FIXTURE -> setOf("tests.fixtures")
        RelationType.DIRECT_IMPORT -> setOf("python.directImports")
        RelationType.DIRECT_CALLEE -> setOf("python.directCallees")
        RelationType.DIRECT_DEPENDENT -> setOf("python.directCallers")
        RelationType.REFERENCED_CONFIGURATION, RelationType.TEXT_REFERENCE -> setOf("text.referencedConfiguration")
        RelationType.SECOND_LEVEL -> setOf("python.transitiveImports")
        RelationType.BRANCH_CHANGE -> setOf("git.branchChanges")
        RelationType.TEMPLATE -> setOf("repository.templates")
        RelationType.SIMILAR_IMPLEMENTATION -> setOf("repository.similarImplementations")
        RelationType.INSTRUCTION ->
            setOf("guidelines.agents", "guidelines.copilotInstructions", "guidelines.project")
        RelationType.PACKAGE_INIT, RelationType.PROJECT_CONFIGURATION, RelationType.SAME_PACKAGE -> emptySet()
    }
