# Copilot Context Bridge

A native PyCharm plugin that prepares safe, batch-aware repository context for Microsoft 365 Copilot and imports complete Python function changes back through validated PSI operations.

## Install after cloning

On Windows, close PyCharm and run:

```powershell
git clone https://github.com/MyDataIntelligence/pycharmToCopilot.git
cd pycharmToCopilot
powershell -ExecutionPolicy Bypass -File .\install.ps1
```

The script builds with the included Gradle Wrapper, detects the newest compatible PyCharm profile, installs atomically, and keeps the previous plugin directory in `%LOCALAPPDATA%\CopilotContextBridge\plugin-backups`. Backups deliberately stay outside PyCharm's plugin directory so the IDE cannot load a stale duplicate plugin ID. Use `-BuildOnly`, `-TargetConfigDir`, or `-PluginsDir` when needed. The first build downloads the PyCharm SDK and can take several minutes.

Manual fallback: run `gradlew.bat buildPlugin`, then choose **Settings → Plugins → gear → Install Plugin from Disk** and select `build/distributions/copilot-context-bridge-1.0.0.zip`.

Supported IDEs: PyCharm Community/Professional 2025.1–2025.2 and unified PyCharm free/Pro 2025.3–2026.2. The plugin uses only `PythonCore` APIs. Build toolchain: JDK 21, Gradle 9.5.0, Kotlin 2.1.20, IntelliJ Platform Gradle Plugin 2.18.1. On Windows the installer builds in `%LOCALAPPDATA%` to avoid OneDrive locking Gradle's generated files, then copies the final ZIP back to `build/distributions`.

## Live screenshots

- [Main three-step batch workflow](docs/screenshots/live-main-layout.png)
- [Project View multi-select actions](docs/screenshots/live-project-menu-multiselect.png)
- [Prompt skill editor](docs/screenshots/live-prompt-skills.png)
- [Validated function-level import](docs/screenshots/live-import-final-validated.png)
- [Staged file accepted by Microsoft 365 Copilot](docs/screenshots/live-m365-file-attached.png)

## Outbound workflow

1. Select files or a directory in Project View and choose **Copilot Context Bridge → Add to Copilot Context**, **Add with Dependencies**, or **Add with Prompt Skill**.
2. Directories act as discovery roots; they are never upload items. **Add Repository Structure** adds the complete filtered tree without uploading every file.
3. Open the tool window, choose a prompt skill, review pinned/automatic/omitted candidates and press the green **Prepare for Copilot** button.
4. Drag the real staged files, copy them, or open the staging folder. Every batch includes `00_REPO_CONTEXT.md` and at most 19 source files.
5. The dashed area is only a drag handle after preparation; before that it clearly says drag is unavailable. **Copy files**, **Copy text**, **Open folder** and **Next batch** become available after a safe pack exists.
6. Prepared paths move from the active selection into Batch History. Previously prepared files are excluded from later automatic batches, but **Restore** can pin them again.
7. Press **Next batch** and select the next files. Each context document lists earlier prepared batches and tells Copilot that another 20 files or more batches may follow.

The plugin cannot prove that an external browser accepted a drop. History therefore says “prepared”, not “uploaded”. The context asks the user to confirm when every intended batch has arrived.

## Prompt skills and guidelines

Prompt Skills combine a task prompt with editable skill-specific guidelines. The leading built-ins are **General change**, **New reusable Python code**, **Fix issue**, **Create implementation-ready user story**, **Repository code review** and **Refactor selected code**. The new-code workflow prefers proven repository placement, otherwise `scripts/functions/` or `scripts/`, and requires a production module plus matching tests. Review is read-only and prioritizes concrete reuse, duplication and guideline evidence; Refactor can produce an explicit behavior-preserving patch/ZIP. Additional built-ins cover documentation, tests and architecture, plus **Skill Creator**, **Slash Command Creator** and **AGENTS.md Creator**. Skills can be added, duplicated, changed, imported or exported. The editor uses vertically resizable, word-wrapped prompt and guideline panes so long instructions remain usable in a narrow tool window.

The main tool window intentionally has three destinations: **Batch** for the primary workflow, **Import** only for patch validation/diffs/apply, and **More** for context preview, guidelines, prompt skills, settings, quick-copy actions and batch history.

Effective priority is: current Copilot-chat instruction → selected skill guidelines → enabled repository guidelines → global personal guidelines → plugin defaults. Repository sources include `.github/copilot-instructions.md`, `.github/skills/code-guidelines/`, `AGENTS.md`, `CONTRIBUTING.md`, selected README sections and relevant `pyproject.toml` keys. Repository files are written only after an explicit user action.

## File limit and dependency ranking

The configurable maximum is 2–20 and always includes the required context document. Pinned files consume the remaining slots first. Automatic candidates use deterministic scores: direct imports 800, dependents 700, tests 650, referenced config 550, package init 450, project config 400, second-level 300, package neighbours 200 and inferred references 100. Ties use distance, confidence, size and alphabetical path.

Python PSI resolves local references where possible. Text analysis recognizes common JSON/YAML/TOML/SQL/CSV, GitHub/Azure pipeline and Fabric notebook/pipeline references. Dynamic behavior remains inferred or unresolved.

## Returning changes from Copilot

Every context tells Copilot to use its code/file-creation tool and attach a real `copilot-result.copilotpatch` or ZIP instead of pasting code as normal chat text. The patch contains a structured summary and `replace_function` or `add_function` operations. A fenced JSON block is only a fallback when that Copilot interface cannot create files.

Drop the result in **Import Changes**, open it, or paste fallback JSON. The plugin validates repository/session identity, paths and symlinks, Python syntax, qualified names, sync/async and decorator kinds, parent/anchor identity, original hashes and overlapping edits. Each function has its own status and unified diff. `CHANGED` requires explicit Force Replace; unsafe items cannot be selected.

Applying uses a PyCharm write command and is undoable. Only complete PSI functions are replaced or inserted; unrelated code remains untouched. New functions require `parentQualifiedName` and may specify `insertAfterQualifiedName`.

**Paste JSON** reads fallback patch JSON directly from the system clipboard and then leaves validation as a separate explicit action.

See [context format](docs/context-format.md), [patch format](docs/copilotpatch-format.md), [dependency analysis](docs/dependency-analysis.md), [security](docs/security.md), [architecture](docs/architecture.md), and [testing](docs/testing.md).

## Build and development

```powershell
.\gradlew.bat clean check
.\gradlew.bat runIde
.\gradlew.bat buildPlugin
.\gradlew.bat verifyPlugin
```

## Security and limitations

- Likely secrets are never selected automatically and manually pinned suspicious files require confirmation.
- Absolute/traversal paths and symlink escapes are rejected; secret values are never logged.
- Swing-to-browser drag support varies by OS/browser; the staging folder is the reliable fallback.
- Static analysis cannot fully resolve dynamic imports or runtime Fabric names.
- Omitted files are listed but never presented as supplied.
- Locally changed exported functions require conflict handling.
- Copilot must follow the patch schema for automatic import.
- PyCharm 2026.2's native `reformat(PyFunction)` post-processor can crash immediately after a structural replacement. On build 262 and newer the plugin preserves the already parsed replacement formatting and skips that unsafe post-step; **Code -> Reformat Code** remains available afterward.
- Compatibility stops at build 262 until a newer IDE line is verified.

Licensed under the MIT License.
