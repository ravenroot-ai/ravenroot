package ai.ravenroot.server;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.catalog.NodeRuntimeNature;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.deployment.IngressDisposition;
import ai.ravenroot.api.deployment.IngressTarget;
import ai.ravenroot.api.node.InboundSourceCapable;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.NodePackages;
import ai.ravenroot.core.runtime.UnknownBehaviorPolicy;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.server.security.DisabledLoopbackAuthenticator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real proof for the listening-source blind spot: a real Ravenroot server, the real built editor
 * it serves, a real inbound source admitting real traffic, and a real browser watching the canvas.
 *
 * <h2>Why this exists as a real harness and not only as unit tests</h2>
 * <p>Every layer between the source and the paint had to be right at once, and each one of them was
 * individually defensible while the whole was blind: the event carried a deployment id the wire
 * projection did not publish, the session status named no deployment, the routing rule matched on a
 * traversal id the document could never learn, and the monitoring projection reset itself on every
 * new traversal. A test that stubs any one of those cannot see that failure, because the failure was
 * that nobody owned the join.
 *
 * <h2>Why twenty seconds</h2>
 * <p>The source admits one message every {@value #ADMISSION_INTERVAL_MS} ms, and the browser watches
 * for {@link #OBSERVATION_WINDOW}. A short sample can fall entirely between admissions and read as
 * "the stream is broken", which is the false conclusion the original investigation reached; the
 * window is chosen so several admissions are certain rather than likely.
 *
 * <h2>Running it</h2>
 * <pre>
 * ./scripts/verify-source-session-editor-activity.sh
 * </pre>
 */
class SourceSessionEditorActivityBrowserTest {
    static final int ADMISSION_INTERVAL_MS = 700;
    static final Duration OBSERVATION_WINDOW = Duration.ofSeconds(22);
    static final String SOURCE_BEHAVIOR = "test.stream.consume";
    static final String SOURCE_NODE = "consume";
    static final String LOG_NODE = "log-admission";

    /**
     * The graph under observation: an inbound source into the built-in {@code log} node.
     *
     * <p>The nodes carry explicit positions only so the captured screenshot is legible evidence: a
     * graph with no coordinates opens with every node stacked on one point, which proves nothing to
     * a reader even when every assertion below has passed.</p>
     *
     * <p>{@code log} rather than an inert passthrough on purpose. Its output travels on the
     * completion event's author projection, so a canvas that paints but shows no log output would
     * mean the routing was fixed and the diagnostic projection still lost — the issue predicted the
     * two are the same defect, and this is where that prediction is checked rather than argued.</p>
     */
    static final String GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="message" for="node" attr.name="message" attr.type="string"/>
              <key id="layoutX" for="node" attr.name="layoutX" attr.type="double"/>
              <key id="layoutY" for="node" attr.name="layoutY" attr.type="double"/>
              <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="listening-source" edgedefault="directed">
                <node id="error"><data key="kind">ERROR</data><data key="layoutX">80</data><data key="layoutY">260</data></node>
                <node id="start"><data key="kind">START</data><data key="layoutX">80</data><data key="layoutY">80</data></node>
                <node id="consume"><data key="kind">BEHAVIOR</data><data key="behavior">test.stream.consume</data><data key="layoutX">300</data><data key="layoutY">80</data></node>
                <node id="log-admission"><data key="kind">BEHAVIOR</data><data key="behavior">log</data><data key="message">admitted {{payload}}</data><data key="layoutX">540</data><data key="layoutY">80</data></node>
                <node id="end"><data key="kind">END</data><data key="layoutX">780</data><data key="layoutY">80</data></node>
                <edge source="start" target="consume"><data key="outcome">continue</data></edge>
                <edge source="consume" target="log-admission"><data key="outcome">continue</data></edge>
                <edge source="log-admission" target="end"><data key="outcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    @Test
    void aListeningSourceGraphPaintsItsNodesMonitoringAndLogOutputInTheRealEditor() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("ravenroot.sourceSessionEditor.browserTest"),
                "run scripts/verify-source-session-editor-activity.sh for the real browser proof");
        Path ui = Path.of(System.getProperty("user.dir")).resolve("../ravenroot-ui")
                .toAbsolutePath().normalize();
        Path distribution = ui.resolve("dist");
        assertTrue(Files.isRegularFile(distribution.resolve("index.html")),
                "the built editor is required; run npm run build in ravenroot-ui first");
        Path output = Files.createDirectories(Path.of(System.getProperty("user.dir"), "target",
                "source-session-editor-activity", UUID.randomUUID().toString()));

        var source = new AdmittingSourceBehavior();
        // `standard()`, not `new BehaviorRegistry()`: the built-in catalog is what carries `log`, and
        // without it the unknown-behavior policy passes that node through -- a graph that runs, paints
        // and reports success while producing no log output at all.
        BehaviorRegistry behaviors = NodePackages.register(BehaviorRegistry.standard(), new NodePackage() {
            @Override public String id() { return "test.source.stream.package"; }
            @Override public String version() { return "1.0.0"; }
            @Override public String sdkContract() { return NodeSdk.CONTRACT; }
            @Override public List<NodeBehavior> behaviors() { return List.of(source); }
        });

        try (var engine = new PekkoExecutionEngine("source-session-editor-activity")) {
            var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor(), behaviors,
                    new InMemoryArtifactRegistry(), new DisabledProgramRuntime(),
                    ExecutionIdentitySource.randomUuids(), null, 8, UnknownBehaviorPolicy.passThrough());
            // A concrete port, chosen before construction, because the default browser-origin policy
            // is derived from the port the server is ASKED for. Passing 0 would allow
            // `http://127.0.0.1:0` and refuse the real editor's own same-origin POST with a 403 that
            // never reaches authorization -- which reads, from the editor, as "the server rejected
            // the source session start" and says nothing about origins.
            try (var server = new RavenrootServer(application,
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), freePort()), distribution,
                    new DisabledLoopbackAuthenticator())) {
                server.start();
                Files.writeString(output.resolve("listening-source.graphml"), GRAPH);
                try {
                    runBrowser(ui, output, "http://127.0.0.1:" + server.port());
                } finally {
                    // Printed on success and on failure alike: "the browser saw nothing" and "the
                    // source produced nothing" are different diagnoses, and the second one is not
                    // about this issue at all.
                    System.out.println("{\"event\":\"source-session-editor-activity\",\"admitted\":"
                            + source.admitted() + "}");
                }
            }
        } finally {
            source.shutdown();
        }

        assertTrue(source.admitted() >= 20,
                "the source must actually admit traffic for the observation to mean anything, admitted="
                        + source.admitted());
    }

    private static int freePort() throws IOException {
        try (var socket = new java.net.ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static void runBrowser(Path ui, Path output, String origin) throws Exception {
        Path executable = ui.resolve("node_modules/.bin/playwright");
        if (!Files.isExecutable(executable)) {
            throw new IllegalStateException("the installed Playwright binary is required for the real harness");
        }
        var builder = new ProcessBuilder(executable.toString(), "test",
                "--config=playwright.source-session-activity.config.js")
                .directory(ui.toFile()).redirectErrorStream(true)
                .redirectOutput(output.resolve("playwright-driver.log").toFile());
        builder.environment().put("RAVENROOT_SOURCE_SESSION_ORIGIN", origin);
        builder.environment().put("RAVENROOT_SOURCE_SESSION_GRAPH",
                output.resolve("listening-source.graphml").toString());
        builder.environment().put("RAVENROOT_SOURCE_SESSION_WINDOW_MS",
                String.valueOf(OBSERVATION_WINDOW.toMillis()));
        // Playwright CLEARS its own output directory at start, so it gets a subdirectory of its own:
        // pointed at `output` it deleted the graph the browser is about to open and the log this
        // method reads when the run fails.
        builder.environment().put("RAVENROOT_SOURCE_SESSION_OUTPUT_DIR",
                Files.createDirectories(output.resolve("playwright")).toString());
        Process process = builder.start();
        if (!process.waitFor(6, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IllegalStateException("the browser observation did not finish; see " + output);
        }
        assertEquals(0, process.exitValue(), () -> {
            try {
                return "the browser observed no activity; driver log:\n"
                        + Files.readString(output.resolve("playwright-driver.log"));
            } catch (IOException unreadable) {
                return "the browser observed no activity and its log is unreadable at " + output;
            }
        });
    }

    /**
     * An inbound source that admits one message every {@link #ADMISSION_INTERVAL_MS} ms.
     *
     * <p>It stands in for {@code mail.imap.consume}, {@code kafka.consume} and {@code amqp.consume}
     * only in where the bytes come from. Everything the defect lived in is the real thing: a real
     * deployment, real {@link ai.ravenroot.api.deployment.TrustedIngress} admission, one real
     * traversal per message with a new id nobody told the editor, and real events on the real stream.
     * A broker would add an external dependency and no evidence.</p>
     */
    private static final class AdmittingSourceBehavior implements NodeBehavior, InboundSourceCapable {
        // One scheduler PER SOURCE, never one per behavior. A behavior instance can be asked for a
        // source more than once across a deployment's life, and a shared timer would be stopped for
        // good by the first of them to be torn down -- leaving a deployment that reports READY and
        // admits nothing, which is a fixture that lies in exactly the direction this test must not.
        private final java.util.List<ScheduledExecutorService> schedulers =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        private final AtomicInteger admitted = new AtomicInteger();

        int admitted() {
            return admitted.get();
        }

        void shutdown() {
            schedulers.forEach(ScheduledExecutorService::shutdownNow);
        }

        @Override
        public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor(SOURCE_BEHAVIOR, "Stream source", "Test",
                    "Admits one deterministic message per interval into its deployment.", "actor",
                    false, List.of(), Set.of("inbound-source"),
                    NodeRuntimeNature.SOURCE, Set.of(NodeRuntimeNature.SOURCE));
        }

        @Override
        public NodeAction create(NodeConfiguration configuration) {
            return message -> CompletableFuture.completedFuture(
                    ai.ravenroot.api.execution.NodeResult.continueWith(message.payload()));
        }

        @Override
        public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context) {
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "test-stream-source");
                thread.setDaemon(true);
                return thread;
            });
            schedulers.add(scheduler);
            return new InboundSource() {
                @Override public CompletionStage<Void> start(InboundSourceContext started) {
                    scheduler.scheduleAtFixedRate(() -> {
                        int ordinal = admitted.incrementAndGet();
                        IngressDisposition disposition = started.ingress().offer(started.identity(),
                                IngressTarget.start(), "message-" + ordinal);
                        // A refused offer is an expected operating condition, not a fault; it simply
                        // did not happen, so it must not be counted as traffic the browser should see.
                        if (disposition != IngressDisposition.ACCEPTED) admitted.decrementAndGet();
                    }, ADMISSION_INTERVAL_MS, ADMISSION_INTERVAL_MS, TimeUnit.MILLISECONDS);
                    return CompletableFuture.completedFuture(null);
                }

                @Override public CompletionStage<Void> stop() {
                    scheduler.shutdownNow();
                    return CompletableFuture.completedFuture(null);
                }
            };
        }
    }
}
