package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.*;
import ai.ravenroot.api.deployment.*;
import ai.ravenroot.api.persistence.*;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DurableBusinessReplayTest {
    @TempDir Path directory;
    @Test void restartedReplayKeepsBusinessAndOriginalCausalFactsWithoutLoadingExecutionAggregate() throws Exception {
        Path database = directory.resolve("replay.db");
        UUID process, traversal;
        try (var store = new SqliteExecutionStore(database, Clock.systemUTC()); var engine = new SameThreadExecutionEngine()) {
            var contexts = new ConcurrentHashMap<String, InboundSourceContext>();
            var monitor = new ExecutionMonitor();
            var settled = new CountDownLatch(1);
            monitor.subscribe(event -> { if (event.type().isTraversalTerminal()) settled.countDown(); });
            var deployment = SourceLocalTraversalTest.deployment(engine, store, contexts, new CopyOnWriteArrayList<>(),
                    monitor, CorrelatedJoinRuntimeRecoveryTest.GRAPH);
            deployment.start(TestIdentities.TENANT_A).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertInstanceOf(IngressReceipt.DurablyCommitted.class, contexts.get("a").ingress().offerDurably(
                    TestIdentities.TENANT_A, IngressTarget.node("a"), Map.of("operation", "business-replay", "partition", "part-1",
                            "data", "private-input-marker"), "a", "transport-message-1"));
            assertTrue(settled.await(5, TimeUnit.SECONDS));
            var start = monitor.eventsAfter(0).stream().filter(event -> event.type() == ExecutionEventType.EXECUTION_STARTED).findFirst().orElseThrow();
            process = start.processInstanceId(); traversal = start.traversalId();
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        try (var store = new SqliteExecutionStore(database, Clock.systemUTC()); var engine = new SameThreadExecutionEngine()) {
            var metadataReads = new AtomicInteger();
            ExecutionStore metadataOnly = (ExecutionStore) Proxy.newProxyInstance(ExecutionStore.class.getClassLoader(),
                    new Class<?>[]{ExecutionStore.class}, (object, method, args) -> {
                        assertNotEquals("load", method.getName(), "replay must not load a whole process aggregate");
                        assertNotEquals("correlatedJoin", method.getName(), "replay must not read contribution payloads");
                        if (method.getName().equals("executionInvocationNodeIds")) {
                            assertTrue(((Set<?>) args[1]).size() <= 1000); metadataReads.incrementAndGet();
                        }
                        try { return method.invoke(store, args); }
                        catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
                    });
            try (var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor(),
                    BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()),
                    new ai.ravenroot.core.programming.InMemoryArtifactRegistry(),
                    new ai.ravenroot.core.programming.DisabledProgramRuntime(), ExecutionIdentitySource.randomUuids(), metadataOnly)) {
                var tenant = application.durableEventsAfter(TestIdentities.TENANT_A.tenantId(), 0, 1000);
                var selected = application.durableEventPageForProcess(TestIdentities.TENANT_A.tenantId(), process, 0, 1000).events();
                assertEquals(tenant, selected);
                assertEquals(2, metadataReads.get(), "one selected-node binding query per bounded page/process");
                assertFalse(selected.isEmpty());
                assertTrue(selected.stream().allMatch(event -> process.equals(event.processInstanceId()) && traversal.equals(event.traversalId())
                        && "business-replay".equals(event.applicationCorrelation().applicationCorrelationId())));
                assertEquals(Set.of("a", "join"), selected.stream().map(DurableExecutionEvent::nodeId).filter(Objects::nonNull)
                        .collect(java.util.stream.Collectors.toSet()));
                assertTrue(selected.stream().anyMatch(event -> "correlated-waiting".equals(event.outcome())));
                assertTrue(selected.stream().allMatch(event -> !event.toString().contains("private-input-marker")));
                assertTrue(application.durableEventsAfter(TestIdentities.TENANT_B.tenantId(), 0, 1000).isEmpty());
                assertThrows(IllegalArgumentException.class, () -> application.durableEventsAfter(TestIdentities.TENANT_A.tenantId(), 0, 1001));
            }
        }
    }
}
