package nl.ferron.copilotcontextbridge.patch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CopilotPatchSnifferTest {
    @Test
    fun acceptsOnlySchemaShapedJson() {
        assertTrue(
            CopilotPatchSniffer.matchesJson(
                """{"formatVersion":1,"repositoryId":"repo","sessionId":"batch","replacements":[{"operation":"replace_function","path":"src/a.py","qualifiedName":"run","originalHash":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","replacement":"def run():\n    return 1\n"}]}""",
            ),
        )
        assertFalse(CopilotPatchSniffer.matchesJson("""{"formatVersion":1,"items":[]}"""))
        assertFalse(
            CopilotPatchSniffer.matchesJson(
                """{"formatVersion":1,"repositoryId":"repo","sessionId":"batch","replacements":[{"operation":"replace_function"}]}""",
            ),
        )
        assertFalse(CopilotPatchSniffer.matchesJson("not json"))
    }
}
