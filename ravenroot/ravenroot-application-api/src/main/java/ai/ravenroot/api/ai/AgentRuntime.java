package ai.ravenroot.api.ai;

import java.util.concurrent.CompletionStage;

/**
 * Runs an agent session while Ravenroot retains ownership of graph and actor lifecycle.
 *
 * <h2>This is the <em>embedding</em> surface, and nothing else (ADR 0029)</h2>
 * <p>The {@code agent} node that resolved against this interface left the core catalog on 29 August
 * 2026 together with {@code llm-prompt}, so <b>no artefact this project ships calls it any more.</b>
 * It remains for an application that <em>embeds</em> Ravenroot as a library: that application
 * composes its own {@code BehaviorEnvironment}, registers an implementation here through
 * {@code AgentRuntimeRegistry}, and supplies the behaviour factory that reads it through
 * {@code BehaviorRegistry.registerFactory(...)}. The seam is intact; only the factory is gone.</p>
 *
 * <p>A plugin bundle does not arrive this way: it reaches a model through the managed HTTP channel
 * and implements {@code NodeBehavior}, not this interface. See {@link ModelProvider} for the same
 * point stated on the other half of this SPI.</p>
 *
 * <h2>Implementations must be safe for concurrent use (ADR 0024 §3)</h2>
 * <p>One runtime serves every agent node, and several invocations of the same node run at the same time. Conversation state, tool registries and scratch buffers must therefore be scoped to the invocation rather than to the runtime. State kept on the runtime is shared between concurrent agent runs, which is a correctness problem before it is a performance one: two runs would see each other's context.</p>
 *
 * <p>This is stated rather than newly imposed. Nothing here was ever documented as single-threaded;
 * it was true by accident, because one logical graph node was backed by one actor and an actor
 * handles one message at a time. ADR 0024 removes that serialisation deliberately, so an
 * implementation that relied on it is now racy. The implementations in this repository are already
 * safe; a third-party one never had a rule to follow, so here it is.</p>
 */
public interface AgentRuntime {
/**
 * Identifies this runtime implementation for diagnostics and response metadata.
 * @return a stable runtime identifier
 */
    String id();

/**
 * Starts a bounded agent delegation.
 * @param request validated delegation request, including its trusted tool-policy evaluator
 * @return a stage completing with the runtime response or exceptionally when the delegation fails
 */
    CompletionStage<AgentResponse> invoke(AgentRequest request);
}
