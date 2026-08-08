# Live PyCharm 2026.2 release test matrix

This is the authoritative live acceptance gate. Every scenario must be observed in an actual PyCharm 2026.2.0.1 instance. The screenshot column is the required output path, not evidence that already exists.

Rules:

- every screenshot must show the complete PyCharm window, including title bar, Project View, tool window and relevant dialog/popup;
- do not crop a control or substitute a rendered mock-up;
- status stays `PENDING` until the expected result is observed and the named PNG is reviewed;
- filesystem, clipboard, Undo, persistence and Copilot outcomes require a short supplemental assertion in the release log;
- `PASS`, `FAIL`, `BLOCKED` and `NOT_APPLICABLE` are allowed only after execution; `NOT_APPLICABLE` needs a reason;
- no release while a required row is `PENDING`, `FAIL` or `BLOCKED`.

## Installation, startup and stable layout

| ID | Scenario | Expected result | Status | Full-window screenshot |
|---|---|---|---|---|
| L001 | Install from a fresh clone with `install.ps1` | Build/install completes atomically and exactly one plugin instance loads | PENDING | `docs/screenshots/live-audit/L001-fresh-clone-install.png` |
| L002 | Repeat `install.ps1` update | Previous plugin is backed up outside plugin directory; updated plugin loads once | PENDING | `docs/screenshots/live-audit/L002-repeat-install-update.png` |
| L003 | Manual ZIP install fallback | Built ZIP is accepted by Install Plugin from Disk | PENDING | `docs/screenshots/live-audit/L003-manual-zip-install.png` |
| L004 | Open fixture project | Tool window opens on compact three-step Batch page and fits at normal width | PASS | `docs/screenshots/live-audit/L174-reinstalled-final.png` |
| L005 | Resize tool window narrow/wide | Primary controls remain readable; scrolling appears instead of clipping | PASS | `docs/screenshots/live-audit/L005-responsive-layout.png` |
| L006 | Switch Batch → Import → Preview → More | Navigation remains stable and no controls leak between pages | PASS | `docs/screenshots/live-audit/L175-import-final.png`, `L151-preview-tab.png`, `L152-more-tab.png`, `L174-reinstalled-final.png` |
| L007 | Open every More destination repeatedly | More action/card positions do not jump from bottom to top or reorder | PASS | `docs/screenshots/live-audit/L007-more-stable-button-order.png` |
| L008 | Reopen project | Configured auto-open behavior is respected without duplicate tool windows | PENDING | `docs/screenshots/live-audit/L008-project-reopen.png` |

## Project View selection

| ID | Scenario | Expected result | Status | Full-window screenshot |
|---|---|---|---|---|
| L009 | Right-click one file | Bridge submenu shows Add, Add with Dependencies, Add with Prompt Skill and Open | PASS | `docs/screenshots/live-audit/L009-project-menu-one-file.png` |
| L010 | Ctrl multi-select two files | One Add action pins both selected paths | PENDING | `docs/screenshots/live-audit/L010-multiselect-two-files.png` |
| L011 | Shift multi-select a range | Every selected file is pinned once in stable path order | PENDING | `docs/screenshots/live-audit/L011-multiselect-range.png` |
| L012 | Add from different folders successively | Earlier and later pins remain together | PENDING | `docs/screenshots/live-audit/L012-cross-folder-selection.png` |
| L013 | Multi-select Remove | All selected pinned paths are removed; unrelated pins remain | PENDING | `docs/screenshots/live-audit/L013-multiselect-remove.png` |
| L014 | Add directory | Directory becomes discovery root; directory itself is never an attachment | PENDING | `docs/screenshots/live-audit/L014-directory-discovery-root.png` |
| L015 | Add Repository Structure | Full filtered tree is enabled without pinning every repository file | PENDING | `docs/screenshots/live-audit/L015-add-repository-structure.png` |
| L016 | Add with Dependencies | Selected files pin and automatic resolver rules run | PENDING | `docs/screenshots/live-audit/L016-add-with-dependencies.png` |
| L017 | Add with Prompt Skill | Skill chooser is readable; selection and chosen skill persist | PENDING | `docs/screenshots/live-audit/L017-add-with-prompt-skill.png` |
| L018 | Deleted or moved pinned file | Invalid path is visible with actionable warning, never silently retained as valid | PENDING | `docs/screenshots/live-audit/L018-invalid-pinned-path.png` |

