package nl.ferron.copilotcontextbridge.settings

/** Context-aware prompts introduced by the packing/import workflow delta. */
object DeltaPromptLibrary {
    const val PREPARE_PR_ID = "prepare-pr-from-branch-changes"
    const val ANALYZE_STORY_ID = "analyze-user-story-against-repository"

    fun skills(): List<AppSettings.PromptSkillState> = listOf(preparePr(), analyzeUserStory())

    fun createStoryPolicy(): ContextPolicyState =
        ContextPolicyState.defaultFor(DevelopmentPromptLibrary.USER_STORY_ID).apply {
            returnMode = CopilotReturnMode.TEXT_ONLY.name
            previousBatchMode = PreviousBatchMode.NEVER.name
            maxRepositoryFiles = 25
            rule("direct-callers")?.enabled = false
            rule("transitive-imports")?.enabled = false
            rule("similar-implementations")?.apply {
                enabled = true
                priority = 75
                bundleGroup = "examples"
            }
        }

    private fun preparePr(): AppSettings.PromptSkillState =
        AppSettings.PromptSkillState(
            id = PREPARE_PR_ID,
            name = "Prepare PR from branch changes",
            description = "Create a factual pull-request description from current Git branch changes.",
            prompt =
                """
                Je bent de Copilot Pull Request Writer. Gebruik 00_REPO_CONTEXT.md, 01_PR_CHANGES.md,
                geselecteerde gewijzigde bestanden, matching tests en repository-instructies als primaire bron.
                Beschrijf uitsluitend wijzigingen die daadwerkelijk in de meegestuurde Git-context staan.

                Lever exact deze compacte structuur:

                ### Title
                <Concise imperative title>

                ### Summary
                - <main change>

                ### Why
                <Why this change is needed; mark unknown when intent is not evidenced>

                ### Changes
                - <implementation change grounded in the diff>

                ### Testing
                - <test actually evidenced as performed, or Not run>

                ### Risks / impact
                <Concise risks or None known>

                ### Reviewer focus
                <What reviewers should verify>

                Noem branch, base, commits en files alleen zoals geleverd. Verzin geen tests, resultaten,
                issue-ID's of gebruikersintentie. Geef geen brede codeoplossing en wijzig geen repositorybestanden.
                """.trimIndent(),
            guidelines =
                """
                - Baseer iedere claim op de meegestuurde Git-diff of repository-instructies.
                - Houd de PR-beschrijving compact, reviewergericht en zonder duplicatie.
                - Zet niet-uitgevoerde tests expliciet op Not run.
                - Neem nooit secrets of absolute lokale paden op.
                """.trimIndent(),
            contextPolicy = preparePrPolicy(),
            category = "Git / PR",
        )

    private fun analyzeUserStory(): AppSettings.PromptSkillState =
        AppSettings.PromptSkillState(
            id = ANALYZE_STORY_ID,
            name = "Analyze user story against repository",
            description = "Map an existing story to concrete repository locations, tests, risks and implementation hints.",
            prompt =
                """
                Je bent de Copilot Repository User Story Analyzer. De gebruiker levert een bestaande user story.
                Analyseer die gericht tegen de meegestuurde repositorycontext. Zoek waarschijnlijke implementatie,
                matching tests, direct relevante configuratie, vergelijkbare implementaties en instructies.
                Doe geen brede dependencycrawl en schrijf nog geen code.

                Lever compact:
                1. What the story requires
                2. Likely repository/component
                3. Likely files
                4. Existing related implementation
                5. Likely technical approach
                6. Tests affected
                7. Risks / unknowns
                8. GitHub Copilot implementation handoff

                Scheid bevestigde feiten, sterke aanwijzingen en aannames. Noem omitted context die een conclusie
                beperkt. De handoff moet concreet genoeg zijn om samen met de repositorycontext aan GitHub Copilot
                te geven, maar bevat geen verzonnen API's, commando's of testresultaten.
                """.trimIndent(),
            guidelines =
                """
                - Houd de analyse gericht en verwijs naar concrete repository-relatieve paden.
                - Prioriteer matching tests en direct relevante configuratie boven brede dependencies.
                - Label onzekerheid en ontbrekende context expliciet.
                - Lever geen code of architectuuressay.
                """.trimIndent(),
            contextPolicy = analyzeStoryPolicy(),
            category = "User stories",
        )

    private fun preparePrPolicy(): ContextPolicyState =
        ContextPolicyState.defaultFor(PREPARE_PR_ID).apply {
            returnMode = CopilotReturnMode.TEXT_ONLY.name
            previousBatchMode = PreviousBatchMode.NEVER.name
            maxRepositoryFiles = 100
            rule("branch-changes")?.apply {
                enabled = true
                required = true
                priority = 100
                bundleGroup = "pr"
            }
            rule("matching-tests")?.priority = 90
            rule("direct-imports")?.enabled = false
            rule("direct-callees")?.enabled = false
            rule("direct-callers")?.enabled = false
            rule("transitive-imports")?.enabled = false
            rule("referenced-config")?.enabled = false
            rule("similar-implementations")?.enabled = false
            rule("templates")?.enabled = false
        }

    private fun analyzeStoryPolicy(): ContextPolicyState =
        ContextPolicyState.defaultFor(ANALYZE_STORY_ID).apply {
            returnMode = CopilotReturnMode.TEXT_ONLY.name
            previousBatchMode = PreviousBatchMode.NEVER.name
            maxRepositoryFiles = 35
            rule("matching-tests")?.priority = 100
            rule("direct-callers")?.enabled = false
            rule("transitive-imports")?.enabled = false
            rule("similar-implementations")?.apply {
                enabled = true
                priority = 80
                bundleGroup = "examples"
            }
        }
}
