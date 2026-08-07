package nl.ferron.copilotcontextbridge.security

class IgnoreMatcher(
    patterns: Collection<String>,
) {
    private data class Rule(
        val regex: Regex,
        val negated: Boolean,
        val directoryOnly: Boolean,
    )

    private val rules = patterns.mapNotNull(::compileRule)

    fun isIgnored(
        relativePath: String,
        isDirectory: Boolean = false,
    ): Boolean {
        val path = relativePath.replace('\\', '/').trimStart('/') + if (isDirectory) "/" else ""
        var ignored = false
        for (rule in rules) {
            if ((!rule.directoryOnly || isDirectory || path.contains('/')) && rule.regex.matches(path)) {
                ignored = !rule.negated
            }
        }
        return ignored
    }

    private fun compileRule(raw: String): Rule? {
        var value = raw.trim()
        if (value.isBlank() || value.startsWith('#')) return null
        val negated = value.startsWith('!')
        if (negated) value = value.drop(1)
        if (value.isBlank()) return null
        val anchored = value.startsWith('/')
        if (anchored) value = value.drop(1)
        val directoryOnly = value.endsWith('/')
        if (directoryOnly) value = value.dropLast(1)
        val hasSlash = value.contains('/')
        val body = globToRegex(value)
        val prefix = if (anchored || hasSlash) "^" else "^(?:.*/)?"
        val suffix = if (directoryOnly) "(?:/.*)?/?$" else "/?$"
        return Rule(Regex(prefix + body + suffix), negated, directoryOnly)
    }

    private fun globToRegex(glob: String): String {
        val out = StringBuilder()
        var index = 0
        while (index < glob.length) {
            when (val char = glob[index]) {
                '*' -> {
                    if (index + 1 < glob.length && glob[index + 1] == '*') {
                        out.append(".*")
                        index++
                    } else {
                        out.append("[^/]*")
                    }
                }
                '?' -> out.append("[^/]")
                '.', '(', ')', '+', '|', '^', '$', '@', '%' -> out.append('\\').append(char)
                else -> out.append(char)
            }
            index++
        }
        return out.toString()
    }
}
