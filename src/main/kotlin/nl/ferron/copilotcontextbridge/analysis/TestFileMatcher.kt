package nl.ferron.copilotcontextbridge.analysis

/** Conservative filename matcher: exact conventions first, then token/edit similarity. */
object TestFileMatcher {
    fun matches(
        testPath: String,
        productionPath: String,
    ): Boolean = score(testPath, productionPath) >= 72

    fun score(
        testPath: String,
        productionPath: String,
    ): Int {
        val test = normalizedStem(testPath, testFile = true)
        val production = normalizedStem(productionPath, testFile = false)
        if (test.isBlank() || production.isBlank()) return 0
        if (test == production) return 100
        val testTokens = tokens(test)
        val productionTokens = tokens(production)
        if (testTokens == productionTokens) return 96
        if (testTokens.containsAll(productionTokens) || productionTokens.containsAll(testTokens)) {
            val shorter = minOf(testTokens.size, productionTokens.size)
            val longer = maxOf(testTokens.size, productionTokens.size)
            if (shorter > 0 && longer - shorter <= 1) return 86
        }
        val distance = levenshtein(test, production)
        val similarity = 100 - (distance * 100 / maxOf(test.length, production.length))
        return similarity.takeIf { it >= 72 && maxOf(test.length, production.length) >= 5 } ?: 0
    }

    private fun normalizedStem(
        path: String,
        testFile: Boolean,
    ): String {
        var stem =
            path
                .replace('\\', '/')
                .substringAfterLast('/')
                .substringBeforeLast('.')
                .lowercase()
        if (testFile) {
            stem =
                stem
                    .removePrefix("test_")
                    .removePrefix("test-")
                    .removeSuffix("_test")
                    .removeSuffix("-test")
        }
        return stem.replace(Regex("[^a-z0-9]+"), "_").trim('_')
    }

    private fun tokens(value: String): Set<String> = value.split('_').filter(String::isNotBlank).toSet()

    private fun levenshtein(
        left: String,
        right: String,
    ): Int {
        var previous = IntArray(right.length + 1) { it }
        left.forEachIndexed { leftIndex, leftCharacter ->
            val current = IntArray(right.length + 1)
            current[0] = leftIndex + 1
            right.forEachIndexed { rightIndex, rightCharacter ->
                current[rightIndex + 1] =
                    minOf(
                        current[rightIndex] + 1,
                        previous[rightIndex + 1] + 1,
                        previous[rightIndex] + if (leftCharacter == rightCharacter) 0 else 1,
                    )
            }
            previous = current
        }
        return previous[right.length]
    }
}
