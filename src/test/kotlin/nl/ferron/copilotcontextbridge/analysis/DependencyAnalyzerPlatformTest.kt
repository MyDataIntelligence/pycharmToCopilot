package nl.ferron.copilotcontextbridge.analysis

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import nl.ferron.copilotcontextbridge.ProjectRoot
import nl.ferron.copilotcontextbridge.model.RelationType
import nl.ferron.copilotcontextbridge.settings.ContextPolicyState
import nl.ferron.copilotcontextbridge.state.ContextSelectionService
import java.nio.file.Files

class DependencyAnalyzerPlatformTest : BasePlatformTestCase() {
    fun testPsiAndRepositoryResolversProduceIndependentDeterministicCandidates() {
        PsiTestUtil.addSourceRoot(module, ProjectRoot.virtualFile(project))
        val main =
            addRepositoryFile(
                "main.py",
                """
                from helper import run

                CONFIG_PATH = "config/settings.yaml"

                def execute() -> str:
                    return run()
                """.trimIndent(),
            )
        addRepositoryFile(
            "helper.py",
            """
            from deep import value

            def run() -> str:
                return value()
            """.trimIndent(),
        )
        addRepositoryFile("deep.py", "def value() -> str:\n    return 'ok'\n")
        addRepositoryFile("caller.py", "from main import execute\n\nRESULT = execute()\n")
        addRepositoryFile("tests/test_main.py", "from main import execute\n\ndef test_execute():\n    assert execute() == 'ok'\n")
        addRepositoryFile("tests/test_mains.py", "from main import execute\n\ndef test_execute_plural():\n    assert execute() == 'ok'\n")
        addRepositoryFile("tests/test_neighbor.py", "def test_neighbor():\n    assert True\n")
        addRepositoryFile("config/settings.yaml", "enabled: true\n")
        project.getService(ContextSelectionService::class.java).addFiles(listOf(main))
        val policy = ContextPolicyState.defaultFor("integration")
        policy.rule("direct-callers")!!.enabled = true
        policy.rule("nearby-tests")!!.enabled = true
        policy.rule("transitive-imports")!!.apply {
            enabled = true
            maxDepth = 2
        }

        val result = DependencyAnalyzer(project, policy).analyze()
        val candidates = result.candidates.associateBy { it.relativePath }

        assertTrue("helper.py" in candidates)
        assertTrue("caller.py" in candidates)
        assertTrue("tests/test_main.py" in candidates)
        assertTrue("tests/test_neighbor.py" in candidates)
        assertTrue("config/settings.yaml" in candidates)
        assertEquals(2, candidates.getValue("deep.py").depth)
        assertTrue(result.relations.any { it.from == "main.py" && it.to == "helper.py" && it.type == RelationType.DIRECT_IMPORT })
        assertTrue(result.relations.any { it.from == "main.py" && it.to == "helper.py" && it.type == RelationType.DIRECT_CALLEE })
        assertTrue(result.relations.any { it.from == "caller.py" && it.to == "main.py" && it.type == RelationType.DIRECT_IMPORT })
        assertTrue(result.relations.any { it.from == "tests/test_main.py" && it.to == "main.py" && it.type == RelationType.RELATED_TEST })
        assertTrue(
            result.relations.any {
                it.from == "tests/test_mains.py" &&
                    it.to == "main.py" &&
                    it.type == RelationType.RELATED_TEST &&
                    it.evidence.startsWith("fuzzy test filename match")
            },
        )
        assertEquals("python.matchingTests", candidates.getValue("tests/test_mains.py").resolverId)
        assertEquals("matching-tests", candidates.getValue("tests/test_mains.py").policyRuleId)
        assertTrue(result.relations.any { it.to == "tests/test_neighbor.py" && it.type == RelationType.NEARBY_TEST })
        assertTrue(
            result.relations.any {
                it.from == "main.py" && it.to == "config/settings.yaml" && it.type == RelationType.REFERENCED_CONFIGURATION
            },
        )
    }

    private fun addRepositoryFile(
        relativePath: String,
        text: String,
    ): VirtualFile {
        val path = ProjectRoot.path(project).resolve(relativePath)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
        return requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
    }
}
