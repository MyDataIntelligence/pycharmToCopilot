# Release evidence — 2026-08-08

This record separates verified automated/install evidence from the still-pending visual acceptance matrix.

## Automated result

- Gradle: 9.5.0.
- Kotlin: 2.1.20.
- Compilation target/toolchain: JDK 21 (`jvmToolchain(21)` and `JVM_21`).
- Command: `.\gradlew.bat test ktlintCheck buildPlugin verifyPlugin -PccbBuildDir=build-final-livefix --no-daemon`.
- Result: `BUILD SUCCESSFUL`; 50 suites and 188 tests, 0 failures, 0 errors and 0 skipped tests.
- The same final-audit command rebuilt the ZIP and ran the verifier.
- Plugin Verifier result: `Compatible` on PC-251.26927.90, PC-252.28539.58, PY-252.28539.58 and PY-262.8665.369. No incompatible API use was reported.

The Gradle launcher itself used the locally installed JDK 17. Gradle selected the configured JDK 21 toolchain for plugin compilation.

## ZIP and installer result

- Repository ZIP: `build/distributions/copilot-context-bridge-1.0.0.zip`.
- Repository copy produced by `install.ps1`: 812234 bytes, SHA-256 `901AA4B35E7BA3C4691970B0DE214765C0F3831B0C934B7A404916A6C311E553`.
- Final verification ZIP: `build-final-livefix/distributions/copilot-context-bridge-1.0.0.zip`.
- Final verification size: 799730 bytes.
- Final verification SHA-256: `90D69C1C91652CE0CDC3EBE6439B09DCFB85E6403E6386B04F9C623DDF4A5308`.
- Archive inspection found one `copilot-context-bridge/` plugin root with the plugin and searchable-options JARs.
- `install.ps1` completed successfully, rebuilt the current source and copied the ZIP into the repository distribution folder.
- `install.ps1` completed successfully and atomically installed the current build to `%APPDATA%/JetBrains/PyCharm2026.2/plugins/copilot-context-bridge`.
- The previous installed version was backed up outside the IDE plugin directory under `%LOCALAPPDATA%/CopilotContextBridge/plugin-backups/PyCharm2026.2`.
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

The reviewed full-window screenshots [`L174-reinstalled-final.png`](screenshots/live-audit/L174-reinstalled-final.png), [`L148-after-prepare-correct-coordinates.png`](screenshots/live-audit/L148-after-prepare-correct-coordinates.png), [`L151-preview-tab.png`](screenshots/live-audit/L151-preview-tab.png), [`L152-more-tab.png`](screenshots/live-audit/L152-more-tab.png), [`L163-pinned-dropdown.png`](screenshots/live-audit/L163-pinned-dropdown.png), [`L166-automatic-dropdown-open.png`](screenshots/live-audit/L166-automatic-dropdown-open.png), [`L168-new-session-confirmed.png`](screenshots/live-audit/L168-new-session-confirmed.png), [`L169-session-selector.png`](screenshots/live-audit/L169-session-selector.png), [`L171-session-switched-old.png`](screenshots/live-audit/L171-session-switched-old.png), [`L172-prompt-skills.png`](screenshots/live-audit/L172-prompt-skills.png), [`L173-context-policy.png`](screenshots/live-audit/L173-context-policy.png), [`L154-structured-import-review.png`](screenshots/live-audit/L154-structured-import-review.png), [`L155-native-diff.png`](screenshots/live-audit/L155-native-diff.png), [`L156-import-confirmation.png`](screenshots/live-audit/L156-import-confirmation.png), [`L160-delete-confirmation-2.png`](screenshots/live-audit/L160-delete-confirmation-2.png) and [`L176-delete-warning-wrapped.png`](screenshots/live-audit/L176-delete-warning-wrapped.png) show the installed tool window, compact all-file dropdowns, prepared prompt, Preview/More screens, session switching, prompt policy editor and import/delete review flows. These prove installation and the exercised primary workflows, but not every interactive behavior in the live matrix.

## Visual acceptance status

The broad matrix in `live-pycharm-test-matrix.md` remains `PENDING`; only reviewed screenshots are cited as evidence. The Windows Computer Use runtime could enumerate and uniquely identify the real PyCharm window, but some scripted click/accessibility attempts were not reliable after reset. Unreviewed captures were rejected rather than used as evidence.

No matrix row was marked `PASS` without a reviewed full-window screenshot. The release is therefore buildable, verifier-compatible and installable; a screenshot-per-scenario audit of every interactive row is not claimed complete in this environment.
