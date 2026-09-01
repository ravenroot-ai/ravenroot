package ai.ravenroot.core.runtime;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * How many times each join on a message's causal past has already fired.
 *
 * <h2>What {@code {J: k}} says</h2>
 * <p>Exactly one thing: <em>this message has k firings of join J in its own causal past</em>. It is
 * therefore the lap of J that this message belongs to, and it is what correlates arrivals: the k-th
 * arrival on one branch pairs with the k-th arrivals of the others, and two arrivals of the same
 * branch carrying the same k are the same arrival redelivered rather than two contributions to one
 * quorum. A join absent from the map reads 0, so the context a traversal starts with is simply
 * empty.
 *
 * <h2>Why it is a runtime parameter and not part of the message</h2>
 * <p>The token must not be able to pass through user code. {@code NodeResult}'s payload and
 * attributes come back <em>from the node</em>, so a behaviour could set, clear or forge a lap and
 * decide which arrivals its own join correlates together — a falsifiable channel for something the
 * runtime is supposed to know on its own. So this travels beside the dispatch, read from
 * {@code GraphRunner}'s own scope and from the message it delivered, never from what a node
 * returned.
 *
 * <p>It is also deliberately <em>not</em> a new field on {@code NodeMessage}. That type is public
 * contract of {@code ravenroot-application-api} with a binary-compatibility test defending its shape,
 * and in-process execution — the only execution there is — has no need to serialise the token at all.
 *
 * <h2>Why a retry is not a second lap</h2>
 * <p>Because the token lives in the <em>content</em> of a delivery, not in the identity of an
 * invocation. A retry redelivers the same content along the same edge, so it carries the same lap and
 * lands in the same bucket; a genuine second lap has by construction passed through the join's own
 * firing, so it carries {@code k+1}. Nothing here reads an invocation or attempt identifier, which is
 * what makes the distinction hold under either answer to PERS-04.
 *
 * @param laps join node id to the number of firings of that join in this message's past; never
 *             {@code null}, immutable, and entries with lap 0 are equivalent to absent ones
 */
record IterationContext(Map<String, Integer> laps) {

    /** What the start node's dispatch carries: every join reads 0. */
    static final IterationContext EMPTY = new IterationContext(Map.of());

    IterationContext {
        laps = Map.copyOf(laps == null ? Map.of() : laps);
    }

    /** How many times {@code joinNodeId} has fired in this message's causal past. */
    int lapOf(String joinNodeId) {
        return laps.getOrDefault(joinNodeId, 0);
    }

    /**
     * This context with {@code joinNodeId} advanced past the bucket it just fired.
     *
     * <p>Set rather than incremented: the caller has just decided which bucket fired, from the
     * record, and deriving it again from the map would let the two disagree after a recovery in which
     * the map is rebuilt and the record is authoritative.</p>
     */
    IterationContext firing(String joinNodeId, int firedBucket) {
        var next = new LinkedHashMap<>(laps);
        next.put(joinNodeId, firedBucket + 1);
        return new IterationContext(next);
    }

    /**
     * The pointwise maximum of several contexts: what a join's downstream dispatch inherits from the
     * arrivals that satisfied it.
     *
     * <p>Maximum rather than, say, the first contributor's: a branch that passed through an inner
     * join more times than its sibling knows something the sibling does not, and taking the smaller
     * number would put a later message back into an earlier bucket of that inner join. Deterministic
     * regardless of arrival order because max is commutative and associative — the caller's own
     * ordering by {@code BranchId} is what makes the merged <em>payload</em> stable, and this needs no
     * ordering at all.</p>
     */
    static IterationContext merge(Collection<IterationContext> contexts) {
        // Sorted so that the map's iteration order — which shows up in toString and in any diagnostic
        // that prints it — does not depend on which branch happened to arrive first.
        var merged = new TreeMap<String, Integer>();
        for (IterationContext context : contexts) {
            context.laps.forEach((join, lap) -> merged.merge(join, lap, Math::max));
        }
        return merged.isEmpty() ? EMPTY : new IterationContext(merged);
    }
}
