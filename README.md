# Copilot Context Bridge

A native PyCharm plugin that prepares safe, batch-aware repository context for Microsoft 365 Copilot or GitHub Copilot, and imports reviewed Python function and file changes through validated PSI/write-command operations.

## Install after cloning

Close PyCharm, then run on Windows:

```powershell
git clone https://github.com/MyDataIntelligence/pycharmToCopilot.git
cd pycharmToCopilot
powershell -ExecutionPolicy Bypass -File .\install.ps1
```

The script builds with the included Gradle Wrapper, detects the newest compatible PyCharm profile, installs atomically, and keeps the previous plugin under `%LOCALAPPDATA%\CopilotContextBridge\plugin-backups`. Backups deliberately remain outside PyCharm's plugin directory so the IDE cannot load a duplicate plugin ID. The first build downloads the PyCharm SDK and can take several minutes.

Useful installer options:

```powershell
.\install.ps1 -BuildOnly
.\install.ps1 -TargetConfigDir "$env:APPDATA\JetBrains\PyCharm2026.2"
.\install.ps1 -PluginsDir "D:\custom\pycharm\plugins"
```

Manual fallback: run `.\gradlew.bat buildPlugin`, then choose **Settings → Plugins → gear → Install Plugin from Disk** and select `build/distributions/copilot-context-bridge-1.0.0.zip`.

Supported IDE builds are declared as 251 through 262 (`since-build=251`, `until-build=262.*`). The plugin is compiled against PyCharm Community 2025.2.6.1. Its configured verifier matrix covers Community/Professional 2025.1–2025.2 and unified PyCharm 2026.2.0.1. PyCharm 2026.2 is the current stable release used for the live release matrix; compatibility is considered proven only after Plugin Verifier and that live matrix pass. The plugin uses `PythonCore`, so the intended targets are free/Community and Professional editions.

Build toolchain: JDK 21, Gradle 9.5.0, Kotlin 2.1.20, IntelliJ Platform Gradle Plugin 2.18.1. On Windows the installer builds below `%LOCALAPPDATA%` to avoid OneDrive locks, then copies the ZIP to `build/distributions`.

## Primary outbound workflow

1. Multi-select files in Project View and choose **Copilot Context Bridge → Add to Copilot Context**, **Add with Dependencies**, or **Add with Prompt Skill**. Repeat from other folders; pinned paths persist together.
2. In the tool window, verify **Files in this batch**, choose a Prompt Library entry, and review its Context Policy.
3. Press the green **Prepare for Copilot** button. This creates a safe temporary pack and never changes repository files.
4. Drag or copy the real staged attachments, or open the staging folder as the reliable fallback.
5. Start **Next batch** when more context is needed. History records what was prepared; a new conversation session excludes old batches from current-session reasoning.

Directories are discovery roots, not upload items. **Add Repository Structure** includes the filtered tree without selecting every file. The plugin cannot prove that a browser accepted a drop, so history says “prepared”, not “uploaded”. The generated prompt says that another 20 files or more batches may follow and asks the user to confirm when the intended set is complete.

## Context-aware Prompt Library

Every Prompt Library entry owns:

- its task prompt and editable prompt-specific guidelines;
- a versioned, data-driven `ContextPolicy`;
- target: Microsoft 365 Copilot or GitHub Copilot;
- return mode: `.copilotpatch`, code-tool files, text-only, or direct repository editing;
- previous-batch behavior;
- repository-file and physical-attachment limits;
- resolver rules with enablement, priority, required flag, depth, file limit, bundle group and keep-separate behavior;
- optional Return Instructions appended to the inherited mode contract.

This avoids hardcoded prompt-specific `if` chains. Rules cover pinned files, matching and nearby tests, fixtures, direct imports/callers/callees, transitive imports, referenced configuration, `AGENTS.md`, Copilot instructions, project guidelines, similar implementations, templates and branch changes.

Built-ins include **General change**, **New reusable Python code**, **Debug problem**, **Create user story**, **Review code**, **Refactor selected code**, documentation, generated tests, architecture, branch-to-PR preparation, repository-to-user-story analysis, **Skill Creator**, **Slash Command Creator**, and **AGENTS.md Creator**. Creator prompts target GitHub Copilot direct editing where appropriate. Entries and their policies can be added, duplicated, edited, imported, exported or reset.

Effective guideline priority is: current chat instruction → prompt-specific guidelines → enabled repository guidelines → global personal guidelines → plugin defaults. Sources include `.github/copilot-instructions.md`, `.github/skills/code-guidelines/`, root/scoped `AGENTS.md`, `CONTRIBUTING.md`, selected README sections and relevant `pyproject.toml` keys. Repository guidance is never silently rewritten.

## Repository context versus physical attachments

Two limits are intentionally separate:

- `maxRepositoryFiles`: repository files the policy may analyse and pack (default 50, configurable up to 500).
- `maxAttachments`: physical files sent to Copilot (default and maximum 20), including `00_REPO_CONTEXT.md`.

