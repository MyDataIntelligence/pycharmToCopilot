# Context and attachment format

Every prepared batch contains one generated `00_REPO_CONTEXT.md`. It is always a physical attachment and counts toward `ContextPolicy.maxAttachments`.

## Limits and packing

`maxRepositoryFiles` and `maxAttachments` are separate:

- repository files are the original project files selected by the policy;
- attachments are the actual files staged for Copilot;
- pinned repository files stay separate unless a policy explicitly changes that rule;
- automatic repository files may be bundled by `bundleGroup` when `bundleAutomaticContext` is enabled;
- bundles obey deterministic file-count, byte, character and estimated-token limits and split only between complete source files;
- generated branch context may become a generated attachment;
- the physical plan must never exceed `maxAttachments` (2–20, default 20).

Example: `00_REPO_CONTEXT.md`, six pinned source files, and four automatic bundles represent 37 repository files but only 11 physical attachments.

Automatic bundle names are deterministic, for example `01_AUTO_TESTS_01.md`. Each file section records repository-relative path, type, hash, selection reason, policy rule, relationship and complete content. Text files with extensions unsupported by Microsoft 365 Copilot may be staged as a safe `.txt` representation. Original repository files are never renamed.

## Mandatory sections

The generated document contains:

1. the editable Microsoft 365 Copilot first-response protocol;
2. multi-batch protocol, including that another 20 files or more batches may follow;
3. selected Prompt Library entry, target, return mode and active Context Policy summary;
4. repository identity without an absolute machine path by default;
5. complete filtered repository tree and ignored-path summary;
6. physical attachment plan and repository-file count;
7. staged/bundled name → original repository-relative path mapping;
8. included, omitted and excluded candidates with reasons and evidence;
9. plain-text dependency relationships and optional Mermaid graph;
10. Python symbol index and deterministic function hashes;
11. previous batches according to `previousBatchMode`;
12. effective coding guidelines and source precedence;
13. effective Return Instructions for the selected return mode.

The document explicitly states that omitted/excluded file contents were not supplied and must not be invented.

## Kickoff prompt

The prepared Batch view renders a separate short prompt directly below the file drag zone. It is not a replacement for this context document. It tells Copilot to read `00_REPO_CONTEXT.md`, identifies the selected Prompt Skill and session/batch, preserves original repository paths as identity, references the effective Return Instructions, and warns that more batches may follow. **Copy prompt** copies only this message; **Copy as text** places it before the complete combined context. The global template and optional project override require `{sessionId}`, `{batchNumber}`, `{promptSkill}`, and the literal `00_REPO_CONTEXT.md`.

## Function hash

For a replaceable Python function:

1. take complete Python PSI text, including decorators, `def`/`async def`, signature, docstring and body;
2. replace CRLF and CR line endings with LF;
3. preserve every other character exactly;
4. encode UTF-8;
5. compute SHA-256;
6. prefix the lowercase hexadecimal digest with `sha256:`.

The staging manifest stores the same hash and the exported base function text. Import can therefore distinguish the exported base, current local function and Copilot proposal.

## Sessions and exclusions

A conversation session has a stable session ID and monotonically increasing batch numbers. `SAME_SESSION_ONLY`, `NEVER`, and `ALWAYS` determine which prior batches influence collection. A new conversation session keeps project preferences but does not silently mix prior-session attachments into the new prompt.

Exclusions have batch, session or permanent/project scope. **Include once** adds a temporary override for the current batch; it does not delete the persisted exclusion.

## Return Instructions inheritance

Effective Return Instructions are resolved as:

1. default text for the selected return mode;
2. project override for that mode, when configured;
3. prompt-specific addition.

The four modes are `COPILOT_PATCH_FILE`, `CODE_TOOL_FILES`, `TEXT_ONLY`, and `DIRECT_REPOSITORY_EDIT`. Validation checks that the chosen contract retains required paths, hashes, function identity and complete-source requirements where applicable.
