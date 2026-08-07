package nl.ferron.copilotcontextbridge.context

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import nl.ferron.copilotcontextbridge.model.BatchSummary
import nl.ferron.copilotcontextbridge.model.ContextCandidate
import nl.ferron.copilotcontextbridge.model.DependencyRelation
import nl.ferron.copilotcontextbridge.model.PythonSymbol
import nl.ferron.copilotcontextbridge.model.RankedSelection
import nl.ferron.copilotcontextbridge.model.RelationConfidence
import nl.ferron.copilotcontextbridge.model.RelationType
import nl.ferron.copilotcontextbridge.settings.AppSettings
import java.nio.file.Path

class ContextMarkdownRendererTest : BasePlatformTestCase() {
    fun testRendersCompleteMultiBatchContractMappingsOmissionsAndHashes() {
        val app = AppSettings.getInstance().state
        app.mandatoryFirstQuestion = "Welke wijziging wil je uitvoeren?"
        app.returnFileInstruction = "Use the code tool and attach a real .copilotpatch file."
        val relation =
            DependencyRelation(
                "src/main.py",
                "src/helper.py",
                RelationType.DIRECT_IMPORT,
                RelationConfidence.CONFIRMED,
                evidence = "resolved import",
            )
        val pinned = candidate("src/main.py", true, 1000, listOf(relation))
        val automatic = candidate("src/helper.py", false, 800, listOf(relation))
        val omitted = candidate("tests/test_main.py", false, 650, emptyList())
        val selection = RankedSelection(listOf(pinned, automatic), listOf(omitted), emptyList(), listOf("one omitted"))

        val markdown =
            ContextMarkdownRenderer.render(
                ContextMarkdownRenderer.Input(
                    "fixture-repository",
                    "session-2",
                    selection,
                    "fixture-repository/\n└── src/",
                    listOf(relation),
                    mapOf("src/main.py" to listOf(PythonSymbol("run", "function", "sha256:abc"))),
                    mapOf("src/main.py" to "src__main.py", "src/helper.py" to "src__helper.py"),
                    "# Effective coding guidelines\n\nRepository first.",
                    listOf("AGENTS.md"),
                    "Fix issue",
                    "Fix the described problem.",
                    listOf(BatchSummary("session-1", "2026-08-07T01:00:00Z", "Review", listOf("src/old.py"), "HANDED_OFF")),
                    true,
                ),
            )

        assertTrue(markdown.contains("Welke wijziging wil je uitvoeren?"))
        assertTrue(markdown.contains("another 20 files, or more batches"))
        assertTrue(markdown.contains("session-1"))
        assertTrue(markdown.contains("`src__main.py` | `src/main.py`"))
        assertTrue(markdown.contains("src/main.py` → `src/helper.py"))
        assertTrue(markdown.contains("`run` — function — `sha256:abc`"))
        assertTrue(markdown.contains("tests/test_main.py"))
        assertTrue(markdown.contains("contents of omitted files were not supplied", ignoreCase = true))
        assertTrue(markdown.contains("Use the code tool and attach a real .copilotpatch file."))
        assertTrue(markdown.contains("\"summary\""))
        assertTrue(markdown.contains("```mermaid"))
        assertFalse(markdown.contains("C:\\Users"))
    }

    fun testAbsolutePathAndMermaidAreStrictlyOptIn() {
        val selection = RankedSelection(listOf(candidate("src/main.py", true, 1000, emptyList())), emptyList(), emptyList(), emptyList())

        val markdown =
            ContextMarkdownRenderer.render(
                ContextMarkdownRenderer.Input(
                    "repo",
                    "session",
                    selection,
                    "repo/",
                    emptyList(),
                    emptyMap(),
                    mapOf("src/main.py" to "src__main.py"),
                    "guidelines",
                    emptyList(),
                    "General",
                    "Prompt",
                    emptyList(),
                    false,
                    "C:\\explicit\\repo",
                ),
            )

        assertTrue(markdown.contains("Explicitly included local path: `C:\\explicit\\repo`"))
        assertFalse(markdown.contains("```mermaid"))
    }

    private fun candidate(
        path: String,
        pinned: Boolean,
        score: Int,
        relations: List<DependencyRelation>,
    ) = ContextCandidate(
        path,
        Path.of(path),
        score,
        if (pinned) 0 else 1,
        RelationConfidence.CONFIRMED,
        relations,
        pinned = pinned,
        size = 10,
    )
}
