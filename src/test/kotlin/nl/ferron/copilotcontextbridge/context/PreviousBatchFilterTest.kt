package nl.ferron.copilotcontextbridge.context

import junit.framework.TestCase
import nl.ferron.copilotcontextbridge.model.ContextCandidate
import nl.ferron.copilotcontextbridge.model.RelationConfidence
import java.nio.file.Path

class PreviousBatchFilterTest : TestCase() {
    fun testPreviouslySentExternalCandidateIsMarkedForOmission() {
        val candidate = candidate("archive::src/old.py", previouslySent = true)

        val result = PreviousBatchFilter.markIgnored(listOf(candidate), avoidPrevious = true)

        assertEquals("already exported in an earlier batch", result.single().ignoredReason)
        assertTrue(result.single().previouslySent)
    }

    fun testPinnedPreviouslySentCandidateRemainsEligible() {
        val candidate = candidate("archive::src/old.py", previouslySent = true, pinned = true)

        val result = PreviousBatchFilter.markIgnored(listOf(candidate), avoidPrevious = true)

        assertNull(result.single().ignoredReason)
    }

    fun testPreviousBatchAvoidanceCanBeDisabled() {
        val candidate = candidate("archive::src/old.py", previouslySent = true)

        val result = PreviousBatchFilter.markIgnored(listOf(candidate), avoidPrevious = false)

        assertNull(result.single().ignoredReason)
    }

    private fun candidate(
        path: String,
        previouslySent: Boolean,
        pinned: Boolean = false,
    ) = ContextCandidate(
        relativePath = path.substringAfter("::"),
        absolutePath = Path.of(path.replace("::", "-")),
        score = 200,
        depth = 1,
        confidence = RelationConfidence.INFERRED,
        relations = emptyList(),
        pinned = pinned,
        previouslySent = previouslySent,
        repositoryId = path.substringBefore("::"),
    )
}
