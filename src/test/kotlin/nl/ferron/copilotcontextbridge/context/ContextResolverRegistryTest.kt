package nl.ferron.copilotcontextbridge.context

import junit.framework.TestCase
import nl.ferron.copilotcontextbridge.analysis.RepositoryScanner
import nl.ferron.copilotcontextbridge.model.ContextCandidate
import nl.ferron.copilotcontextbridge.model.DependencyRelation
import nl.ferron.copilotcontextbridge.model.RelationConfidence
import nl.ferron.copilotcontextbridge.model.RelationType
import nl.ferron.copilotcontextbridge.settings.ContextPolicyState
import nl.ferron.copilotcontextbridge.settings.ContextRuleState
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

    fun testAddOnResolverCanExposeExecutableHandler() {
        val metadata =
            ContextResolverMetadata(
                "custom.executable",
                "Executable custom resolver",
                "Test-only dynamically registered resolver.",
                ResolverCategory.REPOSITORY,
                setOf(RelationType.SAME_PACKAGE),
                ResolverStrategy.TRAVERSAL,
            )
        var invoked = false

        ContextResolverRegistry.register(
            metadata,
            ContextResolverHandler { context, rule ->
                invoked = true
                assertEquals("custom-rule", rule.id)
                context.include(
                    context.seedPaths.first(),
                    "src/custom.py",
                    RelationType.SAME_PACKAGE,
                    "dynamic resolver",
                    RelationConfidence.CONFIRMED,
                    1,
                )
            },
        )
        try {
            val handler = ContextResolverRegistry.handler(metadata.id)
            assertNotNull(handler)
            val candidates = linkedSetOf<String>()
            val depths = mutableMapOf<String, Int>()
            val relations = mutableListOf<DependencyRelation>()
            handler!!.resolve(
                ResolverExecutionContext(
                    RepositoryScanner.Snapshot(emptyList(), emptyList()),
                    setOf("src/app.py"),
                    candidates,
                    depths,
                    relations,
                    emptyMap(),
                ) { source, target, type, evidence, confidence, depth ->
                    candidates += target
                    depths[target] = depth
                    relations += DependencyRelation(source, target, type, confidence, depth, evidence)
                },
                ContextRuleState("custom-rule", metadata.id, 100),
            )
            assertTrue(invoked)
            assertEquals(setOf("src/custom.py"), candidates)
            assertEquals(RelationConfidence.CONFIRMED, relations.single().confidence)
        } finally {
            assertTrue(ContextResolverRegistry.unregister(metadata.id))
        }
    }
}
