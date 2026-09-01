package ai.ravenroot.api.execution;

import java.io.Serial;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * The bounded memory an engine keeps of nodes that have already terminated (CORE-04).
 *
 * <h2>Why anything is retained at all</h2>
 * <p>{@link ExecutionEngine#send} owes two different answers to two different callers: a node that
 * terminated refuses with {@link NodeNotAcceptingException}, which is an ordinary outcome the caller
 * may retry or route elsewhere, while a reference the engine never issued fails with
 * {@link IllegalArgumentException}, which is a defect in the caller. An engine that forgets a node the
 * instant it terminates cannot tell those two apart and has to report the second for both.</p>
 *
 * <h2>Why it is bounded</h2>
 * <p>Ravenroot spawns one node per <em>graph node per {@code GraphRunner}</em>, and the application
 * layer builds a new {@code GraphRunner} for every graph execution while sharing one engine for the
 * life of the application. Retention therefore grows with the number of executions multiplied by the
 * graph's size, not with the size of the graph, so "remember every node ever spawned" is unbounded
 * growth on the ordinary production path rather than a small constant.</p>
 *
 * <p>The bound is a capacity on <em>terminated</em> nodes only. A node that still exists is never held
 * here and never ages out: it lives in the engine's live registry until it terminates, so bounding
 * this history cannot change the answer {@code status} gives for a node that is still running,
 * draining or cancelling. What it can change is the answer for a node that terminated long ago and
 * whose reference nobody has touched since — beyond the capacity, that reference degrades from
 * "terminated" to "never issued". Eviction is therefore least-recently-used rather than
 * least-recently-created: every {@link #get(NodeRef)}, and so every {@code status} and every
 * {@code send}, renews the entry, which keeps exactly the references a caller is still using.</p>
 */
public final class TerminalNodeHistory {
    /**
     * How many terminated nodes an engine remembers by default.
     *
     * <p>Large enough that a caller holding a reference from a recent execution always gets the
     * accurate answer, small enough that the worst case is a few hundred kilobytes of immutable
     * snapshots rather than an unbounded leak: each entry is one {@link NodeStatus} and one already
     * completed stage, and neither the node, its behaviour nor its actor is reachable from here.</p>
     */
    public static final int DEFAULT_CAPACITY = 1024;

    private final int capacity;
    private final Map<NodeRef, Entry> entries;

    /**
     * What is remembered about a terminated node.
     *
     * @param status     the immutable terminal snapshot, with no outstanding messages by definition
     * @param terminated the stage the node's own termination completed, so a late {@code stop} or
     *                   {@code cancel} still reports an {@code onStop} that failed rather than
     *                   silently reporting success
     */
    public record Entry(NodeStatus status, CompletionStage<Void> terminated) {
/**
 * Ensures the cached terminal snapshot and completion stage are both present.
 */
        public Entry {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(terminated, "terminated");
            if (!status.state().terminal()) {
                throw new IllegalArgumentException(
                        "Only a terminated node belongs in the history, but state was " + status.state());
            }
        }
    }

/**
 * Creates the default bounded history used to distinguish recently terminated references.
 */
    public TerminalNodeHistory() {
        this(DEFAULT_CAPACITY);
    }

/**
 * Creates a least-recently-used terminal history with a fixed retention limit.
 *
 * @param capacity maximum number of terminal nodes retained before oldest entries are evicted
 */
    public TerminalNodeHistory(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Serial
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<NodeRef, Entry> eldest) {
                return size() > TerminalNodeHistory.this.capacity;
            }
        };
    }

/**
 * The maximum number of terminated nodes retained at once.
 * @return the configured maximum number of retained terminal entries
 */
    public int capacity() {
        return capacity;
    }

/**
 * How many terminated nodes are retained right now.
 * @return the number of terminal entries currently retained
 */
    public int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    /**
     * Retains one terminated node, evicting the least recently used entry when the capacity is
     * exceeded.
     *
     * @throws IllegalArgumentException if the lifecycle has not terminated
 * @param lifecycle a terminated lifecycle whose status and completion are to be retained
     */
    public void record(NodeLifecycle lifecycle) {
        Objects.requireNonNull(lifecycle, "lifecycle");
        var entry = new Entry(lifecycle.status(), lifecycle.terminated());
        synchronized (entries) {
            entries.put(lifecycle.node(), entry);
        }
    }

/**
 * What is remembered about {@code node}, renewing it against eviction.
 * @param node the engine-issued reference to look up
 * @return the retained terminal snapshot, if it has not been evicted
 */
    public Optional<Entry> get(NodeRef node) {
        if (node == null) {
            return Optional.empty();
        }
        synchronized (entries) {
            return Optional.ofNullable(entries.get(node));
        }
    }

    /** Forgets every retained node. Called by an engine that is closing and owes nobody an answer. */
    public void clear() {
        synchronized (entries) {
            entries.clear();
        }
    }
}
