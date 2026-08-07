package nl.ferron.copilotcontextbridge.settings

object Defaults {
    const val FIRST_QUESTION = "Wat wil je precies bereiken met deze bestanden en welke wijziging wil je dat ik uitvoer?"

    const val RETURN_FILE_INSTRUCTION =
        "Use your code/file-creation tool to create and attach a real downloadable file named " +
            "`copilot-result.copilotpatch` (JSON) or `copilot-result.zip`. Do not paste the patch or replacement code " +
            "as ordinary chat text. Only if this Copilot interface has no file-creation tool may you fall back to one " +
            "fenced JSON block and state that the fallback was necessary."

    const val COMBINED_TEXT_INTRO =
        "This is a complete text copy of one Copilot Context Bridge batch. Each section records the original " +
            "repository-relative path, staged filename, hash and selection reason before the exact supplied content."

    val ignorePatterns =
        listOf(
            ".git/",
            ".platform/",
            ".idea/",
            ".venv/",
            "venv/",
            "env/",
            "__pycache__/",
            ".pytest_cache/",
            ".mypy_cache/",
            ".ruff_cache/",
            ".gradle/",
            "node_modules/",
            "dist/",
            "build/",
            "target/",
            "coverage/",
            "*.pyc",
            "*.pyo",
            "*.class",
            "*.jar",
        )

    val secretPatterns =
        listOf(
            ".env",
            ".env.*",
            "*.pem",
            "*.key",
            "*.pfx",
            "*.p12",
            "id_rsa",
            "id_ed25519",
            "credentials*",
            "secrets*",
            "service-account*",
        )

    val globalGuidelines =
        """
        # General approach

        - Prefer existing code, modules, utilities and packages already present in the project.
        - Do not silently duplicate existing helpers.
        - Preserve unrelated behavior.
        - Make the smallest safe change that satisfies the request.
        - Prefer clear and explicit code over clever compact code.
        - Do not perform unrelated refactors unless explicitly requested.

        # Python style

        - Use type hints on function parameters and return values.
        - Use Google-style docstrings in English.
        - Use dataclasses for structured results where appropriate.
        - Keep functions focused and reasonably bounded.
        - Use pathlib instead of manual string path construction where appropriate.
        - Use standard-library functionality before adding dependencies.
        - Preserve repository-specific formatting and naming conventions.

        # Logging

        - Use Python's standard logging module. Do not introduce loguru.
        - Prefer lazy logging formatting such as logger.info("Processing file: %s", path).
        - Log meaningful boundaries, decisions and useful configuration values.
        - Never log secrets or every trivial value/data row.

        # Microsoft Fabric

        - Ensure APIs and examples are valid for Microsoft Fabric, not automatically Databricks.
        - Prefer notebookutils.notebook.exit(...) for controlled Fabric notebook output.
        - Do not use Databricks dbutils.exit or %pip install in pipeline-invoked notebooks.
        - Keep notebooks thin and move reusable logic to Python modules.
        - Use Fabric-compatible Lakehouse and OneLake path handling.
        - Do not mask real technical failures with a successful notebook exit.

        # Testing and review

        - Update or add happy-path, failure-path, edge-case and regression tests where practical.
        - Mock network, Fabric, Azure and filesystem boundaries appropriately.
        - Check imports, callers, tests, configuration, error handling, path safety and secrets.
        - Change only requested functionality.
        """.trimIndent()
}
