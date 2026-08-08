# Release evidence — 2026-08-08

This record separates verified automated/install evidence from the still-pending visual acceptance matrix.

## Automated result

- Gradle: 9.5.0.
- Kotlin: 2.3.20.
- Compilation target/toolchain: JDK 21 (`jvmToolchain(21)` and `JVM_21`).
- Command: `.\gradlew.bat test ktlintCheck -PccbBuildDir=C:\Users\Ferro\AppData\Local\Temp\ccb-zip-final-test --no-build-cache --no-daemon`.
- Result: `BUILD SUCCESSFUL`; 149 tests, 0 failures, 0 errors and 0 skipped tests.
- Plugin build/verifier command: `.\gradlew.bat buildPlugin verifyPlugin -PccbBuildDir=C:\Users\Ferro\AppData\Local\Temp\ccb-zip-final-test --no-build-cache --no-daemon`.
- Plugin Verifier result: compatible on PC 2025.1.3.1, PC 2025.2.6.1, PY 2025.2.6.1 and PY 2026.2.0.1. It reports two uses of the supported but deprecated `PsiFileFactory.createFileFromText` overload in patch validation; no incompatible API use was reported.

The Gradle launcher itself used the locally installed JDK 17. Gradle selected the configured JDK 21 toolchain for plugin compilation.

## ZIP and installer result

- Repository ZIP: `build/distributions/copilot-context-bridge-1.0.0.zip`.
- Size: 705812 bytes.
- SHA-256: `76D32DC7CB6E963675DA4F6F0346D3F9908AD5DACDDA223ADBE3C7ED2A1ED78D`.
- Archive inspection found one `copilot-context-bridge/` plugin root with the plugin and searchable-options JARs.
- `install.ps1 -BuildOnly` completed successfully and copied the ZIP into the repository distribution folder.
- `install.ps1` completed successfully and atomically installed to `%APPDATA%/JetBrains/PyCharm2026.2/plugins/copilot-context-bridge`.
- The previous installed version was backed up outside the IDE plugin directory under `%LOCALAPPDATA%/CopilotContextBridge/plugin-backups/PyCharm2026.2`.
- Exactly one installed directory contains `copilot-context-bridge-1.0.0.jar`.

## GitHub Actions result

- Workflow run: `31222834805` on commit `0413965c1df30ecfb92f4f00362aa30c96351ab6`.
- Result: successful in 12 minutes and 4 seconds.
- Successful gates: Linux tests/static checks, plugin ZIP build, Plugin Verifier and artifact upload.
- Artifact: `copilot-context-bridge`, artifact ID `9011220531`, 612931-byte GitHub artifact archive.
- The workflow uses an executable Unix Gradle wrapper plus `actions/checkout@v7`, `actions/setup-java@v5`, `gradle/actions/setup-gradle@v6` and `actions/upload-artifact@v7`.

## Actual PyCharm startup result

PyCharm 2026.2.0.1 was launched with this repository. Its current `idea.log` records:

- `Loaded custom plugins: Copilot Context Bridge (1.0.0)`;
- the project-scoped open-on-startup activity with `enabled=true`;
- no `PluginException`, `ClassNotFoundException`, `NoClassDefFoundError` or plugin `ERROR` after that startup.

This proves installation and plugin loading, but not every interactive behavior in the live matrix.

## Visual acceptance status

All 130 rows in `live-pycharm-test-matrix.md` remain `PENDING`. The Windows Computer Use runtime could enumerate and uniquely identify the real PyCharm window, but both screenshot and accessibility capture failed after reset with:

```text
Error: node_repl exec context not found
```

GDI and `PrintWindow` fallback captures rendered the JetBrains client area black and were rejected rather than used as evidence. Those invalid captures were quarantined outside the repository. No matrix row was marked `PASS` without a reviewed full-window screenshot.

The release is therefore buildable and installable, while the requested screenshot-per-scenario visual gate is not yet complete in this environment.
