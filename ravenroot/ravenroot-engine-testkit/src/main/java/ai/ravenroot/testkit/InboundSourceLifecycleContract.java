package ai.ravenroot.testkit;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.DeploymentState;
import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.deployment.IngressTarget;
import ai.ravenroot.api.deployment.RequestReplyAdmission;
import ai.ravenroot.api.deployment.RequestReplyRefusal;
import ai.ravenroot.api.deployment.RequestReplyTerminalState;
import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.InboundSourceCapable;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.runtime.BehaviorEnvironment;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultGraphDeployment;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.NodePackages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mandatory conformance suite for {@link InboundSourceCapable}/{@link InboundSource} against every
 * adapter's {@link ExecutionEngine}, complementing {@link GraphDeploymentContract}.
 *
 * <h2>What this suite proves, and what it cannot</h2>
 * <p>Every fixture here is a bookkeeping fake: a counter, never a socket or a thread. Two of
 * {@link GraphDeploymentContract}'s own omissions apply here for the identical reason and are
 * <em>deliberately absent</em> rather than silently untested:</p>
 * <ul>
 *   <li><b>Restart without duplicated activity.</b> {@link #restartProducesAFreshSourceInstance}
 *       proves a fresh {@link InboundSource} is created and the old one's {@code stop} was called --
 *       which a fake with no real subscription passes trivially. Whether a <em>real</em> adapter's
 *       socket or consumer group actually closed before the new one opened needs a count only that
 *       adapter's own module can take, the same way {@code PekkoGraphDeploymentResidualityTest}
 *       supplies what this class cannot.</li>
 *   <li><b>Zero residual resources.</b> Not tested here for the same reason: a fake holds no resource
 *       to leak, so a suite built on one can never fail this way regardless of what a real source
 *       does.</li>
 * </ul>
 * <p>The governing question is {@link GraphDeploymentContract}'s own: would this test pass against a
 * bookkeeping fake? If a future addition here would not, it belongs in an adapter's own module
 * instead.</p>
 *
 * <h2>What is inherited rather than re-proved</h2>
 * <p>Concurrent/idempotent start and stop, and late delivery after stop, are already proved for the
 * deployment itself by {@link GraphDeploymentContract}. A source's {@code createSource}/{@code start}
 * runs exactly once per {@code doStart} and its {@code stop} exactly once per {@code doStop} --
 * inside the same single-flight section those tests already exercise -- so a second concurrent caller
 * observing the one in-flight stage rather than a duplicate applies to a source's activity for free
 * and is not repeated here.</p>
 */
public abstract class InboundSourceLifecycleContract {
    protected static final SecurityContext TCK_IDENTITY = new SecurityContext("source-tck-request",
            "source-tck-tenant", "source-tck-subject", PrincipalType.WORKLOAD, "urn:ravenroot:source-tck");

    /**
     * How long {@code startWaitsForTheSourcesOwnReadiness} waits for a signal (source created,
     * then started) before treating its absence as a genuine hang rather than slow engine/actor
     * bootstrap. Deliberately generous and deliberately not the thing that test's own assertions
     * depend on -- see that test's own Javadoc.
     */
    private static final java.time.Duration SOURCE_SIGNAL_SAFETY_NET = java.time.Duration.ofSeconds(60);

    /**
     * See {@code startWaitsForTheSourcesOwnReadiness}'s own inline comment for what this is and,
     * as importantly, what it is not -- it cannot cause that test to fail on a correct implementation
     * no matter its value, which is exactly what distinguishes it from the bound removed here.
     */
    private static final java.time.Duration ORDERING_VIOLATION_GRACE_PERIOD = java.time.Duration.ofMillis(500);

    private ExecutionEngine engine;

    protected abstract ExecutionEngine createEngine(String systemName);

    protected final ExecutionEngine engine() {
        if (engine == null) {
            engine = createEngine("ravenroot-source-tck-" + UUID.randomUUID());
        }
        return engine;
    }

    @AfterEach
    final void closeEngine() {
        if (engine != null) {
            engine.close();
        }
    }

    // ------------------------------------------------------------------------------- fixtures

    private static String graphNaming(String... behaviors) {
        var xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="node-kind" for="node" attr.name="kind" attr.type="string"/>
                  <key id="node-behavior" for="node" attr.name="behavior" attr.type="string"/>
                  <key id="edge-outcome" for="edge" attr.name="outcome" attr.type="string"/>
                  <graph id="with-sources" edgedefault="directed">
                    <node id="error"><data key="node-kind">ERROR</data></node>
                    <node id="start"><data key="node-kind">START</data></node>
                """);
        String previous = "start";
        for (int i = 0; i < behaviors.length; i++) {
            String nodeId = "source" + i;
            xml.append("    <node id=\"").append(nodeId).append("\"><data key=\"node-kind\">BEHAVIOR</data>")
                    .append("<data key=\"node-behavior\">").append(behaviors[i]).append("</data></node>\n");
            xml.append("    <edge id=\"e").append(i).append("\" source=\"").append(previous).append("\" target=\"")
                    .append(nodeId).append("\"><data key=\"edge-outcome\">continue</data></edge>\n");
            previous = nodeId;
        }
        xml.append("    <node id=\"end\"><data key=\"node-kind\">END</data></node>\n");
        xml.append("    <edge id=\"eend\" source=\"").append(previous).append("\" target=\"end\">")
                .append("<data key=\"edge-outcome\">continue</data></edge>\n");
        xml.append("  </graph>\n</graphml>\n");
        return xml.toString();
    }

    /** A source whose every hook is a counted, controllable no-op. */
    protected static final class RecordingSource implements InboundSource {
        final AtomicInteger startCalls = new AtomicInteger();
        final AtomicInteger stopCalls = new AtomicInteger();
        final AtomicInteger rollbackCalls = new AtomicInteger();
        final AtomicReference<InboundSourceContext> lastContext = new AtomicReference<>();
        volatile CompletionStage<Void> startResult = CompletableFuture.completedFuture(null);
        volatile CompletionStage<Void> stopResult = CompletableFuture.completedFuture(null);
        /**
         * Completed synchronously, the instant {@link #start} is called -- before its own
         * returned stage ({@link #startResult}) is even evaluated. This is the signal a test awaits
         * to know "start was invoked and has not yet returned control past its own gate", which is a
         * fact about ordering, not about how long engine/actor bootstrap took to get here.
         */
        final CompletableFuture<Void> startInvoked = new CompletableFuture<>();

        @Override
        public CompletionStage<Void> start(InboundSourceContext context) {
            lastContext.set(context);
            startCalls.incrementAndGet();
            startInvoked.complete(null);
            return startResult;
        }

        @Override
        public CompletionStage<Void> stop() {
            stopCalls.incrementAndGet();
            return stopResult;
        }

        @Override
        public CompletionStage<Void> rollback() {
            rollbackCalls.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
    }

    /** A behavior that hands out one fresh {@link RecordingSource} per {@code createSource} call. */
    private static final class RecordingBehavior implements NodeBehavior, InboundSourceCapable {
        private final String name;
        private final List<RecordingSource> issued;
        private final CompletionStage<Void> startResultOverride;
        private final AtomicInteger createSourceCalls = new AtomicInteger();
        /** Completed the instant {@link #createSource} runs -- the signal side of "wait for the
         * source to exist" that replaces polling {@link #issued} at an interval. */
        final CompletableFuture<RecordingSource> issuedSignal = new CompletableFuture<>();

        RecordingBehavior(String name, List<RecordingSource> issued) {
            this(name, issued, null);
        }

        /**
         * @param startResultOverride if non-null, baked into every issued source's {@code start}
         *                            result <em>before</em> {@code start} can ever be called on it --
         *                            never mutated after the fact, which would race the very call this
         *                            exists to gate.
         */
        RecordingBehavior(String name, List<RecordingSource> issued, CompletionStage<Void> startResultOverride) {
            this.name = name;
            this.issued = issued;
            this.startResultOverride = startResultOverride;
        }

        @Override
        public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor(name, name, "Custom", "source-tck fixture", "actor", false,
                    List.of(), java.util.Set.of());
        }

        @Override
        public NodeAction create(NodeConfiguration configuration) {
            return message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
        }

        @Override
        public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context) {
            createSourceCalls.incrementAndGet();
            var source = new RecordingSource();
            if (startResultOverride != null) {
                source.startResult = startResultOverride;
            }
            issued.add(source);
            issuedSignal.complete(source);
            return source;
        }
    }

    private record RecordingPackage(NodeBehavior behavior) implements NodePackage {
        @Override
        public String id() {
            return "ai.ravenroot.testkit.source-tck";
        }

        @Override
        public String version() {
            return "1.0.0";
        }

        @Override
        public String sdkContract() {
            return NodeSdk.CONTRACT;
        }

        @Override
        public List<NodeBehavior> behaviors() {
            return List.of(behavior);
        }
    }

    private DefaultGraphDeployment deployment(DeploymentId id, BehaviorRegistry registry, String graphMl) {
        return new DefaultGraphDeployment(id, engine(), registry, new ExecutionMonitor(),
                ExecutionIdentitySource.randomUuids(), graphMl.getBytes(StandardCharsets.UTF_8),
                DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY);
    }

    // ------------------------------------------------------------------------------------- tests

    /**
     * The property this suite exists for: package registration alone -- the plugin loader's whole
     * job -- never reaches {@link InboundSourceCapable#createSource}, and neither does building a
     * {@link DefaultGraphDeployment} that merely names the behavior in its graph. Only {@code start}
     * does.
     */
    @Test
    final void noSourceActivityBeforeStart() {
        var issued = new java.util.ArrayList<RecordingSource>();
        var behavior = new RecordingBehavior("recording-source", issued);
        var registry = NodePackages.register(BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()),
                new RecordingPackage(behavior));

        assertEquals(0, behavior.createSourceCalls.get(),
                "registering the package into the catalog must not create a source");

        var deployment = deployment(DeploymentId.of("no-activity"), registry, graphNaming("recording-source"));
        assertEquals(DeploymentState.COLD, deployment.status().state());
        assertEquals(0, behavior.createSourceCalls.get(),
                "building the deployment must not create a source either -- only start() may");

        deployment.start(TCK_IDENTITY).toCompletableFuture().join();
        assertEquals(1, behavior.createSourceCalls.get());
        assertEquals(1, issued.get(0).startCalls.get());
    }

    /**
     * Readiness is not reported until the source's own start stage is -- not merely requested.
     *
     * <h2>Ordering, not duration</h2>
     * <p>The property this test exists to prove is an <em>order</em> between two completions: the
     * source's own {@code start} finishes before the deployment reports {@code READY}. Order is
     * observable without measuring how long either one took, and this test no longer tries to.</p>
     *
     * <p>Earlier, this test polled {@link RecordingSource#startCalls} at an interval bounded by a
     * fixed 10 seconds, then asserted {@code started.isDone()} was still false. That bound had to
     * cover engine and actor-system bootstrap (real, and slow under load) <em>as well as</em> the
     * property under test, so a busy machine could exhaust the whole budget before the source was
     * even created -- a false failure with nothing wrong with the ordering guarantee itself.</p>
     *
     * <p>Now: {@link RecordingBehavior#issuedSignal} and {@link RecordingSource#startInvoked} are
     * completions, not booleans polled at an interval -- waiting on them blocks the calling thread (no
     * CPU spent spinning, so this test no longer competes with the engine's own threads for a core)
     * until the real event happens, however long that takes.</p>
     *
     * <p>The wait for them to complete is still bounded -- {@link #SOURCE_SIGNAL_SAFETY_NET} -- but
     * that bound is declared for what it is: a safety net against a genuine hang (the source never
     * gets created at all, a real bug), not a margin the ordering assertion depends on. Engine/actor
     * bootstrap latency is irreducibly temporal -- this suite cannot make a real actor system start
     * instantly -- and widening or removing that bound would not change what this test can prove; it
     * only changes how long a genuine hang takes to report.</p>
     *
     * <h2>The trap in the first attempt at this fix</h2>
     * <p>An earlier version of this test reasoned: "once {@code startInvoked} is observed complete, the
     * deployment's own thread must still be blocked on the gate, so {@code started.isDone()} must be
     * false" -- and asserted exactly that as a direct snapshot check. It was wrong, and mutation-proved
     * wrong: with the ordering guarantee deliberately broken in {@code DefaultGraphDeployment} (the
     * source's own start stage no longer awaited before the deployment is marked ready), that
     * assertion <em>still passed</em>, every time. The reason is that {@code startInvoked.complete}
     * does not block the completing thread -- the deployment's own virtual thread races straight on
     * to finishing {@code doStart} with no context switch required, while <em>this</em> thread has to
     * be woken from its own wait first. Under the broken ordering, the deployment thread almost always
     * wins that race, so a direct snapshot taken after waking up observes {@code started} already
     * done far too often to be a reliable check -- exactly backwards from what the assertion needed.</p>
     *
     * <p>The fix is to stop taking a snapshot after waking up, and instead attach the check directly
     * to {@code started}'s own completion via {@link CompletableFuture#whenComplete}. That callback
     * runs exactly once, at the instant {@code started} completes -- whenever in real time that
     * happens to be, on the thread that completes it if it was not yet complete, or immediately upon
     * attachment if it already was -- and asks the one question that decides everything: was
     * {@code gate}, which only this test completes and does not complete until well after this
     * callback is registered, already done at that instant? In the correct implementation {@code gate}
     * is completed strictly before {@code started} can be (nothing else can un-block the wait inside
     * {@code startSources}), so the callback always observes it done. Under the broken ordering,
     * {@code started} can complete before this test ever calls {@code gate.complete}, and the callback
     * observes exactly that. No wakeup race is involved in either case: the check does not wait for an
     * event and then look, it is invoked <em>by</em> the event.</p>
     *
     * <h2>The second trap: two waiters on one future are not ordered against each other</h2>
     * <p>The obvious way to wait for the callback above to have run is to wait on {@code started}
     * itself -- it already has a bounded {@code get} on it for the {@code READY} assertion, so why not
     * read {@code gateWasAlreadyDoneWhenStartedCompleted} right after? Because {@link
     * CompletableFuture#get} unblocks the instant the future it is called on is marked complete, and
     * marking {@code started} complete and running a <em>separately attached</em> {@code whenComplete}
     * callback are not the same event -- both are triggered by the same completion, but nothing orders
     * one against the other when they are two independent dependents of the same future. On this
     * machine that gap is normally too small to see; it showed up as an intermittent false failure only
     * when running the full class repeatedly, not in isolated single-method runs, which is exactly the
     * kind of failure that a change "obviously" tested by inspection can still ship with. The fix is to
     * keep the {@link CompletableFuture} that {@code whenComplete} itself returns -- that stage
     * completes only after its callback has finished running, or exceptionally if the callback throws
     * -- and wait on <em>that</em> one before reading the flag it set.</p>
     */
    @Test
    final void startWaitsForTheSourcesOwnReadiness() throws Exception {
        var issued = new java.util.ArrayList<RecordingSource>();
        var gate = new CompletableFuture<Void>();
        // Baked into the fixture before start() can ever be called on it (see the constructor
        // Javadoc) -- installing it afterwards would race doStart's own virtual thread, which calls
        // start() synchronously, right after createSource returns.
        var behavior = new RecordingBehavior("gated-source", issued, gate);
        var registry = NodePackages.register(BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()),
                new RecordingPackage(behavior));
        var deployment = deployment(DeploymentId.of("gated"), registry, graphNaming("gated-source"));

        var started = deployment.start(TCK_IDENTITY).toCompletableFuture();

        // The ordering proof: attached to started's own completion, not evaluated after a separate
        // wakeup -- see the class Javadoc's "trap in the first attempt" for why that distinction is
        // the whole of what makes this reliable. orderingCheck (not started itself) is what this test
        // waits on below: CompletableFuture#get unblocks the instant its future is marked complete,
        // which is not the same instant a *separately attached* whenComplete callback finishes running
        // -- the two are triggered together but not ordered against each other, so waiting on started
        // and then reading gateWasAlreadyDoneWhenStartedCompleted would itself be racing the callback.
        // Waiting on the stage whenComplete returns is what actually waits for the callback to finish.
        var gateWasAlreadyDoneWhenStartedCompleted = new java.util.concurrent.atomic.AtomicBoolean();
        var orderingCheck = started.whenComplete(
                (status, error) -> gateWasAlreadyDoneWhenStartedCompleted.set(gate.isDone()));

        // Still a signal, not an interval, and still not the property under test -- see above. This is
        // here only so a genuine "the source was never created" failure is reported as that, distinctly
        // from an ordering violation, rather than surfacing as a timeout on the line below.
        RecordingSource source = behavior.issuedSignal.get(SOURCE_SIGNAL_SAFETY_NET.toSeconds(), TimeUnit.SECONDS);
        source.startInvoked.get(SOURCE_SIGNAL_SAFETY_NET.toSeconds(), TimeUnit.SECONDS);

        // A grace period, not a correctness bound -- see the class Javadoc's "trap in the first
        // attempt" for the measurement that makes this necessary: a source whose start is fired and
        // forgotten (the violation this test exists to catch) finishes the rest of doStart's own
        // synchronous tail in microseconds, fast enough to sometimes race ahead of this thread waking
        // from the waits just above before this test ever reaches gate.complete. This sleep exists
        // only to give that race a fair, generous margin to resolve in the direction that catches the
        // bug. It cannot produce a false failure on the correct implementation: started structurally
        // cannot complete until gate does, no matter how long this thread sleeps first, so a longer or
        // shorter value here never changes the correct implementation's outcome -- only how reliably a
        // genuinely broken one gets caught. This is the one place in this test method that is
        // irreducibly temporal, and it is declared as exactly that rather than left implicit.
        Thread.sleep(ORDERING_VIOLATION_GRACE_PERIOD.toMillis());

        gate.complete(null);
        assertEquals(DeploymentState.READY, orderingCheck.get(10, TimeUnit.SECONDS).state());
        assertTrue(gateWasAlreadyDoneWhenStartedCompleted.get(),
                "the deployment reported ready before the source's own start had completed");
    }

    /**
     * A second source failing to start rolls back the first, which had already reached readiness --
     * {@code rollback}, not {@code stop}, because nothing it did was ever actually served.
     */
    @Test
    final void aFailedSourceRollsBackSiblingsThatAlreadyStarted() throws Exception {
        var issuedA = new java.util.ArrayList<RecordingSource>();
        var issuedB = new java.util.ArrayList<RecordingSource>();
        var behaviorA = new RecordingBehavior("source-a", issuedA);
        var failing = new FailingBehavior("source-b");
        var registry = NodePackages.register(
                NodePackages.register(BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()),
                        new RecordingPackage(behaviorA)),
                new RecordingPackage(failing));
        var deployment = deployment(DeploymentId.of("rollback"), registry, graphNaming("source-a", "source-b"));

        var start = deployment.start(TCK_IDENTITY).toCompletableFuture();
        assertThrows(ExecutionException.class, () -> start.get(10, TimeUnit.SECONDS));

        assertEquals(DeploymentState.FAILED, deployment.status().state());
        assertEquals(1, issuedA.get(0).rollbackCalls.get(), "the already-started sibling must be rolled back");
        assertEquals(0, issuedA.get(0).stopCalls.get(), "rollback, not an ordinary stop");
    }

    /** One source failing to stop must not prevent a sibling's stop from being called (best-effort). */
    @Test
    final void stopStopsEverySourceEvenIfOneFails() throws Exception {
        var issued = new java.util.ArrayList<RecordingSource>();
        var behaviorA = new RecordingBehavior("stop-a", issued);
        var behaviorB = new RecordingBehavior("stop-b", issued);
        var registry = NodePackages.register(
                NodePackages.register(BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()),
                        new RecordingPackage(behaviorA)),
                new RecordingPackage(behaviorB));
        var deployment = deployment(DeploymentId.of("stop-both"), registry, graphNaming("stop-a", "stop-b"));
        deployment.start(TCK_IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);

        issued.get(0).stopResult = CompletableFuture.failedFuture(new IllegalStateException("stop-a refuses"));

        deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(1, issued.get(0).stopCalls.get());
        assertEquals(1, issued.get(1).stopCalls.get(), "the second source's stop must still run");
    }

    /**
     * Restart produces a fresh source instance rather than reusing the stopped one, and the old
     * instance's stop was called first -- the contract-level half of "no duplication"; see the class
     * Javadoc for what only a real adapter's own test can add to this.
     */
    @Test
    final void restartProducesAFreshSourceInstance() throws Exception {
        var issued = new java.util.ArrayList<RecordingSource>();
        var behavior = new RecordingBehavior("restart-source", issued);
        var registry = NodePackages.register(BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()),
                new RecordingPackage(behavior));
        var deployment = deployment(DeploymentId.of("restart"), registry, graphNaming("restart-source"));
        deployment.start(TCK_IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);
        var first = issued.get(0);

        deployment.restart(TCK_IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(1, first.stopCalls.get(), "the pre-restart source must have been stopped");
        assertEquals(2, issued.size(), "restart must create a fresh source, not reuse the old one");
        assertNotSame(first, issued.get(1));
        assertEquals(1, issued.get(1).startCalls.get());
    }

    /**
     * A source receives request/reply authority already bound to the identity that activated its
     * deployment. Restart fences that view by generation: retaining the old context must never let
     * a stopped source dispatch into the replacement runtime.
     */
    @Test
    final void requestReplyAuthorityIsIdentityBoundAndGenerationFenced() throws Exception {
        var issued = new java.util.ArrayList<RecordingSource>();
        var behavior = new RecordingBehavior("request-reply-source", issued);
        var registry = NodePackages.register(BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()),
                new RecordingPackage(behavior));
        var deployment = deployment(DeploymentId.of("request-reply-source"), registry,
                graphNaming("request-reply-source"));

        deployment.start(TCK_IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);
        InboundSourceContext firstContext = issued.get(0).lastContext.get();
        assertSame(TCK_IDENTITY, firstContext.identity());

        var first = assertInstanceOf(RequestReplyAdmission.Accepted.class,
                firstContext.requestReply().request(IngressTarget.start(), PayloadValue.of("first"),
                        Instant.now().plusSeconds(5)));
        var firstOutcome = first.exchange().completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(RequestReplyTerminalState.COMPLETED, firstOutcome.state());
        assertEquals("first", firstOutcome.payload());

        var replacementIdentity = new SecurityContext("source-tck-restart", "replacement-tenant",
                "replacement-subject", PrincipalType.WORKLOAD, "urn:ravenroot:source-tck:replacement");
        deployment.restart(replacementIdentity).toCompletableFuture().get(10, TimeUnit.SECONDS);

        var stale = assertInstanceOf(RequestReplyAdmission.Refused.class,
                firstContext.requestReply().request(IngressTarget.start(), PayloadValue.of("stale"),
                        Instant.now().plusSeconds(5)));
        assertEquals(RequestReplyRefusal.ADMISSION_CLOSED, stale.reason());

        InboundSourceContext replacementContext = issued.get(1).lastContext.get();
        assertSame(replacementIdentity, replacementContext.identity());
        var replacement = assertInstanceOf(RequestReplyAdmission.Accepted.class,
                replacementContext.requestReply().request(IngressTarget.start(), PayloadValue.of("replacement"),
                        Instant.now().plusSeconds(5)));
        var replacementOutcome = replacement.exchange().completion().toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        assertEquals(RequestReplyTerminalState.COMPLETED, replacementOutcome.state());
        assertEquals("replacement", replacementOutcome.payload());
    }

    /**
     * The security property {@link InboundSourceContext}'s own Javadoc documents: a source receives
     * exactly its own deployment's ingress, by identity -- never a different deployment's, and never
     * one it could have reached any other way.
     */
    @Test
    final void eachSourceReceivesExactlyItsOwnDeploymentsIngress() throws Exception {
        var issuedX = new java.util.ArrayList<RecordingSource>();
        var issuedY = new java.util.ArrayList<RecordingSource>();
        var behaviorX = new RecordingBehavior("ingress-x", issuedX);
        var behaviorY = new RecordingBehavior("ingress-y", issuedY);
        var registryX = NodePackages.register(BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()),
                new RecordingPackage(behaviorX));
        var registryY = NodePackages.register(BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()),
                new RecordingPackage(behaviorY));
        var deploymentX = deployment(DeploymentId.of("ingress-x-deployment"), registryX, graphNaming("ingress-x"));
        var deploymentY = deployment(DeploymentId.of("ingress-y-deployment"), registryY, graphNaming("ingress-y"));

        deploymentX.start(TCK_IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);
        deploymentY.start(TCK_IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertSame(deploymentX.ingress(), issuedX.get(0).lastContext.get().ingress());
        assertSame(deploymentY.ingress(), issuedY.get(0).lastContext.get().ingress());
        assertNotSame(issuedX.get(0).lastContext.get().ingress(), issuedY.get(0).lastContext.get().ingress());
        assertEquals(DeploymentId.of("ingress-x-deployment"), issuedX.get(0).lastContext.get().deploymentId());
    }

    /** A behavior whose source always fails to start, to drive the rollback test. */
    private static final class FailingBehavior implements NodeBehavior, InboundSourceCapable {
        private final String name;

        FailingBehavior(String name) {
            this.name = name;
        }

        @Override
        public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor(name, name, "Custom", "source-tck fixture", "actor", false,
                    List.of(), java.util.Set.of());
        }

        @Override
        public NodeAction create(NodeConfiguration configuration) {
            return message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
        }

        @Override
        public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context) {
            return new InboundSource() {
                @Override
                public CompletionStage<Void> start(InboundSourceContext ignored) {
                    return CompletableFuture.failedFuture(new IllegalStateException(name + " refuses to start"));
                }

                @Override
                public CompletionStage<Void> stop() {
                    return CompletableFuture.completedFuture(null);
                }
            };
        }
    }
}
