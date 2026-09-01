package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.AuthorizedRavenrootApplication;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.programming.ArtifactProvenanceVerifier;
import ai.ravenroot.api.programming.ArtifactState;
import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.programming.ProgramAdmission;
import ai.ravenroot.api.programming.ProgramRequest;
import ai.ravenroot.api.programming.ProgramRuntime;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.api.security.ToolDecision;
import ai.ravenroot.core.ai.AgentRuntimeRegistry;
import ai.ravenroot.core.ai.ModelProviderRegistry;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import ai.ravenroot.core.security.OutboundHttpPolicy;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-12 and SEC-25, at the node the graph actually runs.
 *
 * <p>{@code ProgramNodeBehaviorFactory} had <b>no test of any kind</b> before this file — the sole
 * state gate protecting artifact execution had never been shown to work, in either direction. These
 * tests exist so the gate can fail, not merely be present.
 *
 * <p>The interleaving is driven from inside the runtime double, which is the honest place for it: a
 * real adapter redeems in the middle of its own asynchronous work, and this reproduces that ordering
 * exactly — admit, retire, redeem — with ordinary sequential calls and no clock.
 */
class ProgramNodeAdmissionTest {
    /**
     * Stated rather than defaulted. The registry now refuses to release any source
     * unless a provenance verifier is configured, so a test about the admission GATES has to say
     * explicitly that provenance is not what it is testing — otherwise every refusal below could be
     * the verifier's and the gate under test would prove nothing.
     */
    private static final ArtifactProvenanceVerifier VERIFIED = artifact -> { };

    private static final String OWNER = AuthorizedRavenrootApplication.OWNER_TENANT_METADATA;

    /**
     * The defect, end to end. The retirement lands after the node has admitted the artifact and
     * before the runtime redeems it — the window that is ~671 ms idle and ~5.4 s at 8x load in the
     * real adapter (measured on this path).
     */
    @Test
    void anArtifactRetiredWhileTheSandboxIsStartingIsNotExecuted() {
        var artifacts = new InMemoryArtifactRegistry(VERIFIED);
        GeneratedArtifact artifact = active(artifacts, "tenant-a");
        var runtime = new RedeemingRuntime(admission ->
                artifacts.transition(artifact.id(), ArtifactState.ACTIVE, ArtifactState.RETIRED, Map.of()));

        var failure = assertThrows(ExecutionException.class,
                () -> run(artifacts, runtime, artifact.id(), TestIdentities.TENANT_A));

        var refused = assertInstanceOf(SecurityException.class, failure.getCause());
        assertTrue(refused.getMessage().contains("not ACTIVE"), refused.getMessage());
        assertEquals(0, runtime.executed.get(), "the source must never have been handed to a sandbox");
    }

    /** SEC-25 tenant isolation: the artifact is ACTIVE, so the state gate cannot reject it first. */
    @Test
    void oneTenantCannotExecuteAnotherTenantsActiveArtifact() {
        var artifacts = new InMemoryArtifactRegistry(VERIFIED);
        GeneratedArtifact artifact = active(artifacts, "tenant-b");
        var runtime = new RedeemingRuntime(admission -> { });

        var refused = assertThrows(SecurityException.class,
                () -> run(artifacts, runtime, artifact.id(), TestIdentities.TENANT_A));

        assertTrue(refused.getMessage().contains("content is not ready for tenant"), refused.getMessage());
        assertEquals(0, runtime.executed.get(), "tenant-a must not reach tenant-b's source");
    }

    /** The happy path, so every refusal above is a refusal rather than a broken fixture. */
    @Test
    void theOwningTenantExecutesItsOwnActiveArtifact() throws Exception {
        var artifacts = new InMemoryArtifactRegistry(VERIFIED);
        GeneratedArtifact artifact = active(artifacts, "tenant-a");
        var runtime = new RedeemingRuntime(admission -> { });

        NodeResult result = run(artifacts, runtime, artifact.id(), TestIdentities.TENANT_A);

        assertEquals("input-ran", result.payload());
        assertEquals(1, runtime.executed.get());
        assertEquals(artifact.id(), result.attributes().get("program.artifact"));
        assertEquals(artifact.sha256(), result.attributes().get("program.sha256"));
    }

    /** Content resolution is server-owned: a descriptor cannot select a non-ACTIVE registry row. */
    @Test
    void nonActiveContentIsRefusedBeforeTheRuntimeReceivesSource() {
        var artifacts = new InMemoryArtifactRegistry(VERIFIED);
        GeneratedArtifact artifact = registered(artifacts, "tenant-a");
        artifacts.transition(artifact.id(), ArtifactState.GENERATED, ArtifactState.VALIDATED);
        var runtime = new RedeemingRuntime(admission -> { });

        var refused = assertThrows(SecurityException.class,
                () -> run(artifacts, runtime, artifact.id(), TestIdentities.TENANT_A));

        assertTrue(refused.getMessage().contains("content is not ready for tenant"), refused.getMessage());
        assertEquals(0, runtime.redeemed.get(), "unqualified content must not reach the runtime");
    }

    /** A runtime double that redeems, as the contract requires, and lets a test act in between. */
    private static final class RedeemingRuntime implements ProgramRuntime {
        private final java.util.function.Consumer<ProgramAdmission> betweenAdmissionAndRedemption;
        final AtomicInteger redeemed = new AtomicInteger();
        final AtomicInteger executed = new AtomicInteger();
        final AtomicReference<GeneratedArtifact> source = new AtomicReference<>();

        private RedeemingRuntime(java.util.function.Consumer<ProgramAdmission> between) {
            this.betweenAdmissionAndRedemption = between;
        }

