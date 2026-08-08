package nl.ferron.copilotcontextbridge.context

import nl.ferron.copilotcontextbridge.model.AttachmentKind
import nl.ferron.copilotcontextbridge.model.AttachmentPlan
import nl.ferron.copilotcontextbridge.model.ContextCandidate
import nl.ferron.copilotcontextbridge.model.PlannedAttachment
import nl.ferron.copilotcontextbridge.model.sourceKey
import nl.ferron.copilotcontextbridge.settings.ContextPolicyState
import nl.ferron.copilotcontextbridge.settings.CopilotTarget
import nl.ferron.copilotcontextbridge.staging.StagedFilenameService
import nl.ferron.copilotcontextbridge.staging.TextFileSupport
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** Builds a deterministic attachment plan; all originals remain traceable through the master index. */
object ContextAttachmentPacker {
    const val DEFAULT_MAX_BUNDLE_BYTES = 80_000L
    const val DEFAULT_MAX_BUNDLE_CHARACTERS = 80_000
    const val DEFAULT_ESTIMATED_MAX_BUNDLE_TOKENS = 20_000
    const val DEFAULT_MAX_SOURCES_PER_BUNDLE = 15
    private const val SECTION_OVERHEAD_CHARACTERS = 700L

    fun plan(
        candidates: Collection<ContextCandidate>,
        policy: ContextPolicyState,
        maximumAttachments: Int = policy.maxAttachments,
    ): AttachmentPlan {
        val pinned = candidates.filter { it.pinned }.sortedBy { it.relativePath.lowercase() }
        val automatic = candidates.filterNot { it.pinned }.sortedWith(candidateOrder())
        val attachments = mutableListOf<PlannedAttachment>()
        val pinnedNames = StagedFilenameService.namesFor(pinned.map { stagedSourcePath(it) })
        pinned.forEach { candidate ->
            val converted = needsTextCopy(candidate, policy)
            attachments +=
                PlannedAttachment(
                    pinnedNames.getValue(stagedSourcePath(candidate)) + if (converted) ".txt" else "",
                    AttachmentKind.PINNED_ORIGINAL,
                    listOf(candidate),
                    "pinned",
                    converted,
                )
        }
        val automaticAttachments = mutableListOf<PlannedAttachment>()
        if (policy.bundleAutomaticContext) {
            val (separate, bundlable) = automatic.partition { shouldKeepSeparate(it, policy) }
            val separateNames = StagedFilenameService.namesFor(separate.map(::stagedSourcePath))
            separate.forEach { candidate ->
                val converted = needsTextCopy(candidate, policy)
                automaticAttachments +=
                    PlannedAttachment(
                        separateNames.getValue(stagedSourcePath(candidate)) + if (converted) ".txt" else "",
                        AttachmentKind.PINNED_ORIGINAL,
                        listOf(candidate),
                        "automatic-separate",
                        converted,
                    )
            }
            bundlable
                .groupBy { groupFor(it, policy) }
                .toSortedMap(compareBy<String>({ groupRank(it) }, { it.lowercase() }))
                .forEach { (group, groupCandidates) ->
                    split(groupCandidates.sortedWith(candidateOrder()), group, policy).forEachIndexed { index, chunk ->
                        automaticAttachments +=
                            PlannedAttachment(
                                stagedName =
                                    "${groupRank(group).toString().padStart(2, '0')}_${groupPrefix(group)}_" +
                                        "${(index + 1).toString().padStart(2, '0')}.md",
                                kind = AttachmentKind.AUTOMATIC_BUNDLE,
                                candidates = chunk,
                                bundleGroup = group,
                            )
                    }
                }
        } else {
            val automaticNames = StagedFilenameService.namesFor(automatic.map(::stagedSourcePath))
            automatic.forEach { candidate ->
                val converted = needsTextCopy(candidate, policy)
                automaticAttachments +=
                    PlannedAttachment(
                        automaticNames.getValue(stagedSourcePath(candidate)) + if (converted) ".txt" else "",
                        AttachmentKind.PINNED_ORIGINAL,
                        listOf(candidate),
                        "automatic",
                        converted,
                    )
            }
        }
        val automaticBudget = (maximumAttachments.coerceIn(1, 20) - 1 - pinned.size).coerceAtLeast(0)
        val selectedAutomatic =
            automaticAttachments
                .sortedWith(
                    compareByDescending<PlannedAttachment> { it.candidates.maxOfOrNull(ContextCandidate::score) ?: Int.MIN_VALUE }
                        .thenBy { groupRank(it.bundleGroup) }
                        .thenBy { it.stagedName.lowercase() },
                ).take(automaticBudget)
        attachments += selectedAutomatic
        val includedPaths = attachments.flatMapTo(hashSetOf()) { it.candidates.map(ContextCandidate::sourceKey) }
        val omitted = automatic.filterNot { it.sourceKey in includedPaths }
        val uniqueAttachments = makeNamesUnique(attachments)
        val mapping =
            uniqueAttachments
                .flatMap { attachment ->
                    attachment.candidates.map { it.sourceKey to attachment.stagedName }
                }.toMap()
        return AttachmentPlan(uniqueAttachments, mapping, omitted)
    }

