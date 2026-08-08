package nl.ferron.copilotcontextbridge.settings

object KickoffPromptTemplateRenderer {
    private val requiredPlaceholders =
        linkedMapOf(
            "{sessionId}" to "session id",
            "{batchNumber}" to "batch number",
            "{promptSkill}" to "selected prompt skill",
        )

    fun render(
        template: String,
        sessionId: String,
        batchNumber: Int,
        promptSkill: String,
    ): String {
        val errors = validationErrors(template)
        require(errors.isEmpty()) { errors.joinToString(" ") }
        require(sessionId.isNotBlank()) { "Session id cannot be empty." }
        require(batchNumber > 0) { "Batch number must be positive." }
        require(promptSkill.isNotBlank()) { "Prompt skill cannot be empty." }

        return template
            .replace("{sessionId}", sessionId)
            .replace("{batchNumber}", batchNumber.toString())
            .replace("{promptSkill}", promptSkill)
            .trim()
    }

    fun validationErrors(template: String): List<String> =
        buildList {
            if (template.isBlank()) add("Kickoff prompt template cannot be empty.")
            if (!template.contains("00_REPO_CONTEXT.md")) {
                add("Kickoff prompt template must tell Copilot to use 00_REPO_CONTEXT.md.")
            }
            requiredPlaceholders.forEach { (placeholder, meaning) ->
                if (!template.contains(placeholder)) add("Kickoff prompt template must contain $placeholder for the $meaning.")
            }
        }
}
