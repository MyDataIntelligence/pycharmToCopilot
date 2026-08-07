# Architecture

The tool window is deliberately thin. Project state owns pinned paths, discovery roots and batch history. Analysis services create immutable candidates, relations and symbol indexes in background read actions. The context renderer has no filesystem side effects. Staging is the only outbound writer and writes exclusively below the system temp directory.

Inbound processing is split into parsing, path/session validation, PSI location, snippet parsing, preview and write-command application. Unsafe and ambiguous states never reach the write service. All selected changes share one Undo command by default.

Primary packages are `actions`, `analysis`, `context`, `guidelines`, `patch`, `security`, `settings`, `staging`, `state`, and `ui`.