## Context Policy and Prompt Library

| ID | Scenario | Expected result | Status | Full-window screenshot |
|---|---|---|---|---|
| L019 | Open More → Prompt Library | Categorised built-ins and custom entries are readable at narrow width | PASS | `docs/screenshots/live-audit/L172-prompt-skills.png` |
| L020 | Select every built-in | General, new code, debug, story, review, refactor, docs, tests, architecture, PR, analysis and creators are selectable | PENDING | `docs/screenshots/live-audit/L020-all-builtins.png` |
| L021 | Edit and save prompt/guidelines | Long wrapped text remains usable and changes persist | PENDING | `docs/screenshots/live-audit/L021-edit-prompt-guidelines.png` |
| L022 | Add custom prompt | New entry receives editable prompt, guidelines, policy and return addition | PENDING | `docs/screenshots/live-audit/L022-add-custom-prompt.png` |
| L023 | Duplicate prompt | Copy has independent ID/state and identical initial policy | PENDING | `docs/screenshots/live-audit/L023-duplicate-prompt.png` |
| L024 | Delete custom prompt | Confirmation works and final remaining entry cannot be deleted | PENDING | `docs/screenshots/live-audit/L024-delete-custom-prompt.png` |
| L025 | Import/export Prompt Library | Round-trip preserves category, prompt, guidelines, policy and return addition | PENDING | `docs/screenshots/live-audit/L025-prompt-library-roundtrip.png` |
| L026 | Open selected prompt Context Policy | Dialog exposes target, return mode, previous batches, both limits and rules | PASS | `docs/screenshots/live-audit/L173-context-policy.png` |
| L027 | Toggle policy rule | Resolver enablement changes prepared candidates after recalculation | PENDING | `docs/screenshots/live-audit/L027-policy-rule-toggle.png` |
| L028 | Change priority | Candidate order changes deterministically and priority is visible | PENDING | `docs/screenshots/live-audit/L028-policy-priority.png` |
| L029 | Configure maxDepth/maxFiles | Rule limits persist and constrain resolver output | PENDING | `docs/screenshots/live-audit/L029-policy-rule-limits.png` |
| L030 | Configure bundle group/keep separate | Prepared attachment mapping follows edited packing rule | PENDING | `docs/screenshots/live-audit/L030-policy-packing-rule.png` |
| L031 | Change target and return mode | Effective Return Instructions and prompt output contract update | PENDING | `docs/screenshots/live-audit/L031-policy-target-return-mode.png` |
| L032 | Previous batches: same session/never/always | Each mode changes previous-path handling exactly as labelled | PENDING | `docs/screenshots/live-audit/L032-policy-previous-batches.png` |
| L033 | Change repository-file and attachment limits | Independent limits validate and prepared counts follow both | PENDING | `docs/screenshots/live-audit/L033-policy-independent-limits.png` |
| L034 | Toggle automatic bundling | Same source selection becomes bundles or separate attachments as policy permits | PENDING | `docs/screenshots/live-audit/L034-policy-bundle-toggle.png` |
| L035 | Reset policy | Selected prompt returns to its own built-in defaults | PENDING | `docs/screenshots/live-audit/L035-policy-reset.png` |

## Dependency resolvers, ranking and files view

