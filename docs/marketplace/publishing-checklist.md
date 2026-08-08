# Publishing checklist

The repository produces the Marketplace upload ZIP but does not publish automatically.

## Before the first upload

- [ ] Confirm that `Copilot Context Bridge` and plugin ID `nl.ferron.copilot-context-bridge` are available in JetBrains Marketplace.
- [ ] Confirm the MyDataIntelligence vendor/organization account and public repository URL.
- [ ] Review the English description and change notes in `META-INF/plugin.xml`.
- [ ] Run `./gradlew.bat clean test ktlintCheck buildPlugin verifyPlugin`.
- [ ] Inspect the distribution and confirm `META-INF/pluginIcon.svg` is a 40 x 40 SVG with transparent padding.
- [ ] Install the built ZIP from disk in the latest supported PyCharm Community and Professional editions.
- [ ] Run the live matrix and redact personal information from screenshots.
- [ ] Upload at least the Batch, Preview and Import screenshots at 1280 x 800 (16:10).
- [ ] Select accurate Marketplace tags and attach the MIT license.

## Upload artifact

Upload the ZIP produced in `build/distributions/` by the `buildPlugin` task. The first upload is performed manually in JetBrains Marketplace so its review, organization and listing details can be confirmed.

## Optional later automation

After the first manual upload, the existing IntelliJ Platform Gradle Plugin exposes `publishPlugin`. Supply the Marketplace token through the `PUBLISH_TOKEN` environment variable; never commit a token or signing material. Publishing is intentionally absent from GitHub Actions.
