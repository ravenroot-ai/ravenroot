package ai.ravenroot.core.runtime;

import ai.ravenroot.api.correlation.*;
import ai.ravenroot.api.history.*;
import ai.ravenroot.api.persistence.*;
import ai.ravenroot.api.activity.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Source-fenced SDK projection; IDs and cursors never broaden the authenticated scope. */
final class DefaultDeploymentHistoryReader implements DeploymentHistoryReader {
    private final ExecutionStore store;
    private final DeploymentHistorySource source;
    private final BusinessScope scope;
    private final BooleanSupplier current;
    private final BooleanSupplier captureEnabled;
    private final IntSupplier activityMaximum;
    private final Function<ActivityQuery, CompletionStage<ActivityPage>> activity;
    DefaultDeploymentHistoryReader(ExecutionStore store, BusinessScope scope, DeploymentHistorySource source, BooleanSupplier current,
            BooleanSupplier captureEnabled, IntSupplier activityMaximum,
            Function<ActivityQuery, CompletionStage<ActivityPage>> activity) {
        this.store = store; this.scope = scope; this.source = source; this.current = current; this.captureEnabled = captureEnabled;
        this.activityMaximum = activityMaximum; this.activity = activity;
    }
    private void check() {
        if (!current.getAsBoolean()) throw new HistoryReadException(HistoryReadException.Reason.SOURCE_CHANGED);
        if (store == null || !store.supports(StoreCapability.EXECUTION_HISTORY)
                || !store.supports(StoreCapability.PROCESS_INVENTORY))
            throw new HistoryReadException(HistoryReadException.Reason.UNAVAILABLE);
    }
    private final class Read {
        private final long deadline = System.nanoTime() + store.businessHistoryPolicy().readTimeout().toNanos();
        <T> T get(CompletionStage<T> stage) {
            try {
                long left = deadline - System.nanoTime();
                if (left <= 0) throw new TimeoutException();
                return stage.toCompletableFuture().get(left, TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw new HistoryReadException(HistoryReadException.Reason.UNAVAILABLE);
            } catch (TimeoutException unavailable) {
                throw new HistoryReadException(HistoryReadException.Reason.UNAVAILABLE);
            } catch (ExecutionException failure) {
                if (failure.getCause() instanceof RuntimeException cause) throw cause;
                throw new HistoryReadException(HistoryReadException.Reason.UNAVAILABLE);
            }
        }
        ExecutionKey process(UUID process) {
            var key = new ExecutionKey(scope.tenantId(), Objects.requireNonNull(process));
            var row = get(store.findProcessInstance(key)).filter(value ->
                    value.deploymentId().filter(scope.deploymentId()::equals).isPresent()
                    && scope.graphVersion().equals(value.graphVersionPin().reference()));
            if (row.isEmpty()) throw new HistoryReadException(HistoryReadException.Reason.NOT_FOUND);
            return key;
        }
        ExecutionKey selection(UUID process, UUID traversal, UUID invocation, UUID attempt) {
            var key = process(process);
            if (!get(store.executionSelectionExists(key, Objects.requireNonNull(traversal),
                    Optional.ofNullable(invocation), Optional.ofNullable(attempt))))
                throw new HistoryReadException(HistoryReadException.Reason.NOT_FOUND);
            return key;
        }
        void history(String id) {
            store.businessHistoryPolicy().selection().requireId(id, "application correlation id");
            if (get(store.businessHistory(scope, id)).isEmpty())
                throw new HistoryReadException(HistoryReadException.Reason.NOT_FOUND);
        }
    }
    private <T> T read(Function<Read, T> operation) {
        check(); T result = operation.apply(new Read()); check(); return result;
    }
    @Override public BusinessHistoryPolicy policy() { check(); return store.businessHistoryPolicy(); }
    @Override public DeploymentHistorySource source() { return source; }
    private Optional<String> cursor(String view, Optional<String> value, boolean encode) {
        String filter = "reader:" + source.incarnationId() + ':' + view;
        return value.map(v -> encode ? BusinessCursor.encode(scope, filter, List.of(v)) : BusinessCursor.decode(scope, filter, v, 1).getFirst());
    }
    private <T> HistoryPage<T> page(String view, HistoryPage<T> page) {
        return new HistoryPage<>(page.items(), cursor(view, page.nextCursor(), true), page.availability());
    }
    @Override public BusinessHistoryPage histories(BusinessHistoryQuery query) {
        return read(r -> {
            Objects.requireNonNull(query);
            var raw = r.get(store.businessHistories(scope, new BusinessHistoryQuery(query.applicationCorrelationId(), query.publicReference(),
                    query.lifecycles(), cursor("histories", query.cursor(), false), query.limit())));
            return new BusinessHistoryPage(raw.items(), cursor("histories", raw.nextCursor(), true));
        });
    }
    @Override public CorrelatedTraversalPage traversals(String id, Optional<String> cursor, int limit) {
        return read(r -> { r.history(id);
            var raw = r.get(store.correlatedTraversals(scope, id, cursor("traversals", cursor, false), limit));
            return new CorrelatedTraversalPage(raw.items(), cursor("traversals", raw.nextCursor(), true)); });
    }
    @Override public HistoryPage<TraversalSummary> uncorrelatedTraversals(Optional<String> cursor, int limit) {
        return read(r -> page("uncorrelated-traversals", r.get(store.uncorrelatedDeploymentTraversals(scope, cursor("uncorrelated-traversals", cursor, false), limit))));
    }
    @Override public HistoryPage<TraversalSummary> processTraversals(UUID process, Optional<String> cursor, int limit) {
        return read(r -> page("process-traversals", r.get(store.executionTraversals(r.process(process), cursor("process-traversals", cursor, false), limit))));
    }
    @Override public HistoryPage<NodeVisitSummary> visits(UUID process, UUID traversal, Optional<String> node,
            Optional<String> cursor, int limit) {
        return read(r -> page("visits", r.get(store.executionVisits(r.selection(process, traversal, null, null), traversal, node, cursor("visits", cursor, false), limit))));
    }
    @Override public HistoryPage<NodeAttemptSummary> attempts(UUID process, UUID traversal, UUID invocation,
            Optional<String> cursor, int limit) {
        return read(r -> page("attempts", r.get(store.executionVisitAttempts(r.selection(process, traversal, Objects.requireNonNull(invocation), null), traversal, invocation, cursor("attempts", cursor, false), limit))));
    }
    @Override public HistoryPage<VisitParent> parents(UUID process, UUID traversal, UUID invocation,
            Optional<String> cursor, int limit) {
        return read(r -> page("parents", r.get(store.executionVisitParents(r.selection(process, traversal, Objects.requireNonNull(invocation), null), traversal, invocation, cursor("parents", cursor, false), limit))));
    }
    @Override public CorrelatedJoinPage joins(String id, Optional<String> cursor, int limit) {
        return read(r -> { r.history(id); var raw = r.get(store.correlatedJoins(scope, id, Set.of(), cursor("joins", cursor, false), limit));
            return new CorrelatedJoinPage(raw.items(), cursor("joins", raw.nextCursor(), true)); });
    }
    @Override public HistoryActivityPage activity(UUID process, UUID traversal, UUID invocation, UUID attempt,
            long afterCursor, int limit) {
        return read(r -> {
            r.selection(process, traversal, invocation, attempt);
            if (limit < 1 || limit > store.businessHistoryPolicy().maxPageSize())
                throw new IllegalArgumentException("invalid activity history page size");
            if (!captureEnabled.getAsBoolean()) return new HistoryActivityPage(HistoryActivityPage.Availability.CAPTURE_DISABLED, null);
            if (limit > activityMaximum.getAsInt()) throw new IllegalArgumentException("invalid activity history page size");
            try {
                var page = r.get(activity.apply(new ActivityQuery(afterCursor, limit, process, traversal, null,
                        invocation, attempt, Set.of())));
                return new HistoryActivityPage(page.records().isEmpty() ? HistoryActivityPage.Availability.NOT_RETAINED
                        : HistoryActivityPage.Availability.AVAILABLE, page);
            } catch (ActivityArchiveException expired) {
                if (expired.reason() == ActivityArchiveException.Reason.CURSOR_EXPIRED) {
                    long floor = expired.retainedFromCursor();
                    return new HistoryActivityPage(HistoryActivityPage.Availability.EXPIRED, new ActivityPage(List.of(), floor - 1, floor));
                }
                if (expired.reason() == ActivityArchiveException.Reason.INVALID_REQUEST)
                    throw new IllegalArgumentException("invalid history activity query");
                throw new HistoryReadException(HistoryReadException.Reason.UNAVAILABLE);
            }
        });
    }
}
