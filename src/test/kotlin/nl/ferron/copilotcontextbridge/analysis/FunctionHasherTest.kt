package nl.ferron.copilotcontextbridge.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FunctionHasherTest {
    @Test fun `line ending normalization is deterministic`() {
        assertEquals(FunctionHasher.hash("@x\r\ndef f():\r\n    pass\r\n"), FunctionHasher.hash("@x\ndef f():\n    pass\n"))
    }

    @Test fun `all non-line-ending text remains significant`() {
        assertNotEquals(FunctionHasher.hash("def f():\n    return 1\n"), FunctionHasher.hash("def f():\n    return 2\n"))
    }
}
