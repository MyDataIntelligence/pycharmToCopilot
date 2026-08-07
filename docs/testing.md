# Testing

`gradlew check` runs deterministic allocation, filename, ignore, traversal, secret, hashing, patch JSON/ZIP and Python PSI tests. Platform tests parse real Python PSI and cover qualified nested symbols. Build validation also runs plugin structure checks and ktlint.

GitHub Actions builds on JDK 21, caches Gradle dependencies, runs tests/static checks/Plugin Verifier and uploads the installable ZIP. Direct browser drag remains an environment-dependent manual check; `FileListTransferable` behavior is unit-tested.

The complete release checklist is maintained in [test-plan.md](test-plan.md).
