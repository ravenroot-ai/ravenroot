package ai.ravenroot.api.ai;

import java.util.concurrent.CompletionStage;

/**
 * Vendor-neutral boundary implemented by LLM adapters outside the core.
 *
 * <h2>This is the <em>embedding</em> surface, and nothing else (ADR 0029)</h2>
 * <p>Stated here rather than left to be inferred from who happens to call it. {@code llm-prompt} and
 * {@code agent} left the core catalog, and with them went the only two behaviours
 * the product shipped that read this interface. <b>No artefact this project ships calls it any
 * more.</b> What it is for is an application that <em>embeds</em> Ravenroot as a library: such an
 * application composes its own {@code BehaviorEnvironment}, registers an implementation of this
 * interface in {@code ModelProviderRegistry}, and supplies the behaviour factory that reads it
 * through {@code BehaviorRegistry.registerFactory(...)}. What the core stopped supplying is the
 * factory, not the seam.</p>
 *
 * <p><b>A plugin bundle is not this route.</b> A bundle node reaches a model through the managed HTTP
 * channel and implements {@code NodeBehavior}; it neither implements this interface nor passes
 * through {@code ModelProviderRegistry}. Reaching for this interface because a node needs a model is
 * therefore the wrong seam unless the caller is an embedder.</p>
 *
 * <p>That this interface is kept alive largely by its own tests is a real cost of the decision, and
 * it is recorded as such in ADR 0029 rather than absorbed. The counterpart is that P1/P2/P3/P6 of the
 * release gate, and the anti-false-green guard they all rest on, keep working unchanged: that guard
 * requires this type to be <em>present</em> in the built artefact, so removing it would not weaken
 * the gate quietly — it would fail the build, which is the better of the two outcomes but not a
 * change anyone should make by accident.</p>
 *
 * <h2>Implementations must be safe for concurrent use (ADR 0024 §3)</h2>
 * <p>One provider instance is shared by every node that resolves it, and those nodes are invoked concurrently, so one provider may be asked for several completions at once. Connection pools, HTTP clients and rate limiters held here are shared state and must carry their own synchronisation; per-request state belongs in local variables.</p>
 *
 * <p>This is stated rather than newly imposed. Nothing here was ever documented as single-threaded;
 * it was true by accident, because one logical graph node was backed by one actor and an actor
 * handles one message at a time. ADR 0024 removes that serialisation deliberately, so an
 * implementation that relied on it is now racy. The implementations in this repository are already
 * safe; a third-party one never had a rule to follow, so here it is.</p>
 */
public interface ModelProvider {
/**
 * Identifies the provider implementation used for a completion.
 * @return a stable provider identifier suitable for response metadata and diagnostics
 */
    String id();

/**
 * Requests one model completion without exposing vendor-specific transport types.
 * @param request validated prompt, execution context, credential reference, and provider parameters
 * @return a stage completing with the normalized provider response or exceptionally on provider failure
 */
    CompletionStage<ModelResponse> generate(ModelRequest request);
}
