package nl.ferron.copilotcontextbridge.context

import nl.ferron.copilotcontextbridge.model.RelationType

/** Resolver identities associated with a discovered relationship. */
internal fun RelationType.contextResolvers(): Set<String> = ContextResolverRegistry.resolversFor(this)
