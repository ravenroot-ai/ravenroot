package ai.ravenroot.core.activity;

import static org.junit.jupiter.api.Assertions.*;

import ai.ravenroot.api.activity.*;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ActivityCaptureTest {
  @Test
  void disabledCaptureDoesNotTouchContentOrArchive() {
    var redactions = new AtomicInteger();
    var policy =
        new ActivityCapturePolicy(
            false,
            ActivityFailurePolicy.BEST_EFFORT,
            Set.of(),
            Set.of(ActivityContentKind.INPUT_PAYLOAD),
            PayloadLimits.DEFAULTS,
            1,
            Duration.ofMillis(10),
            Duration.ofDays(1),
            (kind, value) -> {
              redactions.incrementAndGet();
              return value;
            });
    try (var capture = new ActivityCapture(policy, null)) {
      capture.input(context(), message(Map.of("secret", "value"))).toCompletableFuture().join();
    }
    assertEquals(0, redactions.get());
  }

  @Test
  void strictTimeoutBoundsBlockingAppendAndCapacityWhileBestEffortWarningCannotFailExecution()
      throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    ActivityArchive blocking =
        new ActivityArchive() {
          @Override
          public int maximumPageSize() {
            return 10;
          }

          @Override
          public java.util.concurrent.CompletionStage<ActivityAppendResult> append(
              ActivityEvent event) {
            entered.countDown();
            try {
              release.await();
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(interrupted);
            }
            return CompletableFuture.completedFuture(
                new ActivityAppendResult(event.eventId(), 1, false));
          }

          @Override
          public java.util.concurrent.CompletionStage<ActivityPage> read(
              String tenantId, ActivityQuery query) {
            throw new UnsupportedOperationException();
          }
        };
    var strictPolicy = policy(ActivityFailurePolicy.STRICT, 1, Duration.ofMillis(30));
    try (var capture = new ActivityCapture(strictPolicy, blocking)) {
      var first = capture.input(context(), message(Map.of("value", 1))).toCompletableFuture();
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      var capacity =
          assertThrows(
              java.util.concurrent.CompletionException.class,
              () ->
                  capture
                      .input(context(), message(Map.of("value", 2)))
                      .toCompletableFuture()
                      .join());
      assertEquals(
          ActivityCaptureException.Reason.CAPACITY,
          ((ActivityCaptureException) capacity.getCause()).reason());
      var timeout = assertThrows(java.util.concurrent.CompletionException.class, first::join);
      assertEquals(
          ActivityCaptureException.Reason.TIMEOUT,
          ((ActivityCaptureException) timeout.getCause()).reason());
      release.countDown();
    }

    ActivityArchive failing =
        new ActivityArchive() {
          @Override
          public int maximumPageSize() {
            return 10;
          }

          @Override
          public java.util.concurrent.CompletionStage<ActivityAppendResult> append(
              ActivityEvent event) {
            return CompletableFuture.failedFuture(new IllegalStateException("no payload here"));
          }

          @Override
          public java.util.concurrent.CompletionStage<ActivityPage> read(
              String tenantId, ActivityQuery query) {
            throw new UnsupportedOperationException();
          }
        };
    try (var capture =
        new ActivityCapture(
            policy(ActivityFailurePolicy.BEST_EFFORT, 2, Duration.ofSeconds(1)),
            failing,
            Clock.systemUTC(),
            warning -> {
              throw new IllegalStateException("diagnostic sink failed");
            })) {
      assertDoesNotThrow(
          () -> capture.input(context(), message(Map.of("value", 3))).toCompletableFuture().join());
    }
  }

  @Test
  void rejectsNullOrMismatchedAppendAcknowledgement() {
    for (ActivityAppendResult result :
        new ActivityAppendResult[] {null, new ActivityAppendResult("0".repeat(64), 1, false)}) {
      ActivityArchive archive =
          new ActivityArchive() {
            @Override
            public int maximumPageSize() {
              return 10;
            }

            @Override
            public java.util.concurrent.CompletionStage<ActivityAppendResult> append(
                ActivityEvent event) {
              return CompletableFuture.completedFuture(result);
            }

            @Override
            public java.util.concurrent.CompletionStage<ActivityPage> read(
                String tenantId, ActivityQuery query) {
              throw new UnsupportedOperationException();
            }
          };
      try (var capture =
          new ActivityCapture(
              policy(ActivityFailurePolicy.STRICT, 2, Duration.ofSeconds(1)), archive)) {
        var failure =
            assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> capture.input(context(), message("value")).toCompletableFuture().join());
        assertEquals(
            ActivityCaptureException.Reason.WRITE,
            ((ActivityCaptureException) failure.getCause()).reason());
      }
    }
  }

  @Test
  void stripsSensitiveCauseGraphsFromCaptureAndArchiveFailures() {
    String sentinel = "PAYLOAD-SENTINEL-528";
    var nested = new IllegalArgumentException("nested " + sentinel);
    nested.addSuppressed(new IllegalStateException("suppressed " + sentinel));
    var raw = new IllegalStateException("outer " + sentinel, nested);
    var redacting =
        new ActivityCapturePolicy(
            true,
            ActivityFailurePolicy.STRICT,
            Set.of(),
            Set.of(ActivityContentKind.INPUT_PAYLOAD),
            PayloadLimits.DEFAULTS,
            1,
            Duration.ofSeconds(1),
            Duration.ofDays(1),
            (kind, value) -> {
              throw raw;
            });
    ActivityCaptureException captureFailure;
    try (var capture = new ActivityCapture(redacting, new NeverUsedArchive())) {
      var completion =
          assertThrows(
              java.util.concurrent.CompletionException.class,
              () ->
                  capture
                      .input(context(), message(Map.of("secret", sentinel)))
                      .toCompletableFuture()
                      .join());
      captureFailure = assertInstanceOf(ActivityCaptureException.class, completion.getCause());
    }
    assertNull(captureFailure.getCause());
    assertEquals(IllegalStateException.class.getName(), captureFailure.failureClass());
    assertFalse(stackTrace(captureFailure).contains(sentinel));

    var archiveFailure =
        new ActivityArchiveException(
            ActivityArchiveException.Reason.UNAVAILABLE, "activity archive unavailable", raw);
    assertNull(archiveFailure.getCause());
    assertEquals(IllegalStateException.class.getName(), archiveFailure.failureClass());
    assertFalse(stackTrace(archiveFailure).contains(sentinel));
  }

  private static ActivityCapturePolicy policy(
      ActivityFailurePolicy failurePolicy, int inFlight, Duration timeout) {
    return new ActivityCapturePolicy(
        true,
        failurePolicy,
        Set.of(),
        Set.of(ActivityContentKind.INPUT_PAYLOAD),
        PayloadLimits.DEFAULTS,
        inFlight,
        timeout,
        Duration.ofDays(1),
        ai.ravenroot.api.activity.ActivityRedactor.none());
  }

  private static ActivityCapture.Context context() {
    return new ActivityCapture.Context(
        new SecurityContext("request", "tenant", "subject", PrincipalType.USER, "issuer"),
        "graph",
        "v1",
        "hash",
        UUID.randomUUID(),
        UUID.randomUUID(),
        "node",
        UUID.randomUUID(),
        UUID.randomUUID(),
        1,
        "PROCESS",
        Set.of(),
        null);
  }

  private static NodeMessage message(Object payload) {
    var context = context();
    return new NodeMessage(
        context.security(),
        context.processInstanceId(),
        context.traversalId(),
        context.invocationId(),
        context.attemptId(),
        Set.of(),
        context.nodeId(),
        payload,
        Map.of());
  }

  private static String stackTrace(Throwable failure) {
    var output = new java.io.StringWriter();
    failure.printStackTrace(new java.io.PrintWriter(output));
    return output.toString();
  }

  private static final class NeverUsedArchive implements ActivityArchive {
    @Override
    public int maximumPageSize() {
      return 1;
    }

    @Override
    public java.util.concurrent.CompletionStage<ActivityAppendResult> append(ActivityEvent event) {
      throw new AssertionError("redaction must fail before archive access");
    }

    @Override
    public java.util.concurrent.CompletionStage<ActivityPage> read(
        String tenantId, ActivityQuery query) {
      throw new AssertionError("not used");
    }
  }
}
