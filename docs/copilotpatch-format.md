# `.copilotpatch` format v1

The primary format is JSON. Required root fields are `formatVersion: 1`, `repositoryId`, `sessionId`, `summary`, and one or more `replacements`.

`replace_function` requires `path`, `qualifiedName`, `originalHash`, and `replacement` (or ZIP `replacementFile`). `add_function` omits the hash and requires `parentQualifiedName`; an empty parent means module level. `insertAfterQualifiedName` is optional. Async or classmethod/staticmethod changes require explicit allow flags.

The summary template contains `overview`, `functions[{path,qualifiedName,change,reason}]`, `testsPerformed`, `risks`, and `limitations`. “Not run” must be used instead of claiming tests that were not executed.

ZIP uses `changes.json` at root and snippets below `replacements/`; `CHANGE_SUMMARY.md` is recommended. Limits: 20 MB archive, 50 MB expanded, 10 MB per entry, 100 entries and 50 operations. Traversal and absolute ZIP entries are rejected.

The machine-readable schema is in `docs/schema/copilotpatch-v1.schema.json`.
