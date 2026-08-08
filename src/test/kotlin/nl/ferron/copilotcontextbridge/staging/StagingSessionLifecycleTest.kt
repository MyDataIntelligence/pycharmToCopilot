package nl.ferron.copilotcontextbridge.staging

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import nl.ferron.copilotcontextbridge.model.AttachmentKind
import nl.ferron.copilotcontextbridge.model.AttachmentPlan
import nl.ferron.copilotcontextbridge.model.ContextCandidate
import nl.ferron.copilotcontextbridge.model.ContextPack
import nl.ferron.copilotcontextbridge.model.PlannedAttachment
import nl.ferron.copilotcontextbridge.model.RankedSelection
import nl.ferron.copilotcontextbridge.model.RelationConfidence
import nl.ferron.copilotcontextbridge.state.ContextSelectionService
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class StagingSessionLifecycleTest : BasePlatformTestCase() {
    fun testCompleteContextOnlyPackCreatesManifestAndCanBeDeleted() {
        val pack =
            ContextPack(
                sessionId = "stage-${UUID.randomUUID()}",
                repositoryId = project.name,
                markdown = "# Context\n",
                selection = RankedSelection(emptyList(), emptyList(), emptyList(), emptyList()),
                relations = emptyList(),
                symbols = emptyMap(),
                repositoryTree = "repository/\n",
                guidelineSources = emptyList(),
                estimatedBytes = 10,
                promptSkillId = "general-change",
                attachmentPlan = AttachmentPlan.empty(),
            )
        val service = StagingService(project)

        val result = service.stage(pack)

        assertTrue(Files.isRegularFile(result.directory.resolve("00_REPO_CONTEXT.md")))
        assertTrue(Files.isRegularFile(result.manifest))
        assertTrue(Files.isRegularFile(result.directory.resolve(".session/base-functions.json")))
        service.deleteSession(result.directory)
        project.getService(ContextSelectionService::class.java).deleteBatch(pack.sessionId)
    }

    fun testFailedStagingRemovesPartialSessionSoRetryIsNotBlocked() {
        val sessionId = "failed-stage-${UUID.randomUUID()}"
        val missing =
            ContextCandidate(
                relativePath = "src/missing.py",
                absolutePath = Path.of("src/missing.py"),
                score = 1,
                depth = 1,
                confidence = RelationConfidence.INFERRED,
                relations = emptyList(),
            )
        val pack =
            ContextPack(
                sessionId = sessionId,
                repositoryId = project.name,
                markdown = "# Context\n",
                selection = RankedSelection(listOf(missing), emptyList(), emptyList(), emptyList()),
                relations = emptyList(),
                symbols = emptyMap(),
                repositoryTree = "repository/\n",
                guidelineSources = emptyList(),
                estimatedBytes = 10,
                promptSkillId = "general-change",
                attachmentPlan =
                    AttachmentPlan(
                        attachments =
                            listOf(
                                PlannedAttachment(
                                    "05_AUTO_REFERENCES_01.md",
                                    AttachmentKind.AUTOMATIC_BUNDLE,
                                    listOf(missing),
                                ),
                            ),
                        repositoryToAttachment = mapOf(missing.relativePath to "05_AUTO_REFERENCES_01.md"),
                    ),
            )
        val expected = Path.of(System.getProperty("java.io.tmpdir"), "CopilotContextBridge", "${project.name}_$sessionId")

        var failed = false
        try {
            StagingService(project).stage(pack)
        } catch (error: Exception) {
            failed = true
        }
        assertTrue("The missing attachment source must fail staging", failed)
        assertFalse(Files.exists(expected))
    }

    fun testKeepMarkerAndSafeSessionDeletion() {
        val root = Path.of(System.getProperty("java.io.tmpdir"), "CopilotContextBridge")
        val session = root.resolve("test-${UUID.randomUUID()}")
        Files.createDirectories(session.resolve(".session"))
        Files.writeString(session.resolve("safe-copy.py"), "print('copy')\n")
        val service = StagingService(project)

        service.keepSession(session)
        assertTrue(Files.isRegularFile(session.resolve(".session/.keep")))

        service.deleteSession(session)
        assertFalse(Files.exists(session))
    }

    fun testDeletionRejectsPathsOutsideStagingRoot() {
        val outside = Path.of(System.getProperty("java.io.tmpdir"), "not-a-copilot-session")

        try {
            StagingService(project).deleteSession(outside)
            fail("Unsafe staging deletion should be rejected.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("outside the staging root"))
        }
    }
}