| ID | Scenario | Expected result | Status | Full-window screenshot |
|---|---|---|---|---|
| L036 | Direct local Python import | Confirmed dependency appears with rule/reason/priority | PENDING | `docs/screenshots/live-audit/L036-direct-import.png` |
| L037 | Direct caller/dependent | Importing caller appears only when enabled | PENDING | `docs/screenshots/live-audit/L037-direct-caller.png` |
| L038 | Direct callee/reference | Resolved called symbol file appears with evidence | PENDING | `docs/screenshots/live-audit/L038-direct-callee.png` |
| L039 | Matching test | Related test is selected by matching-tests resolver | PENDING | `docs/screenshots/live-audit/L039-matching-test.png` |
| L040 | Fixtures | Shared fixture enters tests bundle when enabled | PENDING | `docs/screenshots/live-audit/L040-test-fixture.png` |
| L041 | Nearby tests | Disabled by default; appears only after explicit enable | PENDING | `docs/screenshots/live-audit/L041-nearby-tests.png` |
| L042 | Transitive imports depth two | Second-level dependency appears with distance two | PENDING | `docs/screenshots/live-audit/L042-transitive-import.png` |
| L043 | JSON/YAML/TOML/SQL/CSV reference | Referenced configuration is inferred and mapped | PENDING | `docs/screenshots/live-audit/L043-referenced-configuration.png` |
| L044 | Fabric notebook/pipeline relation | Notebook run or pipeline activity is labelled inferred/dynamic honestly | PENDING | `docs/screenshots/live-audit/L044-fabric-relation.png` |
| L045 | Package initializer/project config | Relevant `__init__.py` and `pyproject.toml` candidates appear | PENDING | `docs/screenshots/live-audit/L045-package-project-config.png` |
| L046 | Similar implementation/templates | Optional resolvers add evidence-ranked candidates | PENDING | `docs/screenshots/live-audit/L046-similar-template-context.png` |
| L047 | Branch changes | Branch/base/merge-base/commits/files/diff context appears | PENDING | `docs/screenshots/live-audit/L047-git-branch-context.png` |
| L048 | Generated/ignored/secret candidate | Candidate is penalised/excluded with reason and no secret content | PENDING | `docs/screenshots/live-audit/L048-excluded-sensitive-candidate.png` |
| L049 | Open included/omitted/excluded views | Every file shows path, relation, score/priority and decision reason | PENDING | `docs/screenshots/live-audit/L049-context-file-views.png` |
| L050 | Pin omitted candidate | Candidate becomes pinned and lowest eligible automatic item is displaced | PENDING | `docs/screenshots/live-audit/L050-pin-omitted.png` |
| L051 | Exclude for current batch | Candidate disappears only from current batch | PENDING | `docs/screenshots/live-audit/L051-exclude-batch.png` |
| L052 | Exclude for session | Candidate stays excluded across batches in current session | PENDING | `docs/screenshots/live-audit/L052-exclude-session.png` |
| L053 | Exclude permanently | Candidate remains excluded after new session/reopen | PENDING | `docs/screenshots/live-audit/L053-exclude-permanent.png` |
| L054 | Include once | Excluded file is included in current batch without deleting exclusion | PENDING | `docs/screenshots/live-audit/L054-include-once.png` |
| L055 | Remove permanent exclusion | File becomes eligible after recalculation | PENDING | `docs/screenshots/live-audit/L055-remove-exclusion.png` |
| L056 | Recalculate during active calculation | Latest selection wins; stale result never replaces it | PENDING | `docs/screenshots/live-audit/L056-reactive-recalculation.png` |

## Attachment packing, preview and staging

