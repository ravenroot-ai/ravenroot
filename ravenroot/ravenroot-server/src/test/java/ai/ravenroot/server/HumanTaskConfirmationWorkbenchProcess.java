package ai.ravenroot.server;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.catalog.NodeRuntimeNature;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.deployment.IngressReceipt;
import ai.ravenroot.api.deployment.IngressTarget;
import ai.ravenroot.api.node.InboundSourceCapable;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.persistence.GraphDefinitionReferences;
import ai.ravenroot.api.persistence.HumanTaskPolicy;
import ai.ravenroot.api.persistence.HumanTaskQuery;
import ai.ravenroot.api.persistence.HumanTaskStatus;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.humantask.HumanTaskService;
import ai.ravenroot.core.humantask.HumanTaskHandlerDispatcher;
import ai.ravenroot.core.humantask.PinnedGraphHumanTaskContinuationExecutor;
import ai.ravenroot.core.recovery.ExecutionRecoveryService;
import ai.ravenroot.core.recovery.RepeatabilityDeclarations;
import ai.ravenroot.core.runtime.BehaviorEnvironment;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.GraphExecutionLimits;
import ai.ravenroot.core.runtime.NodePackages;
import ai.ravenroot.core.runtime.UnknownBehaviorPolicy;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import ai.ravenroot.persistence.sqlite.SqliteGraphDefinitionStore;
import ai.ravenroot.server.security.LocalTokenAuthenticator;
import ai.ravenroot.server.recovery.ExecutionRecoveryDriver;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-only child-JVM composition for the Human Task confirmation restart harness.
 *
 * <p>The first child creates the only process-local deployment registration and uses the captured
 * trusted source surface to offer two separate durable ingress records. Recovery and verification
 * children deliberately open the same durable stores without recreating that local registration.
 * This class adds no product route or production lifecycle hook.</p>
 */
public final class HumanTaskConfirmationWorkbenchProcess {
    /** First-child readiness marker, emitted after both durable ingress records become Human Tasks. */
    public static final String TASKS_READY = "HUMAN_TASK_CONFIRMATION_TASKS_READY";
    /** Recovery-child readiness marker, emitted after durable task discovery without local registration. */
    public static final String RECOVERY_READY = "HUMAN_TASK_CONFIRMATION_RECOVERY_READY";

    static final String TENANT = "local";
    static final String DEPLOYMENT_ID = "human-task-e2e";
    static final String SOURCE_NODE_ID = "human-task-e2e-source";
    static final String NODE_ID = "human-confirmation";
    static final String DOWNSTREAM_NODE_ID = "human-confirmation-downstream";
    static final String GRAPH_ID = "human-task-confirmation-e2e";
    static final String FIRST_INGRESS_KEY = "human-task-e2e-1";
    static final String SECOND_INGRESS_KEY = "human-task-e2e-2";

    // The RECOVERY child is deliberately terminated after its route-triggered sweep. Keep the
    // abandoned fixture claim short so VERIFY proves lease fencing/reclaim without spending the
    // whole test at the production-sized lease boundary.
    static final Duration FIXTURE_WORK_CLAIM_LEASE = Duration.ofSeconds(5);
    // VERIFY permits the abandoned lease to elapse once, then three further lease periods for the
    // 100 ms driver tick, SQLite commit, and pinned graph continuation. The budget stays coupled
    // to the fencing interval under test instead of masking a stale claim with a generic timeout.
    static final Duration VERIFY_COMPLETION_TIMEOUT = FIXTURE_WORK_CLAIM_LEASE.multipliedBy(4);
    // A child can spend the normal task/bootstrap budget before it starts lease-aware VERIFY work.
    // The parent consumes this value so its readiness deadline cannot race the child’s derived
    // recovery budget.
    static final Duration VERIFY_READY_TIMEOUT = Duration.ofSeconds(30).plus(VERIFY_COMPLETION_TIMEOUT);

    private HumanTaskConfirmationWorkbenchProcess() {
    }

