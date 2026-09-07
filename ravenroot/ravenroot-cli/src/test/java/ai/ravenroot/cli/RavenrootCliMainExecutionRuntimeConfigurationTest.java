package ai.ravenroot.cli;

import ai.ravenroot.api.execution.CancellationSignal;
import ai.ravenroot.api.execution.EngineCapability;
import ai.ravenroot.api.execution.EngineState;
import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.api.execution.ExecutionEnginePolicy;
import ai.ravenroot.api.execution.Mailbox;
import ai.ravenroot.api.execution.NodeContext;
import ai.ravenroot.api.execution.NodeLifecycleState;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeRef;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.execution.NodeStatus;
import ai.ravenroot.api.execution.RavenNode;
import ai.ravenroot.api.execution.Scheduler;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.runtime.ExecutionRuntimeConfiguration;
import ai.ravenroot.core.runtime.GraphRunner;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RavenrootCliMainExecutionRuntimeConfigurationTest {
    private static final String GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="cli-runtime" edgedefault="directed">
                <node id="error"><data key="kind">ERROR</data></node>
                <node id="start"><data key="kind">START</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="start-end" source="start" target="end">
                  <data key="outcome">continue</data>
                </edge>
              </graph>
            </graphml>
            """;

    @Test
    void localCompositionProjectsOneTupleIntoTheEngineAndActualRunnerShutdown() throws Exception {
        var environment = Map.of(
                "RAVENROOT_ENGINE", "recording",
                ExecutionRuntimeConfiguration.MAX_STASHED_COMMANDS_PER_NODE_VARIABLE, "7",
                ExecutionRuntimeConfiguration.LIFECYCLE_STEP_SECONDS_VARIABLE, "2",
                ExecutionRuntimeConfiguration.TERMINAL_HISTORY_CAPACITY_VARIABLE, "9",
                ExecutionRuntimeConfiguration.RUNNER_SHUTDOWN_STEP_SECONDS_VARIABLE, "1");
        var policy = new AtomicReference<ExecutionEnginePolicy>();
        var probe = new ProbeEngine();
        var runtime = RavenrootCliMain.embeddedRuntime(environment, (id, name, selected) -> {
            assertEquals("recording", id);
            assertEquals("ravenroot-cli", name);
            policy.set(selected);
            return probe;
        });
        CompletableFuture<?> submitting = null;
        try {
            assertSame(probe, runtime.engine());
            assertEquals(new ExecutionEnginePolicy(7, Duration.ofSeconds(2), 9), policy.get());
            submitting = CompletableFuture.supplyAsync(() -> runtime.application().startGraphMl(
                    new SecurityContext("cli-runtime-request", "local", "cli-runtime-subject",
                            PrincipalType.USER, "urn:ravenroot:cli-runtime-test"),
                    UUID.randomUUID(), new ByteArrayInputStream(GRAPH.getBytes(StandardCharsets.UTF_8)),
                    "payload"));

            probe.firstCancellation().toCompletableFuture().get(5, TimeUnit.SECONDS);
            submitting.get(5, TimeUnit.SECONDS);
            assertTrue(probe.cancellationCount() > 0,
                    "the configured one-second runner bound must escalate before fallback cleanup");
        } finally {
            probe.close();
            if (submitting != null) {
                submitting.handle((ignored, failure) -> null).get(5, TimeUnit.SECONDS);
            }
            runtime.application().close();
        }
    }

    @Test
    void historicalEmbeddedApplicationHelperKeepsTheRunnerDefault() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ai/ravenroot/cli/RavenrootCliMain.java"));
        String compact = source.replaceAll("\\s+", " ");
        assertTrue(compact.contains("return embeddedApplication(engine, monitor, environmentVariables, "
                        + "ai.ravenroot.core.runtime.GraphRunner.DEFAULT_SHUTDOWN_BOUND);"),
                "the existing helper must remain a source-compatible default-bound delegate");
        assertEquals(Duration.ofSeconds(10), GraphRunner.DEFAULT_SHUTDOWN_BOUND);
    }

    @Test
    void compositionFailureRemainsPrimaryWhenEngineCleanupAlsoFails() {
        var engine = new ProbeEngine(new IllegalStateException("cleanup failed"));
        var environment = Map.of("RAVENROOT_UNKNOWN_BEHAVIOR", "malformed");

        var failure = assertThrows(IllegalArgumentException.class,
                () -> RavenrootCliMain.embeddedRuntime(environment, (id, name, policy) -> engine));

        assertTrue(failure.getMessage().contains("RAVENROOT_UNKNOWN_BEHAVIOR"));
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("cleanup failed", failure.getSuppressed()[0].getMessage());
    }

    @Test
    void remoteUnknownCommandDoesNotParseMalformedLocalEngineSettings() throws Exception {
        String dummyToken = "remote-short-circuit-" + UUID.randomUUID();
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        var process = new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"),
                RavenrootCliMain.class.getName(), "--server", "http://127.0.0.1:1",
                "definitely-not-a-command")
                .redirectErrorStream(true);
        Map<String, String> environment = process.environment();
        environment.put("RAVENROOT_TOKEN", dummyToken);
        environment.put(ExecutionRuntimeConfiguration.MAX_STASHED_COMMANDS_PER_NODE_VARIABLE, "malformed-stash");
        environment.put(ExecutionRuntimeConfiguration.LIFECYCLE_STEP_SECONDS_VARIABLE, "malformed-lifecycle");
        environment.put(ExecutionRuntimeConfiguration.TERMINAL_HISTORY_CAPACITY_VARIABLE, "malformed-history");
        environment.put(ExecutionRuntimeConfiguration.RUNNER_SHUTDOWN_STEP_SECONDS_VARIABLE, "malformed-runner");

        Process child = process.start();
        String output = "";
        try {
            assertTrue(child.waitFor(5, TimeUnit.SECONDS), "remote CLI child did not terminate");
            output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(2, child.exitValue(), output);
            assertTrue(output.contains("Unknown command: definitely-not-a-command"), output);
            assertFalse(output.contains("must be a whole number"), output);
            assertFalse(output.contains(dummyToken), "the dummy credential reached output");
        } finally {
            if (child.isAlive()) child.destroyForcibly();
            assertTrue(child.waitFor(5, TimeUnit.SECONDS), "owned CLI child survived cleanup: " + output);
        }
    }

    /** Executes real behavior while leaving graceful stop unsettled until the runner escalates. */
    private static final class ProbeEngine implements ExecutionEngine {
        private final Map<NodeRef, Entry> nodes = new ConcurrentHashMap<>();
        private final AtomicInteger cancellations = new AtomicInteger();
        private final CompletableFuture<Void> firstCancellation = new CompletableFuture<>();
        private final RuntimeException closeFailure;
        private volatile EngineState state = EngineState.RUNNING;

        private ProbeEngine() {
            this(null);
        }

        private ProbeEngine(RuntimeException closeFailure) {
            this.closeFailure = closeFailure;
        }

        @Override public String id() { return "cli-shutdown-bound-probe"; }
        @Override public Set<EngineCapability> capabilities() { return Set.of(); }
        @Override public Scheduler scheduler() { return (delay, task) -> () -> true; }
        @Override public EngineState state() { return state; }

        @Override
        public NodeRef spawn(String logicalName, RavenNode node) {
            if (!state.accepting()) throw new IllegalStateException("probe is not accepting nodes");
            NodeRef ref = new NodeRef(logicalName + "-" + UUID.randomUUID());
            Entry entry = new Entry(ref, node);
            nodes.put(ref, entry);
            node.onStart(entry.context);
            return ref;
        }

        @Override
        public CompletionStage<NodeResult> send(NodeRef target, NodeMessage message) {
            Entry entry = nodes.get(target);
            return entry == null
                    ? CompletableFuture.failedFuture(new IllegalArgumentException("unknown probe node"))
                    : entry.node.onMessage(message, entry.context);
        }

        @Override
        public Optional<NodeStatus> status(NodeRef target) {
            return nodes.containsKey(target)
                    ? Optional.of(new NodeStatus(target, NodeLifecycleState.RUNNING, null, 0))
                    : Optional.empty();
        }

        @Override
        public CompletionStage<Void> stop(NodeRef target) {
            Entry entry = nodes.get(target);
            return entry == null ? CompletableFuture.completedFuture(null) : entry.gracefulStop;
        }

        @Override
        public CompletionStage<Void> cancel(NodeRef target) {
            Entry entry = nodes.remove(target);
            if (entry == null) return CompletableFuture.completedFuture(null);
            entry.cancel();
            cancellations.incrementAndGet();
            firstCancellation.complete(null);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> drain() {
            state = EngineState.DRAINING;
            return CompletableFuture.allOf(nodes.keySet().stream()
                    .map(this::stop).map(CompletionStage::toCompletableFuture)
                    .toArray(CompletableFuture[]::new));
        }

        int cancellationCount() { return cancellations.get(); }
        CompletionStage<Void> firstCancellation() { return firstCancellation; }

        @Override
        public void close() {
            state = EngineState.CLOSED;
            Set.copyOf(nodes.keySet()).forEach(this::cancel);
            if (closeFailure != null) throw closeFailure;
        }

        private final class Entry {
            private final RavenNode node;
            private final ProbeCancellation cancellation = new ProbeCancellation();
            private final CompletableFuture<Void> gracefulStop = new CompletableFuture<>();
            private final AtomicBoolean stopped = new AtomicBoolean();
            private final NodeContext context;

            private Entry(NodeRef ref, RavenNode node) {
                this.node = node;
                this.context = new NodeContext() {
                    @Override public NodeRef self() { return ref; }
                    @Override public Scheduler scheduler() { return ProbeEngine.this.scheduler(); }
                    @Override public Mailbox mailbox() { return () -> 0; }
                    @Override public CancellationSignal cancellation() { return cancellation; }
                };
            }

            private void cancel() {
                cancellation.cancel();
                if (stopped.compareAndSet(false, true)) node.onStop(context);
                gracefulStop.complete(null);
            }
        }
    }

    private static final class ProbeCancellation implements CancellationSignal {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final CopyOnWriteArrayList<OnceListener> listeners = new CopyOnWriteArrayList<>();

        @Override public boolean cancelled() { return cancelled.get(); }

        @Override
        public void onCancel(Runnable listener) {
            OnceListener owned = new OnceListener(listener);
            listeners.add(owned);
            if (cancelled.get() && listeners.remove(owned)) owned.run();
        }

        private void cancel() {
            if (!cancelled.compareAndSet(false, true)) return;
            for (OnceListener listener : listeners) {
                if (listeners.remove(listener)) listener.run();
            }
        }

        private static final class OnceListener {
            private final Runnable delegate;
            private final AtomicBoolean invoked = new AtomicBoolean();

            private OnceListener(Runnable delegate) {
                this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
            }

            private void run() {
                if (!invoked.compareAndSet(false, true)) return;
                try {
                    delegate.run();
                } catch (RuntimeException ignored) {
                    // A cancellation observer cannot prevent the remaining listeners from running.
                }
            }
        }
    }
}
