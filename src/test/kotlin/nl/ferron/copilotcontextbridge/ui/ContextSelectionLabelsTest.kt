package nl.ferron.copilotcontextbridge.ui

import junit.framework.TestCase
import nl.ferron.copilotcontextbridge.model.ContextCandidate
import nl.ferron.copilotcontextbridge.model.DependencyRelation
import nl.ferron.copilotcontextbridge.model.RelationConfidence
import nl.ferron.copilotcontextbridge.model.RelationType
import java.nio.file.Path

class ContextSelectionLabelsTest : TestCase() {
    fun testPinnedFileIsClearlyIdentifiedAsManuallySelected() {
        val candidate = candidate(pinned = true)

        assertEquals("Manually selected (Pinned)", ContextSelectionLabels.category(candidate))
        assertTrue(ContextSelectionLabels.detail(candidate).contains("selected by you"))
    }

    fun testAutomaticFileExposesAutomaticCategoryAndEveryRelationshipReason() {
        val candidate =
            candidate(
                pinned = false,
                relations =
                    listOf(
                        relation(RelationType.DIRECT_IMPORT),
                        relation(RelationType.RELATED_TEST),
                    ),
            )

        assertEquals("Automatically added", ContextSelectionLabels.category(candidate))
        val detail = ContextSelectionLabels.detail(candidate)
        assertTrue(detail.contains("direct import"))
        assertTrue(detail.contains("related test"))
    }

    private fun candidate(
        pinned: Boolean,
        relations: List<DependencyRelation> = listOf(relation(RelationType.PINNED)),
    ) = ContextCandidate(
        relativePath = "src/example.py",
        absolutePath = Path.of("src/example.py"),
        score = 100,
        depth = 0,
        confidence = RelationConfidence.CONFIRMED,
        relations = relations,
        pinned = pinned,
    )

    private fun relation(type: RelationType) =
        DependencyRelation(
            from = "src/main.py",
            to = "src/example.py",
            type = type,
            confidence = RelationConfidence.CONFIRMED,
        )
}
