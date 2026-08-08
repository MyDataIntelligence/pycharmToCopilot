# JetBrains Marketplace listing

## Name

Copilot Context Bridge

## Vendor

MyDataIntelligence

## Short description

Prepare repository-aware Copilot context and safely review returned code in PyCharm.

## Overview

Copilot Context Bridge creates deterministic context packs for Microsoft 365 Copilot without renaming or modifying repository files. Pin files from different folders, let context policies discover relevant tests, dependencies, configuration and guidelines, then stage no more than the configured physical attachment limit.

Returned changes can be reviewed as structured `.copilotpatch`/JSON/ZIP input or as a source-only ZIP. Native diffs, repository-relative path validation, function hashes, conflict handling and Undo keep the import explicit and reviewable.

### Main workflows

- Select files, folders, external repositories or ZIP archives from the Batch tab.
- Choose an editable prompt skill and context policy.
- Preview the complete generated text and every included, omitted or excluded file.
- Prepare, drag or copy a safe multi-batch context pack.
- Import complete functions and file operations through native diff review.
- Apply only explicitly selected changes and keep unrelated code unchanged.

### Security and limitations

- Likely secret files and credential patterns are blocked or require confirmation.
- Paths are normalized and must remain inside an approved repository root.
- Static analysis cannot fully resolve dynamic imports.
- Direct Swing-to-browser dragging depends on the operating system and browser; opening the staged folder is always available.
- Microsoft 365 Copilot must follow the generated return contract for reliable structured import.

## Getting started

1. Open a project in PyCharm.
2. Open **Copilot Context Bridge** from the right tool-window bar.
3. Add repository files, a folder or a ZIP in **Batch**.
4. Choose a prompt skill and select **Prepare for Copilot**.
5. Drag or copy the attachments and paste the generated kickoff prompt into Copilot.
6. Drop the returned `.copilotpatch`, JSON or ZIP onto **Import** and review every diff before Apply.

## Suggested Marketplace tags

- AI Assistant
- Code tools
- Python

## Media upload notes

Use the full-window screenshots from `docs/screenshots/live-audit/`. Export Marketplace copies at a consistent 1280 x 800 (16:10) size and remove project-specific or personal information before upload.