    /**
     * Starts one test-only fixture child.
     *
     * <p>No argument preserves the committed generic packaged-server lifecycle probe. Harness runs
     * pass {@code --phase=first|recovery|verify}, a database path and the fixed listener port.</p>
     *
     * @param args child fixture arguments
     * @throws Exception when fixture composition or trusted ingress fails
     */
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            RavenrootServerMain.main(args);
            return;
        }
        Arguments arguments = Arguments.parse(args);
        runFixture(arguments);
    }

    private static void runFixture(Arguments arguments) throws Exception {
        Clock clock = Clock.systemUTC();
        HumanTaskPolicy policy = HumanTaskConfiguration.fromEnvironment(System.getenv());
        try (var store = new SqliteExecutionStore(arguments.database(), clock, policy);
             var definitions = new SqliteGraphDefinitionStore(arguments.database(), clock,
                     GraphDefinitionReferences.NONE);
             var engine = new PekkoExecutionEngine("human-task-confirmation-" + arguments.phase())) {
            var tasks = new HumanTaskService(store, clock, policy);
            var source = new CapturingSourceBehavior();
            BehaviorEnvironment environment = BehaviorEnvironment.safeDefaults();
            BehaviorRegistry behaviors = NodePackages.register(BehaviorRegistry.standard(environment,
                    ai.ravenroot.api.publication.PublicationPolicyResolver.none(),
                    ai.ravenroot.api.publication.PublicationAuditSink.noop(), tasks, policy),
                    source.packageDefinition());
            var monitor = new ExecutionMonitor();
            String workerId = "human-task-confirmation-" + arguments.phase().wireName();
            var continuation = new PinnedGraphHumanTaskContinuationExecutor(definitions, store, tasks,
                    engine, behaviors, monitor, ExecutionIdentitySource.randomUuids(), workerId,
                    FIXTURE_WORK_CLAIM_LEASE);
            var recovery = new ExecutionRecoveryService(store, List.of(TENANT), workerId, 100,
                    FIXTURE_WORK_CLAIM_LEASE, RepeatabilityDeclarations.NONE_DECLARED,
                    new HumanTaskHandlerDispatcher(store, tasks, continuation));
            try (var driver = new ExecutionRecoveryDriver(recovery, Duration.ofMillis(100));
                 var application = new DefaultRavenrootApplication(engine, monitor, behaviors,
                    environment.artifacts(), environment.programRuntime(), ExecutionIdentitySource.randomUuids(),
                    store, 1, UnknownBehaviorPolicy.passThrough(), definitions, null, tasks,
                    GraphExecutionLimits.DEFAULTS);
                 var server = new RavenrootServer(application,
                         new InetSocketAddress(InetAddress.getLoopbackAddress(), arguments.port()),
                         arguments.uiDirectory(), new LocalTokenAuthenticator(arguments.token()))) {
                // Route settlements synchronously request a bounded durable sweep. The periodic
                // driver below is used only long enough to make the first fixture task escalate;
                // leaving it active would make the second task's one-second timer a browser-speed
                // race rather than the required deterministic one-escalated/one-waiting fixture.
                server.installHumanTasks(tasks, tenantId -> recovery.sweepOnce(tenantId), policy);
                server.start();
                Thread shutdown = new Thread(() -> {
                    // Process.destroy sends a normal JVM termination signal. Close this test-owned
                    // composition before SQLite's JVM shutdown so the next child can reopen its lock.
                    server.close();
                    application.close();
                    engine.close();
                }, "human-task-confirmation-fixture-shutdown");
                Runtime.getRuntime().addShutdownHook(shutdown);
                try {
                    String graphVersion;
                    if (arguments.phase() == Phase.FIRST) {
                        driver.start();
                        graphVersion = registerAndOffer(application, source, tasks, driver);
                    } else {
                        graphVersion = graphVersion(tasks);
                        if (arguments.phase() == Phase.VERIFY) driver.start();
                    }
                    awaitTaskCount(tasks, 2);
                    if (arguments.phase() == Phase.FIRST) {
                        assertAttentionFixture(tasks);
                    }
                    if (arguments.phase() == Phase.VERIFY) {
                        awaitCompletedProcesses(store, tasks);
                    }
                    emit(arguments.phase() == Phase.FIRST ? TASKS_READY : RECOVERY_READY,
                            arguments, tasks, graphVersion);
                    awaitTermination();
                } finally {
                    try {
                        Runtime.getRuntime().removeShutdownHook(shutdown);
                    } catch (IllegalStateException shuttingDown) {
                        // The hook owns closing during a normal child termination.
                    }
                }
            }
        }
    }

    private static String registerAndOffer(DefaultRavenrootApplication application,
                                           CapturingSourceBehavior source,
                                           HumanTaskService tasks,
                                           ExecutionRecoveryDriver driver) throws Exception {
        SecurityContext identity = fixtureIdentity();
        String graphVersion = application.registerLocalDeployment(identity, DEPLOYMENT_ID,
                new ByteArrayInputStream(graph().getBytes(StandardCharsets.UTF_8)))
                .graphVersion().orElseThrow(() -> new IllegalStateException(
                        "fixture deployment did not report its canonical graph version"));
        application.startLocalDeployment(identity, DEPLOYMENT_ID).toCompletableFuture().join()
                .orElseThrow(() -> new IllegalStateException("fixture deployment was not registered"));
        InboundSourceContext context = source.awaitContext();
        assertDurable(context.ingress().offerDurably(context.identity(), IngressTarget.start(),
                PayloadValue.of("first"), SOURCE_NODE_ID, FIRST_INGRESS_KEY));
        awaitTaskCount(tasks, 1);
        awaitStatusCount(tasks, HumanTaskStatus.ESCALATED, 1);
        // The durable timer/recovery path above is the product path for escalation. Stop its
        // periodic schedule before the second ingress so that its distinct task stays WAITING
        // through browser startup; route settlements still invoke the same recovery authority.
        driver.close();
        assertDurable(context.ingress().offerDurably(context.identity(), IngressTarget.start(),
                PayloadValue.of("second"), SOURCE_NODE_ID, SECOND_INGRESS_KEY));
        return graphVersion;
    }

    private static void assertDurable(IngressReceipt receipt) {
        if (!(receipt instanceof IngressReceipt.DurablyCommitted)) {
            throw new IllegalStateException("fixture ingress was not durable: "
                    + receipt.getClass().getSimpleName());
        }
    }

    private static void awaitTaskCount(HumanTaskService tasks, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            int count = tasks.inbox(fixtureRequester(), HumanTaskQuery.everything(10)).items().size();
            if (count == expected) {
                return;
            }
            Thread.sleep(25);
        }
        throw new IllegalStateException("fixture did not produce " + expected + " Human Tasks");
    }

    private static void awaitStatusCount(HumanTaskService tasks, HumanTaskStatus status, int expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            long count = tasks.inbox(fixtureRequester(), HumanTaskQuery.everything(10)).items().stream()
                    .filter(task -> task.status() == status).count();
            if (count == expected) return;
            Thread.sleep(25);
        }
        throw new IllegalStateException("fixture did not produce " + expected + " " + status + " tasks");
    }

    private static void assertAttentionFixture(HumanTaskService tasks) {
        var items = tasks.inbox(fixtureRequester(), HumanTaskQuery.everything(10)).items();
        long escalated = items.stream().filter(task -> task.status() == HumanTaskStatus.ESCALATED).count();
        long waiting = items.stream().filter(task -> task.status() == HumanTaskStatus.WAITING).count();
        if (escalated != 1 || waiting != 1) {
            throw new IllegalStateException("fixture requires exactly one ESCALATED and one WAITING task");
        }
    }

    private static void awaitCompletedProcesses(SqliteExecutionStore store, HumanTaskService tasks)
            throws InterruptedException {
        long deadline = System.nanoTime() + VERIFY_COMPLETION_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            var items = tasks.inbox(fixtureRequester(), HumanTaskQuery.everything(10)).items();
            boolean complete = items.size() == 2 && items.stream().allMatch(task -> store
                    .findProcessInstance(task.key()).toCompletableFuture().join()
                    .map(process -> process.status() == ProcessInstanceStatus.COMPLETED)
                    .orElse(false));
            if (complete) return;
            Thread.sleep(25);
        }
        throw new IllegalStateException("verify child did not complete both durable continuations within "
                + VERIFY_COMPLETION_TIMEOUT + " after the fixture claim lease " + FIXTURE_WORK_CLAIM_LEASE);
    }

    private static String graphVersion(HumanTaskService tasks) {
        return tasks.inbox(fixtureRequester(), HumanTaskQuery.everything(10)).items().stream()
                .findFirst().orElseThrow(() -> new IllegalStateException("fixture has no Human Task"))
                .request().graphVersionPin().reference();
    }

    private static void emit(String marker, Arguments arguments, HumanTaskService tasks,
                             String graphVersion) {
        var items = tasks.inbox(fixtureRequester(), HumanTaskQuery.everything(10)).items();
        if (items.size() != 2) {
            throw new IllegalStateException("fixture readiness requires exactly two tasks");
        }
        String locators = items.stream()
                .map(task -> "{\"taskId\":\"" + task.request().taskId() + "\",\"generation\":"
                        + task.generation() + "}")
                .reduce((left, right) -> left + "," + right).orElseThrow();
        System.out.println(marker + " {\"phase\":\"" + arguments.phase().wireName()
                + "\",\"serviceOrigin\":\"http://127.0.0.1:" + arguments.port()
                + "\",\"graphVersion\":\"" + graphVersion
                + "\",\"deploymentId\":\"" + DEPLOYMENT_ID
                + "\",\"nodeId\":\"" + NODE_ID + "\",\"locators\":[" + locators + "]}");
        System.out.flush();
    }

    private static SecurityContext fixtureIdentity() {
        return SecurityContext.of(fixtureRequester());
    }

    private static RequestContext fixtureRequester() {
        return new RequestContext("human-task-e2e-source", "human-task-e2e",
                PrincipalType.USER, "urn:ravenroot:test", TENANT, Set.of(Role.APPROVER), Set.of());
    }

    /**
     * Returns the immutable GraphML fixture registered by the first child.
     *
     * <p>The parent loopback control serves these same bytes to the dedicated browser test so its
     * active document has the durable tasks' exact graph-version pin. This remains test-only
     * fixture data and is never a production route.</p>
     *
     * @return fixture GraphML bytes decoded as UTF-8 text
     */
    static String graph() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="kind" for="node" attr.name="kind" attr.type="string"/>
                  <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
                  <key id="title" for="node" attr.name="title" attr.type="string"/>
                  <key id="responseContentType" for="node" attr.name="responseContentType" attr.type="string"/>
                  <key id="responseSchema" for="node" attr.name="responseSchema" attr.type="string"/>
                  <key id="responseSchemaVersion" for="node" attr.name="responseSchemaVersion" attr.type="string"/>
                  <key id="responseKind" for="node" attr.name="responseKind" attr.type="string"/>
                  <key id="maxResponseBytes" for="node" attr.name="maxResponseBytes" attr.type="string"/>
                  <key id="confirmationPresentationVersion" for="node" attr.name="confirmationPresentationVersion" attr.type="string"/>
                  <key id="confirmationPrompt" for="node" attr.name="confirmationPrompt" attr.type="string"/>
                  <key id="confirmationComment" for="node" attr.name="confirmationComment" attr.type="string"/>
                  <key id="confirmationActions" for="node" attr.name="confirmationActions" attr.type="string"/>
                  <key id="escalateAfterSeconds" for="node" attr.name="escalateAfterSeconds" attr.type="string"/>
                  <key id="expiresAfterSeconds" for="node" attr.name="expiresAfterSeconds" attr.type="string"/>
                  <key id="joinSemantics" for="graph" attr.name="join.semantics" attr.type="string"/>
                  <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
                  <graph id="human-task-confirmation-e2e" edgedefault="directed">
                    <data key="joinSemantics">declared</data>
                    <node id="start"><data key="kind">start</data></node>
                    <node id="human-task-e2e-source"><data key="kind">behavior</data><data key="behavior">test.confirmation-source</data></node>
                    <node id="human-confirmation"><data key="kind">behavior</data><data key="behavior">human-task</data><data key="title">Confirm durable restart</data><data key="responseContentType">application/vnd.ravenroot.payload+json</data><data key="responseSchema">ravenroot.human-task.response</data><data key="responseSchemaVersion">1</data><data key="responseKind">MAP</data><data key="maxResponseBytes">65536</data><data key="confirmationPresentationVersion">1</data><data key="confirmationPrompt">""" + "P".repeat(8192) + """
                </data><data key="confirmationComment">REQUIRED</data><data key="confirmationActions">RESOLVE,DENY,CANCEL</data><data key="escalateAfterSeconds">1</data><data key="expiresAfterSeconds">300</data></node>
                    <node id="human-confirmation-downstream"><data key="kind">behavior</data><data key="behavior">test.confirmation-downstream</data></node>
                    <node id="end"><data key="kind">end</data></node>
                    <edge source="start" target="human-task-e2e-source"><data key="outcome">continue</data></edge>
                    <edge source="human-task-e2e-source" target="human-confirmation"><data key="outcome">continue</data></edge>
                    <edge source="human-confirmation" target="human-confirmation-downstream"><data key="outcome">resolved</data></edge>
                    <edge source="human-confirmation-downstream" target="end"><data key="outcome">continue</data></edge>
                  </graph>
                </graphml>
                """;
    }

    private static void awaitTermination() throws InterruptedException {
        synchronized (HumanTaskConfirmationWorkbenchProcess.class) {
            HumanTaskConfirmationWorkbenchProcess.class.wait();
        }
    }

    private enum Phase {
        FIRST("first"), RECOVERY("recovery"), VERIFY("verify");
        private final String wireName;
        Phase(String wireName) { this.wireName = wireName; }
        String wireName() { return wireName; }
        static Phase parse(String value) {
            for (Phase phase : values()) if (phase.wireName.equals(value)) return phase;
            throw new IllegalArgumentException("unknown fixture phase");
        }
    }

    private record Arguments(Phase phase, Path database, int port, String token, Path uiDirectory) {
        private Arguments {
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(database, "database");
            if (port < 1 || port > 65_535) throw new IllegalArgumentException("invalid fixture port");
            if (token == null || token.length() < 32) throw new IllegalArgumentException("invalid fixture token");
            Objects.requireNonNull(uiDirectory, "uiDirectory");
        }

        static Arguments parse(String[] args) {
            Map<String, String> values = java.util.Arrays.stream(args)
                    .map(argument -> argument.split("=", 2))
                    .collect(java.util.stream.Collectors.toMap(
                            pair -> pair[0], pair -> pair.length == 2 ? pair[1] : "",
                            (left, right) -> { throw new IllegalArgumentException("duplicate fixture argument"); }));
            return new Arguments(Phase.parse(required(values, "--phase")), Path.of(required(values, "--database")),
                    Integer.parseInt(required(values, "--port")), required(values, "--token"),
                    Path.of(required(values, "--ui-dir")));
        }

        private static String required(Map<String, String> values, String key) {
            String value = values.get(key);
            if (value == null || value.isBlank()) throw new IllegalArgumentException("missing fixture argument");
            return value;
        }
    }

    private static final class CapturingSourceBehavior implements NodeBehavior, InboundSourceCapable {
        private final List<InboundSourceContext> contexts = new CopyOnWriteArrayList<>();

        private NodePackage packageDefinition() {
            return new NodePackage() {
                @Override public String id() { return "test.confirmation.workbench"; }
                @Override public String version() { return "1.0.0"; }
                @Override public String sdkContract() { return NodeSdk.CONTRACT; }
                @Override public List<NodeBehavior> behaviors() {
                    return List.of(CapturingSourceBehavior.this, new DownstreamBehavior());
                }
            };
        }

        @Override public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor("test.confirmation-source", "Confirmation source", "Test",
                    "Test-only durable ingress source.", "actor", false, List.of(),
                    Set.of(), NodeRuntimeNature.SOURCE, Set.of(NodeRuntimeNature.SOURCE));
        }

        @Override public NodeAction create(NodeConfiguration configuration) {
            return message -> CompletableFuture.completedFuture(
                    ai.ravenroot.api.execution.NodeResult.continueWith(message.payload()));
        }

        @Override public InboundSource createSource(NodeConfiguration configuration,
                                                    InboundSourceContext context) {
            return new InboundSource() {
                @Override public CompletionStage<Void> start(InboundSourceContext started) {
                    contexts.add(started);
                    return CompletableFuture.completedFuture(null);
                }
                @Override public CompletionStage<Void> stop() {
                    return CompletableFuture.completedFuture(null);
                }
            };
        }

        private InboundSourceContext awaitContext() throws InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline) {
                if (!contexts.isEmpty()) return contexts.getFirst();
                Thread.sleep(10);
            }
            throw new IllegalStateException("fixture source did not start");
        }
    }

    private static final class DownstreamBehavior implements NodeBehavior {
        @Override public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor("test.confirmation-downstream", "Confirmation downstream", "Test",
                    "Test-only downstream continuation witness.", "actor", false, List.of(), Set.of());
        }

        @Override public NodeAction create(NodeConfiguration configuration) {
            return message -> CompletableFuture.completedFuture(
                    ai.ravenroot.api.execution.NodeResult.continueWith(message.payload()));
        }
    }
}
