# Release evidence — 2026-08-08

This record separates verified automated/install evidence from the still-pending visual acceptance matrix.

## Automated result

- Gradle: 9.5.0.
- Kotlin: 2.1.20.
- Compilation target/toolchain: JDK 21 (`jvmToolchain(21)` and `JVM_21`).
- Command: `.\gradlew.bat test ktlintCheck buildPlugin verifyPlugin -PccbBuildDir=build-final-release23 --no-daemon --console=plain`.
- Result: `BUILD SUCCESSFUL`; 221 tests, 0 failures, 0 errors and 0 skipped tests.
- The release23 gate passed all 221 tests, including external restore, runtime resolver handlers and external caller analysis, non-Python whole-file patch operations, relation identity, prompt ordering/import migration, previous-batch filtering, the Chosen/Pinned context-files view, responsive Batch layout, ignored-tree guideline discovery and policy duplication.
- Plugin Verifier result: `Compatible` on PC-251.26927.90, PC-252.28539.58, PY-252.28539.58 and PY-262.8665.369. No incompatible API use was reported.

The Gradle launcher itself used the locally installed JDK 17. Gradle selected the configured JDK 21 toolchain for plugin compilation.

## ZIP and installer result

- Repository ZIP: `build/distributions/copilot-context-bridge-1.0.0.zip`.
- Current release23 ZIP produced by `buildPlugin`: 848018 bytes, SHA-256 `FA50AE451B4F27DF564CE1DAE7B79F6CFAF42B807831CD52B37F91463FD6F552`.
- Final verification ZIP: `build-final-release23/distributions/copilot-context-bridge-1.0.0.zip`.
- Final verification size: 848018 bytes.
- Final verification SHA-256: `FA50AE451B4F27DF564CE1DAE7B79F6CFAF42B807831CD52B37F91463FD6F552`.
- The ZIP includes the compact Batch dropdown renderer, wrapped Context Files details and their regression tests.
- Archive inspection found one `copilot-context-bridge/` plugin root with the plugin and searchable-options JARs.
- `install.ps1 -BuildOnly` was run successfully after release23; it produced a fresh ZIP and copied it to `build/distributions`.
- The installer now runs `clean buildPlugin` in its isolated build directory before packaging, preventing stale Kotlin classes from leaking into a later install.
- A full `install.ps1` run after release23 completed successfully after closing the controlled test instance and atomically installed `%APPDATA%/JetBrains/PyCharm2026.2/plugins/copilot-context-bridge`.
- Fresh-clone check: a depth-1 clone of `origin/main` ran `install.ps1 -BuildOnly` from the cloned repository and exited 0, producing `build/distributions/copilot-context-bridge-1.0.0.zip` without relying on repository-local generated files.
- The previous installed version was backed up outside the IDE plugin directory under `%LOCALAPPDATA%/CopilotContextBridge/plugin-backups/PyCharm2026.2`.
- The restarted PyCharm 2026.2 instance loaded `Copilot Context Bridge (1.0.0)` and the live Batch/Preview/More views were observed without plugin class-loading errors.
- Exactly one installed directory contains `copilot-context-bridge-1.0.0.jar`.

## GitHub Actions result

- Workflow run: `31222834805` on commit `0413965c1df30ecfb92f4f00362aa30c96351ab6`.
- Result: successful in 12 minutes and 4 seconds.
- Successful gates: Linux tests/static checks, plugin ZIP build, Plugin Verifier and artifact upload.
- Artifact: `copilot-context-bridge`, artifact ID `9011220531`, 612931-byte GitHub artifact archive.
- The workflow uses an executable Unix Gradle wrapper plus `actions/checkout@v7`, `actions/setup-java@v5`, `gradle/actions/setup-gradle@v6` and `actions/upload-artifact@v7`.

## Actual PyCharm startup result

PyCharm 2026.2.0.1 was launched with this repository after the final install. Its current `idea.log` records:

- `Loaded custom plugins: Copilot Context Bridge (1.0.0)`;
- the project-scoped open-on-startup activity with `enabled=true`;
- no `PluginException`, `ClassNotFoundException`, `NoClassDefFoundError` or plugin `ERROR` after that startup.

