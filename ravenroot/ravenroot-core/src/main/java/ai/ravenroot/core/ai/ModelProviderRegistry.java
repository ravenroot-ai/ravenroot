package ai.ravenroot.core.ai;

import ai.ravenroot.api.ai.ModelProvider;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Explicit provider composition; credentials and vendor SDKs remain outside the core.
 *
 * <h2>No behaviour shipped by the core reads this registry any more (ADR 0029)</h2>
 * <p>Stated here rather than left to be discovered by grepping for callers and being surprised.
 * {@code LlmPromptNodeBehaviorFactory} was the only one, and it left the core with the node type it
 * served. This registry is now purely the <em>embedding</em> seam: an application
 * that embeds Ravenroot as a library composes its own {@link
 * ai.ravenroot.core.runtime.BehaviorEnvironment}, registers a {@link ai.ravenroot.api.ai.ModelProvider}
 * here and supplies the behaviour factory that reads it, through
 * {@code BehaviorRegistry.registerFactory(...)}. A plugin bundle does not come this way: it reaches a
 * model through the managed HTTP channel and implements {@code NodeBehavior}, not {@code
 * ModelProvider}.</p>
 *
 * <h2>Released artifact boundary</h2>
 * <p>A call to {@link #register} reachable from {@code RavenrootServerMain#main} or {@code
 * RavenrootCliMain#main} violates the artifact boundary. <b>The reason is not that it would arm a node in the
 * shipped catalogue</b> — there is no such node in the artefact to arm. The reason is that
 * the released artefact must not compose a model adapter into itself at all: the embedding seam is
 * supplied from outside the artefact or it is not supplied. See {@code
 * ReleaseArtifactBoundaryChecks#registrationReachabilityViolations} and ADR 0017.</p>
 */
public final class ModelProviderRegistry {
    private final Map<String, ModelProvider> providers = new ConcurrentHashMap<>();

    public ModelProviderRegistry register(ModelProvider provider) {
        if (provider == null || provider.id() == null || provider.id().isBlank()) {
            throw new IllegalArgumentException("Model provider and id are required");
        }
        providers.put(provider.id(), provider);
        return this;
    }

    public Optional<ModelProvider> find(String id) {
        return Optional.ofNullable(providers.get(id));
    }
}
