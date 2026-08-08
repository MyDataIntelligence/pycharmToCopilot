package nl.ferron.copilotcontextbridge.settings

object Defaults {
    const val FIRST_QUESTION = "Wat wil je precies bereiken met deze bestanden en welke wijziging wil je dat ik uitvoer?"

    val KICKOFF_PROMPT_TEMPLATE =
        """
        Please read 00_REPO_CONTEXT.md first. It is the master index for this batch.
        Then read every attached file listed in that context file.
        Use the selected task instructions: {promptSkill}.
        Use the original repository paths from 00_REPO_CONTEXT.md as file identities.
        Follow the effective Return Instructions included in 00_REPO_CONTEXT.md.
        This is batch {batchNumber} in session {sessionId}.
        More batches may follow. Wait until I confirm that all batches are uploaded before final analysis or changes.
        """.trimIndent()

    const val RETURN_FILE_INSTRUCTION =
        "Use your code/file-creation tool to create and attach a real downloadable file named " +
            "`copilot-result.copilotpatch` (JSON) or `copilot-result.zip`. Do not paste the patch or replacement code " +
            "as ordinary chat text. Only if this Copilot interface has no file-creation tool may you fall back to one " +
            "fenced JSON block and state that the fallback was necessary."

    const val COMBINED_TEXT_INTRO =
        "This is a complete text copy of one Copilot Context Bridge batch. Each section records the original " +
            "repository-relative path, staged filename, hash and selection reason before the exact supplied content."

    val PYTHON_AUTHORING_RULES =
        """
        # Python naming and docstrings

        - Write English Google-style docstrings that follow the Sphinx Napoleon example: https://sphinxcontrib-napoleon.readthedocs.io/en/latest/example_google.html.
        - Document the current functional contract with a concise summary and the applicable `Args:`, `Returns:`, `Yields:` and `Raises:` sections. Do not add empty or irrelevant sections.
        - Keep docstrings purely functional. Never record change history or implementation commentary such as "changed because", "modified to", "updated so that", or similar wording.
        - Name every function and method with a clear leading verb and a descriptive snake_case name that states its action and subject, for example `load_pipeline_config` or `validate_workspace_path`.
        - Use meaningful, domain-specific names for variables and parameters. Avoid vague names such as `data`, `value`, `item`, `obj`, `tmp` or single-letter names unless their meaning is genuinely conventional and unambiguous in the local scope.
        """.trimIndent()

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

    /** Extensions that should stay out of generated context attachments unless explicitly pinned. */
    val excludedContextExtensions =
        listOf(
            "bin",
            "class",
            "jar",
            "war",
            "png",
            "jpg",
            "jpeg",
            "gif",
            "webp",
            "ico",
            "pdf",
            "zip",
            "7z",
            "tar",
            "gz",
            "bz2",
            "db",
            "sqlite",
            "sqlite3",
            "parquet",
            "feather",
            "xlsx",
            "xls",
            "docx",
            "pptx",
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
        - Write English Google-style docstrings following the Sphinx Napoleon example: https://sphinxcontrib-napoleon.readthedocs.io/en/latest/example_google.html.
        - Use `Args:`, `Returns:`, `Yields:` and `Raises:` exactly where applicable; omit sections that do not apply.
        - Describe only the function's current behavior in a docstring. Never mention that code was changed, modified or updated, and never explain change history there.
        - Start every function and method name with a clear verb and use a descriptive snake_case action name.
        - Give variables and parameters meaningful domain-specific names; avoid vague names such as `data`, `value`, `item`, `obj` or `tmp` when a precise name is available.
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
