package nl.ferron.copilotcontextbridge.context

import nl.ferron.copilotcontextbridge.model.ContextCandidate
import nl.ferron.copilotcontextbridge.model.RelationConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Path

class DependencyRankerTest {
    private fun candidate(
        path: String,
        score: Int,
        pinned: Boolean = false,
        size: Long = 10,
    ) = ContextCandidate(
        path,
        Path.of(path),
        score,
        1,
        RelationConfidence.CONFIRMED,
        emptyList(),
        pinned = pinned,
        size = size,
    )

    @Test fun `pinned files win and context consumes one slot`() {
        val result = DependencyRanker.allocate(listOf(candidate("auto.py", 900), candidate("manual.py", 1, true)), 2)
        assertEquals(listOf("manual.py"), result.included.map { it.relativePath })
    }

    @Test fun `overflow is deterministic`() {
        val result =
            DependencyRanker.allocate(
                listOf(candidate("b.py", 800), candidate("a.py", 800), candidate("tiny.py", 800, size = 1)),
                3,
            )
        assertEquals(listOf("tiny.py", "a.py"), result.included.map { it.relativePath })
        assertEquals("b.py", result.omitted.single().relativePath)
    }

    @Test fun `too many pinned files is invalid`() {
        val result = DependencyRanker.allocate((1..20).map { candidate("$it.py", 1000, true) }, 20)
        assertFalse(result.valid)
    }
}