    private fun split(
        candidates: List<ContextCandidate>,
        group: String,
        policy: ContextPolicyState,
    ): List<List<ContextCandidate>> {
        val maxFiles =
            policy.rules.filter { it.bundleGroup == group && it.enabled }.minOfOrNull { it.maxFiles.coerceIn(1, 100) }
                ?: DEFAULT_MAX_SOURCES_PER_BUNDLE
        val maxBytes =
            policy.rules
                .filter { it.bundleGroup == group && it.enabled }
                .mapNotNull { it.parameters["maxBundleBytes"]?.toLongOrNull() }
                .minOrNull() ?: DEFAULT_MAX_BUNDLE_BYTES
        val maxCharacters =
            minOf(
                policy.maxBundleCharacters.takeIf { it > 0 } ?: DEFAULT_MAX_BUNDLE_CHARACTERS,
                policy.rules
                    .filter { it.bundleGroup == group && it.enabled }
                    .mapNotNull { it.parameters["maxBundleCharacters"]?.toIntOrNull()?.takeIf { limit -> limit > 0 } }
                    .minOrNull() ?: Int.MAX_VALUE,
            ).toLong()
        val maxTokens =
            minOf(
                policy.estimatedMaxBundleTokens.takeIf { it > 0 } ?: DEFAULT_ESTIMATED_MAX_BUNDLE_TOKENS,
                policy.rules
                    .filter { it.bundleGroup == group && it.enabled }
                    .mapNotNull { it.parameters["estimatedMaxBundleTokens"]?.toIntOrNull()?.takeIf { limit -> limit > 0 } }
                    .minOrNull() ?: Int.MAX_VALUE,
            ).toLong()
        val chunks = mutableListOf<MutableList<ContextCandidate>>()
        var current = mutableListOf<ContextCandidate>()
        var bytes = 0L
        var characters = 0L
        candidates.forEach { candidate ->
            val estimatedBytes = candidate.size + SECTION_OVERHEAD_CHARACTERS
            val estimatedCharacters = estimatedCharacters(candidate) + SECTION_OVERHEAD_CHARACTERS
            val prospectiveCharacters = characters + estimatedCharacters
            val prospectiveTokens = (prospectiveCharacters + 3L) / 4L
            if (
                current.isNotEmpty() &&
                (
                    current.size >= maxFiles ||
                        bytes + estimatedBytes > maxBytes ||
                        prospectiveCharacters > maxCharacters ||
                        prospectiveTokens > maxTokens
                )
            ) {
                chunks += current
                current = mutableListOf()
                bytes = 0L
                characters = 0L
            }
            current += candidate
            bytes += estimatedBytes
            characters += estimatedCharacters
        }
        if (current.isNotEmpty()) chunks += current
        return chunks
    }

    private fun candidateOrder() =
        compareByDescending<ContextCandidate> { it.score }
            .thenBy { it.depth }
            .thenBy { it.repositoryId.lowercase() }
            .thenBy { it.relativePath.lowercase() }

    private fun stagedSourcePath(candidate: ContextCandidate): String =
        if (candidate.repositoryId.isBlank()) candidate.relativePath else "${candidate.repositoryId}/${candidate.relativePath}"

    private fun groupFor(
        candidate: ContextCandidate,
        policy: ContextPolicyState,
    ): String {
        val resolver = resolverFor(candidate)
        return policy.rules
            .firstOrNull { it.resolver == resolver && it.enabled }
            ?.bundleGroup
            ?.ifBlank { null }
            ?: "references"
    }

    private fun groupPrefix(group: String): String = "AUTO_" + group.uppercase().replace(Regex("[^A-Z0-9]+"), "_").trim('_')

    private fun groupRank(group: String): Int =
        when (group.lowercase()) {
            "tests" -> 1
            "dependencies" -> 2
            "configuration" -> 3
            "instructions" -> 4
            "references" -> 5
            else -> 6
        }

    private fun shouldKeepSeparate(
        candidate: ContextCandidate,
        policy: ContextPolicyState,
    ): Boolean {
        val resolver = resolverFor(candidate)
        return policy.rules.any { it.enabled && it.resolver == resolver && it.keepSeparate }
    }

    private fun resolverFor(candidate: ContextCandidate): String = ContextResolverRegistry.primaryResolver(candidate)

    private fun estimatedCharacters(candidate: ContextCandidate): Long =
        runCatching {
            if (Files.isRegularFile(candidate.absolutePath) && candidate.size <= DEFAULT_MAX_BUNDLE_BYTES * 20) {
                Files.readString(candidate.absolutePath, StandardCharsets.UTF_8).length.toLong()
            } else {
                candidate.size
            }
        }.getOrDefault(candidate.size)

    private fun makeNamesUnique(attachments: List<PlannedAttachment>): List<PlannedAttachment> {
        val used = hashSetOf<String>()
        return attachments.map { attachment ->
            var name = attachment.stagedName
            var suffix = 2
            while (!used.add(name.lowercase())) {
                val dot = attachment.stagedName.lastIndexOf('.')
                name =
                    if (dot > 0) {
                        attachment.stagedName.take(dot) + "__$suffix" + attachment.stagedName.drop(dot)
                    } else {
                        attachment.stagedName + "__$suffix"
                    }
                suffix++
            }
            attachment.copy(stagedName = name)
        }
    }

    private fun needsTextCopy(
        candidate: ContextCandidate,
        policy: ContextPolicyState,
    ): Boolean =
        policy.target == CopilotTarget.MICROSOFT_365.name &&
            TextFileSupport.requiresMicrosoft365TextCopy(candidate.relativePath)
}