Pinned files have priority and remain separate by default. Automatic context can be bundled into deterministic Markdown files by group (`tests`, `dependencies`, `configuration`, `instructions`, or a custom group). A physical batch can therefore represent more than 19 repository files while remaining at or below 20 attachments. The context document and session manifest map every attachment back to all original repository-relative paths. Original files are never renamed.

Candidates are ranked deterministically. Defaults include direct imports 800, dependents 700, tests 650, referenced configuration 550, package initializers 450, project configuration 400, second-level dependencies 300, package neighbours 200 and inferred relationships 100. Policy priorities affect resolver ordering; ties use dependency distance, confidence, smaller size, then alphabetical path. Included, omitted and excluded candidates retain their evidence and reason.

`Add files` and the outbound drop zone also accept a ZIP as a discovery source. Safe UTF-8 text entries keep their full archive-relative paths and are labelled with the archive name. They are automatic candidates rather than pinned files, so loose explicitly selected files win and larger archives flow deterministically across later batches. Ignored, secret-like, binary, traversal, duplicate, symlink/special, oversized and excessive entries are excluded or rejected; archive content is materialized only in a retention-limited temporary cache.

Exclusions can apply to the current batch, current conversation session, or permanently for the project. **Include once** overrides an exclusion for only the current batch. Starting a new session resets session-scoped history/exclusions while retaining project configuration.

## Return Instructions

Return Instructions inherit in this order: mode default → optional project override → prompt-specific addition. The editor shows the effective result and validates required identity and safety fields. Both **Copy context only** and **Copy return instructions** remain available as compact actions and previews; the instructions are also embedded in the prepared context.

For Microsoft 365 Copilot, the default code-return contract tells Copilot to use its code/file-creation tool and attach a real downloadable result rather than ordinary chat text. Text-only output is an explicit mode, not an accidental fallback.

## Import from Copilot

The Import page accepts `.copilotpatch`, matching JSON, or ZIP by drop, file picker, or pasted JSON. A ZIP with root `changes.json` always uses the strict structured format. A plain code ZIP is a controlled fallback: exact repository-relative paths are matched first, then a unique basename may be proposed; ambiguous basenames are rejected. Every proposed whole-file add/replace still requires diff review, selection, revalidation and Apply confirmation. Supported structured operations are:

- `replace_function`: replace one complete top-level, method, async, decorated or unambiguous nested Python function;
- `add_function`: insert one complete function at a validated module/parent/anchor;
- `add_file`: create one complete new file at a safe repository-relative path;
- `delete_file`: delete one existing file after path and exported-hash validation.

Validation covers schema, repository/session identity, traversal/absolute/symlink paths, target type, Python syntax, qualified names, parent and anchor identity, decorators, sync/async compatibility, hashes and overlapping changes. Native PyCharm diff views show each operation; conflicts can show `BASE (exported)`, `CURRENT (local)` and `PROPOSED (Copilot)`. Safe operations can be selected individually. Conflicts require an explicit keep-current/use-Copilot decision or Force Replace.

Apply runs as a PyCharm write command and supports Undo. Function operations preserve unrelated code. File additions/deletions are limited to validated project paths. Post-apply reporting distinguishes validation that actually ran from checks that were not run.

See [context format](docs/context-format.md), [patch format](docs/copilotpatch-format.md), [dependency analysis](docs/dependency-analysis.md), [security](docs/security.md), [architecture](docs/architecture.md), [testing](docs/testing.md), and the [live PyCharm test matrix](docs/live-pycharm-test-matrix.md).

## Build and development

```powershell
.\gradlew.bat clean test
.\gradlew.bat ktlintCheck
.\gradlew.bat buildPlugin
.\gradlew.bat verifyPlugin
.\gradlew.bat runIde
```

CI uses JDK 21, dependency caching, tests/static checks, Plugin Verifier and uploads the built ZIP. It does not publish to Marketplace.

## Security and honest limitations

- Likely secrets are never automatically selected; suspicious pinned files require explicit confirmation.
- Absolute/traversal paths, ZIP escapes and repository symlink escapes are rejected; secret contents are never logged.
- Swing-to-browser file drag varies by OS/browser. Opening the staging folder is the reliable fallback.
- Static analysis cannot fully resolve dynamic imports or runtime Fabric references; inferred relations remain labelled inferred.
- Omitted files are listed but never presented as supplied or analysed in full.
- Locally changed exported functions/files require conflict handling.
- Automatic import depends on Copilot following the versioned patch contract.
- PyCharm 2026.2's native `reformat(PyFunction)` post-processor may be unsafe immediately after structural replacement. On build 262+ the plugin preserves parsed replacement formatting and skips that post-step; **Code → Reformat Code** remains available afterward.
- Compatibility beyond build 262 is not claimed until verified.

Licensed under the MIT License.
