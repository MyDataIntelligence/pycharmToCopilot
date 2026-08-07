package nl.ferron.copilotcontextbridge.patch

import junit.framework.TestCase
import nl.ferron.copilotcontextbridge.analysis.FunctionHasher

class UnifiedDiffTest : TestCase() {
    fun testAdditionUsesZeroOldLinesAndNoSyntheticRemoval() {
        val diff = UnifiedDiff.create("src/new.py", "", "def created():\n    return 1\n")

        assertTrue(diff.contains("@@ -1,0 +1,2 @@"))
        assertFalse(diff.lineSequence().any { it == "-" })
        assertTrue(diff.contains("+def created():"))
    }

    fun testLineEndingsProduceTheSameDiff() {
        val old = "def run():\r\n    return 1\r\n"
        val new = "def run():\r\n    return 2\r\n"

        assertEquals(
            UnifiedDiff.create("module.py", FunctionHasher.normalize(old), FunctionHasher.normalize(new)),
            UnifiedDiff.create("module.py", old, new),
        )
    }
}
