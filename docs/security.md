# Security model

## Outbound context

Default filename rules block `.env*`, keys, certificates, credential/secret names and common service-account files. Content scanning recognises private-key headers, bearer/GitHub/AWS tokens, Azure SAS signatures, connection strings and secret-like assignments. Findings expose the rule and line—not the secret value. Automatic candidates are excluded. Suspicious pinned files require explicit confirmation for the current staging action.

Ignored directories, virtual environments, interpreter libraries, build output and large files are not recursively parsed. The default textual scan cap is 2 MB per file. Omitted and excluded files remain named as unavailable; their contents are never implied.

The Context Policy may collect many repository files, but only a bounded physical attachment plan is staged. Automatic bundles contain source provenance and hashes. Unsupported text extensions may be copied to `.txt`; binary sniffing prevents accidental binary-as-text export.

## Paths and staging

All repository paths are normalised and resolved against the real repository root. Absolute paths, `..` traversal, different-drive paths and symlink escapes fail closed. The staging service creates a unique directory below the dedicated system-temp root, never renames or modifies originals, and copies current unsaved editor text where supported.

Outbound ZIP discovery is read with compressed, expanded, per-entry and entry-count limits. Directory entries count toward the archive limit. Canonical archive-relative names are required; traversal, case-insensitive duplicates and Unix symlink/device metadata are rejected. Only valid UTF-8 text that passes ignore and secret scanning is cached, beneath a hash-addressed temporary root cleaned after the retention window. The top-level archive folders are preserved.

The session manifest records repository ID, session/batch, plugin version, attachment/source mapping, hashes, function hashes, reasons, relations, policy and guideline sources. It is kept under `.session` and is not an upload attachment. Retention cleanup is constrained to direct session children, skips sessions marked keep, and defaults to seven days.

Batch/session/permanent exclusions are distinct persisted decisions. **Include once** is a narrow current-batch override, not a secret-scanner bypass; suspicious content still requires confirmation.

## Inbound patches

JSON is accepted only when schema sniffing recognises a Copilot patch. ZIP entry names and expansion sizes have independent limits. Every operation revalidates repository-relative path, real path/symlink containment, project membership, target existence/non-existence, file type, repository/session identity and hashes.

Inbound ZIP dispatch is deterministic: root `changes.json` selects strict structured parsing and can never fall back. Without it, plain code files use exact paths first; only one unique repository basename may be proposed when no exact path exists. Duplicate basename matches and multiple entries mapping to one target fail closed. Existing-file hashes are captured before review and checked again immediately before Apply.

Function snippets are parsed with Python PSI and must contain exactly one complete function with a matching identity. `add_file` cannot overwrite an existing path. `delete_file` cannot proceed when its exported hash differs unless the UI provides and the user explicitly chooses the permitted conflict action. Overlapping operations are rejected.

Apply runs under PyCharm write-command infrastructure and is Undoable. No operation is silently selected after validation changes; the importer revalidates immediately before apply. Logs never include source file contents, patch replacement bodies, clipboard contents or detected secret values.

## Trust boundary

Microsoft 365 Copilot and GitHub Copilot output is untrusted input. Prompt instructions reduce malformed output but do not replace local validation. The plugin does not log in to Copilot, send chats automatically, claim a browser accepted a drag, execute arbitrary patch-provided commands, commit, or push.
