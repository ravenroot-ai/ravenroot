package ai.ravenroot.core.runtime;

import java.util.Objects;

/**
 * Which branch of a fan-in an arrival belongs to (ADR 0024 §4).
 *
 * <h2>Why this stopped being a bare node id</h2>
 * <p>A predecessor's node id identifies a branch only while that predecessor produces one arrival.
 * It becomes insufficient when ADR 0024's data-parallel fan-out
 * lets one upstream result produce N child invocations of the same successor. All N would present the
 * same identity, and every mechanism downstream keys on it:
 *
 * <ul>
 *   <li>{@code JoinCoordinator.payloads} is keyed by branch, so child 2 overwrites child 1;</li>
 *   <li>{@code JoinRecord.branches} is keyed by branch, so N children collapse into one entry and the
 *       quorum count for that branch can never exceed one;</li>
 *   <li>the second child is classified {@code Discarded(DUPLICATE)}, which is documented as "never an
 *       error" — so N-1 real results would disappear with no failure anywhere.</li>
 * </ul>
 *
 * <p>This type makes collisions distinguishable even while every predecessor still has at most one
 * child. Any future multiplicity can therefore reuse the representation without losing results.
 *
 * <h2>The wire form is unchanged while there are no children, and that is deliberate</h2>
 * <p>{@link #value()} of a whole-predecessor branch is exactly the predecessor's node id. So every
 * persisted {@code JoinRecord}, every {@code JoinSpec} branch list and every stored key keeps the
 * bytes it has today, and the store port is untouched — it still sees opaque strings and still knows
 * nothing about topology, which is the constraint its own javadoc imposes. A child branch serialises
 * as {@code nodeId#ordinal}; that form appears only once an expansion exists to mint one.
 *
 * <h2>Ordering is part of the identity</h2>
 * <p>{@code Comparable} by node id then ordinal, because {@code JoinCoordinator.merge} sorts arrivals
 * by branch to give a fan-in a deterministic result order. Sorting children lexicographically by
 * their serialised form would order child 10 before child 2; comparing the ordinal numerically is
 * what keeps a parallel-map result in item order. The ordering promised to authors is not decided
 * here — but whichever policy is chosen, the comparator must not decide it by accident.
 *
 * <h2>The same trick again, for the iteration</h2>
 * <p>A branch of a join inside a cycle presents itself once per lap, and those presentations are
 * different arrivals that must not overwrite one another. So the identity gained a third component,
 * and it serialises the way {@code #ordinal} did: {@code @lap} appears only for laps above zero, so
 * an acyclic graph writes exactly the same <em>branch keys</em> as before. {@code JoinKey},
 * {@code JoinStore} and the shape
 * {@code Map<String, JoinBranchOutcome>} are all untouched.
 *
 * <p><b>The claim is about the keys and stops there.</b> The rest of the record does change on an
 * acyclic graph, because a join that re-arms records a firing differently: where it used to write
 * {@code SATISFIED} with a {@code settledAt}, it now writes {@code OPEN} with
 * {@code firedThrough = 0}. Saying "no persisted record changes by a byte" would be a larger claim
 * than this component supports and than any test asserts — {@code anAcyclicGraphWritesBareBranchKeys}
 * asserts the key set, which is exactly this.
 *
 * @param nodeId       the predecessor node this branch arrives from; never blank
 * @param childOrdinal the child's position within a data-parallel expansion of that predecessor, or
 *                     {@link #NO_CHILD} when the predecessor produced a single arrival
 * @param lap          which firing of the join this arrival belongs to, counted from zero; see
 *                     {@link IterationContext} for what the number means
 */
record BranchId(String nodeId, int childOrdinal, int lap) implements Comparable<BranchId> {

    /** This branch is the predecessor's whole output, not one item of an expansion. */
    static final int NO_CHILD = -1;

    /** Separates the node id from a child ordinal in {@link #value()}. */
    private static final char CHILD_SEPARATOR = '#';

