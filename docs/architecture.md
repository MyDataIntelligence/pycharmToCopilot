# Architecture

## Direction and boundaries

```text
Project View / Prompt Library
  → persistent selection + conversation session
  → ContextPolicy + resolver registry
  → immutable candidates and relations
  → repository-file allocation
  → physical attachment packing (≤ 20)
  → preview
  → safe temp staging / clipboard / drag

Copilot patch file / JSON / ZIP
  → schema sniff + parser
  → safe paths + repository/session validation
  → Python PSI/file operation validation
  → native two-way/three-way diff + per-operation choice
  → revalidation
  → one Undoable write command
```

UI code orchestrates services but does not own analysis or patch semantics. Expensive repository/PSI work uses background tasks and read actions; write operations use IntelliJ write-command infrastructure. Generation IDs prevent an older asynchronous calculation or patch validation from replacing newer UI state.

## Persistent state

Application state stores global guidelines, Prompt Library entries, Context Policies, Return Instruction defaults and editable global patterns. Project state stores repository settings, pinned paths/discovery roots, exclusions, prompt choice, project Return Instruction overrides, conversation session and batch history.

A session ID identifies one intended Copilot conversation. Batch history is immutable evidence of prepared packs. `previousBatchMode` controls whether previous prepared paths influence the next analysis. A new session does not delete history but creates a fresh current-session boundary.

## Context policy and resolvers

`ContextPolicyState` owns target, return mode, previous-batch mode, file/attachment limits, bundling and independent rules. Each rule names a resolver by string ID and carries priority, required flag, depth, file limit, bundle group, keep-separate flag and parameters. A projection maps policy rules to analysis options. Resolver results are normalised into immutable candidates so ranking and packing are reusable and testable.

The current resolver families are Python dependency/test, textual/structured configuration, guidelines/instructions, repository similarity/templates and Git branch changes. Adding a prompt does not require adding prompt-name conditionals.

## Allocation and physical packing

Repository-file allocation and attachment packing are separate services. The ranker chooses at most `maxRepositoryFiles`; the packer maps those sources into at most `maxAttachments`, including `00_REPO_CONTEXT.md`. Pinned originals remain separate by default. Automatic candidates may be bundled deterministically by policy group. `AttachmentPlan` is the single source of truth for preview, staging, clipboard/drag, manifest and context mapping.

Staging is the only outbound filesystem writer and writes below the dedicated system-temp root. It stores generated context, separate pinned copies, automatic bundles/generated Git context and `.session/context-session.json` plus exported base-function data. Original repository files are read-only.

## Guidelines and Return Instructions

Guideline detection and merge are independent from rendering. Source files are never rewritten without an explicit save/create action. Return Instructions resolve mode default → project override → prompt addition. The validator protects required schema/path/hash/complete-source clauses from accidental removal.

## Inbound import

Inbound services are split into schema sniffing, JSON/ZIP parsing, path/session validation, Python function location/snippet parsing, file operation validation, native diff creation and application. Supported operations are function replace/add and file add/delete. Unsafe, missing, ambiguous, overlapping or stale states do not reach the write service.

Exported base function/file hashes make optimistic concurrency explicit. A conflict diff can show exported base, current local and proposed source. Selection and conflict decisions live in the review model, not in generated diff text. Apply performs a final validation pass immediately before one Undo command (or configured per-operation commands).

Primary packages are `actions`, `analysis`, `context`, `guidelines`, `patch`, `security`, `settings`, `staging`, `state`, and `ui`.

## Platform compatibility

The build uses JDK 21 and IntelliJ Platform Gradle Plugin 2.18.1. It currently compiles against PyCharm Community 2025.2.6.1, declares IDE builds 251–262, and configures Plugin Verifier for Community/Professional 2025.x and unified PyCharm 2026.2.0.1. Declared compatibility is not runtime proof; the 2026.2 full live matrix remains the release gate.
