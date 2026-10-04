package ai.ravenroot.core.activity;

import ai.ravenroot.api.activity.ActivityArchive;
import ai.ravenroot.api.activity.ActivityCapturePolicy;
import ai.ravenroot.api.activity.ActivityContentKind;
import ai.ravenroot.api.activity.ActivityEvent;
import ai.ravenroot.api.activity.ActivityFailurePolicy;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.security.SecurityContext;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Engine-neutral bounded capture boundary shared by embedded and Service composition. */
public final class ActivityCapture implements AutoCloseable {
  private static final System.Logger LOG = System.getLogger(ActivityCapture.class.getName());
  private static final CompletionStage<Void> DONE = CompletableFuture.completedFuture(null);
  private static final ActivityCapture DISABLED =
      new ActivityCapture(
          ActivityCapturePolicy.DISABLED, null, Clock.systemUTC(), ActivityCapture::logWarning);
  private static final java.util.concurrent.ScheduledThreadPoolExecutor TIMEOUTS =
      timeoutExecutor();

  private final ActivityCapturePolicy policy;
  private final ActivityArchive archive;
  private final Clock clock;
  private final Consumer<ActivityCaptureWarning> warnings;
  private final AtomicInteger inFlight = new AtomicInteger();
  private final java.util.concurrent.ThreadPoolExecutor appendExecutor;

  public ActivityCapture(ActivityCapturePolicy policy, ActivityArchive archive) {
    this(policy, archive, Clock.systemUTC(), ActivityCapture::logWarning);
  }

