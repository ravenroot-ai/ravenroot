package ai.ravenroot.core.runtime;

import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.JoinSemantics;
import ai.ravenroot.core.graph.NodeKind;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The validated join configuration of one fan-in node (CORE-03).
 *
 * <h2>A branch is a distinct predecessor node, not an incoming edge</h2>
 * <p>{@link #branches()} holds the <em>distinct</em> predecessor node ids, and this is a correction
 * rather than a restatement. The runtime previously took its expected arrival count from
 * {@code GraphManager.predecessorCount}, which counts incoming <em>edges</em>. A decision node
 * wired to a join twice — {@code d -[accepted]-> j} and {@code d -[rejected]-> j}, which is ordinary
 * and valid — therefore made the join expect two arrivals when only one outcome can ever be taken,
 * so an {@code all} join over it could never complete. Counting distinct predecessors makes the
 * expectation equal to the maximum number of arrivals the topology can actually produce, which is
 * also the only number a quorum can be meaningfully validated against.</p>
 *
 * <h2>Quorum is a count</h2>
 * <p>Not a fraction and not a predicate. A fraction needs a rounding rule, and that rule then
 * becomes an unwritten part of the contract that differs between a reader's intuition and the
 * implementation on exactly the values that matter ({@code 0.5} of 3). A predicate is code, and
 * graph content carrying executable policy is the thing SEC-09 exists to prevent. A count has one
 * reading, is validated against the branch count at composition, and covers both existing policies:
 * {@code all} is {@code quorum = N} and {@code any} is {@code quorum = 1}.</p>
 */
public record JoinSpec(String nodeId, List<String> branches, int quorum, Duration timeout, boolean rearm) {

    /**
     * A join with the default behaviour of every join today: it re-arms after each firing.
     *
     * <p>Kept as the four-component shape so that composing a spec by hand — which the runtime does in
     * exactly one place and the tests do in several — does not have to state the one value that has no
     * alternative yet.</p>
     */
    public JoinSpec(String nodeId, List<String> branches, int quorum, Duration timeout) {
        this(nodeId, branches, quorum, timeout, true);
    }

    /**
     * Graph property selecting {@code all} or {@code any}.
     *
     * <p>Aliased from {@link JoinSemantics} rather than spelled again here. The name is
     * read in two places — this class, which builds the coordinator, and
     * {@link ai.ravenroot.core.graph.GraphDefinition}, which refuses a join that can never proceed —
     * and two spellings of one serialized name is how the refusal and the runtime come to disagree
     * about which nodes are joins.</p>
     */
    public static final String POLICY_PROPERTY = JoinSemantics.POLICY_PROPERTY;
    /** Graph property carrying an explicit {@code k of n} quorum. @see #POLICY_PROPERTY */
    public static final String QUORUM_PROPERTY = JoinSemantics.QUORUM_PROPERTY;
    /** Graph property carrying an ISO-8601 join deadline. @see #POLICY_PROPERTY */
    public static final String TIMEOUT_PROPERTY = JoinSemantics.TIMEOUT_PROPERTY;

    /**
     * The longest {@code joinTimeout} a graph may ask for.
     *
     * <p>A join deadline is not just a deadline, it is a <em>retention window</em>. Until it fires
     * or is cancelled, the scheduled task holds its coordinator, and through it every arrival
     * payload of that join and the traversal's ingress {@link ai.ravenroot.api.security.SecurityContext}.
     * Whatever number appears in this property is therefore how long a graph — a document, and one
     * that is authored rather than reviewed as code — can pin live process memory. Validating only
     * that the value is positive left {@code PT2400H} entirely legal: a hundred-day retention window
     * chosen by graph content, on a runtime that has no other bound on it.</p>
     *
     * <p>Twenty-four hours is the ceiling because it is the largest window that is still bounded by
     * something operationally real. Within a day, a stuck join is reclaimed by any ordinary deploy,
     * restart or failover, so the worst case is a cost the operator already pays for. Beyond a day,
     * the wait is no longer a correlation deadline at all — it is a business-process timer, and one
     * that expects to survive a restart, which an in-memory scheduled task cannot do. A graph that
     * genuinely needs to wait a week does not need a longer {@code joinTimeout}; it needs durable
     * timers, and silently accepting the value would let it believe otherwise until the first
     * restart quietly discarded every pending join.</p>
     */
    public static final Duration MAX_TIMEOUT = Duration.ofHours(24);

    public JoinSpec {
        Objects.requireNonNull(nodeId, "nodeId");
        branches = List.copyOf(branches);
        if (quorum < 1 || quorum > branches.size()) {
            throw new IllegalArgumentException("quorum " + quorum + " is outside 1.." + branches.size());
        }
    }

    /** Total number of branches that can deliver to this join. */
    public int branchCount() {
        return branches.size();
    }

    public boolean hasTimeout() {
        return timeout != null;
    }

    /**
     * Whether this join arms itself again after firing, so that a cycle through it runs more than once.
     *
     * <p><b>Always {@code true} today, and read from nothing.</b> Re-arming is the default, and no
     * serialized property currently switches it off. This is therefore the seam and not the feature:
     * {@code parse} deliberately does
     * not look for a fourth node property beside {@code joinPolicy}, {@code joinQuorum} and
     * {@code joinTimeout}, because inventing a name here would create a wire contract, and a name that
     * ships cannot be changed.</p>
     *
     * <p>What it does buy is that the one-shot path stays written and stays reachable: the coordinator
     * branches on this value at the point of firing, so supporting such a property requires adding a
     * parser rather than reconstructing a behaviour from the description of it.</p>
     */
    public boolean rearm() {
        return rearm;
    }

    /**
     * Validates every fan-in node of {@code graph} and returns their specs, keyed by node id.
     *
     * <p>Nodes with fewer than two distinct predecessors are not joins and are absent from the
     * result. Configuring a join property on one is rejected rather than ignored: a
     * {@code joinQuorum} on a node that never joins anything is always a mistake, and silently
     * ignoring it is how a graph author comes to believe a quorum is in force when none is.</p>
     */
    public static Map<String, JoinSpec> validate(GraphDefinition graph) {
        Objects.requireNonNull(graph, "graph");
        var specs = new LinkedHashMap<String, JoinSpec>();
        for (GraphNode node : graph.nodes()) {
            List<String> branches = JoinSemantics.distinctPredecessors(graph, node.id());
            // A START is an ingress, even when a state-machine graph has feedback transitions to
            // it. Its first invocation is deliberately external and therefore has no predecessor;
            // treating those feedback edges as a join makes the traversal fail before START runs.
            // `each` is the explicit compatibility policy for legacy state-machine merge points:
            // every selected transition invokes the node independently instead of synchronising
            // mutually-exclusive routes as though they were parallel branches.
            if (node.kind() == NodeKind.START || JoinSemantics.isEach(node)) {
                rejectJoinOnlyProperties(node);
                continue;
            }
            if (branches.size() < 2) {
                rejectJoinPropertiesOnNonJoin(node, branches.size());
                continue;
            }
            // Several predecessors is a topology, not a request. In a document declaring
            // `join.semantics=declared` a node is a synchronisation point only where the author wrote
            // one of the three join properties on it, so an undeclared fan-in gets no coordinator and
            // each arrival invokes it independently. Without the marker this is always true and the
            // paragraphs below apply unchanged. JoinSemantics owns the predicate because
            // GraphDefinition's load-time refusal has to ask the same question and get the same
            // answer.
            if (!JoinSemantics.isJoin(graph, node, branches)) {
                continue;
            }
            specs.put(node.id(), parse(node, branches));
        }
        return Map.copyOf(specs);
    }

    private static void rejectJoinOnlyProperties(GraphNode node) {
        if (isPresent(node, QUORUM_PROPERTY) || isPresent(node, TIMEOUT_PROPERTY)) {
            throw new JoinConfigurationException(node.id(), POLICY_PROPERTY,
                    "is each, so joinQuorum and joinTimeout cannot be set because arrivals are not joined");
        }
    }

    private static void rejectJoinPropertiesOnNonJoin(GraphNode node, int branchCount) {
        for (String property : List.of(POLICY_PROPERTY, QUORUM_PROPERTY, TIMEOUT_PROPERTY)) {
            if (isPresent(node, property)) {
                throw new JoinConfigurationException(node.id(), property,
                        "is set on a node that is not a fan-in: it has " + branchCount
                                + " distinct predecessor(s) and a join needs at least 2");
            }
        }
    }

    private static JoinSpec parse(GraphNode node, List<String> branches) {
        Integer quorum = parseQuorum(node, branches.size());
        Policy policy = parsePolicy(node);
        int effective = reconcile(node, policy, quorum, branches.size());
        return new JoinSpec(node.id(), branches, effective, parseTimeout(node));
    }

    private static Integer parseQuorum(GraphNode node, int branchCount) {
        String raw = rawValue(node, QUORUM_PROPERTY);
        if (raw == null) {
            return null;
        }
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException error) {
            throw new JoinConfigurationException(node.id(), QUORUM_PROPERTY,
                    "must be a whole number of branches, got '" + raw + "'");
        }
        if (value < 1) {
            throw new JoinConfigurationException(node.id(), QUORUM_PROPERTY,
                    "must be at least 1, got " + value + "; a join that requires no arrival is not a join");
        }
        if (value > branchCount) {
            throw new JoinConfigurationException(node.id(), QUORUM_PROPERTY,
                    "is " + value + " but the node has only " + branchCount
                            + " branches, so the quorum can never be reached");
        }
        return value;
    }

    private static Policy parsePolicy(GraphNode node) {
        String raw = rawValue(node, POLICY_PROPERTY);
        if (raw == null) {
            return null;
        }
        try {
            return Policy.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new JoinConfigurationException(node.id(), POLICY_PROPERTY,
                    "is '" + raw + "'; expected all, any or each, or use " + QUORUM_PROPERTY + " for k of n");
        }
    }

    /**
     * Resolves policy and quorum into one number, rejecting a disagreement rather than letting one
     * win. {@code joinPolicy=all} with {@code joinQuorum=1} states two different contracts, and
     * whichever the implementation happened to prefer would be a coin toss for the reader.
     */
    private static int reconcile(GraphNode node, Policy policy, Integer quorum, int branchCount) {
        if (policy == null) {
            return quorum == null ? defaultQuorum(node, branchCount) : quorum;
        }
        int implied = policy == Policy.ALL ? branchCount : 1;
        if (quorum != null && quorum != implied) {
            throw new JoinConfigurationException(node.id(), QUORUM_PROPERTY,
                    "is " + quorum + " but " + POLICY_PROPERTY + "=" + policy.name().toLowerCase(Locale.ROOT)
                            + " implies " + implied + "; set one or the other, not two that disagree");
        }
        return implied;
    }

    /**
     * The quorum a fan-in gets when its author configured none: every branch, except on the error
     * terminal, where it is one.
     *
     * <h2>Why the terminal needs its own default</h2>
     * <p>A graph must now declare exactly one {@code ERROR}, so every fallible node in it routes to
     * the same node, and two such routes make that node a fan-in by the rule above. Under the
     * ordinary default its quorum would be the number of fallible nodes — meaning the error terminal
     * would wait for <em>all</em> of them to fail. Measured before this line existed, with two
     * fallible nodes and one failure: {@code JoinFailureException: Join 'error' can never reach its
     * quorum, because branches it needs are never taken: quorum 2 of 2 branches, arrived=[]
     * failed=[] outstanding=[first] notTaken=[second]}. The structure the product now requires was
     * unusable past a single fallible node, and it failed as a join defect rather than as anything a
     * reader could connect to the error terminal.</p>
     *
     * <h2>Why a quorum of one and not an exemption from joining</h2>
     * <p>Excluding {@code ERROR} from fan-in detection entirely — the treatment {@code START} gets
     * above — was the other candidate and is worse. Without a coordinator each arrival would invoke
     * the terminal independently, so two branches failing at once would run it twice and race over
     * the traversal's result payload. Keeping it a join with a quorum of one preserves the single
     * firing, and the surplus arrival is <em>recorded</em> rather than dropped in silence:
     * {@code JOIN_ARRIVAL_DISCARDED}, the same event any {@code joinPolicy=any} node emits.</p>
     *
     * <h2>A document property re-enables exactly the defect above</h2>
     * <p>Both sentences hold only while no {@code joinPolicy} is authored, which is what makes this a
     * <em>default</em>. {@code joinPolicy=each} does not select a different quorum — it removes the
     * node from fan-in detection altogether, at the {@code isEach} branch in {@link #validate}, so
     * this method is never reached for it and no coordinator is ever created. Each arrival then
     * invokes the terminal on its own, which is precisely the alternative rejected above, reinstated
     * from the document rather than from the code.</p>
     *
     * <p>Measured, 200 traversals of a graph with two failing branches routed to an {@code ERROR}
     * terminal carrying {@code joinPolicy=each}: the terminal completed <strong>twice</strong> in
     * 200 runs out of 200, {@code JOIN_ARRIVAL_DISCARDED} was emitted <strong>zero</strong> times in
     * 200 out of 200, and the reported failure varied — {@code {second=179, first=21}}. Against the
     * same graph without the property: one completion, one discard.</p>
     *
     * <p><strong>What that does and does not cost, stated exactly.</strong> Counting one event type on
     * one node can make the losing failure appear unrecorded. Dumping the whole stream shows both
     * failures recorded either way:
     * {@code NODE_FAILED} is emitted for {@code first} and for {@code second}, with node, error class
     * and message, in 200 runs out of 200 <em>with</em> {@code each} as well as without. The entire
     * difference between the two streams is two events, {@code JOIN_SATISFIED} and
     * {@code JOIN_ARRIVAL_DISCARDED}. So what {@code each} removes is <strong>the record that an
     * arrival was set aside</strong>, not the record of the failure — a reader reconstructing the run
     * from its events still sees both faults, but nothing tells them one of the two was dropped on
     * the way to the result.</p>
     *
     * <p>The fan-out shape costs more than that, and it is the one measured last. With
     * {@code each} on an ordinary merge upstream of the terminal and two failing branches, the result
     * payload carries one of the two failures, varying — {@code {first=63, second=137}}. Without the
     * property the merge is an ordinary quorum-of-all join, and the result payload carries
     * <em>both</em> failures, as the list a fan-in produces, in 200 runs out of 200. There the
     * property does not merely lose the discard record: it loses content the coordinated form
     * delivered.</p>
     *
     * <p>This is not hypothetical authoring. {@code ravenroot-ui/src/graph-document.js} stamps
     * {@code joinPolicy=each} on every non-{@code START} node with more than one predecessor and no
     * explicit policy when it serialises a legacy state-machine document — a guard that excludes
     * {@code START} and nothing else, so it reaches {@code ERROR}. The block predates the single error
     * terminal and now reaches the point every failure route converges on. Whether {@code each} should
     * be refused on a terminal, or the
     * editor should skip terminals when stamping it, changes which graphs are legal and how legacy
     * imports migrate — a decision deliberately not taken here.</p>
     *
     * <p>This is a default, not a rule. An author who genuinely wants the terminal to wait for every
     * fallible branch writes {@code joinPolicy=all} on it and gets exactly that.</p>
     */
    private static int defaultQuorum(GraphNode node, int branchCount) {
        return node.kind() == NodeKind.ERROR ? 1 : branchCount;
    }

    /**
     * Parses the timeout as an ISO-8601 duration and nothing else.
     *
     * <p>A bare number was considered and rejected: it needs an implied unit, every implied unit is
     * someone's wrong guess, and {@code joinTimeout=30} meaning half a minute to its author and
     * thirty milliseconds to the runtime is a defect that only surfaces under load.</p>
     */
    private static Duration parseTimeout(GraphNode node) {
        String raw = rawValue(node, TIMEOUT_PROPERTY);
        if (raw == null) {
            return null;
        }
        Duration value;
        try {
            value = Duration.parse(raw);
        } catch (DateTimeParseException error) {
            throw new JoinConfigurationException(node.id(), TIMEOUT_PROPERTY,
                    "must be an ISO-8601 duration such as PT30S, got '" + raw + "'");
        }
        if (value.isZero() || value.isNegative()) {
            throw new JoinConfigurationException(node.id(), TIMEOUT_PROPERTY,
                    "must be strictly positive, got " + raw);
        }
        if (value.compareTo(MAX_TIMEOUT) > 0) {
            throw new JoinConfigurationException(node.id(), TIMEOUT_PROPERTY,
                    "is " + raw + ", which exceeds the maximum of " + MAX_TIMEOUT
                            + "; a join deadline holds the traversal's payloads and security context in memory "
                            + "until it fires, so a longer wait needs a durable timer rather than a longer timeout");
        }
        return value;
    }

    private static boolean isPresent(GraphNode node, String property) {
        return rawValue(node, property) != null;
    }

    private static String rawValue(GraphNode node, String property) {
        Object configured = node.properties().get(property);
        if (configured == null) {
            return null;
        }
        String text = configured.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private enum Policy {
        ALL,
        ANY
    }
}
