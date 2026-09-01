package ai.ravenroot.server.qa03;

import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.ExecutionRecorder;
import ai.ravenroot.core.runtime.GraphRunner;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

/**
 * The program {@link PekkoEngineDispatchKillTest} forks and then kills with {@code SIGKILL} — the
 * engine-adapter cell of the crash/replay matrix (QA-03).
 *
 * <h2>The gap this closes</h2>
 * <p>Every other cell in this matrix kills a bare {@code SqliteExecutionStore}: the "RUNNING"
 * transition it commits is written directly by the test, never by a real engine dispatching a real
 * node. That leaves zero kill coverage on the actual production path — {@link GraphRunner} committing
 * a node's {@code RUNNING} transition through {@link ExecutionRecorder} and <em>then</em> calling
 * {@link ai.ravenroot.api.execution.ExecutionEngine#send}, exactly the ordering
 * {@code ExecutionRecorder}'s class javadoc states and {@link ai.ravenroot.core.recovery.RecoveryOutcome.Dispatched}'s
 * javadoc depends on. Nothing before this cell had a live {@link PekkoExecutionEngine} actor in flight
 * at the moment of the kill.</p>
 *
 * <h2>What is proved, and how</h2>
 * <p>The registered {@code "hang"} behavior is only ever invoked by a real actor the real engine
 * spawned and sent a real {@link ai.ravenroot.api.execution.NodeMessage} to. It announces
 * {@link #AT_BOUNDARY} and then never completes, which is what makes this a placed kill rather than a
 * sampled one: the process is killed only once that behavior has actually run, which by construction
 * cannot happen before {@code GraphRunner}'s {@code nodeStarted} committed the attempt's
 * {@code RUNNING} transition under the fence — {@code record(...)} is synchronous and returns before
 * {@code engine.send(...)} is ever called (see {@code GraphRunner.run}). So by the time a reader sees
 * {@code AT_BOUNDARY} on stdout, the store already durably says {@code RUNNING} on disk.</p>
 *
 * <p>After the kill, the parent reopens <em>only</em> the SQLite file — the child's whole actor system
 * died with it — and runs {@link ai.ravenroot.core.recovery.ExecutionRecoveryService#sweepOnce()}
 * exactly as production would. This is the invariant no existing cell exercised: an attempt a real
 * engine adapter actually dispatched is recovered the same way a directly-written {@code RUNNING}
 * batch would be, with no engine, no actor system and no in-memory state left to consult.</p>
 *
 * <h2>Process death, not machine death</h2>
 * <p>As with every other cell, {@link Process#destroyForcibly()} sends a real {@code SIGKILL} the
 * child cannot catch, block or handle. A {@code SIGKILL} leaves the operating system page cache
 * intact, so this cell cannot distinguish {@code synchronous=FULL} from a reduced mode either; see
 * {@code CrashReplayMatrixScopeTest} in {@code ravenroot-persistence-sqlite} for the matrix-wide
 * statement of that limit. No randomness is introduced here: the kill point is announced, not sampled.</p>
 */
public final class PekkoEngineDispatchKillBoundary {

    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    static final String TENANT = "acme";
    static final UUID INSTANCE_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");
    static final UUID TRAVERSAL_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    static final ExecutionKey KEY = new ExecutionKey(TENANT, INSTANCE_ID);
    static final String HANG_NODE_ID = "hang";
    static final String HANG_BEHAVIOR = "hang-forever";
    static final String WORKER_ID = "qa03-engine-adapter-kill-worker";
    static final Duration LEASE_TTL = Duration.ofSeconds(30);

    static final String AT_BOUNDARY = "AT_BOUNDARY";
    static final String COMPLETED = "UNEXPECTEDLY_COMPLETED";

    private static final SecurityContext SECURITY = new SecurityContext("qa03-engine-kill-request", TENANT,
            "qa03-kill-worker", PrincipalType.USER, "urn:ravenroot:qa03");

    private PekkoEngineDispatchKillBoundary() {
    }

    /** start -> hang -> end. The engine actually dispatches every node here, including "start". */
    static GraphDefinition graph() {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior(HANG_NODE_ID, HANG_BEHAVIOR),
                GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", HANG_NODE_ID), GraphEdge.to(HANG_NODE_ID, "end")));
    }

    public static void main(String[] args) throws Exception {
        Path databaseFile = Path.of(args[0]);
        long revisionAtRunningPrecondition = Long.parseLong(args[1]);

        var store = new SqliteExecutionStore(databaseFile, Clock.fixed(NOW, ZoneOffset.UTC));
        var recorder = ExecutionRecorder.open(store, KEY, WORKER_ID, LEASE_TTL, revisionAtRunningPrecondition);

        var behaviors = new BehaviorRegistry().register(HANG_BEHAVIOR, message -> {
            // Reached only by a real actor the real PekkoExecutionEngine spawned and delivered a real
            // NodeMessage to -- see the class javadoc for why that is exactly the fact this cell needs.
            System.out.println(AT_BOUNDARY);
            System.out.flush();
            return new CompletableFuture<NodeResult>(); // never completes: this node hangs forever
        });

        try (var manager = GraphManager.from(graph());
             var engine = new PekkoExecutionEngine("qa03-engine-adapter-kill");
             var runner = new GraphRunner(manager, engine, behaviors, new ExecutionMonitor())) {
            // Not joined: this stage never completes because "hang" never does, and the point is to
            // keep this process alive under a real dispatch rather than to wait on its outcome.
            runner.execute(SECURITY, INSTANCE_ID, TRAVERSAL_ID, "payload", "graph-v1", null, null, recorder);
            // Long enough that a parent which failed to kill us hits its own timeout and reports that,
            // rather than this process quietly hanging forever and the test hanging with it.
            new CountDownLatch(1).await(10, java.util.concurrent.TimeUnit.MINUTES);
        }
        System.out.println(COMPLETED);
        System.out.flush();
    }
}
