# Changelog

## 1.0.0 — unreleased

- Added context-aware Prompt Library entries with editable, versioned Context Policies.
- Split repository-file analysis limits from the 20 physical-attachment limit and added deterministic automatic-context bundling.
- Added conversation sessions, previous-batch modes, scoped exclusions and Include once.
- Added editable inherited Return Instructions for patch, code-tool files, text and direct-edit modes.
- Added branch-to-PR and repository-to-user-story prompts plus GitHub Copilot creator prompts.
- Added native per-operation and three-way conflict diff flows.
- Extended patch import with complete new-file and delete-file operations alongside function replacement/addition.
- Added clone-to-install `install.ps1` workflow with atomic update and ZIP fallback.
- Expanded automated and live PyCharm release test plans. Live results remain pending until executed.
- Kept the More navigation in one stable scrollable row instead of letting Swing reorder wrapped tab rows.
- Fixed independent caller, callee, nearby-test, depth and per-resolver file-limit handling in Context Policies.
- Hardened Prompt Library editing/import validation and restored prompt-specific policy defaults correctly.
- Allowed `install.ps1 -BuildOnly` while PyCharm is open; live installation still requires PyCharm to be closed.