| ID | Scenario | Expected result | Status | Full-window screenshot |
|---|---|---|---|---|
| L057 | Prepare simple batch | `00_REPO_CONTEXT.md` plus pinned copies are staged safely | PENDING | `docs/screenshots/live-audit/L057-prepare-simple-batch.png` |
| L058 | 20 physical attachments | Count reaches exactly 20 and never 21 | PENDING | `docs/screenshots/live-audit/L058-twenty-physical-attachments.png` |
| L059 | More than 20 repository files via bundles | Repository count exceeds attachment count; physical count stays ≤20 | PENDING | `docs/screenshots/live-audit/L059-bundled-repository-files.png` |
| L060 | Too many pinned separate files | Clear validation error; no invalid staging directory is produced | PENDING | `docs/screenshots/live-audit/L060-pinned-overflow.png` |
| L061 | Automatic overflow | Highest-ranked context is included; omitted set and reason are exact | PENDING | `docs/screenshots/live-audit/L061-automatic-overflow.png` |
| L062 | Duplicate basenames | Stable staged names are unique; originals remain unchanged | PENDING | `docs/screenshots/live-audit/L062-duplicate-basename-mapping.png` |
| L063 | Unsupported text extension | Safe `.txt` staged representation preserves original mapping | PENDING | `docs/screenshots/live-audit/L063-text-extension-conversion.png` |
| L064 | Unsaved editor document | Staged content and hash reflect current editor text | PENDING | `docs/screenshots/live-audit/L064-unsaved-document.png` |
| L065 | Preview top-level tab | Exact context text and Included/Omitted/Excluded file views remain readable | PASS | `docs/screenshots/live-audit/L151-preview-tab.png`, `L212-preview-wrapped-context-files.png` |
| L066 | Copy context only | Clipboard contains generated Markdown without source-body concatenation | PENDING | `docs/screenshots/live-audit/L066-copy-context-only.png` |
| L067 | Copy as text | Clipboard contains metadata, original paths and explicit full file boundaries | PENDING | `docs/screenshots/live-audit/L067-copy-combined-text.png` |
| L068 | Copy files | Clipboard exposes exact staged OS file list | PENDING | `docs/screenshots/live-audit/L068-copy-file-list.png` |
| L069 | Drag handle | Copy-only OS file-list drag contains context plus final staged attachments | PENDING | `docs/screenshots/live-audit/L069-drag-file-list.png` |
| L070 | Open staging folder | Explorer opens exact unique temp session directory | PENDING | `docs/screenshots/live-audit/L070-open-staging-folder.png` |
| L071 | Keep staged session | Keep marker is visible and cleanup skips session | PENDING | `docs/screenshots/live-audit/L071-keep-session.png` |
| L072 | Delete staged session | Confirmation removes only temp copies, not repository/history | PENDING | `docs/screenshots/live-audit/L072-delete-session.png` |
| L073 | Retention cleanup | Expired non-kept session disappears; kept/current sessions remain | PENDING | `docs/screenshots/live-audit/L073-retention-cleanup.png` |

## Guidelines and Return Instructions

| ID | Scenario | Expected result | Status | Full-window screenshot |
|---|---|---|---|---|
| L074 | Detect guideline sources | Root/scoped AGENTS, Copilot instructions and configured sources appear | PENDING | `docs/screenshots/live-audit/L074-guideline-sources.png` |
| L075 | Toggle/open guideline source | Effective preview updates; source opens in editor | PENDING | `docs/screenshots/live-audit/L075-guideline-toggle-open.png` |
| L076 | Global guideline edit/reset/import/export | Each operation persists and round-trips | PENDING | `docs/screenshots/live-audit/L076-global-guidelines.png` |
| L077 | Repository guideline explicit save | No repository write occurs before Save; requested file is written after Save | PENDING | `docs/screenshots/live-audit/L077-repository-guideline-save.png` |
| L078 | Create modular guideline structure | Confirmation creates only selected safe structure | PENDING | `docs/screenshots/live-audit/L078-create-guideline-structure.png` |
| L079 | Open Return Instructions editor | Mode default, project override, prompt addition and effective preview are distinct | PASS | `docs/screenshots/live-audit/L079-return-instructions-editor.png` |
| L080 | Edit project override/prompt addition | Effective instructions update and persist | PENDING | `docs/screenshots/live-audit/L080-return-instructions-inheritance.png` |
| L081 | Remove required return clause | Validation warning identifies missing identity/safety requirement | PENDING | `docs/screenshots/live-audit/L081-return-instructions-validation.png` |
| L082 | Reset Return Instructions | Selected mode returns to safe default while other modes remain intact | PENDING | `docs/screenshots/live-audit/L082-return-instructions-reset.png` |
| L083 | Batch Copy return text | Clipboard contains the effective return-format instructions only | PASS | `docs/screenshots/live-audit/L180-copy-return-clicked.png` |

