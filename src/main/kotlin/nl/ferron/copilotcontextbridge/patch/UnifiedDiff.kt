package nl.ferron.copilotcontextbridge.patch

import nl.ferron.copilotcontextbridge.analysis.FunctionHasher

object UnifiedDiff {
    fun create(
        path: String,
        oldText: String,
        newText: String,
    ): String {
        val oldLines = splitLines(oldText)
        val newLines = splitLines(newText)
        val lcs = Array(oldLines.size + 1) { IntArray(newLines.size + 1) }
        for (i in oldLines.indices.reversed()) {
            for (j in newLines.indices.reversed()) {
                lcs[i][j] = if (oldLines[i] == newLines[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
        val body = mutableListOf<String>()
        var i = 0
        var j = 0
        while (i < oldLines.size || j < newLines.size) {
            when {
                i < oldLines.size && j < newLines.size && oldLines[i] == newLines[j] -> {
                    body += " ${oldLines[i]}"
                    i++
                    j++
                }
                j < newLines.size && (i == oldLines.size || lcs[i][j + 1] >= lcs[i + 1][j]) -> {
                    body += "+${newLines[j]}"
                    j++
                }
                else -> {
                    body += "-${oldLines[i]}"
                    i++
                }
            }
        }
        return buildString {
            appendLine("--- a/$path")
            appendLine("+++ b/$path")
            appendLine("@@ -1,${oldLines.size} +1,${newLines.size} @@")
            body.forEach(::appendLine)
        }
    }

    private fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val normalized = FunctionHasher.normalize(text)
        return normalized.split('\n').let { lines -> if (lines.lastOrNull().isNullOrEmpty()) lines.dropLast(1) else lines }
    }
}
