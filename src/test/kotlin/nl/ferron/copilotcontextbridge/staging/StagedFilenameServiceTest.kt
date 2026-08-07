package nl.ferron.copilotcontextbridge.staging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StagedFilenameServiceTest {
    @Test fun `repository paths become stable flat names`() {
        val names = StagedFilenameService.namesFor(listOf("src/functions/config.py", "tests/config.py"))
        assertEquals("src__functions__config.py", names["src/functions/config.py"])
        assertEquals("tests__config.py", names["tests/config.py"])
    }

    @Test fun `sanitization collisions get deterministic hashes`() {
        val names = StagedFilenameService.namesFor(listOf("a b.py", "a_b.py"))
        assertNotEquals(names["a b.py"], names["a_b.py"])
        assertTrue(names.values.all { it.length <= 190 })
    }
}
