package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.*;
import ai.ravenroot.api.persistence.*;
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** The result projection and business lifecycle remain separate authorities during a read. */
class ExecutionResultReadCoherenceTest {
    private static final String TENANT = "result-reader";
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    private static final class Fixture implements AutoCloseable {
        final UUID traversal = UUID.randomUUID();
        final ExecutionKey key = new ExecutionKey(TENANT, UUID.randomUUID());
        final InMemoryExecutionStore backing = new InMemoryExecutionStore();
        final AtomicInteger resultReads = new AtomicInteger();
        final AtomicInteger aggregateReads = new AtomicInteger();
        final SameThreadExecutionEngine engine = new SameThreadExecutionEngine();
        final DefaultRavenrootApplication app;
        final ExecutionResultRegistry registry;
        Supplier<Optional<DurableExecutionResult>> result = Optional::empty;
        Runnable afterAggregateRead = () -> {};
        RuntimeException aggregateFailure;

        Fixture() throws Exception {
            UUID invocation = UUID.randomUUID();
            var completedVisit = new NodeInvocation(invocation, "start", Set.of(), NodeInvocationStatus.COMPLETED,
                    List.of(new NodeAttempt(UUID.randomUUID(), 1, NodeAttemptStatus.COMPLETED,
                            NodeAttemptCompletion.SUCCEEDED, null, 0)));
            backing.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent())
                    .apply(new ExecutionTransition.ProcessCreated(new ProcessInstance(key.processInstanceId(),
                            ProcessInstanceStatus.RUNNING, Map.of(traversal, new Traversal(traversal, "start",
                            TraversalStatus.RUNNING, Map.of(invocation, completedVisit)))), new GraphVersionPin("result-read")))
                    .build()).toCompletableFuture().join();
            ExecutionStore store = (ExecutionStore) Proxy.newProxyInstance(ExecutionStore.class.getClassLoader(),
                    new Class<?>[]{ExecutionStore.class}, (proxy, method, args) -> {
                        if (method.getName().equals("loadExecutionResult")) {
                            resultReads.incrementAndGet();
                            return CompletableFuture.completedFuture(TENANT.equals(args[0])
                                    && traversal.equals(args[1]) ? result.get() : Optional.empty());
                        }
                        if (method.getName().equals("load")) {
                            aggregateReads.incrementAndGet();
                            if (aggregateFailure != null) return CompletableFuture.failedFuture(aggregateFailure);
                            var actual = backing.load((ExecutionKey) args[0]);
                            afterAggregateRead.run();
                            return actual;
                        }
                        try { return method.invoke(backing, args); }
                        catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
            app = new DefaultRavenrootApplication(engine, new ExecutionMonitor(), new BehaviorRegistry(),
                    new InMemoryArtifactRegistry(), new DisabledProgramRuntime(),
                    ExecutionIdentitySource.randomUuids(), store);
            var field = DefaultRavenrootApplication.class.getDeclaredField("executionResults");
            field.setAccessible(true);
            registry = (ExecutionResultRegistry) field.get(app);
            // The real registry's normal accepted-entry operation; the store remains independently controlled.
            registry.started(new ExecutionResultRegistry.Key(TENANT, traversal), key.processInstanceId());
        }
        void lifecycle(TraversalStatus status, ExecutionTerminationReason reason) {
            backing.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.any())
                    .apply(new ExecutionTransition.TraversalTransitioned(traversal, status, reason))
                    .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.valueOf(status.name())))
                    .build()).toCompletableFuture().join();
        }
        DurableExecutionResult completed(Object value) {
            return DurableExecutionResult.of(key, traversal, new GraphVersionPin("result-read"),
                    ProcessInstanceStatus.COMPLETED, null, NOW, NOW, value,
                    ExecutionResultNodes.of(List.of("start", "end"), List.of(), List.of(), List.of(), List.of()),
                    null, 1024);
        }
        ExecutionLookup read() { return app.executionResult(TENANT, traversal); }
        @Override public void close() { app.close(); engine.close(); backing.close(); }
    }

    @Test void refreshesOneCapturedRunningProjectionToTheActualPublishedResult() throws Exception {
        try (var f = new Fixture()) {
            f.lifecycle(TraversalStatus.COMPLETED, null);
            var result = f.completed("hello");
            f.result = () -> f.resultReads.get() == 1 ? Optional.empty() : Optional.of(result);
            var found = assertInstanceOf(ExecutionLookup.Found.class, f.read());
            assertEquals("hello", found.outcome().payload());
            assertEquals(Set.of("start", "end"), found.outcome().visitedNodes());
            assertEquals(2, f.resultReads.get());
            assertEquals(1, f.aggregateReads.get());
        }
    }

    @Test void unpublishedSuccessRemainsNonterminalWithoutWaitingOrRetrying() throws Exception {
        try (var f = new Fixture()) {
            f.lifecycle(TraversalStatus.COMPLETED, null);
            var found = assertInstanceOf(ExecutionLookup.Found.class, f.read());
            assertEquals(ProcessInstanceStatus.RUNNING, found.outcome().status());
            assertNull(found.outcome().payload());
            assertEquals(2, f.resultReads.get());
            assertEquals(1, f.aggregateReads.get());
            f.result = () -> Optional.of(f.completed("later"));
            assertEquals("later", assertInstanceOf(ExecutionLookup.Found.class, f.read()).outcome().payload());
        }
    }

    @Test void nullPayloadIsAValidPublishedCompletion() throws Exception {
        try (var f = new Fixture()) {
            f.lifecycle(TraversalStatus.COMPLETED, null);
            var result = f.completed(null);
            f.result = () -> f.resultReads.get() == 1 ? Optional.empty() : Optional.of(result);
            var found = assertInstanceOf(ExecutionLookup.Found.class, f.read());
            assertEquals(ProcessInstanceStatus.COMPLETED, found.outcome().status());
            assertNull(found.outcome().payload());
            assertEquals(Set.of("start", "end"), found.outcome().visitedNodes());
        }
    }

    @Test void typedExpiredAndRedactedRefreshesCannotBecomePlaceholderSuccess() throws Exception {
        for (boolean expired : List.of(true, false)) try (var f = new Fixture()) {
            f.lifecycle(TraversalStatus.COMPLETED, null);
            var full = f.completed("hello");
            var result = expired ? full.expired() : new DurableExecutionResult(full.key(), full.traversalId(),
                    full.graphVersionPin(), full.status(), full.terminationReason(), full.startedAt(), full.endedAt(),
                    full.retainedUntil(), ExecutionResultPayload.withheld(2048, "application/json"), null, full.nodes());
            f.result = () -> f.resultReads.get() == 1 ? Optional.empty() : Optional.of(result);
            var lookup = f.read();
            if (expired) assertInstanceOf(ExecutionLookup.Expired.class, lookup);
            else assertInstanceOf(ExecutionLookup.Redacted.class, lookup);
            assertEquals(2, f.resultReads.get());
        }
    }

    @Test void refreshedUnknownAndTerminalFailuresKeepTheirOwnClassification() throws Exception {
        try (var f = new Fixture()) {
            f.lifecycle(TraversalStatus.COMPLETED, null);
            f.afterAggregateRead = () -> f.registry.forgetLocally(new ExecutionResultRegistry.Key(TENANT, f.traversal));
            assertInstanceOf(ExecutionLookup.Unknown.class, f.read());
            assertEquals(2, f.resultReads.get());
        }
        for (var reason : Arrays.asList(null, ExecutionTerminationReason.CANCELLED)) try (var f = new Fixture()) {
            f.lifecycle(TraversalStatus.COMPLETED, null);
            var failed = DurableExecutionResult.of(f.key, f.traversal, new GraphVersionPin("result-read"),
                    ProcessInstanceStatus.FAILED, reason, NOW, NOW, null, ExecutionResultNodes.empty(), null, 1024);
            f.result = () -> f.resultReads.get() == 1 ? Optional.empty() : Optional.of(failed);
            var outcome = assertInstanceOf(ExecutionLookup.Found.class, f.read()).outcome();
            assertEquals(ProcessInstanceStatus.FAILED, outcome.status());
            assertEquals(reason, outcome.terminationReason());
        }
    }

    @Test void durableWaitingThenCompletionAndCompensatedFailureRemainAuthoritative() throws Exception {
        try (var f = new Fixture()) {
            var result = f.completed("graph complete");
            f.result = () -> Optional.of(result);
            f.lifecycle(TraversalStatus.WAITING, null);
            var waiting = assertInstanceOf(ExecutionLookup.Found.class, f.read()).outcome();
            assertEquals(ProcessInstanceStatus.WAITING, waiting.status());
            assertNull(waiting.payload());
            assertEquals(Set.of("start", "end"), waiting.visitedNodes());
            f.lifecycle(TraversalStatus.RUNNING, null);
            f.lifecycle(TraversalStatus.COMPLETED, null);
            assertEquals("graph complete", assertInstanceOf(ExecutionLookup.Found.class, f.read()).outcome().payload());
        }
        for (boolean cancelled : List.of(false, true)) try (var f = new Fixture()) {
            var result = f.completed("graph complete");
            f.result = () -> Optional.of(result);
            f.lifecycle(TraversalStatus.WAITING, null);
            var reason = cancelled ? ExecutionTerminationReason.CANCELLED : null;
            f.lifecycle(TraversalStatus.FAILED, reason);
            var failed = assertInstanceOf(ExecutionLookup.Found.class, f.read()).outcome();
            assertEquals(ProcessInstanceStatus.FAILED, failed.status());
            assertEquals(reason, failed.terminationReason());
            assertNull(failed.payload());
        }
    }

    @Test void coldReaderTenantIsolationAndStoreFailuresKeepExistingSemantics() throws Exception {
        try (var f = new Fixture()) {
            f.lifecycle(TraversalStatus.COMPLETED, null);
            var result = f.completed("durable");
            f.result = () -> Optional.of(result);
            f.registry.forgetLocally(new ExecutionResultRegistry.Key(TENANT, f.traversal));
            assertEquals("durable", assertInstanceOf(ExecutionLookup.Found.class, f.read()).outcome().payload());
            assertInstanceOf(ExecutionLookup.Unknown.class, f.app.executionResult("another-tenant", f.traversal));
        }
        try (var f = new Fixture()) {
            f.aggregateFailure = new IllegalStateException("store unavailable");
            assertThrows(RuntimeException.class, f::read);
        }
        try (var f = new Fixture()) {
            f.lifecycle(TraversalStatus.COMPLETED, null);
            f.result = () -> {
                if (f.resultReads.get() == 2) throw new IllegalStateException("result store unavailable");
                return Optional.empty();
            };
            assertThrows(RuntimeException.class, f::read);
            assertEquals(2, f.resultReads.get());
        }
    }
}
