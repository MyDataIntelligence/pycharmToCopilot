# Testing and release evidence

No single green task proves the plugin release. Evidence is collected in layers.

## Automated unit and platform tests

Run from a clean clone with JDK 21:

```powershell
.\gradlew.bat clean test --no-build-cache --rerun-tasks
.\gradlew.bat ktlintCheck
.\gradlew.bat buildPlugin
.\gradlew.bat verifyPlugin
```

These commands are release gates. The current local result is recorded in
[release-evidence-2026-08-08.md](release-evidence-2026-08-08.md); CI must execute equivalent checks and upload the produced ZIP.

Automated coverage must include:

- Context Policy defaults, projection, persistence/codec, duplication/reset and deterministic priority;
- repository-file allocation versus physical attachment packing, pinned separate behavior, bundle grouping/names, overflow and exact mapping;
- batch/session history, previous-batch modes, batch/session/permanent exclusions and Include once;
- Python imports/dependents/tests/config resolution, ranking and Git branch context;
- ignored/secret paths, traversal, symlink containment, scan limits and staged text extension conversion;
- repository tree, guidelines precedence, Return Instruction inheritance and required-clause validation;
- deterministic function/file hashes and staging manifest/base-function data;
- outbound ZIP discovery path preservation, ignore/secret/binary filtering, traversal/duplicate/bomb limits and multi-batch source-key history;
- inbound structured-ZIP detection plus exact-path, unique-basename, ambiguous-basename and explicit whole-file replacement behavior;
- patch JSON/ZIP/sniffing/schema limits and invalid schema;
- PSI location for top-level/method/async/decorated/nested functions;
- complete function replacement/insertion while preserving neighbours;
- `add_file`/`delete_file`, changed hashes, overlapping operations and one-command Undo;
- native two-way/three-way/combined diff request creation and selection decisions;
- asynchronous generation IDs so stale analysis/validation cannot update UI;
- file-list Transferable and combined-text clipboard formatting;
- installer dry-run/build-only behavior and ZIP structure where testable.

## Plugin and installer validation

`buildPlugin` must produce `build/distributions/copilot-context-bridge-1.0.0.zip`. Inspect it for one plugin root, valid `META-INF/plugin.xml`, bundled dependencies, icons and no duplicate plugin ID or development/build files.

Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\install.ps1 -BuildOnly
```

Then test an actual install with PyCharm closed. Verify atomic replacement, backup outside the IDE plugin folder, exactly one installed plugin directory, restart/load, and repeat-install/update. Do not claim clone-to-install success until observed from a fresh clone or equivalent isolated copy.

## Live PyCharm testing

[live-pycharm-test-matrix.md](live-pycharm-test-matrix.md) is the authoritative manual gate. Every row starts `PENDING`. It becomes `PASS` only after the behavior is observed in a real PyCharm 2026.2.0.1 window and the named full-window screenshot is stored. A screenshot includes the entire PyCharm window, not a cropped control. Clipboard, filesystem, Undo and external Copilot checks also need a written assertion because pixels alone cannot prove their complete state.

Direct browser drag is environment-dependent. A rejected drop does not fail the plugin if the OS file-list drag was offered and **Open staging folder** works, but the observed browser outcome must be recorded honestly. Microsoft 365 Copilot testing must stop before sending the chat unless the user explicitly authorises sending.

## Release decision

Release only when:

1. automated clean test, formatting/static checks, build and verifier pass;
2. installer build-only and actual clone-to-install pass;
3. every applicable live-matrix item is `PASS` with its full-window screenshot and supplemental assertion;
4. no required item is `FAIL`, `BLOCKED` or `PENDING`;
5. final ZIP hash/location and exact tested PyCharm build are recorded;
6. git status is reviewed, required files are committed, and the intended remote branch is pushed successfully.

The detailed engineering checklist is also maintained in [test-plan.md](test-plan.md).
