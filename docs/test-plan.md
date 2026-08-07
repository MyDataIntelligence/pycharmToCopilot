# Copilot Context Bridge release test plan

This checklist records gates, not current results. Leave a box unchecked until current command output or live evidence proves it.

## Automated gates

- [x] Clean Kotlin/Java compilation and instrumentation succeed with the configured JDK 21 toolchain.
- [x] All 105 unit and PSI/platform tests pass with no skipped tests.
- [x] Ktlint/static checks pass.
- [x] Context Policy codec/default/projection/packing tests pass.
- [x] Session/exclusion/Return Instruction tests pass.
- [x] Patch parser/sniffer/function/file/native-diff/application/Undo tests pass.
- [x] `buildPlugin` creates the installable ZIP and archive inspection passes.
- [x] Plugin Verifier passes every configured PyCharm target, including 2026.2.0.1.
- [ ] CI performs equivalent gates and uploads the same plugin artifact shape.

## Outbound acceptance

- [ ] Single and Ctrl/Shift multi-file Project View actions work from multiple folders.
- [ ] Pinned files remain until explicit removal; deleted/moved paths show warnings.
- [ ] Every Prompt Library entry owns editable prompt, guidelines, Context Policy and Return Instructions.
- [ ] Policy resolvers, priorities, depth/file limits, targets, return modes and previous-batch modes behave independently.
- [ ] Included, omitted and excluded candidates show path, evidence and reason.
- [ ] Batch/session/permanent exclusions and Include once have exact scope.
- [ ] Repository-file allocation and physical attachment packing are distinct.
- [ ] Every valid prepared pack has one `00_REPO_CONTEXT.md` and at most 20 physical attachments.
- [ ] Pinned files stay separate and automatic bundles map every original repository-relative path.
- [ ] Secret/ignored/unsafe content cannot enter an automatic pack.
- [ ] Preview, copy context, copy Return Instructions, copy files, copy text, drag and open-folder fallback all match the same attachment plan.
- [ ] Batch history, Next batch, Restore, Forget and New session behave consistently.
- [ ] More page controls keep a stable position/order during navigation and refresh.

## Inbound acceptance

- [ ] `.copilotpatch`, schema-matching JSON and safe ZIP imports work; ordinary JSON is rejected.
- [ ] Repository/session/path/symlink/schema/size/hash checks fail closed.
- [ ] Function replace/add supports top-level, method, async, decorated and unambiguous nested targets.
- [ ] `add_file` creates only a new syntactically valid Python file in an existing project directory.
- [ ] `delete_file` requires an existing Python file and exported content hash.
- [ ] Native per-operation, combined and three-way conflict diffs show correct content/identity.
- [ ] Selection, Keep current, Use Copilot and Force Replace require explicit choices.
- [ ] Apply revalidates immediately before write, changes only selected operations, preserves unrelated code/encoding and supports Undo.
- [ ] Post-apply report distinguishes passed, failed and not-run validation.

## Installation and live evidence

- [ ] `install.ps1 -BuildOnly` succeeds from a clean clone/equivalent isolated copy.
- [x] Actual `install.ps1` update works with PyCharm closed and keeps its previous version outside the IDE plugin directory.
- [ ] Manual ZIP installation fallback works.
- [x] Plugin loads exactly once in PyCharm 2026.2.0.1 according to the current IDE log and installed-directory audit.
- [ ] Every row in [live-pycharm-test-matrix.md](live-pycharm-test-matrix.md) is `PASS` or justified `NOT_APPLICABLE`.
- [ ] Every live row has the required reviewed full-window screenshot and supplemental assertion where needed.
- [ ] Microsoft 365 Copilot accepts the prepared attachment list in the tested environment without an automatic chat send.
- [ ] Direct-drag outcome is documented honestly and staging-folder fallback works.

## Final delivery

- [x] Exact commands, versions, test totals, verifier result, ZIP path/hash and known limitations are recorded in [release-evidence-2026-08-08.md](release-evidence-2026-08-08.md).
- [ ] Git diff/status are reviewed; no required TODO or generated junk remains.
- [ ] Required files are committed and `main` is pushed successfully.
