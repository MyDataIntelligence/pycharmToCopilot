# Changelog

## 1.0.0 — unreleased

- Added context-aware Prompt Library entries with editable, versioned Context Policies.
- Split repository-file analysis limits from the 20 physical-attachment limit and added deterministic automatic-context bundling.
- Added conversation sessions, previous-batch modes, scoped exclusions and Include once.
- Added editable inherited Return Instructions for patch, code-tool files, text and direct-edit modes.
- Added branch-to-PR and repository-to-user-story prompts plus GitHub Copilot creator prompts.
- Added native per-operation and three-way conflict diff flows.
- Extended patch import with complete new-file and delete-file operations alongside function replacement/addition.
- Added safe ZIP discovery for outbound batches: archive-relative paths are preserved, unsafe, binary and secret entries are filtered, and unsent archive entries flow across subsequent batches without displacing pinned files.
- Added an inbound plain-code ZIP fallback with exact-path then unique-basename mapping, whole-file diffs, hash revalidation, explicit selection and Undo-safe add/replace operations. Structured ZIPs with root `changes.json` remain the required preferred Copilot output.
- Added clone-to-install `install.ps1` workflow with atomic update and ZIP fallback.
- Expanded automated and live PyCharm release test plans. Live results remain pending until executed.
- Kept the More navigation in one stable scrollable row instead of letting Swing reorder wrapped tab rows.
- Fixed independent caller, callee, nearby-test, depth and per-resolver file-limit handling in Context Policies.
- Hardened Prompt Library editing/import validation and restored prompt-specific policy defaults correctly.
- Allowed `install.ps1 -BuildOnly` while PyCharm is open; live installation still requires PyCharm to be closed.
- Added explicit repository-guideline editing and saving, invalid-pin recovery, synchronized batch-history details and guarded history deletion.
- Hardened patch import against duplicate targets, unsafe ZIP aliases, stale hashes and mixed file/function conflicts; expanded real Undo and multi-file coverage.
- Stabilized Prompt Library refreshes, duplicate-name selection, built-in prompt protection and transactional Context Policy cancellation.
