package ai.ravenroot.api.ai;

import ai.ravenroot.api.security.ToolPolicy;

import java.util.Map;
import java.util.UUID;

/**
 * One bounded delegation to a configured agent runtime.
 *
 * <h2>There is exactly one tool authority (SEC-09)</h2>
 * <p>{@code toolPolicyEvaluator} is the trusted {@link ToolPolicy} composed into the runtime through
 * {@code BehaviorEnvironment}. It is the <strong>only</strong> thing on this request that decides
 * what an agent may call.</p>
 *
 * <p>This record previously also carried a {@code toolPolicy} <em>name</em> read straight from a
 * GraphML node property, alongside the trusted evaluator, with nothing stating which one was
 * authoritative. Nothing consumed it — no {@code AgentRuntime} implementation exists yet — so it was
 * latent rather than live, but it was a policy identifier chosen by graph content sitting on the same
 * record as the policy itself, and the first runtime to resolve a policy by that name would have let
 * a graph select its own permissions. The component is removed rather than documented as inert:
 * carrying a value nobody may act on is how it eventually gets acted on.</p>
 *
 * <p>An agent runtime that needs to know which policy governs it must ask the evaluator. A graph that
 * still declares a {@code toolPolicy} property is not rejected — it is simply an unknown property
 * now, preserved on export and never interpreted, which is exactly SEC-09's treatment of graph
 * content that is not part of a behavior's trusted schema.</p>
 *
 * <h2>{@code credentialReference}</h2>
 * <p>Added because the core's {@code agent} node declared a {@code SECRET_REFERENCE} property, as its
 * {@code llm-prompt} sibling always had, carrying it to the adapter through {@code ModelRequest}. Both
 * node types left the core (ADR 0029) and this component stays: the record is the embedding
 * surface, so the next behaviour to build one is an embedder's or a bundle's, and a component removed
 * here would have to be reinvented there. It is a <b>reference, never a value</b> — the runtime
 * resolves it through the {@code CredentialResolver} its own composition gives it, exactly as a
 * {@code ModelProvider} does.</p>
 *
 * <p>It was added rather than left off, and the paragraph above is why: this record's own history is
 * the argument. The {@code toolPolicy} name was removed because "carrying a value nobody may act on
 * is how it eventually gets acted on" — and the mirror of that rule applies here. Declaring the
 * property on the node while dropping it on the floor at this boundary would have been the same
 * defect wearing the other face: an author would select a credential in the editor, save it, export
 * it, and no runtime could ever receive it. The two cases differ in which direction the value must
 * not be silently inert, not in the principle.</p>
 * @param executionId non-null execution that owns the invocation
 * @param nodeId non-blank graph node that requested the delegation
 * @param agent optional configured agent name; normalized to an empty string
 * @param objective non-blank work objective presented to the runtime
 * @param payload application payload available to the agent
 * @param sessionId optional conversation-session identifier; normalized to an empty string
 * @param credentialReference optional secret reference, never a credential value; preserved verbatim
 * @param toolPolicyEvaluator trusted evaluator for every tool request; an absent evaluator denies all tools
 * @param parameters optional immutable provider-specific parameters
 */
public record AgentRequest(
        UUID executionId,
        String nodeId,
        String agent,
        String objective,
        Object payload,
        String sessionId,
        String credentialReference,
        ToolPolicy toolPolicyEvaluator,
        Map<String, Object> parameters) {

/**
 * Validates required identity and objective fields, normalizes optional text, and freezes parameters.
 */
    public AgentRequest {
        if (executionId == null) throw new IllegalArgumentException("executionId cannot be null");
        if (nodeId == null || nodeId.isBlank()) throw new IllegalArgumentException("nodeId cannot be blank");
        agent = agent == null ? "" : agent;
        if (objective == null || objective.isBlank()) throw new IllegalArgumentException("objective cannot be blank");
        sessionId = sessionId == null ? "" : sessionId;
        // Normalised to empty and NOT trimmed, the same treatment ModelRequest gives it: a reference
        // is read verbatim because trimming maps three distinct references onto one key. See
        // NodePropertyType.SECRET_REFERENCE and SecretReferenceReaderFidelityTest.
        credentialReference = credentialReference == null ? "" : credentialReference;
        toolPolicyEvaluator = toolPolicyEvaluator == null ? ToolPolicy.denyAll() : toolPolicyEvaluator;
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
