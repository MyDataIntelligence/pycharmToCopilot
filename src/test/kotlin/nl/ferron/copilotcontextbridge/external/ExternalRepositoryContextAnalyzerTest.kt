package nl.ferron.copilotcontextbridge.external

import junit.framework.TestCase
import nl.ferron.copilotcontextbridge.model.ContextCandidate
import nl.ferron.copilotcontextbridge.model.RelationConfidence
import nl.ferron.copilotcontextbridge.model.RelationType
import nl.ferron.copilotcontextbridge.settings.ContextPolicyState
import java.nio.file.Files
import java.nio.file.Path

class ExternalRepositoryContextAnalyzerTest : TestCase() {
    fun testExternalDiscoveryFindsImportsTestsConfigurationAndInstructions() {
        val root = Files.createTempDirectory("external-context-analyzer")
        try {
            val seed = candidate(root, "src/payment_service.py", "from src.money import convert\nCONFIG = 'config/app.yaml'\n")
            val discovered =
                listOf(
                    candidate(root, "src/money.py", "def convert(): pass\n"),
                    candidate(root, "tests/test_payment_services.py", "def test_payment(): pass\n"),
                    candidate(root, "config/app.yaml", "currency: EUR\n"),
                    candidate(root, "AGENTS.md", "Follow repository rules.\n"),
                )
            val policy = ContextPolicyState.defaultFor("external")
            val analyzed = ExternalRepositoryContextAnalyzer(policy, 100_000).analyze(listOf(seed), discovered)

            assertEquals(
                RelationType.DIRECT_IMPORT,
                analyzed
                    .single { it.relativePath == "src/money.py" }
                    .relations
                    .single()
                    .type,
            )
            assertEquals(
                RelationType.RELATED_TEST,
                analyzed
                    .single { it.relativePath.contains("test_payment") }
                    .relations
                    .single()
                    .type,
            )
            assertEquals(
                RelationType.REFERENCED_CONFIGURATION,
                analyzed
                    .single { it.relativePath.endsWith("app.yaml") }
                    .relations
                    .single()
                    .type,
            )
            assertEquals("guidelines.agents", analyzed.single { it.relativePath == "AGENTS.md" }.resolverId)
            assertTrue(analyzed.all { it.resolverId.isNotBlank() })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    fun testExternalPolicyAddsTransitiveImportsFixturesAndTemplates() {
        val root = Files.createTempDirectory("external-context-policy")
        try {
            val seed = candidate(root, "src/main.py", "from src.service import run\n")
            val service = candidate(root, "src/service.py", "from src.shared import helper\n")
            val discovered =
                listOf(
                    service,
                    candidate(root, "src/shared.py", "def helper(): pass\n"),
                    candidate(root, "tests/test_main.py", "def test_main(): pass\n"),
                    candidate(root, "tests/conftest.py", "import pytest\n"),
                    candidate(root, "templates/module.py", "def template(): pass\n"),
                )
            val policy =
                ContextPolicyState.defaultFor("external-policy").apply {
                    rule("transitive-imports")!!.enabled = true
                    rule("test-fixtures")!!.enabled = true
                    rule("templates")!!.enabled = true
                }

            val analyzed = ExternalRepositoryContextAnalyzer(policy, 100_000).analyze(listOf(seed), discovered)

            assertTrue(
                analyzed
                    .single { it.relativePath == "src/shared.py" }
                    .relations
                    .any { it.type == RelationType.SECOND_LEVEL },
            )
            assertTrue(
                analyzed
                    .single { it.relativePath == "tests/conftest.py" }
                    .relations
                    .any { it.type == RelationType.TEST_FIXTURE },
            )
            assertTrue(
                analyzed
                    .single { it.relativePath == "templates/module.py" }
                    .relations
                    .any { it.type == RelationType.TEMPLATE },
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun candidate(
        root: Path,
        relative: String,
        text: String,
    ): ContextCandidate {
        val path = root.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
        return ContextCandidate(
            relative,
            path,
            1_000,
            0,
            RelationConfidence.CONFIRMED,
            emptyList(),
            pinned = relative == "src/payment_service.py",
            size = Files.size(path),
            repositoryId = "external",
            repositoryRoot = root,
            repositoryName = "external",
        )
    }
}
