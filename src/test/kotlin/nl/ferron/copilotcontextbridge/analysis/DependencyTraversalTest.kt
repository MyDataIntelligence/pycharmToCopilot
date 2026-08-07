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
        val direct = DependencyTraversal.Options(true, true, true, true, false)
        val secondLevel = direct.copy(secondLevel = true)

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
        val disabled = DependencyTraversal.Options(true, true, false, true, false)
        val enabled = disabled.copy(relatedTests = true)

        assertEquals(mapOf("src/service.py" to 0), DependencyTraversal.collect(setOf("src/service.py"), relations, disabled))
        assertEquals(
            mapOf("src/service.py" to 0, "tests/test_service.py" to 1),
            DependencyTraversal.collect(setOf("src/service.py"), relations, enabled),
        )
    }

    private fun relation(
        from: String,
        to: String,
        type: RelationType,
    ) = DependencyRelation(from, to, type, RelationConfidence.CONFIRMED)
}
