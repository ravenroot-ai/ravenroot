package ai.ravenroot.core.runtime;

import static org.junit.jupiter.api.Assertions.*;

import ai.ravenroot.api.activity.*;
import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.catalog.NodeRetryProperty;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.ToolCallContinuationAction;
import ai.ravenroot.api.node.ToolCallContinuationInput;
import ai.ravenroot.api.node.ToolCallContinuationResult;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionOrigin;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.core.activity.ActivityCapture;
import ai.ravenroot.core.graph.*;
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import ai.ravenroot.core.persistence.InMemoryJoinStore;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GraphRunnerActivityCaptureTest {
  private final JoinTestEngine engine = new JoinTestEngine();
  private final ExecutionMonitor monitor = new ExecutionMonitor();

  @AfterEach
  void closeEngine() {
    engine.close();
  }

  @Test
  void strictInputAndOutputPersistenceGateInvocationAndDownstreamCompletion() throws Exception {
    var archive = new GatedArchive();
    var entries = new AtomicInteger();
    var behaviors =
        new BehaviorRegistry()
            .register(
                "work",
                message -> {
                  entries.incrementAndGet();
                  return CompletableFuture.completedFuture(NodeResult.continueWith("output"));
                })
            .withActivityCapture(
                new ActivityCapture(
                    policy(
                        Set.of(
                            ActivityContentKind.INPUT_PAYLOAD, ActivityContentKind.OUTPUT_PAYLOAD)),
                    archive));

    try (var manager = GraphManager.from(linearGraph());
        var runner = runner(manager, behaviors)) {
      var execution = runner.execute(TestIdentities.TENANT_A, "input").toCompletableFuture();
      ActivityEvent input = archive.await(ActivityContentKind.INPUT_PAYLOAD);
      assertEquals(0, entries.get(), "node invocation must wait for strict input persistence");
      archive.ack(input);

      ActivityEvent output = archive.await(ActivityContentKind.OUTPUT_PAYLOAD);
      assertEquals(1, entries.get());
      assertFalse(
          execution.isDone(), "completion and downstream routing must wait for output persistence");
      assertEquals(input.eventId(), output.causationActivityId());
      archive.ack(output);
      assertEquals("output", execution.get(5, TimeUnit.SECONDS).payload());
    }
  }

  @Test
  void strictCaptureFailuresBypassAuthoredFailureRouteForInputAndOutput() {
    for (ActivityContentKind failedKind :
        List.of(ActivityContentKind.INPUT_PAYLOAD, ActivityContentKind.OUTPUT_PAYLOAD)) {
      var archive = new FailingArchive(failedKind);
      var workEntries = new AtomicInteger();
      var handlerEntries = new AtomicInteger();
      var behaviors =
          new BehaviorRegistry()
              .register(
                  "work",
                  message -> {
                    workEntries.incrementAndGet();
                    return CompletableFuture.completedFuture(
                        NodeResult.continueWith("effect-result"));
                  })
              .register(
                  "handler",
                  message -> {
                    handlerEntries.incrementAndGet();
                    return CompletableFuture.completedFuture(NodeResult.continueWith("handled"));
                  })
              .withActivityCapture(
                  new ActivityCapture(
                      policy(
                          Set.of(
                              ActivityContentKind.INPUT_PAYLOAD,
                              ActivityContentKind.OUTPUT_PAYLOAD)),
                      archive));
      try (var manager = GraphManager.from(failureRouteGraph());
          var runner = runner(manager, behaviors)) {
        assertThrows(
            ExecutionException.class,
            () ->
                runner
                    .execute(TestIdentities.TENANT_A, "input")
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS));
        assertEquals(failedKind == ActivityContentKind.INPUT_PAYLOAD ? 0 : 1, workEntries.get());
        assertEquals(
            0,
            handlerEntries.get(),
            "required capture failure must be terminal, never an authored handled outcome");
      }
    }
  }

  @Test
  void retryAttemptsProduceDistinctStableEventsAndActualOrdinals() throws Exception {
    var archive = new CollectingArchive();
    var entries = new AtomicInteger();
    var behaviors =
        new BehaviorRegistry()
            .register(
                "work",
                message ->
                    entries.incrementAndGet() == 1
                        ? CompletableFuture.failedFuture(new RetryableFailure())
                        : CompletableFuture.completedFuture(NodeResult.continueWith("ok")))
            .withActivityCapture(
                new ActivityCapture(policy(Set.of(ActivityContentKind.INPUT_PAYLOAD)), archive));
    try (var manager = GraphManager.from(retryGraph());
        var runner = runner(manager, behaviors)) {
      assertEquals(
          "ok",
          runner
              .execute(TestIdentities.TENANT_A, "input")
              .toCompletableFuture()
              .get(5, TimeUnit.SECONDS)
              .payload());
    }
    var inputs =
        archive.events.stream()
            .filter(event -> event.contentKind() == ActivityContentKind.INPUT_PAYLOAD)
            .toList();
    assertEquals(2, inputs.size());
    assertEquals(List.of(1, 2), inputs.stream().map(ActivityEvent::attemptOrdinal).toList());
    assertNotEquals(inputs.get(0).attemptId(), inputs.get(1).attemptId());
    assertNotEquals(inputs.get(0).eventId(), inputs.get(1).eventId());
  }

  @Test
  void strictCaptureGatesToolContinuationEffectAndResultPublication() throws Exception {
    var archive = new GatedArchive();
    var effectEntries = new AtomicInteger();
    var behaviors =
        new BehaviorRegistry()
            .withActivityCapture(
                new ActivityCapture(
                    policy(
                        Set.of(
                            ActivityContentKind.INPUT_PAYLOAD, ActivityContentKind.OUTPUT_PAYLOAD)),
                    archive));
    var key = new ExecutionKey(TestIdentities.TENANT_A.tenantId(), java.util.UUID.randomUUID());
    var traversalId = java.util.UUID.randomUUID();
    try (var store = new InMemoryExecutionStore();
        var manager = GraphManager.from(linearGraph());
        var runner = runner(manager, behaviors)) {
      long revision = acceptReentry(store, key, traversalId);
      try (var recorder =
          ExecutionRecorder.open(
              store, key, "activity-tool-continuation", Duration.ofSeconds(30), revision)) {
        var action =
            new ToolCallContinuationAction() {
              @Override
              public void validate(ToolCallContinuationInput input) {}

              @Override
              public CompletionStage<ToolCallContinuationResult> resume(
                  ToolCallContinuationInput input) {
                effectEntries.incrementAndGet();
                return CompletableFuture.completedFuture(
                    new ToolCallContinuationResult(
                        CompletableFuture.completedFuture(NodeResult.continueWith("resumed")),
                        true));
              }
            };
        var execution =
            runner
                .executeFrom(
                    TestIdentities.TENANT_A,
                    key.processInstanceId(),
                    traversalId,
                    "work",
                    "v1",
                    recorder,
                    message ->
                        new ToolCallContinuationInput(
                            message,
                            java.util.UUID.randomUUID(),
                            java.util.UUID.randomUUID(),
                            java.util.UUID.randomUUID(),
                            java.util.UUID.randomUUID(),
                            "test.effect",
                            new byte[0],
                            "args-digest",
                            ToolCallContinuationInput.Decision.APPROVED,
                            1,
                            new byte[0],
                            "checkpoint-digest"),
                    ignored -> {},
                    action,
                    new GraphExecutionBudgetSnapshot(1, 0, 0, 1, 0))
                .toCompletableFuture();

        ActivityEvent input = archive.await(ActivityContentKind.INPUT_PAYLOAD);
        assertEquals(0, effectEntries.get(), "tool effect must wait for strict input persistence");
        CompletableFuture.runAsync(() -> archive.ack(input));
        ActivityEvent output = archive.await(ActivityContentKind.OUTPUT_PAYLOAD);
        assertEquals(1, effectEntries.get());
        assertFalse(
            execution.isDone(), "tool continuation must not complete before output persistence");
        archive.ack(output);
        assertTrue(execution.get(5, TimeUnit.SECONDS));
      }
    }
  }

  @Test
  void strictCaptureGatesExternalWorkReentryBeforeDownstreamDispatch() throws Exception {
    var archive = new GatedArchive();
    var downstreamEntries = new AtomicInteger();
    var behaviors =
        new BehaviorRegistry()
            .register(
                "after",
                message -> {
                  downstreamEntries.incrementAndGet();
                  return CompletableFuture.completedFuture(
                      NodeResult.continueWith(message.payload()));
                })
            .withActivityCapture(
                new ActivityCapture(policy(Set.of(ActivityContentKind.OUTPUT_PAYLOAD)), archive));
    var key = new ExecutionKey(TestIdentities.TENANT_A.tenantId(), java.util.UUID.randomUUID());
    var traversalId = java.util.UUID.randomUUID();
    try (var store = new InMemoryExecutionStore();
        var manager = GraphManager.from(externalReentryGraph());
        var runner = runner(manager, behaviors)) {
      long revision = acceptReentry(store, key, traversalId);
      try (var recorder =
          ExecutionRecorder.open(
              store, key, "activity-external-reentry", Duration.ofSeconds(30), revision)) {
        var execution =
            CompletableFuture.runAsync(
                () ->
                    runner
                        .executeAfterHumanTask(
                            TestIdentities.TENANT_A,
                            key.processInstanceId(),
                            traversalId,
                            "work",
                            "v1",
                            recorder,
                            NodeResult.continueWith("settled"),
                            new GraphExecutionBudgetSnapshot(1, 0, 0, 1, 0))
                        .toCompletableFuture()
                        .join());
        ActivityEvent output = archive.await(ActivityContentKind.OUTPUT_PAYLOAD);
        assertEquals(
            0,
            downstreamEntries.get(),
            "external-work result must be persisted before downstream dispatch");
        assertFalse(execution.isDone());
        archive.ack(output);
        execution.get(5, TimeUnit.SECONDS);
        assertEquals(1, downstreamEntries.get());
      }
    }
  }

  private GraphRunner runner(GraphManager manager, BehaviorRegistry behaviors) {
    return new GraphRunner(
        manager,
        engine,
        behaviors,
        monitor,
        ExecutionIdentitySource.randomUuids(),
        new InMemoryJoinStore(),
        Clock.systemUTC());
  }

  private static JoinFailureException joinFailure(Throwable error) {
    Throwable current = error;
    while (current != null) {
      if (current instanceof JoinFailureException failure) return failure;
      current = current.getCause();
    }
    throw new AssertionError("expected a JoinFailureException", error);
  }

  private static ActivityCapturePolicy policy(Set<ActivityContentKind> contents) {
    return new ActivityCapturePolicy(
        true,
        ActivityFailurePolicy.STRICT,
        Set.of("work"),
        contents,
        PayloadLimits.DEFAULTS,
        8,
        Duration.ofSeconds(2),
        Duration.ofDays(1),
        ActivityRedactor.none());
  }

  private static GraphDefinition linearGraph() {
    return new GraphDefinition(
        List.of(
            GraphNode.start("start"),
            GraphNode.behavior("work", "work"),
            GraphNode.error("error"),
            GraphNode.end("end")),
        List.of(GraphEdge.to("start", "work"), GraphEdge.to("work", "end")));
  }

  private static GraphDefinition failureRouteGraph() {
    return new GraphDefinition(
        List.of(
            GraphNode.start("start"),
            GraphNode.behavior("work", "work"),
            GraphNode.behavior("handler", "handler"),
            GraphNode.error("error"),
            GraphNode.end("end")),
        List.of(
            GraphEdge.to("start", "work"),
            GraphEdge.to("work", "end"),
            new GraphEdge(
                "work",
                "handler",
                null,
                Map.of(FailureRouteEdgeProperty.NAME, FailureRouteEdgeProperty.TRUE)),
            GraphEdge.to("handler", "end")));
  }

  private static GraphDefinition retryGraph() {
    return new GraphDefinition(
        List.of(
            GraphNode.start("start"),
            new GraphNode(
                "work",
                NodeKind.BEHAVIOR,
                "work",
                Map.of(
                    NodeRetryProperty.MAX_ATTEMPTS,
                    "2",
                    NodeRetryProperty.INITIAL_BACKOFF,
                    "1",
                    NodeRetryProperty.BACKOFF_MULTIPLIER,
                    "1.0",
                    NodeRetryProperty.MAX_BACKOFF,
                    "1",
                    NodeRetryProperty.RETRY_ON,
                    RetryableFailure.class.getSimpleName())),
            GraphNode.error("error"),
            GraphNode.end("end")),
        List.of(GraphEdge.to("start", "work"), GraphEdge.to("work", "end")));
  }

  private static GraphDefinition externalReentryGraph() {
    return new GraphDefinition(
        List.of(
            GraphNode.start("start"),
            GraphNode.behavior("work", "work"),
            GraphNode.behavior("after", "after"),
            GraphNode.error("error"),
            GraphNode.end("end")),
        List.of(
            GraphEdge.to("start", "work"),
            GraphEdge.to("work", "after"),
            GraphEdge.to("after", "end")));
  }

  private static long acceptReentry(
      InMemoryExecutionStore store, ExecutionKey key, java.util.UUID traversalId) {
    var traversal =
        new ai.ravenroot.api.application.Traversal(
            traversalId, "work", ai.ravenroot.api.application.TraversalStatus.ACCEPTED, Map.of());
    var created =
        store
            .apply(
                ExecutionBatch.to(key)
                    .expecting(RevisionExpectation.notPresent())
                    .apply(
                        new ExecutionTransition.ProcessCreated(
                            new ai.ravenroot.api.application.ProcessInstance(
                                key.processInstanceId(),
                                ai.ravenroot.api.application.ProcessInstanceStatus.ACCEPTED,
                                Map.of(traversalId, traversal)),
                            new GraphVersionPin("v1")))
                    .recordOrigin(ExecutionOrigin.of("activity-test", "message-1", null))
                    .build())
            .toCompletableFuture()
            .join();
    return store
        .apply(
            ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(
                    new ExecutionTransition.ProcessTransitioned(
                        ai.ravenroot.api.application.ProcessInstanceStatus.RUNNING))
                .build())
        .toCompletableFuture()
        .join()
        .revision();
  }

  private static class CollectingArchive implements ActivityArchive {
    final CopyOnWriteArrayList<ActivityEvent> events = new CopyOnWriteArrayList<>();
    final AtomicInteger cursors = new AtomicInteger();

    @Override
    public int maximumPageSize() {
      return 10;
    }

    @Override
    public CompletionStage<ActivityAppendResult> append(ActivityEvent event) {
      events.add(event);
      return CompletableFuture.completedFuture(
          new ActivityAppendResult(event.eventId(), cursors.incrementAndGet(), false));
    }

    @Override
    public CompletionStage<ActivityPage> read(String tenantId, ActivityQuery query) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class GatedArchive extends CollectingArchive {
    private final ConcurrentMap<String, CompletableFuture<ActivityAppendResult>> pending =
        new ConcurrentHashMap<>();

    @Override
    public CompletionStage<ActivityAppendResult> append(ActivityEvent event) {
      events.add(event);
      var future = new CompletableFuture<ActivityAppendResult>();
      pending.put(event.eventId(), future);
      return future;
    }

    ActivityEvent await(ActivityContentKind kind) throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (System.nanoTime() < deadline) {
        var found = events.stream().filter(event -> event.contentKind() == kind).findFirst();
        if (found.isPresent()) return found.orElseThrow();
        Thread.sleep(5);
      }
      throw new AssertionError("capture did not reach archive for " + kind);
    }

    void ack(ActivityEvent event) {
      pending
          .get(event.eventId())
          .complete(new ActivityAppendResult(event.eventId(), cursors.incrementAndGet(), false));
    }
  }

  private static final class FailingArchive extends CollectingArchive {
    private final ActivityContentKind failedKind;

    private FailingArchive(ActivityContentKind failedKind) {
      this.failedKind = failedKind;
    }

    @Override
    public CompletionStage<ActivityAppendResult> append(ActivityEvent event) {
      if (event.contentKind() == failedKind) {
        return CompletableFuture.failedFuture(new IllegalStateException("archive unavailable"));
      }
      return super.append(event);
    }
  }

  private static final class RetryableFailure extends RuntimeException {}

  @Test
  void enabledCapturePreservesOrdinaryBranchFailureDiagnosticsForEveryPolicy() {
    for (ActivityFailurePolicy failurePolicy : ActivityFailurePolicy.values()) {
      var archive = new CollectingArchive();
      var behaviors = new BehaviorRegistry();
      for (String node : List.of("b0", "b1", "b2")) {
        behaviors.register(
            node,
            message ->
                "b1".equals(node)
                    ? CompletableFuture.failedFuture(
                        new IllegalStateException("branch exploded"))
                    : CompletableFuture.completedFuture(
                        NodeResult.continueWith("from-" + node)));
      }
      behaviors.withActivityCapture(
          new ActivityCapture(
              new ActivityCapturePolicy(
                  true,
                  failurePolicy,
                  Set.of(),
                  Set.of(ActivityContentKind.INPUT_PAYLOAD),
                  PayloadLimits.DEFAULTS,
                  8,
                  Duration.ofSeconds(2),
                  Duration.ofDays(1),
                  ActivityRedactor.none()),
              archive));

      try (var manager = GraphManager.from(JoinMiniGraphs.fanIn(3, JoinMiniGraphs.quorum(3)));
          var runner = runner(manager, behaviors)) {
        var error =
            assertThrows(
                ExecutionException.class,
                () ->
                    runner
                        .execute(TestIdentities.TENANT_A, "input")
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS),
                failurePolicy.name());
        JoinFailureException join = joinFailure(error);
        assertTrue(
            List.of(join.getSuppressed()).stream()
                .anyMatch(suppressed -> "branch exploded".equals(suppressed.getMessage())),
            failurePolicy + " capture must not change the retained branch failure");
      }
    }
  }
}