        @Override
        public String id() {
            return "redeeming-test-runtime";
        }

        @Override
        public CompletionStage<Object> execute(ProgramAdmission admission, ProgramRequest request) {
            betweenAdmissionAndRedemption.accept(admission);
            redeemed.incrementAndGet();
            GeneratedArtifact redeemedArtifact;
            try {
                redeemedArtifact = admission.redeem();
            } catch (SecurityException refused) {
                return CompletableFuture.failedFuture(refused);
            } finally {
                admission.close();
            }
            source.set(redeemedArtifact);
            executed.incrementAndGet();
            return CompletableFuture.completedFuture(request.payload() + "-ran");
        }
    }

    /**
     * Does {@code RavenrootCliMain} have the server's provenance problem too?
     *
     * <p>It has the same wiring — {@code BehaviorEnvironment.safeDefaults()} installs the refusing
     * verifier with no way to configure it — but not the same defect, and these two tests are why.
     * {@code safeDefaults()} also installs {@link ai.ravenroot.api.security.ToolPolicy#denyAll()} and
     * {@link DisabledProgramRuntime}, and <b>either one alone</b> stops a {@code program} node before
     * {@code redeem()} is reached, which is the only place the verifier is consulted. Giving the CLI
     * the server's opt-in today would therefore change nothing observable while advertising that it
     * had — the worse outcome, because an operator who sets it would reasonably conclude program
     * nodes now run.
     *
     * <p>This one covers the first gate. Note that {@code admitForExecution} has already returned a
     * handle by the time authorization refuses: the handle is not the release, so the verifier
     * counter staying at zero is the assertion that carries the claim.
     */
    @Test
    void aDeniedToolPolicyRefusesBeforeProvenanceIsEverConsulted() {
        var verifications = new AtomicInteger();
        var artifacts = new InMemoryArtifactRegistry(artifact -> verifications.incrementAndGet());
        GeneratedArtifact artifact = active(artifacts, "tenant-a");
        var runtime = new RedeemingRuntime(admission -> { });

        var refused = assertThrows(SecurityException.class,
                () -> run(artifacts, runtime, artifact.id(), TestIdentities.TENANT_A,
                        ai.ravenroot.api.security.ToolPolicy.denyAll()));

        assertTrue(refused.getMessage().contains("Tool execution is disabled by default"),
                refused.getMessage());
        assertEquals(0, verifications.get(), "provenance must never have been consulted");
        assertEquals(0, runtime.executed.get());
    }

    /**
     * The second gate, independently: even with every tool allowed, {@link DisabledProgramRuntime}
     * fails without ever calling {@code redeem()}, so the verifier is still never reached. Asserted
     * separately from the tool policy because the CLI has both, and a conclusion resting on only one
     * of them would be wrong the moment the other changed.
     */
    @Test
    void theDisabledRuntimeNeverRedeemsSoProvenanceIsNeverConsulted() {
        var verifications = new AtomicInteger();
        var artifacts = new InMemoryArtifactRegistry(artifact -> verifications.incrementAndGet());
        GeneratedArtifact artifact = active(artifacts, "tenant-a");

        var failure = assertThrows(ExecutionException.class,
                () -> run(artifacts, new DisabledProgramRuntime(), artifact.id(), TestIdentities.TENANT_A));

        var unavailable = assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertTrue(unavailable.getMessage().contains("Programmable-node execution is disabled"),
                unavailable.getMessage());
        assertEquals(0, verifications.get(), "provenance must never have been consulted");
    }

    private static NodeResult run(InMemoryArtifactRegistry artifacts, ProgramRuntime runtime, String artifactId,
                                  SecurityContext identity) throws Exception {
        return run(artifacts, runtime, artifactId, identity,
                invocation -> new ToolDecision(ToolDecision.Disposition.ALLOW, "test", ""));
    }

    private static NodeResult run(InMemoryArtifactRegistry artifacts, ProgramRuntime runtime, String artifactId,
                                  SecurityContext identity, ai.ravenroot.api.security.ToolPolicy toolPolicy)
            throws Exception {
        var registry = BehaviorRegistry.standard(new BehaviorEnvironment(new ModelProviderRegistry(),
                new AgentRuntimeRegistry(), artifacts, runtime, ignored -> Optional.empty(),
                toolPolicy,
                OutboundHttpPolicy.disabled()));
        GeneratedArtifact artifact = artifacts.find(artifactId).orElseThrow();
        var node = new GraphNode("program-node", NodeKind.BEHAVIOR, "program", Map.of(
                "language", artifact.language(), "source", artifact.source(), "artifactId", artifactId));
        return registry.create(node).orElseThrow()
                .handle(new NodeMessage(identity, UUID.randomUUID(), UUID.randomUUID(), node.id(), "input", Map.of()))
                .toCompletableFuture().get();
    }

    private static GeneratedArtifact registered(InMemoryArtifactRegistry artifacts, String owner) {
        return artifacts.create("javascript", "() => 1", Map.of(OWNER, owner));
    }

    private static GeneratedArtifact active(InMemoryArtifactRegistry artifacts, String owner) {
        GeneratedArtifact artifact = registered(artifacts, owner);
        artifacts.transition(artifact.id(), ArtifactState.GENERATED, ArtifactState.VALIDATED);
        artifacts.transition(artifact.id(), ArtifactState.VALIDATED, ArtifactState.TESTED);
        artifacts.transition(artifact.id(), ArtifactState.TESTED, ArtifactState.APPROVED);
        return artifacts.transition(artifact.id(), ArtifactState.APPROVED, ArtifactState.ACTIVE);
    }
}