## Batches, sessions and More page

| ID | Scenario | Expected result | Status | Full-window screenshot |
|---|---|---|---|---|
| L084 | Next batch | Prepared pack archives; active selection resets for next batch | PASS | `docs/screenshots/live-audit/L084-next-batch.png` |
| L085 | Restore historical batch | Historical repository paths become pinned again | PENDING | `docs/screenshots/live-audit/L085-restore-batch.png` |
| L086 | Forget historical batch | Only selected history entry is removed after confirmation | PENDING | `docs/screenshots/live-audit/L086-forget-batch.png` |
| L087 | New conversation session | Session ID changes; new batch starts at one; history remains inspectable | PASS | `docs/screenshots/live-audit/L168-new-session-confirmed.png`, `L169-session-selector.png` |
| L088 | Current-session prior batches | Generated prompt lists current-session batches and possible future 20-file batches | PENDING | `docs/screenshots/live-audit/L088-multibatch-protocol.png` |
| L089 | More → Batch history/session selector | Existing session with five batches can be selected and restored | PASS | `docs/screenshots/live-audit/L169-session-selector.png`, `L171-session-switched-old.png` |
| L090 | More → Context preview and back | Preview opens, Back/Batch returns without layout jump in the wide split layout | PASS | `docs/screenshots/live-audit/L141-context-preview-retained.png`, `L209-current.png`, `L210-batch-return.png`, `L090-more-context-navigation.png` |
| L091 | More → Guidelines and back | Correct page opens; Import controls never appear there | PASS | `docs/screenshots/live-audit/L091-more-guidelines-navigation.png` |
| L092 | More → Prompt Library and back | Editor opens with stable button placement and scroll state | PASS | `docs/screenshots/live-audit/L092-more-prompt-navigation.png` |
| L093 | More → Settings | Correct plugin settings page opens | PASS | `docs/screenshots/live-audit/L093-more-settings.png` |
| L094 | More quick actions repeatedly | Quick copy/history controls keep fixed vertical order and alignment | PASS | `docs/screenshots/live-audit/L213-more-quick-actions-current.png`, `L214-more-after-guidelines-return.png` |

## Patch ingestion and validation

| ID | Scenario | Expected result | Status | Full-window screenshot |
|---|---|---|---|---|
| L095 | Drop `.copilotpatch` on Import | Patch loads and validation starts off EDT | PENDING | `docs/screenshots/live-audit/L095-drop-copilotpatch.png` |
| L096 | Drop matching `.json` | Schema sniff accepts valid patch JSON | PENDING | `docs/screenshots/live-audit/L096-drop-valid-json.png` |
| L097 | Drop ordinary `.json` | Non-patch JSON is rejected without replacing current review | PENDING | `docs/screenshots/live-audit/L097-reject-ordinary-json.png` |
| L098 | Open ZIP patch | `changes.json` and safe snippet files load | PASS | `docs/screenshots/live-audit/L135-structured-replace-file.png` |
| L099 | Paste patch JSON | Clipboard content loads; Paste and Validate actions remain explicit/readable | PENDING | `docs/screenshots/live-audit/L099-paste-json.png` |
| L100 | Drop patch on outbound page | Schema sniff routes valid patch to Import review automatically | PENDING | `docs/screenshots/live-audit/L100-outbound-patch-routing.png` |
| L101 | Traversal/absolute/different-drive path | Patch is rejected before unsafe file access | PENDING | `docs/screenshots/live-audit/L101-reject-unsafe-path.png` |
| L102 | Symlink escape | Real-path containment rejects escaped target | PENDING | `docs/screenshots/live-audit/L102-reject-symlink-escape.png` |
| L103 | Wrong repository/session | Repository mismatch blocks; session mismatch is handled exactly as contract states | PENDING | `docs/screenshots/live-audit/L103-repository-session-mismatch.png` |
| L104 | Invalid or multi-function snippet | Syntax/extra statements are reported; operation is not selectable | PENDING | `docs/screenshots/live-audit/L104-invalid-snippet.png` |
| L105 | Missing/ambiguous function | Exact qualified identity error appears; no automatic apply | PENDING | `docs/screenshots/live-audit/L105-missing-ambiguous-function.png` |
| L106 | Overlapping operations | Conflicting edits in same structural range are rejected | PENDING | `docs/screenshots/live-audit/L106-overlapping-operations.png` |
| L107 | Validation superseded by new patch | Older asynchronous result never overwrites the newer review | PENDING | `docs/screenshots/live-audit/L107-stale-validation.png` |

