# `.copilotpatch` format v1

The primary format is UTF-8 JSON. The importer also accepts `.json` only after schema sniffing, and ZIP packages with `changes.json` at archive root. Required root fields are `formatVersion: 1`, `repositoryId`, `sessionId`, and one or more entries in `replacements`. `summary` is strongly recommended.

Paths must already be canonical repository-relative `.py` paths. Hashes use exactly `sha256:` plus 64 lowercase hexadecimal characters. A patch cannot contain duplicate target identities, and a whole-file operation cannot be combined with another operation for the same file.

## Operations

### `replace_function`

Requires `path`, `qualifiedName`, exported `originalHash`, and complete function source in `replacement` (or ZIP `replacementFile`). The source must contain exactly one complete function including decorators, signature, type hints, docstring and body.

### `add_function`

Requires `path`, `qualifiedName`, `parentQualifiedName`, and complete function source. Empty `parentQualifiedName` means module level. `insertAfterQualifiedName` is optional. Async/decorator-kind changes require explicit allow flags where applicable.

### `add_file`

Requires a new repository-relative `path` and complete file content in `replacement` or `replacementFile`. `qualifiedName` may be omitted and is represented internally as `<file>`. The target must not already exist. Parent traversal, absolute paths and repository/symlink escape are rejected.

### `replace_file`

Requires an existing repository-relative `path`, its exported exact-file `originalHash`, and complete file content in `replacement` or `replacementFile`. It is intended for the `CODE_TOOL_FILES` return mode. The importer shows a whole-file diff, starts source-only fallback ZIPs unselected, rechecks the hash before Apply, and never silently overwrites local changes.

### `delete_file`

Requires an existing repository-relative `path` and exported `originalHash`. It must not contain replacement content. The hash protects against deletion after local changes. Deletion is individually previewed and selected; it is never inferred from an omitted response.

## Example

```json
{
  "formatVersion": 1,
  "repositoryId": "fabric-deployment",
  "sessionId": "20260806_202500_ab12cd",
  "summary": {
    "overview": "Improve submission validation and add a focused helper.",
    "functions": [
      {
        "path": "scripts/functions/livy.py",
        "qualifiedName": "submit_batch",
        "change": "replace",
        "reason": "Validate requests before sending"
      }
    ],
    "testsPerformed": ["Not run: execution environment unavailable"],
    "risks": [],
    "limitations": []
  },
  "replacements": [
    {
      "operation": "replace_function",
      "path": "scripts/functions/livy.py",
      "qualifiedName": "submit_batch",
      "originalHash": "sha256:812bf...",
      "replacement": "def submit_batch(request: LivyBatchRequest) -> str:\n    \"\"\"Submit a Livy batch.\"\"\"\n    ...\n"
    },
    {
      "operation": "add_file",
      "path": "tests/test_new_helper.py",
      "replacement": "def test_new_helper() -> None:\n    ...\n"
    }
  ]
}
```

## Summary contract

`summary` contains `overview`, `functions[{path,qualifiedName,change,reason}]`, `testsPerformed`, `risks`, and `limitations`. Use “Not run: <reason>” instead of claiming a test or validation that was not actually executed.

## ZIP form

ZIP uses:

```text
copilot-result.zip
├── changes.json
├── CHANGE_SUMMARY.md          (recommended)
└── replacements/
    ├── 001_submit_batch.py
    └── 002_test_new_helper.py
```

`replacementFile` paths are canonical archive-relative paths and must stay within `replacements/`. Limits are 20 MB compressed archive, 50 MB expanded, 10 MB per entry, 100 entries and 50 operations. Absolute paths, traversal, symlink-like escapes, duplicate entries and oversized expansion are rejected.

`changes.json` at the ZIP root is mandatory for Copilot-generated ZIP output and always selects the strict structured importer. A source-only ZIP without that manifest is accepted only as a manual fallback: the plugin proposes exact repository-relative matches first, then a single unique basename match. Ambiguous names are rejected, all rows start unselected, and every add or whole-file replacement requires diff review and explicit Apply confirmation.

## Validation and preview states

Function identity uses repository-relative path, qualified parent/function chain, function kind and exported hash—not line numbers. States are `MATCH`, `NEW`, `CHANGED`, `MISSING`, `AMBIGUOUS`, and `INVALID`.

Native PyCharm two-way diff is used for safe replacements/additions/deletions. When exported base text exists and local text changed, the importer can display three sides: `BASE (exported)`, `CURRENT (local)`, `PROPOSED (Copilot)`. Apply revalidates immediately before the write command. Only explicitly selected safe or explicitly resolved conflict operations run, and Undo restores the transaction.

The machine-readable schema is [schema/copilotpatch-v1.schema.json](schema/copilotpatch-v1.schema.json). The schema and parser must remain in sync; release tests reject drift.
