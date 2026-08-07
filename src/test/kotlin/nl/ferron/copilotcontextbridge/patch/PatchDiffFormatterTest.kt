package nl.ferron.copilotcontextbridge.patch

import junit.framework.TestCase

class PatchDiffFormatterTest : TestCase() {
    fun testCombinedDiffIsStableAndContainsEveryReplacementOnce() {
        val targets =
            listOf(
                target("src/z.py", "zeta", ReplacementStatus.CHANGED, "diff-z"),
                target("src/a.py", "beta", ReplacementStatus.NEW, "diff-b"),
                target("src/a.py", "alpha", ReplacementStatus.MATCH, "diff-a"),
            )

        val combined = PatchDiffFormatter.combined(targets)

        assertTrue(combined.indexOf("src/a.py::alpha") < combined.indexOf("src/a.py::beta"))
        assertTrue(combined.indexOf("src/a.py::beta") < combined.indexOf("src/z.py::zeta"))
        assertEquals(1, Regex("diff-a").findAll(combined).count())
        assertEquals(1, Regex("diff-b").findAll(combined).count())
        assertEquals(1, Regex("diff-z").findAll(combined).count())
        assertTrue(combined.contains("[CHANGED]"))
    }

    fun testInvalidReplacementFallsBackToValidationReason() {
        val combined = PatchDiffFormatter.combined(listOf(target("module.py", "run", ReplacementStatus.INVALID, "")))

        assertTrue(combined.contains("Replacement validation message"))
    }

    private fun target(
        path: String,
        name: String,
        status: ReplacementStatus,
        diff: String,
    ): PatchValidator.Target {
        val request = FunctionReplacement("replace_function", path, name, "sha256:test", "def $name():\n    pass\n", null)
        val validated =
            ValidatedReplacement(
                request,
                status,
                "Replacement validation message",
                unifiedDiff = diff,
                selected = status in setOf(ReplacementStatus.MATCH, ReplacementStatus.NEW),
            )
        return PatchValidator.Target(validated, null, null)
    }
}