    /** Separates the branch from its iteration in {@link #value()}; absent at lap 0. */
    private static final char LAP_SEPARATOR = '@';

    BranchId {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("branch nodeId cannot be blank");
        }
        if (childOrdinal < NO_CHILD) {
            throw new IllegalArgumentException("childOrdinal must be " + NO_CHILD
                    + " (no child) or a non-negative position, but was " + childOrdinal);
        }
        if (lap < 0) {
            throw new IllegalArgumentException("lap is counted from zero, but was " + lap);
        }
    }

    /** The whole of a predecessor's output, on the join's first lap. */
    static BranchId of(String nodeId) {
        return new BranchId(nodeId, NO_CHILD, 0);
    }

    /**
     * One child of a data-parallel expansion of {@code nodeId}.
     *
     * <p>No caller in {@code src/main} mints one yet. It exists so that the representation is capable
     * before anything depends on it.
     */
    static BranchId child(String nodeId, int ordinal) {
        if (ordinal < 0) {
            throw new IllegalArgumentException("a child ordinal is non-negative, but was " + ordinal);
        }
        return new BranchId(nodeId, ordinal, 0);
    }

    /** This same branch, on the join's {@code lap}-th firing. */
    BranchId atLap(int lap) {
        return lap == this.lap ? this : new BranchId(nodeId, childOrdinal, lap);
    }

    /** Whether this branch is one item of an expansion rather than a predecessor's whole output. */
    boolean isChild() {
        return childOrdinal != NO_CHILD;
    }

    /**
     * The stable string this branch is stored and compared under.
     *
     * <p>Identical to the node id when there is no child and no lap, so nothing persisted changes
     * until an expansion or a cycle exists. Everything that crosses the store boundary uses this
     * rather than the record, because the store must keep knowing nothing about topology.
     */
    String value() {
        String branch = isChild() ? nodeId + CHILD_SEPARATOR + childOrdinal : nodeId;
        return atLap(branch, lap);
    }

    /**
     * The stored key of {@code branch} at {@code lap}, for the call sites that hold a branch as the
     * bare string a {@code JoinSpec} lists rather than as a {@link BranchId} — a not-taken report and
     * a branch failure both arrive that way, precomputed from the graph and therefore lap-free.
     */
    static String atLap(String branch, int lap) {
        return lap == 0 ? branch : branch + LAP_SEPARATOR + lap;
    }

    /**
     * The lap encoded in a stored branch key, or 0 when it carries none.
     *
     * <p>Read from the <em>last</em> separator and only when every character after it is a digit, so
     * a node whose own id contains an {@code @} is not silently reinterpreted as an iteration of a
     * shorter name. A key that does not end in a well-formed suffix is lap 0 by definition, which is
     * also what makes every record without an iteration suffix readable without migration.</p>
     */
    static int lapIn(String branchKey) {
        int separator = branchKey.lastIndexOf(LAP_SEPARATOR);
        if (separator <= 0 || separator == branchKey.length() - 1) {
            return 0;
        }
        String suffix = branchKey.substring(separator + 1);
        for (int index = 0; index < suffix.length(); index++) {
            if (!Character.isDigit(suffix.charAt(index))) {
                return 0;
            }
        }
        try {
            return Integer.parseInt(suffix);
        } catch (NumberFormatException overflowed) {
            return 0;
        }
    }

    /** {@code branchKey} without its iteration suffix: the branch as {@code JoinSpec} names it. */
    static String branchIn(String branchKey) {
        return lapIn(branchKey) == 0 ? branchKey : branchKey.substring(0, branchKey.lastIndexOf(LAP_SEPARATOR));
    }

    @Override
    public int compareTo(BranchId other) {
        int byNode = nodeId.compareTo(Objects.requireNonNull(other, "other").nodeId);
        if (byNode != 0) {
            return byNode;
        }
        int byOrdinal = Integer.compare(childOrdinal, other.childOrdinal);
        return byOrdinal != 0 ? byOrdinal : Integer.compare(lap, other.lap);
    }

    @Override
    public String toString() {
        return value();
    }
}