The reviewed full-window screenshots [`L174-reinstalled-final.png`](screenshots/live-audit/L174-reinstalled-final.png), [`L148-after-prepare-correct-coordinates.png`](screenshots/live-audit/L148-after-prepare-correct-coordinates.png), [`L151-preview-tab.png`](screenshots/live-audit/L151-preview-tab.png), [`L152-more-tab.png`](screenshots/live-audit/L152-more-tab.png), [`L163-pinned-dropdown.png`](screenshots/live-audit/L163-pinned-dropdown.png), [`L166-automatic-dropdown-open.png`](screenshots/live-audit/L166-automatic-dropdown-open.png), [`L168-new-session-confirmed.png`](screenshots/live-audit/L168-new-session-confirmed.png), [`L169-session-selector.png`](screenshots/live-audit/L169-session-selector.png), [`L171-session-switched-old.png`](screenshots/live-audit/L171-session-switched-old.png), [`L172-prompt-skills.png`](screenshots/live-audit/L172-prompt-skills.png), [`L173-context-policy.png`](screenshots/live-audit/L173-context-policy.png), [`L154-structured-import-review.png`](screenshots/live-audit/L154-structured-import-review.png), [`L155-native-diff.png`](screenshots/live-audit/L155-native-diff.png), [`L156-import-confirmation.png`](screenshots/live-audit/L156-import-confirmation.png), [`L160-delete-confirmation-2.png`](screenshots/live-audit/L160-delete-confirmation-2.png), [`L176-delete-warning-wrapped.png`](screenshots/live-audit/L176-delete-warning-wrapped.png), [`L179-copy-prompt-clicked.png`](screenshots/live-audit/L179-copy-prompt-clicked.png), [`L180-copy-return-clicked.png`](screenshots/live-audit/L180-copy-return-clicked.png), [`L195-after-notification-dismiss.png`](screenshots/live-audit/L195-after-notification-dismiss.png), [`L203-copy-text-success.png`](screenshots/live-audit/L203-copy-text-success.png), [`L204-final-reinstall-startup.png`](screenshots/live-audit/L204-final-reinstall-startup.png) and [`L205-clean-installer-startup.png`](screenshots/live-audit/L205-clean-installer-startup.png) show the installed tool window, compact all-file dropdowns, prepared prompt, Preview/More screens, session switching, prompt policy editor, clipboard actions and import/delete review flows. These prove installation and the exercised primary workflows, but not every interactive behavior in the live matrix.

The final Batch-row smoke test also verified that `Copy files`, `Copy text`, `Open folder` and `Next batch` fit in one visible row after preparation. `L203-copy-text-success.png` is paired with a real clipboard assertion: the clipboard contained 56,094 characters beginning with `# Complete Copilot context batch`, the kickoff prompt and the attached file boundaries. The file-list clipboard assertion remains covered by the earlier staged-file check recorded below.

The latest live navigation captures [`L209-current.png`](screenshots/live-audit/L209-current.png) and [`L210-batch-return.png`](screenshots/live-audit/L210-batch-return.png) show the combined Preview view and the compact Batch view with the kickoff prompt/return-text actions under the drag zone. The new dropdown popup renderer and wrapped Context Files details were separately covered by focused automated tests; these captures predate that renderer change.

The More quick-action order was checked before and after opening Guidelines and returning to More. [`L213-more-quick-actions-current.png`](screenshots/live-audit/L213-more-quick-actions-current.png) and [`L214-more-after-guidelines-return.png`](screenshots/live-audit/L214-more-after-guidelines-return.png) show the same Quick copy and Batch history ordering after navigation.

After the release23 build, `install.ps1` was run in full mode: it rebuilt, atomically replaced the PyCharm 2026.2 plugin, preserved the previous version in the configured backup directory, and PyCharm was restarted. The post-reinstall live captures [`L211-batch-dropdown-after-reinstall.png`](screenshots/live-audit/L211-batch-dropdown-after-reinstall.png) and [`L212-preview-wrapped-context-files.png`](screenshots/live-audit/L212-preview-wrapped-context-files.png) show the new popup provenance labels and wrapped Preview details.

The corresponding `Copy files` assertion exposed a real `javaFileListFlavor` list containing the staged `00_REPO_CONTEXT.md`, `README.md`, `src/main/kotlin/nl/ferron/copilotcontextbridge/settings/AppSettings.kt` and generated automatic attachment, rather than a text-only paste.

The current live session selector was also exercised after reinstall (`10b01161 · 7 batches`); the historical-session screenshots above show switching back to a previous session without losing its batch history.

Additional live rows completed in this audit are recorded in `live-pycharm-test-matrix.md`: narrow-window responsive layout, repeated More navigation, Project View submenu actions, Return Instructions, Next batch reset, Guidelines, Prompt Library, Settings, retained Context Preview, Preview-to-Batch navigation in the wide split layout and a second-batch kickoff prompt. The settings screenshot specifically verifies the previously ambiguous entries now render as `Copilot Context Bridge (Application)` and `Copilot Context Bridge (Project)`.

## Visual acceptance status

The broad matrix in `live-pycharm-test-matrix.md` remains `PENDING`; only reviewed screenshots are cited as evidence. The final reinstall/startup state is captured in `docs/screenshots/live-audit/L181-reinstalled-session-stable-ids.png`. The Windows Computer Use runtime could enumerate and uniquely identify the real PyCharm window, but some scripted click/accessibility attempts were not reliable after reset. Unreviewed captures were rejected rather than used as evidence.

No matrix row was marked `PASS` without a reviewed full-window screenshot. The release is therefore buildable, verifier-compatible and installable; a screenshot-per-scenario audit of every interactive row is not claimed complete in this environment.