## Native diff, conflict choices and apply

| ID | Scenario | Expected result | Status | Full-window screenshot |
|---|---|---|---|---|
| L108 | Safe replace-function diff | Native two-way diff shows complete old/new function and correct identity | PENDING | `docs/screenshots/live-audit/L108-replace-function-diff.png` |
| L109 | Local hash conflict | Native three-way view shows BASE, CURRENT and PROPOSED | PENDING | `docs/screenshots/live-audit/L109-three-way-conflict-diff.png` |
| L110 | Conflict Keep current | Operation is deselected/skipped without modifying file | PENDING | `docs/screenshots/live-audit/L110-conflict-keep-current.png` |
| L111 | Conflict Use Copilot/Force Replace | Explicit decision selects conflict and remains visible before apply | PENDING | `docs/screenshots/live-audit/L111-conflict-use-copilot.png` |
| L112 | Add top-level function diff | Entire new function appears as addition at validated anchor | PENDING | `docs/screenshots/live-audit/L112-add-top-level-function.png` |
| L113 | Add class/nested/decorated/async function | Parent, anchor and function kind validate unambiguously | PENDING | `docs/screenshots/live-audit/L113-add-complex-function.png` |
| L114 | Add-file diff | Complete new file appears as added and existing path cannot be overwritten | PENDING | `docs/screenshots/live-audit/L114-add-file-diff.png` |
| L115 | Delete-file diff | Complete existing file appears as removed with exported-hash protection | PENDING | `docs/screenshots/live-audit/L115-delete-file-diff.png` |
| L116 | Switch individual diffs | Diff updates to chosen operation without losing selections/decisions | PENDING | `docs/screenshots/live-audit/L116-switch-diffs.png` |
| L117 | Combined diff | Every validated selected operation appears once in stable order | PENDING | `docs/screenshots/live-audit/L117-combined-diff.png` |
| L118 | Select all / Select safe / Deselect conflicts / Clear | Each bulk action affects exactly eligible operations | PENDING | `docs/screenshots/live-audit/L118-bulk-selection.png` |
| L119 | Apply confirmation | Dialog summarises selected safe/conflict/file operations before write | PENDING | `docs/screenshots/live-audit/L119-apply-confirmation.png` |
| L120 | Revalidation just before apply | Changed local state refreshes review and blocks stale apply | PENDING | `docs/screenshots/live-audit/L120-preapply-revalidation.png` |
| L121 | Apply multiple functions/files | Only selected operations across files run in one Undo command | PENDING | `docs/screenshots/live-audit/L121-apply-multiple-operations.png` |
| L122 | Preserve unrelated code/encoding | Neighbour functions, surrounding text and encoding remain unchanged | PENDING | `docs/screenshots/live-audit/L122-preserve-unrelated-code.png` |
| L123 | Undo apply | One Undo restores replacements, additions and deletions | PENDING | `docs/screenshots/live-audit/L123-undo-apply.png` |
| L124 | Optional reformat/optimise imports | Only enabled post-processing runs and result is reported honestly | PENDING | `docs/screenshots/live-audit/L124-post-processing.png` |
| L125 | Post-apply validation/report | Applied/skipped files and actually run validations are listed | PENDING | `docs/screenshots/live-audit/L125-post-apply-report.png` |

## External Microsoft 365 Copilot handoff

