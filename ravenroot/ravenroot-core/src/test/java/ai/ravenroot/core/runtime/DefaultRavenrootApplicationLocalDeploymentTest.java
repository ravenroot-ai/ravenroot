package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.application.LocalDeploymentException;
import ai.ravenroot.api.application.LocalDeploymentState;
import ai.ravenroot.api.application.LocalDeploymentStatus;
import ai.ravenroot.api.application.SourceSessionState;
import ai.ravenroot.api.catalog.NodeRuntimeNature;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.deployment.DeploymentAdmissionException;
import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.node.InboundSourceCapable;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Core contract for the tenant-scoped, process-local deployment lifecycle.
 *
 * <p>Deliberately paired with {@code DefaultRavenrootApplicationSourceSessionTest} rather than folded
 * into it: that suite pins source-session behaviour, which the shared registry reimplements and must
 * not have changed. Keeping both green is the evidence that the convergence onto one registry is a
 * convergence and not a replacement.</p>
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class DefaultRavenrootApplicationLocalDeploymentTest {
    private static final SecurityContext TENANT_A = identity("tenant-a");
    private static final SecurityContext TENANT_B = identity("tenant-b");

    /**
     * A deployment differs from a traversal in this respect: a graph with no
     * effective SOURCE is registrable, startable, stoppable, restartable and undeployable. The
     * source-session route refuses this graph outright, which is why the surface had to be widened
     * rather than reused as-is.
     */
    @Test
    void aGraphWithNoSourceIsRegisteredAndControlledThroughItsWholeLifecycle() throws Exception {
        var application = application(new SameThreadExecutionEngine(), new ExecutionMonitor(),
                new RecordingSourceBehavior());
        try {
            LocalDeploymentStatus registered = application.registerLocalDeployment(
                    TENANT_A, "batch-1", graph(NO_SOURCE_GRAPH));
            assertEquals(LocalDeploymentState.REGISTERED, registered.state(),
                    "registration reserves an identity; it must not start anything");
            assertEquals(0, registered.sourceCount());
            assertEquals("LOCAL_PROCESS", LocalDeploymentStatus.SCOPE);

            assertEquals(LocalDeploymentState.READY, command(application.startLocalDeployment(TENANT_A, "batch-1")));
            assertEquals(LocalDeploymentState.READY, command(application.startLocalDeployment(TENANT_A, "batch-1")),
                    "a repeated start is idempotent, not a second activation");

            assertEquals(LocalDeploymentState.STOPPED, command(application.stopLocalDeployment("tenant-a", "batch-1")));
            assertEquals(LocalDeploymentState.STOPPED, command(application.stopLocalDeployment("tenant-a", "batch-1")),
                    "a repeated stop is idempotent");
            assertEquals(LocalDeploymentState.STOPPED,
                    application.localDeployment("tenant-a", "batch-1").orElseThrow().state(),
                    "stop leaves the deployment registered and re-startable");

            assertEquals(LocalDeploymentState.READY,
                    command(application.restartLocalDeployment(TENANT_A, "batch-1")));
            assertEquals(List.of("batch-1"),
                    application.localDeployments("tenant-a").stream().map(LocalDeploymentStatus::deploymentId).toList());

            assertEquals(LocalDeploymentState.STOPPED, command(application.undeployLocalDeployment("tenant-a", "batch-1")));
            assertTrue(application.localDeployment("tenant-a", "batch-1").isEmpty(),
                    "undeploy removes the registration; stop does not");
            assertTrue(application.localDeployments("tenant-a").isEmpty());
            assertTrue(application.undeployLocalDeployment("tenant-a", "batch-1")
                            .toCompletableFuture().get(30, TimeUnit.SECONDS).isEmpty(),
                    "a second undeploy is the same nondisclosing empty answer an unknown id gets");
        } finally {
            application.close();
        }
    }

    /**
     * Restart is a completed stop followed by a start, so a source is bound exactly once afterwards;
     * and undeploy really does stop before it deregisters, rather than dropping a running domain.
     */
    @Test
    void restartRebindsEachSourceExactlyOnceAndUndeployStopsBeforeDeregistering() throws Exception {
        var behavior = new RecordingSourceBehavior();
        var application = application(new SameThreadExecutionEngine(), new ExecutionMonitor(), behavior);
        try {
            application.registerLocalDeployment(TENANT_A, "listener", graph(SOURCE_GRAPH));
            assertEquals(LocalDeploymentState.READY, command(application.startLocalDeployment(TENANT_A, "listener")));
            assertEquals(1, behavior.starts.get());

            assertEquals(LocalDeploymentState.READY, command(application.restartLocalDeployment(TENANT_A, "listener")));
            assertEquals(1, behavior.stops.get(), "restart must retire the old source before binding a new one");
            assertEquals(2, behavior.starts.get(), "restart must leave exactly one live subscription, not two");
            assertEquals(List.of("start", "stop", "start"), behavior.lifecycle,
                    "a restart is an ordered stop-then-start, never the two overlapping");

            assertEquals(LocalDeploymentState.STOPPED, command(application.undeployLocalDeployment("tenant-a", "listener")));
            assertEquals(List.of("start", "stop", "start", "stop"), behavior.lifecycle,
                    "undeploy stops the source before the registration disappears");
            assertTrue(application.localDeployment("tenant-a", "listener").isEmpty());
        } finally {
            application.close();
        }
    }

    /**
     * Which release hook each command uses is part of the SPI contract, so it is pinned here rather
     * than left to be inferred from the fact that every in-tree source defines
     * {@link InboundSource#shutdown()} as {@link InboundSource#stop()} and cannot tell them apart.
     *
     * <p>The rule the assertions encode: {@code shutdown()} means <em>this deployment will never run
     * again in this process</em>. Stop and restart leave it startable, so they must use
     * {@code stop()}; undeploy removes the registration, so nothing can start again and the resource
     * a source keeps across its own restarts has no later opportunity to be released — the
     * application's own close sweep iterates the registry undeploy has just removed from. A future
     * silent flip in either direction leaks that resource or releases it while a restart is still
     * possible, and this test is what catches it.</p>
     */
    @Test
    void undeployReleasesSourcesThroughShutdownWhileStopAndRestartUseStop() throws Exception {
        var behavior = new ReleaseDistinguishingSourceBehavior();
        var application = application(new SameThreadExecutionEngine(), new ExecutionMonitor(), behavior);
        try {
            application.registerLocalDeployment(TENANT_A, "kept", graph(SOURCE_GRAPH));
            assertEquals(LocalDeploymentState.READY, command(application.startLocalDeployment(TENANT_A, "kept")));

            assertEquals(LocalDeploymentState.STOPPED, command(application.stopLocalDeployment("tenant-a", "kept")));
            assertEquals(List.of("start", "stop"), behavior.lifecycle,
                    "a stopped deployment stays registered and re-startable, so its source is released "
                            + "through stop() and keeps what it needs for that next start");

            assertEquals(LocalDeploymentState.READY, command(application.startLocalDeployment(TENANT_A, "kept")));
            assertEquals(LocalDeploymentState.READY, command(application.restartLocalDeployment(TENANT_A, "kept")));
            assertEquals(List.of("start", "stop", "start", "stop", "start"), behavior.lifecycle,
                    "a restart is a stop-then-start; neither half may tell the source it is finished");
            assertEquals(0, behavior.crossRestartReleases.get(),
                    "nothing a source keeps across its own restarts may be released while it can restart");

            assertEquals(LocalDeploymentState.STOPPED, command(application.undeployLocalDeployment("tenant-a", "kept")));
            assertEquals(List.of("start", "stop", "start", "stop", "start", "shutdown"), behavior.lifecycle,
                    "undeploy deregisters, so no start can follow: the source is released through shutdown()");
            assertEquals(1, behavior.crossRestartReleases.get(),
                    "and released exactly once, at the only moment anything still holds a reference to it");
            assertTrue(application.localDeployment("tenant-a", "kept").isEmpty());
        } finally {
            application.close();
        }
        assertEquals(List.of("start", "stop", "start", "stop", "start", "shutdown"), behavior.lifecycle,
                "closing the application must not release an already-undeployed deployment a second time");
    }

    /** Tenant isolation is structural: a sibling's id is indistinguishable from one that never existed. */
    @Test
    void siblingTenantIdsAndUnknownIdsAreTheSameNondisclosingAnswer() throws Exception {
        var application = application(new SameThreadExecutionEngine(), new ExecutionMonitor(),
                new RecordingSourceBehavior());
        try {
            application.registerLocalDeployment(TENANT_A, "shared-name", graph(NO_SOURCE_GRAPH));
            command(application.startLocalDeployment(TENANT_A, "shared-name"));

            assertEquals(Optional.empty(), application.localDeployment("tenant-b", "shared-name"));
            assertEquals(application.localDeployment("tenant-b", "never-existed"),
                    application.localDeployment("tenant-b", "shared-name"),
                    "a sibling's id and an id nobody ever registered must answer identically");
            assertTrue(application.localDeployments("tenant-b").isEmpty());
            assertTrue(application.stopLocalDeployment("tenant-b", "shared-name")
                    .toCompletableFuture().get(30, TimeUnit.SECONDS).isEmpty());
            assertTrue(application.undeployLocalDeployment("tenant-b", "shared-name")
                    .toCompletableFuture().get(30, TimeUnit.SECONDS).isEmpty());
            assertTrue(application.startLocalDeployment(TENANT_B, "shared-name")
                    .toCompletableFuture().get(30, TimeUnit.SECONDS).isEmpty());

            assertEquals(LocalDeploymentState.READY,
                    application.localDeployment("tenant-a", "shared-name").orElseThrow().state(),
                    "none of tenant B's refused commands may have touched tenant A's deployment");

            // The same name in another tenant is a sibling, with its own registration and its own domain.
            application.registerLocalDeployment(TENANT_B, "shared-name", graph(NO_SOURCE_GRAPH));
            assertEquals(LocalDeploymentState.REGISTERED,
                    application.localDeployment("tenant-b", "shared-name").orElseThrow().state());
            assertEquals(LocalDeploymentState.READY,
                    application.localDeployment("tenant-a", "shared-name").orElseThrow().state());
        } finally {
            application.close();
        }
    }

    /** Stopping A must release only A's domain, leaving B and the shared engine able to do work. */
    @Test
    void stoppingOneDeploymentLeavesItsSiblingAndTheSharedEngineServing() throws Exception {
        var engine = new SameThreadExecutionEngine();
        var behavior = new RecordingSourceBehavior();
        var application = application(engine, new ExecutionMonitor(), behavior);
        try {
            application.registerLocalDeployment(TENANT_A, "first", graph(SOURCE_GRAPH));
            application.registerLocalDeployment(TENANT_A, "second", graph(SOURCE_GRAPH));
            command(application.startLocalDeployment(TENANT_A, "first"));
            command(application.startLocalDeployment(TENANT_A, "second"));
            assertEquals(2, behavior.starts.get());

            assertEquals(LocalDeploymentState.STOPPED, command(application.stopLocalDeployment("tenant-a", "first")));
            assertEquals(LocalDeploymentState.READY,
                    application.localDeployment("tenant-a", "second").orElseThrow().state(),
                    "stopping one deployment must not stop its sibling");
            assertEquals(1, behavior.stops.get(), "only the stopped deployment's source may be released");

            // The shared engine is still serving: the surviving deployment accepts an inbound event
            // and carries it to completion after its sibling was torn down.
            var completed = new CountDownLatch(1);
            behavior.onPayload = ignored -> completed.countDown();
            InboundSourceContext surviving = behavior.contexts.getLast();
            assertNotNull(surviving.ingress().offer(surviving.identity(),
                    ai.ravenroot.api.deployment.IngressTarget.start(), "after-sibling-stop"));
            assertTrue(completed.await(20, TimeUnit.SECONDS),
                    "the surviving deployment must still process work on the shared engine");
        } finally {
            application.close();
        }
    }

    /** A registered graph version is immutable; replacing it silently would change a running deployment. */
    @Test
    void reRegisteringTheSameGraphIsANoOpAndADifferentGraphIsATypedConflict() throws Exception {
        var application = application(new SameThreadExecutionEngine(), new ExecutionMonitor(),
                new RecordingSourceBehavior());
        try {
            application.registerLocalDeployment(TENANT_A, "pinned", graph(NO_SOURCE_GRAPH));
            command(application.startLocalDeployment(TENANT_A, "pinned"));

            LocalDeploymentStatus rejoined = application.registerLocalDeployment(
                    TENANT_A, "pinned", graph(NO_SOURCE_GRAPH));
            assertEquals(LocalDeploymentState.READY, rejoined.state(),
                    "an idempotent re-registration reports the current state, it does not reset it");

            var conflict = assertThrows(LocalDeploymentException.class,
                    () -> application.registerLocalDeployment(TENANT_A, "pinned",
                            graph(NO_SOURCE_GRAPH.replace("id=\"g\"", "id=\"g2\""))));
            assertEquals(LocalDeploymentException.Reason.GRAPH_CONFLICT, conflict.reason());
            assertEquals(LocalDeploymentState.READY,
                    application.localDeployment("tenant-a", "pinned").orElseThrow().state());

            var invalid = assertThrows(LocalDeploymentException.class,
                    () -> application.registerLocalDeployment(TENANT_A, "not a valid id", graph(NO_SOURCE_GRAPH)));
            assertEquals(LocalDeploymentException.Reason.DEPLOYMENT_ID_INVALID, invalid.reason());
        } finally {
            application.close();
        }
    }

    /** A start that fails rolls back atomically and leaves the registration startable again. */
    @Test
    void startupFailureRollsBackAndLeavesTheRegistrationIntact() throws Exception {
        var behavior = new ConfigurableSourceBehavior();
        var application = application(new SameThreadExecutionEngine(), new ExecutionMonitor(), behavior);
        try {
            application.registerLocalDeployment(TENANT_A, "flaky", graph(SOURCE_GRAPH));
            behavior.failNextStart = true;
            LocalDeploymentStatus failed = commandStatus(application.startLocalDeployment(TENANT_A, "flaky"));
            assertEquals(LocalDeploymentState.FAILED, failed.state());
            assertEquals("deployment startup failed in this process", failed.diagnostic().orElseThrow(),
                    "the diagnostic is a fixed string, never the adapter's own text");

            assertEquals(LocalDeploymentState.FAILED,
                    application.localDeployment("tenant-a", "flaky").orElseThrow().state(),
                    "a failed start must not destroy the registration it failed under");

            behavior.failNextStart = false;
            assertEquals(LocalDeploymentState.READY, command(application.startLocalDeployment(TENANT_A, "flaky")));
        } finally {
            application.close();
        }
    }

    /**
     * The registry convergence, checked from both directions: a source session is one of
     * these deployments, and a source-less deployment is deliberately <em>not</em> observable as a
     * source session — answered with the same empty result a sibling tenant's id gets, rather than
     * with a shape {@code SourceSessionStatus} could not represent.
     */
    @Test
    void sourceSessionsAndDeploymentsAreOneRegistry() throws Exception {
        var application = application(new SameThreadExecutionEngine(), new ExecutionMonitor(),
                new RecordingSourceBehavior());
        try {
            application.startSourceSession(TENANT_A, "editor-1", graph(SOURCE_GRAPH));
            LocalDeploymentStatus asDeployment = awaitDeploymentState(
                    application, "editor-1", LocalDeploymentState.READY);
            assertEquals(1, asDeployment.sourceCount());
            assertEquals(List.of("editor-1"),
                    application.localDeployments("tenant-a").stream()
                            .map(LocalDeploymentStatus::deploymentId).toList(),
                    "the editor's Run must not create a lifecycle the deployment surface cannot see");

            assertEquals(LocalDeploymentState.STOPPED, command(application.stopLocalDeployment("tenant-a", "editor-1")));
            assertEquals(SourceSessionState.STOPPED,
                    application.sourceSession("tenant-a", "editor-1").orElseThrow().state(),
                    "one mechanism means one answer, whichever surface asks");

            application.registerLocalDeployment(TENANT_A, "sourceless", graph(NO_SOURCE_GRAPH));
            assertTrue(application.sourceSession("tenant-a", "sourceless").isEmpty(),
                    "a source-less deployment is not a source session, and says so without disclosing the id");
            assertTrue(application.stopSourceSession("tenant-a", "sourceless")
                    .toCompletableFuture().get(30, TimeUnit.SECONDS).isEmpty());
            assertEquals(LocalDeploymentState.REGISTERED,
                    application.localDeployment("tenant-a", "sourceless").orElseThrow().state(),
                    "the refused source-session command must not have touched the deployment");
        } finally {
            application.close();
        }
    }

    /** A stop issued while a start is still building waits for it to settle rather than racing it. */
    @Test
    void aStopIssuedDuringStartupSettlesStopped() throws Exception {
        var behavior = new ConfigurableSourceBehavior();
        var application = application(new SameThreadExecutionEngine(), new ExecutionMonitor(), behavior);
        try {
            application.registerLocalDeployment(TENANT_A, "slow", graph(SOURCE_GRAPH));
            var release = new CountDownLatch(1);
            var entered = new CountDownLatch(1);
            behavior.beforeStart = () -> {
                entered.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            };
            CompletionStage<Optional<LocalDeploymentStatus>> starting =
                    application.startLocalDeployment(TENANT_A, "slow");
            assertTrue(entered.await(20, TimeUnit.SECONDS), "the start must actually be in flight");
            assertEquals(LocalDeploymentState.STARTING,
                    application.localDeployment("tenant-a", "slow").orElseThrow().state());

            CompletionStage<Optional<LocalDeploymentStatus>> stopping =
                    application.stopLocalDeployment("tenant-a", "slow");
            release.countDown();
            starting.toCompletableFuture().get(30, TimeUnit.SECONDS);
            assertEquals(LocalDeploymentState.STOPPED,
                    stopping.toCompletableFuture().get(30, TimeUnit.SECONDS).orElseThrow().state());
            assertEquals(1, behavior.stops.get(),
                    "the source built by the racing start must still have been released exactly once");
        } finally {
            application.close();
        }
    }

    /**
     * The per-pod cap stays exactly where the deployment-admission contract put it: on the count of <em>active</em>
     * deployments, checked when something starts, not on the count of registrations.
     *
     * <p>This is a regression guard as much as a feature test. Capping registrations instead would
     * have been the tidier-looking choice and would have silently narrowed source-session behavior: its start path is
     * refused on the active count, so a tenant whose sessions are all stopped can always start
     * another one. A registration cap would have started answering 429 there instead.</p>
     */
    @Test
    void theCapCountsActiveDeploymentsAtStartTimeAndNotRegistrations() throws Exception {
        var application = application(new SameThreadExecutionEngine(), new ExecutionMonitor(),
                new RecordingSourceBehavior(), 1);
        try {
            application.registerLocalDeployment(TENANT_A, "one", graph(NO_SOURCE_GRAPH));
            application.registerLocalDeployment(TENANT_A, "two", graph(NO_SOURCE_GRAPH));
            assertEquals(2, application.localDeployments("tenant-a").size(),
                    "a cold registration owes a graceful shutdown nothing, so it holds no slot");

            assertEquals(LocalDeploymentState.READY, command(application.startLocalDeployment(TENANT_A, "one")));
            var overCap = assertThrows(DeploymentAdmissionException.class,
                    () -> application.startLocalDeployment(TENANT_A, "two"));
            assertEquals(1, overCap.cap());
            assertEquals(LocalDeploymentState.REGISTERED,
                    application.localDeployment("tenant-a", "two").orElseThrow().state(),
                    "a refused start must leave the registration exactly as it was");

            assertEquals(LocalDeploymentState.STOPPED, command(application.stopLocalDeployment("tenant-a", "one")));
            assertEquals(LocalDeploymentState.READY, command(application.startLocalDeployment(TENANT_A, "two")),
                    "a stopped deployment releases its slot, as it always did");
        } finally {
            application.close();
        }
    }

    private static LocalDeploymentState command(
            CompletionStage<Optional<LocalDeploymentStatus>> stage) throws Exception {
        return commandStatus(stage).state();
    }

    private static LocalDeploymentStatus commandStatus(
            CompletionStage<Optional<LocalDeploymentStatus>> stage) throws Exception {
        return stage.toCompletableFuture().get(30, TimeUnit.SECONDS).orElseThrow();
    }

    private static LocalDeploymentStatus awaitDeploymentState(DefaultRavenrootApplication application,
                                                              String deploymentId,
                                                              LocalDeploymentState expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        LocalDeploymentStatus latest = null;
        while (System.nanoTime() < deadline) {
            latest = application.localDeployment("tenant-a", deploymentId).orElseThrow();
            if (latest.state() == expected) return latest;
            Thread.sleep(10);
        }
        throw new AssertionError("deployment did not reach " + expected + "; latest=" + latest);
    }

    private static DefaultRavenrootApplication application(SameThreadExecutionEngine engine,
                                                           ExecutionMonitor monitor, NodeBehavior behavior) {
        return application(engine, monitor, behavior, 8);
    }

    private static DefaultRavenrootApplication application(SameThreadExecutionEngine engine,
                                                           ExecutionMonitor monitor, NodeBehavior behavior,
                                                           int maxActiveDeployments) {
        NodePackage nodePackage = new NodePackage() {
            @Override public String id() { return "test.deployment.package"; }
            @Override public String version() { return "1.0.0"; }
            @Override public String sdkContract() { return NodeSdk.CONTRACT; }
            @Override public List<NodeBehavior> behaviors() { return List.of(behavior); }
        };
        BehaviorRegistry registry = NodePackages.register(new BehaviorRegistry(), nodePackage);
        return new DefaultRavenrootApplication(engine, monitor, registry, new InMemoryArtifactRegistry(),
                new DisabledProgramRuntime(), ExecutionIdentitySource.randomUuids(), null,
                maxActiveDeployments, UnknownBehaviorPolicy.passThrough());
    }

    private static ByteArrayInputStream graph(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static SecurityContext identity(String tenant) {
        return new SecurityContext("request-" + tenant, tenant, "subject", PrincipalType.USER,
                "urn:ravenroot:test");
    }

    private static final class RecordingSourceBehavior implements NodeBehavior, InboundSourceCapable {
        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicInteger stops = new AtomicInteger();
        private final CopyOnWriteArrayList<String> lifecycle = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<InboundSourceContext> contexts = new CopyOnWriteArrayList<>();
        private volatile java.util.function.Consumer<Object> onPayload = ignored -> { };

        @Override
        public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor("test.source", "Test source", "Test", "Test source", "actor",
                    false, List.of(), Set.of(), NodeRuntimeNature.SOURCE, Set.of(NodeRuntimeNature.SOURCE));
        }

        @Override
        public NodeAction create(NodeConfiguration configuration) {
            return message -> {
                onPayload.accept(message.payload());
                return CompletableFuture.completedFuture(
                        ai.ravenroot.api.execution.NodeResult.continueWith(message.payload()));
            };
        }

        @Override
        public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context) {
            return new InboundSource() {
                @Override
                public CompletionStage<Void> start(InboundSourceContext started) {
                    contexts.add(started);
                    starts.incrementAndGet();
                    lifecycle.add("start");
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public CompletionStage<Void> stop() {
                    stops.incrementAndGet();
                    lifecycle.add("stop");
                    return CompletableFuture.completedFuture(null);
                }
            };
        }
    }

    /** A source whose start can be delayed or failed on demand, for rollback and race coverage. */
    private static final class ConfigurableSourceBehavior implements NodeBehavior, InboundSourceCapable {
        private final AtomicInteger stops = new AtomicInteger();
        private volatile boolean failNextStart;
        private volatile Runnable beforeStart = () -> { };

        @Override
        public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor("test.source", "Test source", "Test", "Test source", "actor",
                    false, List.of(), Set.of(), NodeRuntimeNature.SOURCE, Set.of(NodeRuntimeNature.SOURCE));
        }

        @Override
        public NodeAction create(NodeConfiguration configuration) {
            return message -> CompletableFuture.completedFuture(
                    ai.ravenroot.api.execution.NodeResult.continueWith(message.payload()));
        }

        @Override
        public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context) {
            return new InboundSource() {
                @Override
                public CompletionStage<Void> start(InboundSourceContext started) {
                    beforeStart.run();
                    if (failNextStart) {
                        return CompletableFuture.failedFuture(
                                new IllegalStateException("password=hunter2 startup detail"));
                    }
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public CompletionStage<Void> stop() {
                    stops.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public CompletionStage<Void> rollback() {
                    return CompletableFuture.completedFuture(null);
                }
            };
        }
    }

    /**
     * A source whose {@link InboundSource#shutdown()} is observably distinguishable from
     * {@link InboundSource#stop()} — which no in-tree source is, since all four define the former as
     * the latter. It models the one thing the SPI distinction exists for: a resource this source owns
     * privately and keeps across restarts of its own deployment, released only once that deployment
     * will never run again in this process.
     */
    private static final class ReleaseDistinguishingSourceBehavior implements NodeBehavior, InboundSourceCapable {
        private final CopyOnWriteArrayList<String> lifecycle = new CopyOnWriteArrayList<>();
        private final AtomicInteger crossRestartReleases = new AtomicInteger();

        @Override
        public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor("test.source", "Test source", "Test", "Test source", "actor",
                    false, List.of(), Set.of(), NodeRuntimeNature.SOURCE, Set.of(NodeRuntimeNature.SOURCE));
        }

        @Override
        public NodeAction create(NodeConfiguration configuration) {
            return message -> CompletableFuture.completedFuture(
                    ai.ravenroot.api.execution.NodeResult.continueWith(message.payload()));
        }

        @Override
        public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context) {
            return new InboundSource() {
                @Override
                public CompletionStage<Void> start(InboundSourceContext started) {
                    lifecycle.add("start");
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public CompletionStage<Void> stop() {
                    lifecycle.add("stop");
                    return CompletableFuture.completedFuture(null);
                }

                /** Deliberately does not delegate to {@link #stop()}: the two must stay tellable apart. */
                @Override
                public CompletionStage<Void> shutdown() {
                    lifecycle.add("shutdown");
                    crossRestartReleases.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                }
            };
        }
    }

    private static final String SOURCE_GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="g" edgedefault="directed">
                <node id="start"><data key="kind">start</data></node>
                <node id="listener"><data key="kind">behavior</data><data key="behavior">test.source</data></node>
                <node id="end"><data key="kind">end</data></node>
                <node id="error"><data key="kind">error</data></node>
                <edge source="start" target="listener"><data key="outcome">continue</data></edge>
                <edge source="listener" target="end"><data key="outcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    private static final String NO_SOURCE_GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <graph id="g" edgedefault="directed">
                <node id="start"><data key="kind">start</data></node>
                <node id="end"><data key="kind">end</data></node>
                <node id="error"><data key="kind">error</data></node>
                <edge source="start" target="end"/>
              </graph>
            </graphml>
            """;
}
