package nl.ferron.copilotcontextbridge.patch

/** Stable, UI-independent rendering for the individual and combined diff views. */
object PatchDiffFormatter {
    fun combined(targets: Collection<PatchValidator.Target>): String =
        targets
            .sortedWith(compareBy({ it.validated.request.path }, { it.validated.request.qualifiedName }))
            .joinToString("\n\n") { target ->
                val item = target.validated
                "# ${item.request.path}::${item.request.qualifiedName} [${item.status}]\n${item.unifiedDiff.ifBlank { item.message }}"
            }
}
