package ai.ravenroot.core.runtime.builtin;

import ai.ravenroot.core.runtime.BehaviorEnvironment;
import ai.ravenroot.core.runtime.NodeBehaviorFactory;
import ai.ravenroot.api.publication.PublicationAuditSink;
import ai.ravenroot.api.publication.PublicationPolicyResolver;
import ai.ravenroot.core.publication.PublicationBoundaryGuard;
import ai.ravenroot.core.publication.StandardPublicationPolicyEvaluator;

import java.util.List;

/**
 * Canonical lightweight catalog shipped by the core.
 *
 * <h2>No node here reaches a model (ADR 0029)</h2>
 * <p>{@code llm-prompt} and {@code agent} used to be composed in this list, from
 * {@code environment.modelProviders()} and {@code environment.agentRuntimes()}. A vendor-facing node
 * belongs in a plugin bundle an operator
 * compiles and installs, not in the catalog every installation shows. The two registries stay on
 * {@link BehaviorEnvironment} — removing them would break every embedder's source and binary
 * compatibility for a change about node types (the compatibility boundary) — and this list simply no longer
 * reads them.</p>
 *
 * <p>An embedder that wants a generative node supplies the factory as well as the provider:
 * {@code BehaviorRegistry.standard(environment).registerFactory(...)}. What the core stopped
 * supplying is the factory, not the seam.</p>
 */
public final class StandardBehaviorFactories {
    private StandardBehaviorFactories() {
    }

    public static List<NodeBehaviorFactory> all(BehaviorEnvironment environment) {
        return all(environment, PublicationPolicyResolver.none(), PublicationAuditSink.noop());
    }

    /** Core catalog with an operator-owned publication policy boundary. */
    public static List<NodeBehaviorFactory> all(BehaviorEnvironment environment,
                                                PublicationPolicyResolver publicationPolicies,
                                                PublicationAuditSink publicationAudit) {
        return all(environment, publicationPolicies, publicationAudit, null);
    }

    /** Core catalog optionally armed with the durable human-task reference monitor. */
    public static List<NodeBehaviorFactory> all(BehaviorEnvironment environment,
                                                PublicationPolicyResolver publicationPolicies,
                                                PublicationAuditSink publicationAudit,
                                                ai.ravenroot.core.humantask.HumanTaskService humanTasks) {
        return List.of(
                new LogNodeBehaviorFactory(),
                new DelayNodeBehaviorFactory(),
                new HumanTaskNodeBehaviorFactory(humanTasks),
                new TemplateNodeBehaviorFactory(),
                new JsonParseNodeBehaviorFactory(),
                new CelTransformNodeBehaviorFactory(),
                new CelDecisionNodeBehaviorFactory(),
                new JsonPathNodeBehaviorFactory(),
                new HttpRequestNodeBehaviorFactory(environment.outboundHttpPolicy(), environment.credentials(),
                        environment.toolPolicy()),
                new ProgramNodeBehaviorFactory(environment.artifacts(), environment.programRuntime(),
                        environment.toolPolicy()),
                new BoundaryGuardNodeBehaviorFactory(
                        new PublicationBoundaryGuard(publicationPolicies, new StandardPublicationPolicyEvaluator()),
                        publicationAudit));
    }
}
