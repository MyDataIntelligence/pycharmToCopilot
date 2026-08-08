package nl.ferron.copilotcontextbridge.context

import junit.framework.TestCase
import nl.ferron.copilotcontextbridge.model.ContextCandidate
import nl.ferron.copilotcontextbridge.model.DependencyRelation
import nl.ferron.copilotcontextbridge.model.RelationConfidence
import nl.ferron.copilotcontextbridge.model.RelationType
import nl.ferron.copilotcontextbridge.settings.ContextPolicyState
import java.nio.file.Path

class ContextResolverRegistryTest : TestCase() {
    fun testEveryDefaultPolicyResolverHasMetadata() {
        val policy = ContextPolicyState.defaultFor("registry")

        assertTrue(policy.rules.all { ContextResolverRegistry.find(it.resolver) != null })
        assertEquals(
            ContextResolverRegistry.all().size,
            ContextResolverRegistry
                .all()
                .map { it.id }
                .distinct()
                .size,
        )
    }

    fun testPolicyPrioritySelectsPrimaryCandidateProvenance() {
        val policy = ContextPolicyState.defaultFor("registry")
        policy.rule("matching-tests")!!.priority = 90
        policy.rule("test-fixtures")!!.priority = 95
        val candidate =
            ContextCandidate(
                "tests/conftest.py",
                Path.of("tests/conftest.py"),
                1,
                1,
                RelationConfidence.INFERRED,
                listOf(
                    DependencyRelation("src/app.py", "tests/conftest.py", RelationType.RELATED_TEST, RelationConfidence.INFERRED),
                    DependencyRelation("tests/test_app.py", "tests/conftest.py", RelationType.TEST_FIXTURE, RelationConfidence.INFERRED),
                ),
            )

        assertEquals("test-fixtures", ContextResolverRegistry.primaryRule(candidate, policy)?.id)
        assertEquals("tests.fixtures", ContextResolverRegistry.primaryResolver(candidate, policy))
    }

    fun testAddOnResolverCanBeRegisteredAndRemovedWithoutMutatingBuiltIns() {
        val metadata =
            ContextResolverMetadata(
                "custom.domainReferences",
                "Domain references",
                "Project-specific references.",
                ResolverCategory.REPOSITORY,
                setOf(RelationType.SAME_PACKAGE),
                ResolverStrategy.TRAVERSAL,
            )

        ContextResolverRegistry.register(metadata)
        try {
            assertEquals(metadata, ContextResolverRegistry.find(metadata.id))
            assertTrue(metadata.id in ContextResolverRegistry.resolversFor(RelationType.SAME_PACKAGE))
        } finally {
            assertTrue(ContextResolverRegistry.unregister(metadata.id))
        }
        assertNull(ContextResolverRegistry.find(metadata.id))
        assertFalse(ContextResolverRegistry.unregister("python.directImports"))
    }
}
