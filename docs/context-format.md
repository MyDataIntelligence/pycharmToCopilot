# Context format

`00_REPO_CONTEXT.md` is one upload file and contains the mandatory Dutch first-response question, open-ended multi-batch protocol, selected prompt skill, complete filtered repository tree, staged-to-original path table, dependency map, Python symbols and hashes, omitted candidates, effective guidelines and return-file instructions.

Absolute machine paths are excluded by default. Mermaid node IDs are deterministic hashes; a plain-text relation list is always included.

Function hash algorithm: take the complete Python PSI function text including decorators, signature, docstring and body; normalize CRLF and CR to LF; preserve every other character; encode UTF-8; compute SHA-256; prefix with `sha256:`.
