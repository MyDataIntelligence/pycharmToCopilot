package nl.ferron.copilotcontextbridge.context

import junit.framework.TestCase
import nl.ferron.copilotcontextbridge.model.AttachmentKind
import nl.ferron.copilotcontextbridge.model.ContextCandidate
import nl.ferron.copilotcontextbridge.model.DependencyRelation
import nl.ferron.copilotcontextbridge.model.RelationConfidence
import nl.ferron.copilotcontextbridge.model.RelationType
import nl.ferron.copilotcontextbridge.settings.ContextPolicyState
import nl.ferron.copilotcontextbridge.settings.CopilotTarget
import java.nio.file.Path

class ContextAttachmentPackerTest : TestCase() {
    fun testPinnedFilesStaySeparateAndAutomaticFilesAreBundledAndTraceable() {
        val policy = ContextPolicyState.defaultFor("test")
        val candidates =
            listOf(
                candidate("src/main.py", pinned = true, score = 2_000),
                candidate("tests/test_main.py", RelationType.RELATED_TEST, score = 1_650),
                candidate("src/helper.py", RelationType.DIRECT_IMPORT, score = 1_500),
            )

        val plan = ContextAttachmentPacker.plan(candidates, policy)

        assertEquals(4, plan.attachmentCount)
        assertEquals(3, plan.repositoryFileCount)
        assertEquals(AttachmentKind.PINNED_ORIGINAL, plan.attachments.first().kind)
        assertEquals("src__main.py", plan.repositoryToAttachment["src/main.py"])
        assertTrue(plan.repositoryToAttachment.getValue("tests/test_main.py").startsWith("01_AUTO_TESTS_"))
        assertTrue(plan.repositoryToAttachment.getValue("src/helper.py").startsWith("02_AUTO_DEPENDENCIES_"))
        assertTrue(plan.omittedByPolicy.isEmpty())
    }

    fun testPinnedFilesDisplaceLowerPriorityAutomaticContextAtAttachmentLimit() {
        val policy = ContextPolicyState.defaultFor("test").apply { bundleAutomaticContext = false }
        val candidates =
            listOf(
                candidate("src/a.py", pinned = true, score = 2_000),
                candidate("src/b.py", pinned = true, score = 2_000),
                candidate("tests/test_a.py", RelationType.RELATED_TEST, score = 1_650),
                candidate("src/dependency.py", RelationType.DIRECT_IMPORT, score = 1_400),
                candidate("config/settings.json", RelationType.REFERENCED_CONFIGURATION, score = 1_200),
            )

        val plan = ContextAttachmentPacker.plan(candidates, policy, maximumAttachments = 4)

        assertEquals(4, plan.attachmentCount)
        assertEquals(setOf("src/a.py", "src/b.py", "tests/test_a.py"), plan.repositoryToAttachment.keys)
        assertEquals(listOf("src/dependency.py", "config/settings.json"), plan.omittedByPolicy.map { it.relativePath })
    }

    fun testBundleSplittingAndOrderingAreDeterministic() {
        val policy = ContextPolicyState.defaultFor("test")
        policy.rule("matching-tests")!!.maxFiles = 2
        val candidates =
            listOf(
                candidate("tests/test_z.py", RelationType.RELATED_TEST, score = 1_500),
                candidate("tests/test_a.py", RelationType.RELATED_TEST, score = 1_700),
                candidate("tests/test_m.py", RelationType.RELATED_TEST, score = 1_600),
            )

        val first = ContextAttachmentPacker.plan(candidates, policy)
        val second = ContextAttachmentPacker.plan(candidates.reversed(), policy)

        assertEquals(first, second)
        val testBundles = first.attachments.filter { it.kind == AttachmentKind.AUTOMATIC_BUNDLE }
        assertEquals(2, testBundles.size)
        assertEquals(listOf("tests/test_a.py", "tests/test_m.py"), testBundles.first().candidates.map { it.relativePath })
        assertEquals(listOf("tests/test_z.py"), testBundles.last().candidates.map { it.relativePath })
    }

    fun testPolicyCanKeepAnAutomaticResolverSeparate() {
        val policy = ContextPolicyState.defaultFor("test")
        policy.rule("matching-tests")!!.keepSeparate = true

        val plan =
            ContextAttachmentPacker.plan(
                listOf(candidate("tests/test_main.py", RelationType.RELATED_TEST, score = 1_650)),
                policy,
            )

        assertEquals(AttachmentKind.PINNED_ORIGINAL, plan.attachments.single().kind)
        assertEquals("tests__test_main.py", plan.attachments.single().stagedName)
    }

    fun testUnsupportedMicrosoftTextExtensionGetsTemporaryTxtNameButGitHubTargetDoesNot() {
        val candidate = candidate("tests/robot/login.robot", pinned = true, score = 2_000)
        val microsoftPolicy = ContextPolicyState.defaultFor("m365")
        val githubPolicy = ContextPolicyState.defaultFor("github").apply { target = CopilotTarget.GITHUB_COPILOT.name }

        val microsoft = ContextAttachmentPacker.plan(listOf(candidate), microsoftPolicy).attachments.single()
        val github = ContextAttachmentPacker.plan(listOf(candidate), githubPolicy).attachments.single()

        assertEquals("tests__robot__login.robot.txt", microsoft.stagedName)
        assertTrue(microsoft.convertedTextCopy)
        assertEquals("tests__robot__login.robot", github.stagedName)
        assertFalse(github.convertedTextCopy)
    }

    fun testSameRelativePathFromTwoRepositoriesRemainsSeparateAndTraceable() {
        val first = candidate("src/config.py", pinned = true, score = 2_000, repositoryId = "api-service")
        val second = candidate("src/config.py", pinned = true, score = 2_000, repositoryId = "robot-tests")

        val plan = ContextAttachmentPacker.plan(listOf(first, second), ContextPolicyState.defaultFor("multi-repo"))

        assertEquals(2, plan.repositoryFileCount)
        assertEquals(
            2,
            plan.attachments
                .map { it.stagedName }
                .distinct()
                .size,
        )
        assertEquals("api-service__src__config.py", plan.repositoryToAttachment["api-service::src/config.py"])
        assertEquals("robot-tests__src__config.py", plan.repositoryToAttachment["robot-tests::src/config.py"])
    }

    private fun candidate(
        path: String,
        relationType: RelationType? = null,
        pinned: Boolean = false,
        score: Int,
        repositoryId: String = "",
    ): ContextCandidate {
        val relations =
            relationType
                ?.let {
                    listOf(DependencyRelation("src/main.py", path, it, RelationConfidence.CONFIRMED, evidence = "test evidence"))
                }.orEmpty()
        return ContextCandidate(
            relativePath = path,
            absolutePath = Path.of(path),
            score = score,
            depth = if (pinned) 0 else 1,
            confidence = RelationConfidence.CONFIRMED,
            relations = relations,
            pinned = pinned,
            size = 100,
            repositoryId = repositoryId,
            repositoryName = repositoryId,
        )
    }
}
