package ai.ravenroot.sample;

import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.execution.ExecutionEngines;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.GraphExecutionResult;
import ai.ravenroot.core.runtime.GraphRunner;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Minimal third-party application that embeds the framework-independent Ravenroot core. */
public final class EmbeddedSample {

    private EmbeddedSample() {
    }

    public static void main(String[] args) throws Exception {
        var result = execute(args.length == 0 ? "hello ravenroot" : String.join(" ", args));
        System.out.printf("Ravenroot embedded result: %s; visited nodes: %s%n",
                result.payload(), result.visitedNodes());
    }

    static GraphExecutionResult execute(String payload) throws Exception {
        var registry = new BehaviorRegistry().register("uppercase-text", message ->
                CompletableFuture.completedFuture(NodeResult.continueWith(
                        message.payload().toString().toUpperCase())));
        String engineId = System.getenv().getOrDefault("RAVENROOT_ENGINE", "pekko");
        try (var manager = sampleManager();
             var engine = ExecutionEngines.create(engineId, "ravenroot-embedded-sample");
             var runner = new GraphRunner(manager, engine, registry, new ExecutionMonitor())) {
            return runner.execute(callerIdentity(), payload).toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    /**
     * The identity this execution runs under.
     *
     * <h2>Replace this method. It is the one part of the sample you must not copy verbatim.</h2>
     * <p>An embedding application is the <em>trusted adapter at ingress</em>: Ravenroot does not
     * authenticate anyone on your behalf, so whatever this method returns is taken as already
     * proven. Here there is nothing to prove — the sample is a command-line program run by a
     * developer — so it states a fixed local identity. In a real host, the values below come from
     * the request you have <strong>already authenticated</strong> (an OIDC token, an mTLS peer
     * certificate, a session), and fabricating them the way this method does would mean every caller
     * shares one identity and your audit trail records that identity instead of the real one.</p>
     *
     * <p>Note what is built and how. {@link SecurityContext#of(RequestContext)} is the supported way
     * to obtain one, and the projection is the point: {@code RequestContext} carries roles and
     * scopes, {@code SecurityContext} deliberately does not. Authorization is decided once, at
     * ingress, and what travels inward is only identity for scoping and audit — so node and tool
     * code cannot re-decide a question already answered. Building a {@code SecurityContext} directly
     * skips that projection, and the core's own test fixtures are quarantined in test scope
     * precisely so that production code has exactly one way in.</p>
     *
     * <p>{@link PrincipalType#WORKLOAD} rather than {@code USER} because this program acts as
     * itself, not on behalf of a signed-in human. Claiming {@code USER} here would put a person who
     * does not exist into the audit record.</p>
     */
    private static SecurityContext callerIdentity() {
        var authenticatedAtIngress = new RequestContext(
                UUID.randomUUID().toString(),
                "ravenroot-embedded-sample",
                PrincipalType.WORKLOAD,
                "urn:ravenroot:sample:embedded-host",
                "sample-tenant",
                Set.of(),
                Set.of());
        return SecurityContext.of(authenticatedAtIngress);
    }

    static GraphDefinition sampleGraph() {
        try (var manager = sampleManager()) {
            return manager.definition();
        }
    }

    private static GraphManager sampleManager() {
        try (var input = EmbeddedSample.class.getResourceAsStream("/ravenroot-sample.graphml")) {
            if (input == null) {
                throw new IllegalStateException("Missing ravenroot-sample.graphml");
            }
            return GraphManager.readGraphMl(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot close sample GraphML", exception);
        }
    }
}
