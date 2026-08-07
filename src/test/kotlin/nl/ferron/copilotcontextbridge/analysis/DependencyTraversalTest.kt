package nl.ferron.copilotcontextbridge.analysis

import junit.framework.TestCase
import nl.ferron.copilotcontextbridge.model.DependencyRelation
import nl.ferron.copilotcontextbridge.model.RelationConfidence
import nl.ferron.copilotcontextbridge.model.RelationType

class DependencyTraversalTest : TestCase() {
    fun testSecondLevelDependenciesAreControlledAndDepthIsStable() {
        val relations =
            listOf(
                relation("src/first.py", "src/second.py", RelationType.DIRECT_IMPORT),
                relation("src/second.py", "src/third.py", RelationType.DIRECT_IMPORT),
            )
        val direct = options()
        val secondLevel = direct.copy(maximumDepth = 2)

        assertEquals(
            mapOf("src/first.py" to 0, "src/second.py" to 1),
            DependencyTraversal.collect(setOf("src/first.py"), relations, direct),
        )
        assertEquals(
            mapOf("src/first.py" to 0, "src/second.py" to 1, "src/third.py" to 2),
            DependencyTraversal.collect(setOf("src/first.py"), relations, secondLevel),
        )
    }

    fun testRelatedTestsDoNotBypassDisabledTestOptionThroughDependents() {
        val relations = listOf(relation("tests/test_service.py", "src/service.py", RelationType.RELATED_TEST))
        val disabled = options(relatedTests = false)
        val enabled = disabled.copy(relatedTests = true)

        assertEquals(mapOf("src/service.py" to 0), DependencyTraversal.collect(setOf("src/service.py"), relations, disabled))
        assertEquals(
            mapOf("src/service.py" to 0, "tests/test_service.py" to 1),
            DependencyTraversal.collect(setOf("src/service.py"), relations, enabled),
        )
    }

    fun testCallersAndCalleesAreIndependentAndIncomingConfigurationIsNotACaller() {
        val relations =
            listOf(
                relation("src/selected.py", "src/callee.py", RelationType.DIRECT_CALLEE),
                relation("src/caller.py", "src/selected.py", RelationType.DIRECT_CALLEE),
                relation("config/settings.yml", "src/selected.py", RelationType.REFERENCED_CONFIGURATION),
            )

        val calleesOnly = options(directImports = false, directCallees = true, directDependents = false)
        assertEquals(
            mapOf("src/selected.py" to 0, "src/callee.py" to 1),
            DependencyTraversal.collect(setOf("src/selected.py"), relations, calleesOnly),
        )

        val callersOnly = options(directImports = false, directCallees = false, directDependents = true)
        assertEquals(
            mapOf("src/selected.py" to 0, "src/caller.py" to 1),
            DependencyTraversal.collect(setOf("src/selected.py"), relations, callersOnly),
        )
    }

    fun testConfiguredDepthAndPerResolverFileLimitAreHonoredDeterministically() {
        val relations =
            listOf(
                relation("src/root.py", "src/b.py", RelationType.DIRECT_IMPORT),
                relation("src/root.py", "src/a.py", RelationType.DIRECT_IMPORT),
                relation("src/a.py", "src/depth2.py", RelationType.DIRECT_IMPORT),
                relation("src/depth2.py", "src/depth3.py", RelationType.DIRECT_IMPORT),
            )
        val limited =
            options(
                maximumDepth = 3,
                resolverLimits = mapOf("python.directImports" to 1, "python.transitiveImports" to 10),
            )

        assertEquals(
            mapOf("src/root.py" to 0, "src/a.py" to 1, "src/depth2.py" to 2, "src/depth3.py" to 3),
            DependencyTraversal.collect(setOf("src/root.py"), relations, limited),
        )
    }

    fun testNearbyTestsRequireTheirOwnPolicyRule() {
        val relations = listOf(relation("src/service.py", "tests/test_neighbor.py", RelationType.NEARBY_TEST))

        assertEquals(
            mapOf("src/service.py" to 0),
            DependencyTraversal.collect(setOf("src/service.py"), relations, options(nearbyTests = false)),
        )
        assertEquals(
            mapOf("src/service.py" to 0, "tests/test_neighbor.py" to 1),
            DependencyTraversal.collect(setOf("src/service.py"), relations, options(nearbyTests = true)),
        )
    }

    private fun options(
        directImports: Boolean = true,
        directCallees: Boolean = true,
        directDependents: Boolean = true,
        relatedTests: Boolean = true,
        nearbyTests: Boolean = false,
        referencedConfiguration: Boolean = true,
        maximumDepth: Int = 1,
        resolverLimits: Map<String, Int> = emptyMap(),
    ) = DependencyTraversal.Options(
        directImports,
        directCallees,
        directDependents,
        relatedTests,
        nearbyTests,
        referencedConfiguration,
        maximumDepth,
        resolverLimits,
    )

    private fun relation(
        from: String,
        to: String,
        type: RelationType,
    ) = DependencyRelation(from, to, type, RelationConfidence.CONFIRMED)
}
