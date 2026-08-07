# Context policy and dependency analysis

Context collection is data-driven:

```text
Prompt Library entry
  → ContextPolicy
  → enabled resolver rules
  → deterministic candidate ranking
  → repository-file allocation
  → attachment packing
  → prepared Copilot context
```

No prompt-specific `if` chain is required. Resolver IDs are persisted strings, so additional resolvers can be registered without changing the policy schema.

## Policy model

A policy contains `id`, `version`, `target`, `returnMode`, `previousBatchMode`, `maxRepositoryFiles`, `maxAttachments`, `bundleAutomaticContext`, and `rules[]`. Each rule contains `id`, `enabled`, `resolver`, `priority`, `required`, `maxDepth`, `maxFiles`, `bundleGroup`, `keepSeparate`, and string parameters.

The UI under **More → Prompt Library → Context Policy** supports enable/disable, priority and limits, reset, and duplication through prompt duplication. Policy changes are persisted with their Prompt Library entry.

## Resolvers

Default policy rules describe:

- explicit pinned files;
- Python matching tests, nearby tests and fixtures;
- direct imports, callees, callers and transitive imports;
- referenced JSON/YAML/TOML/SQL/CSV/configuration;
- root/scoped `AGENTS.md`, Copilot instructions and project guidelines;
- similar implementations and templates;
- current Git branch changes.

Python PSI/reference resolution produces `CONFIRMED` local import and symbol relations where possible. Naming/path conventions produce `INFERRED` matching tests, package initializers and project configuration. Structured/text scanning recognises GitHub Actions, Azure DevOps, Fabric pipeline/notebook activity, script paths and common configuration references. Runtime-only relationships remain `DYNAMIC` or `UNRESOLVED`; they are never silently promoted to confirmed.

Third-party packages, interpreter libraries, virtual environments, ignored paths, generated output and secret candidates are not normal automatic context.

## Ranking and allocation

Each candidate retains every relation, confidence, dependency distance, evidence, size and source policy rule. Default relationship scores include:

| Relationship | Score |
|---|---:|
| Manually pinned | 1000 |
| Direct resolved import | 800 |
| Direct dependent | 700 |
| Related test | 650 |
| Referenced configuration | 550 |
| Package `__init__.py` | 450 |
| Project configuration | 400 |
| Second level | 300 |
| Same package | 200 |
| Inferred textual relation | 100 |
| Generated penalty | -500 |
| Ignored/secret/excluded | -1000 |

Policy priority orders resolver output while preserving deterministic tie-breaks: higher effective score/priority, shorter distance, stronger/direct relation, smaller file, alphabetical repository-relative path.

Pinned files are allocated first and are never silently removed. Automatic overflow appears as omitted with score, relationship and reason. Excluded files remain inspectable and can be included once or have their scoped exclusion removed.

## Git branch context

The branch resolver records current branch, selected base, merge-base, HEAD, commits, changed paths, status and bounded diffs. It can filter to selected paths. Branch-to-PR prompts make this context required and produce a PR-oriented output template. Diff collection has a size guard; truncation is disclosed.