  public ActivityCapture(
      ActivityCapturePolicy policy,
      ActivityArchive archive,
      Clock clock,
      Consumer<ActivityCaptureWarning> warnings) {
    this.policy = Objects.requireNonNull(policy, "policy");
    if (policy.enabled()) this.archive = Objects.requireNonNull(archive, "archive");
    else this.archive = archive;
    this.clock = Objects.requireNonNull(clock, "clock");
    this.warnings = Objects.requireNonNull(warnings, "warnings");
    if (policy.enabled()) {
      int workers = Math.min(4, policy.maxInFlightWrites());
      this.appendExecutor =
          new java.util.concurrent.ThreadPoolExecutor(
              workers,
              workers,
              0L,
              TimeUnit.MILLISECONDS,
              new java.util.concurrent.ArrayBlockingQueue<>(policy.maxInFlightWrites()),
              runnable -> {
                Thread thread = new Thread(runnable, "ravenroot-activity-append");
                thread.setDaemon(true);
                return thread;
              },
              new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    } else {
      this.appendExecutor = null;
    }
  }

  public static ActivityCapture disabled() {
    return DISABLED;
  }

  public boolean enabled() {
    return policy.enabled();
  }

  public int maximumPageSize() {
    return policy.enabled() ? archive.maximumPageSize() : 0;
  }

  /** Reads a tenant-scoped retained page without exposing the storage adapter to callers. */
  public CompletionStage<ai.ravenroot.api.activity.ActivityPage> read(
      String tenantId, ai.ravenroot.api.activity.ActivityQuery query) {
    if (!policy.enabled()) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("activity content capture is disabled"));
    }
    return archive.read(
        Objects.requireNonNull(tenantId, "tenantId"), Objects.requireNonNull(query, "query"));
  }

  /** Immutable metadata for one attempt; it contains no payload. */
  public record Context(
      SecurityContext security,
      String graphId,
      String graphVersion,
      String graphHash,
      UUID processInstanceId,
      UUID traversalId,
      String nodeId,
      UUID invocationId,
      UUID attemptId,
      int attemptOrdinal,
      String command,
      Set<UUID> parentInvocationIds,
      UUID journalCausationId) {
    public Context {
      Objects.requireNonNull(security, "security");
      Objects.requireNonNull(graphId, "graphId");
      Objects.requireNonNull(graphVersion, "graphVersion");
      Objects.requireNonNull(graphHash, "graphHash");
      Objects.requireNonNull(processInstanceId, "processInstanceId");
      Objects.requireNonNull(traversalId, "traversalId");
      Objects.requireNonNull(nodeId, "nodeId");
      Objects.requireNonNull(invocationId, "invocationId");
      Objects.requireNonNull(attemptId, "attemptId");
      if (attemptOrdinal < 1) throw new IllegalArgumentException("attemptOrdinal must be positive");
      Objects.requireNonNull(command, "command");
      parentInvocationIds =
          Set.copyOf(Objects.requireNonNull(parentInvocationIds, "parentInvocationIds"));
    }
  }

  /** Captures selected input slots; STRICT completion gates invocation. */
  public CompletionStage<Void> input(Context context, NodeMessage message) {
    if (!policy.enabled()) return DONE;
    var selected = new ArrayList<Content>();
    if (policy.selects(context.nodeId(), ActivityContentKind.INPUT_PAYLOAD)) {
      selected.add(new Content(ActivityContentKind.INPUT_PAYLOAD, message.payload(), null));
    }
    if (policy.selects(context.nodeId(), ActivityContentKind.INPUT_ATTRIBUTES)) {
      selected.add(new Content(ActivityContentKind.INPUT_ATTRIBUTES, message.attributes(), null));
    }
    return capture(context, selected);
  }

  /** Captures selected output slots; STRICT completion gates runtime completion and routing. */
  public CompletionStage<Void> output(Context context, NodeResult result) {
    if (!policy.enabled()) return DONE;
    ActivityContentKind inputKind =
        policy.selects(context.nodeId(), ActivityContentKind.INPUT_PAYLOAD)
            ? ActivityContentKind.INPUT_PAYLOAD
            : policy.selects(context.nodeId(), ActivityContentKind.INPUT_ATTRIBUTES)
                ? ActivityContentKind.INPUT_ATTRIBUTES
                : null;
    String inputCause =
        inputKind == null
            ? null
            : ActivityEvent.stableId(
                context.security().tenantId(),
                context.graphId(),
                context.graphVersion(),
                context.graphHash(),
                context.processInstanceId(),
                context.traversalId(),
                context.nodeId(),
                context.invocationId(),
                context.attemptId(),
                inputKind);
    var selected = new ArrayList<Content>();
    if (policy.selects(context.nodeId(), ActivityContentKind.OUTPUT_PAYLOAD)) {
      selected.add(
          new Content(ActivityContentKind.OUTPUT_PAYLOAD, result.payload(), result.outcome()));
    }
    if (policy.selects(context.nodeId(), ActivityContentKind.OUTPUT_ATTRIBUTES)) {
      selected.add(
          new Content(
              ActivityContentKind.OUTPUT_ATTRIBUTES, result.attributes(), result.outcome()));
    }
    return capture(context, selected, inputCause);
  }

  private CompletionStage<Void> capture(Context context, List<Content> selected) {
    return capture(context, selected, null);
  }

  private CompletionStage<Void> capture(Context context, List<Content> selected, String cause) {
    if (selected.isEmpty()) return DONE;
    if (!reserve(selected.size())) {
      selected.forEach(
          content ->
              failed(
                  context,
                  content.kind(),
                  ActivityCaptureException.Reason.CAPACITY,
                  eventId(context, content.kind()),
                  null));
      return policy.failurePolicy() == ActivityFailurePolicy.STRICT
          ? CompletableFuture.failedFuture(
              new ActivityCaptureException(
                  ActivityCaptureException.Reason.CAPACITY,
                  eventId(context, selected.getFirst().kind()),
                  null))
          : DONE;
    }
    var stages = new ArrayList<CompletionStage<Void>>(selected.size());
    int created = 0;
    try {
      for (Content content : selected) {
        String eventId = eventId(context, content.kind());
        Object redacted;
        try {
          redacted = policy.redactor().redact(content.kind(), content.value());
        } catch (RuntimeException failure) {
          release(selected.size() - created);
          failed(
              context, content.kind(), ActivityCaptureException.Reason.REDACTION, eventId, failure);
          return strictOrDone(ActivityCaptureException.Reason.REDACTION, eventId, failure);
        }
        byte[] encoded;
        try {
          encoded = PayloadJson.writeJava(redacted, policy.payloadLimits());
        } catch (RuntimeException failure) {
          release(selected.size() - created);
          failed(
              context, content.kind(), ActivityCaptureException.Reason.ENCODING, eventId, failure);
          return strictOrDone(ActivityCaptureException.Reason.ENCODING, eventId, failure);
        }
        Instant occurredAt = clock.instant();
        Instant expiresAt;
        try {
          expiresAt = occurredAt.plus(policy.retention());
        } catch (DateTimeException overflow) {
          release(selected.size() - created);
          failed(
              context, content.kind(), ActivityCaptureException.Reason.ENCODING, eventId, overflow);
          return strictOrDone(ActivityCaptureException.Reason.ENCODING, eventId, overflow);
        }
        var event =
            new ActivityEvent(
                eventId,
                context.security().tenantId(),
                context.graphId(),
                context.graphVersion(),
                context.graphHash(),
                context.processInstanceId(),
                context.traversalId(),
                context.nodeId(),
                context.invocationId(),
                context.attemptId(),
                context.attemptOrdinal(),
                content.kind(),
                context.command(),
                content.outcome(),
                occurredAt,
                expiresAt,
                context.parentInvocationIds(),
                context.journalCausationId(),
                cause,
                OpaquePayload.of(encoded, "application/json"));
        created++;
        stages.add(write(context, event));
      }
    } catch (RuntimeException unexpected) {
      release(selected.size() - created);
      throw unexpected;
    }
    CompletionStage<Void> all =
        CompletableFuture.allOf(
            stages.stream()
                .map(CompletionStage::toCompletableFuture)
                .toArray(CompletableFuture[]::new));
    return policy.failurePolicy() == ActivityFailurePolicy.STRICT ? all : DONE;
  }

  private CompletionStage<Void> write(Context context, ActivityEvent event) {
    var timed = new CompletableFuture<Void>();
    java.util.concurrent.ScheduledFuture<?> timeout =
        TIMEOUTS.schedule(
            () ->
                timed.completeExceptionally(
                    new ActivityCaptureException(
                        ActivityCaptureException.Reason.TIMEOUT, event.eventId(), null)),
            policy.writeTimeout().toNanos(),
            TimeUnit.NANOSECONDS);
    timed.whenComplete((ignored, failure) -> timeout.cancel(false));
    try {
      appendExecutor.execute(
          () -> {
            CompletionStage<ai.ravenroot.api.activity.ActivityAppendResult> original;
            try {
              original = Objects.requireNonNull(archive.append(event), "archive append stage");
            } catch (RuntimeException failure) {
              release(1);
              timed.completeExceptionally(
                  new ActivityCaptureException(
                      ActivityCaptureException.Reason.WRITE, event.eventId(), failure));
              return;
            }
            try {
              original.whenComplete(
                  (result, failure) -> {
                    release(1);
                    if (failure != null) {
                      timed.completeExceptionally(
                          new ActivityCaptureException(
                              ActivityCaptureException.Reason.WRITE,
                              event.eventId(),
                              unwrap(failure)));
                    } else if (result == null || !event.eventId().equals(result.eventId())) {
                      timed.completeExceptionally(
                          new ActivityCaptureException(
                              ActivityCaptureException.Reason.WRITE, event.eventId(), null));
                    } else {
                      timed.complete(null);
                    }
                  });
            } catch (RuntimeException failure) {
              release(1);
              timed.completeExceptionally(
                  new ActivityCaptureException(
                      ActivityCaptureException.Reason.WRITE, event.eventId(), failure));
            }
          });
    } catch (RuntimeException rejected) {
      release(1);
      timed.completeExceptionally(
          new ActivityCaptureException(
              ActivityCaptureException.Reason.CAPACITY, event.eventId(), rejected));
    }
    if (policy.failurePolicy() == ActivityFailurePolicy.BEST_EFFORT) {
      timed.whenComplete(
          (ignored, failure) -> {
            if (failure != null) {
              Throwable cause = unwrap(failure);
              ActivityCaptureException.Reason reason =
                  cause instanceof ActivityCaptureException capture
                      ? capture.reason()
                      : ActivityCaptureException.Reason.WRITE;
              failed(context, event.contentKind(), reason, event.eventId(), cause);
            }
          });
    }
    return timed;
  }

  private CompletionStage<Void> strictOrDone(
      ActivityCaptureException.Reason reason, String eventId, Throwable failure) {
    return policy.failurePolicy() == ActivityFailurePolicy.STRICT
        ? CompletableFuture.failedFuture(new ActivityCaptureException(reason, eventId, failure))
        : DONE;
  }

  private boolean reserve(int count) {
    while (true) {
      int current = inFlight.get();
      if (count > policy.maxInFlightWrites() - current) return false;
      if (inFlight.compareAndSet(current, current + count)) return true;
    }
  }

  private void release(int count) {
    if (count > 0) inFlight.addAndGet(-count);
  }

  private String eventId(Context context, ActivityContentKind kind) {
    return ActivityEvent.stableId(
        context.security().tenantId(),
        context.graphId(),
        context.graphVersion(),
        context.graphHash(),
        context.processInstanceId(),
        context.traversalId(),
        context.nodeId(),
        context.invocationId(),
        context.attemptId(),
        kind);
  }

  private void failed(
      Context context,
      ActivityContentKind kind,
      ActivityCaptureException.Reason reason,
      String eventId,
      Throwable failure) {
    try {
      Throwable sanitized = failure == null ? null : unwrap(failure);
      String failureClass =
          sanitized instanceof ActivityCaptureException capture && capture.failureClass() != null
              ? capture.failureClass()
              : sanitized == null ? null : sanitized.getClass().getName();
      warnings.accept(
          new ActivityCaptureWarning(
              reason,
              eventId,
              context.security().tenantId(),
              context.processInstanceId(),
              context.traversalId(),
              context.nodeId(),
              context.invocationId(),
              context.attemptId(),
              kind,
              failureClass));
    } catch (RuntimeException warningFailure) {
      // A diagnostic callback has no authority to change graph execution, especially fail-open.
      LOG.log(
          System.Logger.Level.WARNING,
          "activity capture warning callback failed: {0}",
          warningFailure.getClass().getName());
    }
  }

  private static void logWarning(ActivityCaptureWarning warning) {
    LOG.log(
        System.Logger.Level.WARNING,
        "activity capture loss reason={0} eventId={1} tenant={2} process={3} traversal={4} "
            + "node={5} invocation={6} attempt={7} content={8} errorClass={9}",
        warning.reason(),
        warning.eventId(),
        warning.tenantId(),
        warning.processInstanceId(),
        warning.traversalId(),
        warning.nodeId(),
        warning.invocationId(),
        warning.attemptId(),
        warning.contentKind(),
        warning.errorClass());
  }

  private static Throwable unwrap(Throwable failure) {
    Throwable current = failure;
    while ((current instanceof java.util.concurrent.CompletionException
            || current instanceof java.util.concurrent.ExecutionException)
        && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private record Content(ActivityContentKind kind, Object value, String outcome) {}

  @Override
  public void close() {
    if (appendExecutor != null) appendExecutor.shutdown();
  }

  private static java.util.concurrent.ScheduledThreadPoolExecutor timeoutExecutor() {
    var executor =
        new java.util.concurrent.ScheduledThreadPoolExecutor(
            1,
            runnable -> {
              Thread thread = new Thread(runnable, "ravenroot-activity-timeouts");
              thread.setDaemon(true);
              return thread;
            });
    executor.setRemoveOnCancelPolicy(true);
    return executor;
  }
}
