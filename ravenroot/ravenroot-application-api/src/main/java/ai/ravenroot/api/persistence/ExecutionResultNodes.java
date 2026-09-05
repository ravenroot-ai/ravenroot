package ai.ravenroot.api.persistence;

import ai.ravenroot.api.application.RuntimeActivityData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The five node and edge sets a terminal execution reports, in the one form a durable record can
 * hold: deduplicated, ordered, and bounded with the overflow written down rather than dropped.
 *
 * <h2>Ordered, because the in-memory shape is not storable as it stands</h2>
 * <p>{@code ExecutionOutcome} carries these as {@link Set}s with explicitly unspecified iteration
 * order. That is correct for a value a caller reads and discards, and unusable for a durable record:
 * two writes of the same result would serialise to different byte sequences, so the idempotency
 * comparison that refuses a conflicting outcome would refuse an identical one instead — the exact
 * failure mode duplicate-delivery handling exists to prevent. Ascending order is imposed here, once,
 * so every adapter and every comparison agrees without having to.</p>
 *
 * <h2>Bounded, and the bound is visible in the data</h2>
 * <p>These sets are unbounded in principle — an execution may enter as many nodes as the graph has —
 * so a durable record that copied them verbatim would let one pathological run write an unbounded
 * number of rows. Each is therefore capped at {@link #MAX_ENTRIES}, and a capped set carries
 * {@link RuntimeActivityData#TRUNCATION_MARKER} as its final entry. Reusing that marker rather than
 * a second convention matters: a reader that already knows what a truncated Runtime projection looks
 * like recognises a truncated node set without being taught, and a silent cap would report a
 * partially observed run as a fully observed one.</p>
 *
 * <p>Normalisation is idempotent. The marker is stripped before ordering and re-appended after, so
 * reading a stored record and writing it back produces the identical sequence — without which the
 * fingerprint would change on every round trip.</p>
 *
 * @param visitedNodes        every node the traversal entered, as unique membership rather than
 *                            visit order
 * @param defaultedNodes      the entered nodes that ran as an unresolved default
 * @param bypassedNodes       the entered nodes whose behavior was intentionally not invoked
 * @param handledFailureNodes the entered nodes whose failure the traversal survived
 * @param untakenEdges        outgoing edges of a bypassed node that its hardcoded outcome could
 *                            never select; these name edges, not nodes
 */
public record ExecutionResultNodes(List<String> visitedNodes, List<String> defaultedNodes,
                                   List<String> bypassedNodes, List<String> handledFailureNodes,
                                   List<String> untakenEdges) {

    /**
     * Entries retained per set before the overflow marker replaces the rest.
     *
     * <p>A thousand distinct nodes in one traversal is already far past any graph a person authors
     * and reads, so the cap is chosen to be unreachable in practice rather than to be tight; what it
     * has to do is put a ceiling on the rows one result can write, which at five sets is five
     * thousand and change. A tighter bound would start truncating real generated graphs, and
     * truncation is a loss of evidence even when it is announced.</p>
     */
    public static final int MAX_ENTRIES = 1_024;

    /** Which of the five sets an entry belongs to, so adapters can iterate rather than branch. */
    public enum Kind {
        /** {@link ExecutionResultNodes#visitedNodes()}. */
        VISITED,
        /** {@link ExecutionResultNodes#defaultedNodes()}. */
        DEFAULTED,
        /** {@link ExecutionResultNodes#bypassedNodes()}. */
        BYPASSED,
        /** {@link ExecutionResultNodes#handledFailureNodes()}. */
        HANDLED_FAILURE,
        /** {@link ExecutionResultNodes#untakenEdges()}. */
        UNTAKEN_EDGE
    }

    /** Orders, deduplicates and bounds every set, and does so idempotently. */
    public ExecutionResultNodes {
        visitedNodes = normalize(visitedNodes, "visitedNodes");
        defaultedNodes = normalize(defaultedNodes, "defaultedNodes");
        bypassedNodes = normalize(bypassedNodes, "bypassedNodes");
        handledFailureNodes = normalize(handledFailureNodes, "handledFailureNodes");
        untakenEdges = normalize(untakenEdges, "untakenEdges");
    }

    /** The record of an execution that reported nothing, which is every terminal failure. */
    private static final ExecutionResultNodes EMPTY =
            new ExecutionResultNodes(List.of(), List.of(), List.of(), List.of(), List.of());

    /**
     * The empty record, for a terminal execution that produced no traversal detail.
     *
     * @return a record whose five sets are all empty.
     */
    public static ExecutionResultNodes empty() {
        return EMPTY;
    }

    /**
     * Builds a record from the unordered sets an in-engine result carries.
     *
     * @param visitedNodes        entered nodes.
     * @param defaultedNodes      entered nodes that ran an unresolved default.
     * @param bypassedNodes       entered nodes whose behavior was not invoked.
     * @param handledFailureNodes entered nodes whose failure the traversal survived.
     * @param untakenEdges        bypassed-node edges that could not be selected.
     * @return the ordered, deduplicated and bounded record of those sets.
     */
    public static ExecutionResultNodes of(Collection<String> visitedNodes,
                                          Collection<String> defaultedNodes,
                                          Collection<String> bypassedNodes,
                                          Collection<String> handledFailureNodes,
                                          Collection<String> untakenEdges) {
        return new ExecutionResultNodes(copy(visitedNodes), copy(defaultedNodes), copy(bypassedNodes),
                copy(handledFailureNodes), copy(untakenEdges));
    }

    private static List<String> copy(Collection<String> source) {
        return source == null ? List.of() : List.copyOf(source);
    }

    /**
     * The entries of one set, so an adapter can write all five without naming each.
     *
     * @param kind which set to read.
     * @return that set's entries, in the stored order.
     */
    public List<String> entries(Kind kind) {
        return switch (Objects.requireNonNull(kind, "kind")) {
            case VISITED -> visitedNodes;
            case DEFAULTED -> defaultedNodes;
            case BYPASSED -> bypassedNodes;
            case HANDLED_FAILURE -> handledFailureNodes;
            case UNTAKEN_EDGE -> untakenEdges;
        };
    }

    /**
     * Whether any set lost entries to {@link #MAX_ENTRIES}.
     *
     * <p>Derived from the data rather than stored beside it, so the flag cannot disagree with the
     * marker a reader can see for itself.</p>
     *
     * @return whether at least one set ends with {@link RuntimeActivityData#TRUNCATION_MARKER}.
     */
    public boolean truncated() {
        for (Kind kind : Kind.values()) {
            List<String> entries = entries(kind);
            if (!entries.isEmpty() && RuntimeActivityData.TRUNCATION_MARKER.equals(entries.getLast())) {
                return true;
            }
        }
        return false;
    }

    private static List<String> normalize(List<String> source, String name) {
        if (source == null) {
            return List.of();
        }
        boolean marked = false;
        var ordered = new TreeSet<String>();
        for (String entry : source) {
            if (entry == null) {
                throw new IllegalArgumentException(name + " cannot contain a null entry");
            }
            if (RuntimeActivityData.TRUNCATION_MARKER.equals(entry)) {
                marked = true;
                continue;
            }
            ordered.add(entry);
        }
        if (ordered.size() > MAX_ENTRIES) {
            marked = true;
        }
        var bounded = new ArrayList<String>(Math.min(ordered.size(), MAX_ENTRIES) + 1);
        int ceiling = marked ? MAX_ENTRIES - 1 : MAX_ENTRIES;
        for (String entry : ordered) {
            if (bounded.size() >= ceiling) {
                break;
            }
            bounded.add(entry);
        }
        if (marked) {
            bounded.add(RuntimeActivityData.TRUNCATION_MARKER);
        }
        return List.copyOf(bounded);
    }
}