| ID | Scenario | Expected result | Status | Full-window screenshot |
|---|---|---|---|---|
| L126 | Paste copied staged file list into Microsoft 365 Copilot | Exact attachment names appear; chat is not sent automatically | PENDING | `docs/screenshots/live-audit/L126-m365-copy-files.png` |
| L127 | Drag staged file list to Microsoft 365 Copilot | Observed OS/browser result is recorded honestly | PENDING | `docs/screenshots/live-audit/L127-m365-drag-files.png` |
| L128 | Open-folder fallback then drag | Explorer handoff provides exact prepared attachments | PENDING | `docs/screenshots/live-audit/L128-m365-folder-fallback.png` |
| L129 | Inspect prepared prompt in Copilot | First-response question, future-batch notice and Return Instructions are present | PENDING | `docs/screenshots/live-audit/L129-m365-prepared-prompt.png` |
| L130 | Return a code-tool patch file without sending production request | Downloaded sample routes back to Import and validates | PENDING | `docs/screenshots/live-audit/L130-m365-roundtrip-sample.png` |

## ZIP discovery and whole-file import delta

| ID | Scenario | Expected result | Status | Full-window screenshot |
|---|---|---|---|---|
| L131 | Add source ZIP to outbound batch | Safe text entries become automatic candidates; secret-like entries are visibly rejected | PASS | `docs/screenshots/live-audit/L131-outbound-zip-source.png` |
| L132 | Prepare batch containing ZIP entries | Preparation completes off EDT and enables real file drag/copy/folder actions | PASS | `docs/screenshots/live-audit/L132-outbound-zip-prepared.png` |
| L133 | Review source-only ZIP fallback | Exact/unique-basename add and replace proposals validate and all start unselected | PASS | `docs/screenshots/live-audit/L133-source-only-zip-review.png` |
| L134 | Open source-only whole-file diff | Native PyCharm Current/Copilot Proposed diff opens before any Apply | PASS | `docs/screenshots/live-audit/L134-source-only-native-diff.png` |
| L135 | Open strict structured whole-file ZIP | Root `changes.json`, BOM/CRLF snippet, session, exact-file hash and `replace_file` validate | PASS | `docs/screenshots/live-audit/L135-structured-replace-file.png` |

## Final Batch dropdown and kickoff-prompt delta

| ID | Scenario | Expected result | Status | Full-window screenshot |
|---|---|---|---|---|
| L136 | Open Pinned dropdown with overflow | One full-width list shows every pinned path and a visible vertical scrollbar | PASS | `docs/screenshots/live-audit/L163-pinned-dropdown.png` |
| L137 | Open Automatic dropdown with more than 20 repository files | All automatic files remain inspectable with reason tags while attachment count stays at or below 20 | PASS | `docs/screenshots/live-audit/L166-automatic-dropdown-open.png` |
| L138 | Prepare dropdown batch | Packing summary reports physical attachments, represented files and category bundle counts | PASS | `docs/screenshots/live-audit/L148-after-prepare-correct-coordinates.png` |
| L139 | Inspect prompt below prepared drag zone | Rendered prompt includes context index, skill, session, batch, original paths, Return Instructions and future-batch wait text | PASS | `docs/screenshots/live-audit/L148-after-prepare-correct-coordinates.png` |
| L140 | Copy editable kickoff prompt | Clipboard exactly matches the visible rendered prompt and no file contents are mixed in | PASS | `docs/screenshots/live-audit/L179-copy-prompt-clicked.png` |
| L141 | Open More → Context preview after Batch redesign | Complete generated context, source map and Included/Omitted/Excluded views remain available | PASS | `docs/screenshots/live-audit/L141-context-preview-retained.png` |
| L142 | Prepare a second batch in the same session | Prompt shows incremented batch number and warns that more batches may follow | PASS | `docs/screenshots/live-audit/L142-second-batch-prompt.png` |
| L143 | Review structured import and delete | Native diff, Apply confirmation and wrapped explicit delete warning appear before Apply | PASS | `docs/screenshots/live-audit/L154-structured-import-review.png`, `L155-native-diff.png`, `L156-import-confirmation.png`, `L160-delete-confirmation-2.png`, `L176-delete-warning-wrapped.png` |
