package nl.ferron.copilotcontextbridge.security

import java.nio.file.Path

class SecretDetector(
    filenamePatterns: Collection<String>,
) {
    data class Finding(
        val rule: String,
        val line: Int,
    )

    private val filenameMatcher = IgnoreMatcher(filenamePatterns)
    private val contentRules =
        listOf(
            "private-key" to Regex("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
            "bearer-token" to Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]{20,}={0,2}"),
            "github-token" to Regex("\\bgh[pousr]_[A-Za-z0-9]{20,}\\b"),
            "aws-access-key" to Regex("\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b"),
            "azure-sas" to Regex("(?i)[?&]sig=[A-Za-z0-9%+/]{20,}"),
            "secret-assignment" to
                Regex("(?i)\\b(?:api[_-]?key|client[_-]?secret|password|connection[_-]?string)\\b\\s*[:=]\\s*['\"][^'\"]{12,}['\"]"),
        )

    fun suspiciousFilename(relativePath: String): Boolean = filenameMatcher.isIgnored(relativePath, false)

    fun scanText(text: CharSequence): List<Finding> {
        val findings = mutableListOf<Finding>()
        text.lineSequence().forEachIndexed { index, line ->
            contentRules.firstOrNull { (_, regex) -> regex.containsMatchIn(line) }?.let { (name, _) ->
                findings += Finding(name, index + 1)
            }
        }
        return findings
    }

    fun describe(
        path: Path,
        text: CharSequence?,
    ): String? {
        if (suspiciousFilename(path.fileName.toString())) return "suspicious filename"
        val findings = text?.let(::scanText).orEmpty()
        return findings.firstOrNull()?.let { "${it.rule} near line ${it.line}" }
    }
}
