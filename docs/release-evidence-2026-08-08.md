# Release evidence — 2026-08-08

This record separates verified automated/install evidence from the still-pending visual acceptance matrix.

## Automated result

- Gradle: 9.5.0.
- Kotlin: 2.3.20.
- Compilation target/toolchain: JDK 21 (`jvmToolchain(21)` and `JVM_21`).
- Command: `.\gradlew.bat test ktlintCheck buildPlugin verifyPlugin -PccbBuildDir=build-final-audit --no-daemon`.
- Result: `BUILD SUCCESSFUL`; 50 suites and 188 tests, 0 failures, 0 errors and 0 skipped tests.
- The same final-audit command rebuilt the ZIP and ran the verifier.
- Plugin Verifier result: `Compatible` on PC-251.26927.90, PC-252.28539.58, PY-252.28539.58 and PY-262.8665.369. No incompatible API use was reported.

The Gradle launcher itself used the locally installed JDK 17. Gradle selected the configured JDK 21 toolchain for plugin compilation.

## ZIP and installer result

- Repository ZIP: `build/distributions/copilot-context-bridge-1.0.0.zip`.
- Size: 812144 bytes.
- SHA-256: `09CB7E78E184A4EB44A0DA78150BFDA990D08954D0932A3FFF46EB03E9A223CD`.
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

The reviewed full-window screenshots [`L139-final-installed.png`](screenshots/live-audit/L139-final-installed.png) and [`L144-current-final.png`](screenshots/live-audit/L144-current-final.png) show the installed tool window with Batch/Import/Preview/More navigation, the persistent session selector, compact Pinned dropdown, ZIP drop zone, green Prepare action and the prompt card below the drag zone. These prove installation and the primary layout, but not every interactive behavior in the live matrix.

## Visual acceptance status

The broad matrix in `live-pycharm-test-matrix.md` remains `PENDING`; only reviewed screenshots are cited as evidence. The Windows Computer Use runtime could enumerate and uniquely identify the real PyCharm window, but some scripted click/accessibility attempts were not reliable after reset. Unreviewed captures were rejected rather than used as evidence.

No matrix row was marked `PASS` without a reviewed full-window screenshot. The release is therefore buildable, verifier-compatible and installable; a screenshot-per-scenario audit of every interactive row is not claimed complete in this environment.
