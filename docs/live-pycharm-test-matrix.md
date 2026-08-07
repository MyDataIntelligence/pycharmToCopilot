# Live PyCharm release test matrix

This matrix is the authoritative acceptance gate. A test is complete only when its expected result is observed in a real PyCharm 2026.2.0.1 instance and a full-window screenshot is stored under `docs/screenshots/live-audit/`. Filesystem or clipboard assertions supplement screenshots where the UI cannot expose the complete result.

Status values: `PENDING`, `PASS`, `FAIL`, `BLOCKED`.

| ID | Area | Live scenario | Expected result | Status | Screenshot |
|---|---|---|---|---|---|
| L01 | Startup | Open fixture project | Bridge opens and primary Batch view fits | PENDING | |
| L02 | Selection | Context-menu add one file | Exactly one pinned path appears | PENDING | |
| L03 | Selection | Ctrl multi-select two files, then Add | Both selected paths appear as pinned | PENDING | |
| L04 | Selection | Add files from different folders successively | Earlier and later selections remain together | PENDING | |
| L05 | Selection | Multi-select Remove | Every selected pinned path is removed | PENDING | |
| L06 | Selection | Add a directory | Directory becomes discovery root; only files become candidates | PENDING | |
| L07 | Selection | Add Repository Structure | Filtered whole-repository tree is enabled without pinning every file | PENDING | |
| L08 | Selection | Add with Dependencies | Selected file is pinned and automatic fill is enabled | PENDING | |
| L09 | Selection | Add with Prompt Skill | Chosen skill persists and selected files are pinned | PENDING | |
| L10 | Dependencies | Direct Python import | Resolved local import is automatic with confirmed relation | PENDING | |
| L11 | Dependencies | Direct dependent | Importing caller is included when enabled | PENDING | |
| L12 | Dependencies | Related test | Matching test module is included when enabled | PENDING | |
| L13 | Dependencies | Referenced YAML/JSON/SQL | Referenced configuration is included as inferred relation | PENDING | |
| L14 | Dependencies | Package `__init__.py` | Relevant package initializers are included | PENDING | |
| L15 | Dependencies | Project configuration | `pyproject.toml` is considered for Python selections | PENDING | |
| L16 | Dependencies | Enable second-level dependencies | Dependency of a direct dependency is added with depth two | PENDING | |
| L17 | Dependencies | Enable complete package folder | Same-package files fill remaining slots | PENDING | |
| L18 | Dependencies | Disable automatic fill | Only pinned source files remain | PENDING | |
| L19 | Ranking | Generated-file penalty | Generated candidate ranks below normal candidates | PENDING | |
| L20 | Security | Pin likely secret | Preparation is blocked until explicit confirmation | PENDING | |
| L21 | Limits | Pin 20 source files with context reservation | Clear over-limit error; no invalid stage is created | PENDING | |
| L22 | Limits | More candidates than slots | Included and omitted lists total correctly and expose reasons | PENDING | |
| L23 | Selection | Exclude one automatic candidate | Candidate disappears for current batch without unpinning others | PENDING | |
| L24 | Staging | Prepare valid batch | Context plus selected files are copied to unique temp session | PENDING | |
| L25 | Clipboard | Copy files | Clipboard exposes real `FileDrop` list with exact staged count | PENDING | |
| L26 | Clipboard | Copy text | Combined text contains context, boundaries, paths and exact contents | PENDING | |
| L27 | Staging | Open folder | Explorer opens the exact session directory | PENDING | |
| L28 | Drag | Start file drag from drag handle | Copy-only Java file-list drag is offered | PENDING | |
| L29 | Batches | Next batch | Active pack clears and prepared batch remains in history | PENDING | |
| L30 | Batches | Restore batch | Historical paths become pinned again | PENDING | |
| L31 | Batches | Forget batch | Selected history entry disappears | PENDING | |
| L32 | Batches | Avoid previous files | Earlier paths are excluded from later automatic candidates | PENDING | |
| L33 | More | Context preview | Complete current context is readable and copyable | PENDING | |
| L34 | More | Copy context only | Clipboard contains context Markdown but no combined file bodies | PENDING | |
| L35 | More | Copy return instructions | Clipboard matches editable configured return instructions | PENDING | |
| L36 | Context | Repository tree and mapping | Tree, staged/original mapping, dependency map and hashes are present | PENDING | |
| L37 | Guidelines | Detect, toggle and open sources | Source state changes and source opens in editor | PENDING | |
| L38 | Guidelines | Save, reset, export and import global guidelines | Each operation round-trips without silent repository writes | PENDING | |
| L39 | Guidelines | Create missing modular structure | Explicit confirmation creates only requested guideline files | PENDING | |
| L40 | Skills | Select every built-in skill | Every skill is visible, readable and selectable | PENDING | |
| L41 | Skills | Edit and save skill prompt/guidelines | Changes persist after navigating away and back | PENDING | |
| L42 | Skills | Add, duplicate and delete custom skill | CRUD updates list deterministically and never deletes final skill | PENDING | |
| L43 | Skills | Export and import skills | JSON round-trip restores prompt and guidelines | PENDING | |
| L44 | Settings | Project settings persistence | Every project option is editable and survives reopen | PENDING | |
| L45 | Settings | Application settings persistence | Question, text templates, patterns and retention survive reopen | PENDING | |
| L46 | Context | Absolute-path setting | Absolute repository path is absent by default and present only when enabled | PENDING | |
| L47 | Context | Mermaid setting | Mermaid block follows toggle while plain relationship list remains | PENDING | |
| L48 | Import | Paste JSON | Clipboard JSON appears and validates off the EDT | PENDING | |
| L49 | Import | Open `.copilotpatch` file | File loads and shows individual changes | PENDING | |
| L50 | Import | Open ZIP patch | `changes.json` and replacement snippets load safely | PENDING | |
| L51 | Import | Safe function replacement | Complete function changes; neighbours remain unchanged | PENDING | |
| L52 | Import | Undo replacement | One Undo restores complete original function | PENDING | |
| L53 | Import | Multiple functions and files | Selected functions across files apply in one command | PENDING | |
| L54 | Import | Add top-level function | New function appears at requested anchor without whole-file replacement | PENDING | |
| L55 | Import | Add class and nested function | Parent and anchor are resolved unambiguously | PENDING | |
| L56 | Import | Decorated and async functions | Identity and opt-in type/decorator changes validate correctly | PENDING | |
| L57 | Import | Changed hash conflict | Default apply is blocked; explicit Force Replace works | PENDING | |
| L58 | Import | Missing/ambiguous target | Unsafe item is reported and cannot be selected automatically | PENDING | |
| L59 | Import | Traversal/absolute/symlink path | Patch is rejected before file access | PENDING | |
| L60 | Import | Invalid/multi-function snippet | Syntax or extra statements are rejected | PENDING | |
| L61 | Import | Wrong repository/session | Repository mismatch blocks; missing session warns without weakening hashes | PENDING | |
| L62 | Import | Select safe / deselect conflicts | Bulk-selection buttons affect only eligible rows | PENDING | |
| L63 | Import | Optional optimize/reformat/post-command | Enabled post-apply options report what actually ran | PENDING | |
| L64 | Persistence | Restart PyCharm | Selection, batches, settings and custom skills persist as configured | PENDING | |
| L65 | Installation | Rebuild and reinstall from clone | Installer updates atomically and plugin loads once | PENDING | |
| L66 | Copilot | Paste staged files in Microsoft 365 Copilot | Exact staged file list is accepted without sending the chat | PENDING | |
| L67 | Copilot | Drag staged files toward Copilot | Platform result is recorded honestly; fallback remains available | PENDING | |
| L68 | More | Open Context preview card | More navigates to full preview and Back/Batch returns cleanly | PENDING | |
| L69 | More | Open Guidelines card | More navigates to the guideline manager without mixing Import controls | PENDING | |
| L70 | More | Open Prompt skills card | More opens the complete skill editor and long text stays readable | PENDING | |
| L71 | More | Open Settings card | Correct Copilot Context Bridge settings page opens | PENDING | |
| L72 | More | Quick Copy context | Quick action copies the same context as the preview action | PENDING | |
| L73 | More | Quick Copy return instructions | Quick action copies the configured return contract | PENDING | |
| L74 | More | Batch selector with several batches | Selector and history text show the same selected batch | PENDING | |
| L75 | Diff | Switch between function diffs | Diff pane updates to the chosen function and keeps selection state | PENDING | |
| L76 | Diff | View combined diff | All validated replacements appear once in stable file/function order | PENDING | |
| L77 | Diff | Conflict diff | Current local function and proposed replacement are both visible | PENDING | |
| L78 | Diff | New-function diff | Addition is rendered entirely as added lines with correct path and identity | PENDING | |
| L79 | Diff | Invalid replacement details | Validation reason remains visible while Apply stays disabled | PENDING | |
| L80 | Diff | Line counts and long diff scrolling | Old/new counts are correct and both axes remain usable | PENDING | |
| L81 | More | Keep staged session | Keep marker is created and retention cleanup skips the selected batch | PENDING | |
| L82 | More | Delete staged session | Confirmation deletes only temporary safe copies and leaves repository/history intact | PENDING | |
