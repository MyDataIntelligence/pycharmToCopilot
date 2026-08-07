# Security model

Repository-relative paths are normalized and resolved against the real repository root. Absolute paths, traversal and symlink escapes fail closed. ZIP entry paths and expansion sizes are separately constrained.

Default filename rules block environment files, keys, certificates and credential/secret names. Content detection recognizes private-key headers, bearer/GitHub/AWS tokens, Azure SAS signatures and secret-like assignments. Findings expose rule and line only. Automatic candidates are excluded; pinned files require per-staging confirmation.

The staging service creates a unique directory, never renames originals and copies current unsaved editor text when applicable. Retention deletion is constrained to direct children of the dedicated temp root and skips kept sessions.
