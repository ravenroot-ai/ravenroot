package ai.ravenroot.core.persistence;

import ai.ravenroot.api.application.*;
import ai.ravenroot.api.history.*;
import ai.ravenroot.api.persistence.*;
import java.util.*;

/** Scalar edge metadata shares the aggregate publish lock and survives journal compaction. */
final class InMemoryTraversalSummaries {
    private final Map<ExecutionKey, Map<UUID, NavigableMap<String, Long>>> edges = new HashMap<>();
    void publish(ExecutionKey key, ProcessInstance previous, ProcessInstance next, ExecutionBatch batch) {
        var process = edges.computeIfAbsent(key, ignored -> new HashMap<>());
        for (var traversal : next.traversals().keySet()) if (previous == null || !previous.traversals().containsKey(traversal))
            process.putIfAbsent(traversal, new TreeMap<>());
        for (var event : batch.events()) if (event.eventType().equals("EDGE_TRAVERSED"))
            EdgeTraversalEventData.edgeId(event.payload()).ifPresent(edge -> {
                var counts = process.get(event.traversalId());
                if (counts != null) counts.merge(edge, 1L, Long::sum);
            });
    }
    void purge(ExecutionKey key) { edges.remove(key); }
    HistoryPage<TraversalEdgeSummary> edges(ExecutionKey key, ProcessInstance state, UUID traversal,
            Optional<String> cursor, int limit, int maximum) {
        requireLimit(limit, maximum); String query = "edges:" + limit;
        String after = cursor.map(value -> ExecutionHistoryCursor.decode(key, traversal, query, value)).orElse(null);
        if (state == null || !state.traversals().containsKey(traversal)) return HistoryPage.missing();
        var process = edges.get(key); var counts = process == null ? null : process.get(traversal);
        if (counts == null) return new HistoryPage<>(List.of(), Optional.empty(), HistoryPage.Availability.NOT_RECORDED);
        var selected = (after == null ? counts : counts.tailMap(after, false)).entrySet().stream().limit(limit + 1L).toList();
        boolean more = selected.size() > limit; var rows = more ? selected.subList(0, limit) : selected;
        return new HistoryPage<>(rows.stream().map(row -> new TraversalEdgeSummary(row.getKey(), row.getValue())).toList(),
                more ? Optional.of(ExecutionHistoryCursor.encode(key, traversal, query, rows.getLast().getKey())) : Optional.empty(), HistoryPage.Availability.AVAILABLE);
    }
    static HistoryPage<TraversalNodeSummary> nodes(ExecutionKey key, ProcessInstance state, UUID traversal,
            Optional<String> cursor, int limit, int maximum) {
        requireLimit(limit, maximum); String query = "nodes:" + limit;
        String after = cursor.map(value -> ExecutionHistoryCursor.decode(key, traversal, query, value)).orElse(null);
        if (state == null || !state.traversals().containsKey(traversal)) return HistoryPage.missing();
        var visits = state.traversals().get(traversal).invocations().values();
        var selected = visits.stream().map(NodeInvocation::nodeId).distinct().filter(node -> after == null || node.compareTo(after) > 0).sorted().limit(limit + 1L).toList();
        boolean more = selected.size() > limit; var nodes = more ? selected.subList(0, limit) : selected;
        var items = new ArrayList<TraversalNodeSummary>();
        for (var node : nodes) {
            long count = 0, active = 0, completed = 0, failed = 0, attempts = 0;
            for (var visit : visits) if (node.equals(visit.nodeId())) {
                count++; attempts += visit.attempts().size();
                switch (visit.status()) { case COMPLETED -> completed++; case FAILED -> failed++; default -> active++; }
            }
            items.add(new TraversalNodeSummary(node, count, active, completed, failed, attempts));
        }
        return new HistoryPage<>(items, more ? Optional.of(ExecutionHistoryCursor.encode(key, traversal, query, nodes.getLast())) : Optional.empty(), HistoryPage.Availability.AVAILABLE);
    }
    private static void requireLimit(int limit, int maximum) {
        if (limit < 1 || limit > maximum) throw new IllegalArgumentException("history page size exceeds configured bound");
    }
}
