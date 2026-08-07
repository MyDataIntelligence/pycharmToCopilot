# Copilot Context Bridge test plan

This checklist is the release gate for the simplified main workflow and the complete plugin.

## Automated gates

- [x] Kotlin compilation and instrumentation succeed on JDK 21.
- [x] Ktlint/static checks pass.
- [x] All 37 unit and PSI/platform tests pass.
- [x] Plugin ZIP builds successfully.
- [x] Plugin Verifier reports compatible for PyCharm 2025.1, 2025.2 Professional/Community and 2026.2.
- [x] Installer build and atomic installation succeed.

## Main outbound workflow

- [x] A single Project View file exposes all Copilot context actions.
- [x] Ctrl/Shift multiselect preserves every selected file.
- [x] Files from different folders remain pinned together.
- [x] The main workflow presents Files -> Prompt skill -> Prepare for Copilot in order.
- [x] The capacity rail includes `00_REPO_CONTEXT.md` and never exceeds the configured limit.
- [x] Prepare batch creates safe copies and does not change repository files.
- [x] Drag transfer and clipboard file transfer expose a real Java file list.
- [x] Complete-pack text contains metadata, original paths, hashes and exact text content.
- [x] Copy context-only and return instructions remain available under More -> Context preview without cluttering the main flow.
- [x] Their generated instruction sources are adjustable through Settings, guidelines and prompt skills.
- [x] Prepared files move to history and the next batch avoids earlier files by default.

## Inbound workflow

- [x] `.copilotpatch`, matching JSON and ZIP imports validate paths and schema.
- [x] Replace and add-function operations work across one or multiple Python files.
- [x] Decorators, async functions, methods and nested functions retain correct identity.
- [x] Hash conflicts require explicit force; safe changes remain individually selectable.
- [x] Applying selected replacements is one undoable write command and preserves unrelated code.

## Security and resilience

- [x] Traversal, absolute paths and repository escapes are rejected.
- [x] Likely secret files require explicit confirmation and contents never enter logs.
- [x] Ignored/generated paths and oversized scan candidates behave as documented.
- [x] Session cleanup respects retention and keep markers.

## Live IDE checks

- [x] Plugin loads in PyCharm 2026.2.0.1.
- [x] Tool window remains usable at roughly one-third screen width.
- [x] Single-file and multi-file context menus work in the real Project View.
- [x] Batch, Import, More and Prompt Skills render without clipping; Import has a resizable 50/50 list/diff split.
- [x] A real batch stages `00_REPO_CONTEXT.md`, copies it as a Windows file list and opens the staging folder in Explorer.
- [x] Pasted patch JSON validates in a background task without blocking or touching the EDT.
- [x] A safe Python replacement applies in PyCharm 2026.2.0.1, preserves its neighbouring function and saves correctly.
- [x] One PyCharm Undo action restores the complete original function.
- [x] Microsoft 365 Copilot accepts the staged context file through the real clipboard file-list flavor; the test draft was not sent.
- [x] Direct Swing/browser drag remains documented as platform-dependent, with clipboard and staging-folder fallbacks.
