package ai.ravenroot.api.application;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * What an execution produced, as an adapter may report it.
 *
 * <p>This is the boundary projection of {@code GraphRunner}'s in-engine result. It exists because
 * that result was previously computed and discarded: {@code POST /v1/executions} answers 202
 * with identifiers only, and there was no read by execution id at all, so a graph could run to
 * completion and no caller could ever observe that it had produced anything.</p>
 *
 * <h2>{@code defaultedNodes} is not decoration</h2>
 * <p>A node whose behavior could not be resolved is executed as a pass-through default. The engine
 * has always recorded that in {@code GraphExecutionResult.defaultedNodes()}, but nothing carried it
 * out of the process, so an execution that silently skipped half its work reported plain success and
 * a caller had no way to learn the run was degraded. Carrying it here is what makes the degradation
 * observable at all, which is why {@link #degraded()} is on this type rather than left for each
 * adapter to re-derive. Making the defaulting itself fail closed is a separate decision; this
 * type is the reporting half, and the two are independent — a run that fails closed still needs
 * somewhere to say which node did it.</p>
 *
 * <h2>{@code payload} stays {@code Object}</h2>
 * <p>Deliberately the same interior type the engine, the behaviours and the program runtime already
 * pass around, rather than a new serialised form invented here. Adapters project it through the
 * payload model at their own boundary — {@code PayloadValue.fromJava} — exactly as the program
 * artifact test path already does for untrusted node output. Introducing a canonical wire encoding
 * for a result at this layer would be a durable-storage decision wearing an API costume, and there
 * is no durable result storage today (see {@link ExecutionLookup}).</p>
 *
 * @param processInstanceId PERS-01 identity of the process instance; application-generated.
 * @param traversalId       the caller-facing execution id — the identifier the submitter reserved.
 * @param status            {@code RUNNING} until the traversal reaches a terminal state.
 * @param payload           the result payload, {@code null} while running and on failure.
 * @param visitedNodes      every node the traversal actually entered.
 * @param defaultedNodes    the subset of {@code visitedNodes} that ran as an unresolved default.
 * @param bypassedNodes     the subset of {@code visitedNodes} whose behavior was intentionally not
 *                          invoked. <b>Two different decisions put a node here, and this set does not
 *                          say which.</b> Either the <em>traversal</em> was not executing behaviours —
 *                          a Play/Test pass-through submission, or an edge carrying the
 *                          {@code passthrough} command, which is sticky and covers everything
 *                          downstream — or the graph's <em>author</em> switched that one node
 *                          off with {@code execution.bypass}, which applies to exactly that node while
 *                          the rest of the run executes for real. Membership alone therefore no
 *                          longer implies anything about the run as a whole: a fully executing
 *                          production run can populate this set. Where the distinction does live is the
 *                          {@code NODE_BYPASSED} events, whose {@code publicReason} is
 *                          {@link ExecutionEvent#BYPASS_REASON_COMMAND} or
 *                          {@link ExecutionEvent#BYPASS_REASON_AUTHORED}; a caller that needs the cause
 *                          must read them and must not infer it from this field.
 * @param handledFailureNodes the subset of {@code visitedNodes} whose invocation failed inside a
 *                          traversal that nonetheless completed. See {@link #handledFailure()}.
 * @param untakenEdges      outgoing edges of a bypassed node that the bypassed node's own hardcoded
 *                          {@code "continue"} outcome could never select — the node's outcome,
 *                          not the run's, so this is populated by an authored bypass inside an
 *                          otherwise fully executing run as readily as by a test submission. Not a
 *                          subset of {@code visitedNodes}: it names edges, not nodes. See
 *                          {@link #untakenEdges()}.
 * @param paused            whether a pause is currently held on this traversal. See
 *                          {@link #paused()} for why this qualifies {@code status} instead of
 *                          becoming a value of it, and why a terminal outcome never carries it.
 */
public record ExecutionOutcome(UUID processInstanceId, UUID traversalId, ProcessInstanceStatus status,
                               Object payload, Set<String> visitedNodes, Set<String> defaultedNodes,
                               Set<String> bypassedNodes, Set<String> handledFailureNodes,
                               Set<String> untakenEdges, boolean paused) {
/**
 * Freezes all node collections so callers cannot rewrite the reported execution history, and
 * forces the one combination that has no meaning: a terminal execution that is also holding.
 *
 * <p>An execution that has completed or failed has nothing left to hold, so {@code paused} is
 * forced to {@code false} rather than rejected. Rejection would turn a read into a failure at the
 * moment a reader most needs an answer -- a pause and a completion racing each other is exactly
 * the interleaving this component exists to describe -- and the side that loses that race has not
 * produced a malformed outcome, only an out-of-date one. Forcing it keeps "terminal implies not
 * paused" true at the type, so no adapter, projection or consumer can be shown the impossible
 * combination even if one tries to construct it.</p>
 */
    public ExecutionOutcome {
        Objects.requireNonNull(processInstanceId, "processInstanceId");
        Objects.requireNonNull(traversalId, "traversalId");
        Objects.requireNonNull(status, "status");
        visitedNodes = Set.copyOf(Objects.requireNonNull(visitedNodes, "visitedNodes"));
        defaultedNodes = Set.copyOf(Objects.requireNonNull(defaultedNodes, "defaultedNodes"));
        bypassedNodes = Set.copyOf(bypassedNodes == null ? Set.of() : bypassedNodes);
        handledFailureNodes = Set.copyOf(handledFailureNodes == null ? Set.of() : handledFailureNodes);
        untakenEdges = Set.copyOf(untakenEdges == null ? Set.of() : untakenEdges);
        paused = paused && !status.terminal();
    }

/**
 * Compatibility constructor preserving the canonical shape before pauses were observable.
 *
 * <p>Reports {@code paused == false}, and every producer of a stored outcome uses this shape on
 * purpose. A pause is live, process-local state that changes after an outcome has been recorded, so
 * a copy of it stored beside the outcome would be a second source of truth that goes stale the
 * moment it is written. The live value is applied on the way out, with
 * {@link #withPaused(boolean)}.</p>
 * @param processInstanceId durable process that contains this traversal
 * @param traversalId caller-facing traversal identity
 * @param status lifecycle state at the time the outcome was observed
 * @param payload terminal payload, or {@code null} while unavailable
 * @param visitedNodes graph nodes entered during this traversal
 * @param defaultedNodes entered nodes that ran with their unresolved default behavior
 * @param bypassedNodes entered nodes deliberately bypassed
 * @param handledFailureNodes entered nodes whose failures were handled by graph semantics
 * @param untakenEdges labels for bypassed-node edges that could not be selected
 */
    public ExecutionOutcome(UUID processInstanceId, UUID traversalId, ProcessInstanceStatus status,
                            Object payload, Set<String> visitedNodes, Set<String> defaultedNodes,
                            Set<String> bypassedNodes, Set<String> handledFailureNodes,
                            Set<String> untakenEdges) {
        this(processInstanceId, traversalId, status, payload, visitedNodes, defaultedNodes, bypassedNodes,
                handledFailureNodes, untakenEdges, false);
    }

/**
 * Compatibility constructor preserving the pre-command canonical shape.
 * @param processInstanceId durable process that contains this traversal
 * @param traversalId caller-facing traversal identity
 * @param status lifecycle state at the time the outcome was observed
 * @param payload terminal payload, or {@code null} while unavailable
 * @param visitedNodes graph nodes entered during this traversal
 * @param defaultedNodes entered nodes that ran with their unresolved default behavior
 */
    public ExecutionOutcome(UUID processInstanceId, UUID traversalId, ProcessInstanceStatus status,
                            Object payload, Set<String> visitedNodes, Set<String> defaultedNodes) {
        this(processInstanceId, traversalId, status, payload, visitedNodes, defaultedNodes, Set.of(), Set.of(),
                Set.of());
    }

/**
 * Compatibility constructor preserving the canonical shape before handled failures were exposed.
 * @param processInstanceId durable process that contains this traversal
 * @param traversalId caller-facing traversal identity
 * @param status lifecycle state at the time the outcome was observed
 * @param payload terminal payload, or {@code null} while unavailable
 * @param visitedNodes graph nodes entered during this traversal
 * @param defaultedNodes entered nodes that ran with their unresolved default behavior
 * @param bypassedNodes entered nodes deliberately bypassed — by the submission's policy, by an
 *                      edge's command, or by the author's own per-node {@code execution.bypass}
 */
    public ExecutionOutcome(UUID processInstanceId, UUID traversalId, ProcessInstanceStatus status,
                            Object payload, Set<String> visitedNodes, Set<String> defaultedNodes,
                            Set<String> bypassedNodes) {
        this(processInstanceId, traversalId, status, payload, visitedNodes, defaultedNodes, bypassedNodes,
                Set.of(), Set.of());
    }

/**
 * Compatibility constructor preserving the canonical shape before untaken edges were exposed.
 * @param processInstanceId durable process that contains this traversal
 * @param traversalId caller-facing traversal identity
 * @param status lifecycle state at the time the outcome was observed
 * @param payload terminal payload, or {@code null} while unavailable
 * @param visitedNodes graph nodes entered during this traversal
 * @param defaultedNodes entered nodes that ran with their unresolved default behavior
 * @param bypassedNodes entered nodes deliberately bypassed — by the submission's policy, by an
 *                      edge's command, or by the author's own per-node {@code execution.bypass}
 * @param handledFailureNodes entered nodes whose failures were handled by graph semantics
 */
    public ExecutionOutcome(UUID processInstanceId, UUID traversalId, ProcessInstanceStatus status,
                            Object payload, Set<String> visitedNodes, Set<String> defaultedNodes,
                            Set<String> bypassedNodes, Set<String> handledFailureNodes) {
        this(processInstanceId, traversalId, status, payload, visitedNodes, defaultedNodes, bypassedNodes,
                handledFailureNodes, Set.of());
    }

/**
 * Compatibility alias: a legacy execution id identifies one traversal, not a whole process.
 * @return legacy execution identifier, equal to the traversal ID
 */
    public UUID executionId() {
        return traversalId;
    }

    /**
     * Whether this run completed with at least one node executed as an unresolved default.
     *
     * <p>A {@code true} here on a {@code COMPLETED} execution is the case the whole type exists for:
     * the status alone says success, and only {@link #defaultedNodes()} says the success is partial.
 * @return whether unresolved default behavior ran for any entered node
     */
    public boolean degraded() {
        return !defaultedNodes.isEmpty();
    }

    /**
     * Whether this run recovered from at least one node failure.
     *
     * <p>Exactly the {@link #degraded()} shape, for exactly the {@code defaultedNodes} reason stated
     * above. A node that failed inside a traversal the author designed to survive it — through a
     * declared failure route, or a {@code k of n} fan-in whose quorum was met without that branch —
     * leaves the execution {@code COMPLETED}, so the status alone reports plain success and only
     * {@link #handledFailureNodes()} says a real fault occurred. The engine has recorded that fault
     * in the engine; carrying it through this projection is what makes it observable to a caller outside
     * the process, which is the only place the distinction is ever read.</p>
     *
     * <p>An <em>unhandled</em> failure never appears here: it produces no result at all, and this
     * type reports it as {@code FAILED} instead.</p>
 * @return whether graph semantics recovered from a node failure
     */
    public boolean handledFailure() {
        return !handledFailureNodes.isEmpty();
    }

    /**
     * Outgoing edges of a bypassed node that the <em>bypassed node's</em> own hardcoded
     * {@code "continue"} outcome could never select, as required by the documented contract. Each entry is
     * {@code "<source>-><target> [outcome=<outcome>]"} for one edge that was never a routing
     * candidate on this traversal.
     *
     * <p><b>Nothing here says the run was a test.</b> Three different decisions bypass a node and all
     * three feed this field identically: a {@code TEST_PASSTHROUGH} submission, an individual edge
     * carrying the {@code passthrough} command under {@code STANDARD} policy (see {@code
     * NodeCommand}), and the graph author's own per-node {@code execution.bypass} flag. The
     * previous wording here offered {@link #bypassedNodes()} plus the submission's policy as the pair
     * that <em>would</em> tell you it was a test run; that pair does not either, because an
     * authored bypass populates {@code bypassedNodes} under {@code STANDARD} in a run that executed
     * every other node for real. The cause is carried by the {@code NODE_BYPASSED} events'
     * {@code publicReason}, and nowhere in this record.</p>
 * @return immutable labels for bypassed-node edges that could not be selected
     */
    public Set<String> untakenEdges() {
        return untakenEdges;
    }

    /**
     * Whether this traversal is holding on a pause and will not begin another node until it is
     * resumed.
     *
     * <p>Exactly the {@link #degraded()} and {@link #handledFailure()} shape, and for the same
     * reason: {@link #status()} alone reports {@code RUNNING}, and a reader cannot tell a traversal
     * that is progressing from one that is deliberately held. Before this, only the caller that
     * issued the pause knew. A second operator, a reconnecting client, or the same operator on a
     * different session read a held execution as a running one -- or, because a held traversal also
     * stops publishing events, as a stalled one, which is the opposite conclusion and the one that
     * gets acted on.</p>
     *
     * <h4>Why this is not a {@link ProcessInstanceStatus} value</h4>
     * <p>{@code ProcessInstanceStatus} is the durable lifecycle vocabulary: it is persisted, the
     * durable process inventory queries it, and its {@code canTransitionTo} rules are a state machine
     * other components rely on. A pause is none of those things. It is held in the process that owns
     * the traversal, it is written to no store, and a restart forgets it. A durable status that a
     * restart silently drops would be a claim this system cannot keep, so the pause qualifies the
     * status instead of becoming one. A consumer switching over {@code status} therefore keeps
     * working unchanged, and one that cares about holds reads this as well.</p>
     *
     * <p><strong>Always {@code false} on a terminal outcome</strong>, enforced by this record's own
     * constructor rather than by whichever caller builds one.</p>
 * @return whether a pause is currently held on this traversal; never {@code true} for a terminal
 *         {@link #status()}
     */
    public boolean paused() {
        return paused;
    }

    /**
     * The same outcome with live pause state applied.
     *
     * <p>The one supported way to put a pause onto an outcome, because a pause is read at the moment
     * of the read and never stored beside the outcome it qualifies. A stored outcome is an immutable
     * record of what a traversal has done; whether it is holding right now is a fact about the
     * runtime rather than about that history, and the two have different lifetimes. Keeping them
     * apart is what stops a retained outcome from reporting a hold that was released long ago.</p>
     *
     * <p>A terminal outcome returns itself unchanged, so this method cannot construct the
     * combination the constructor refuses.</p>
 * @param paused whether a pause is currently held on this traversal
 * @return this outcome when the value is already correct or the status is terminal, and otherwise a
 *         copy carrying the given pause state
     */
    public ExecutionOutcome withPaused(boolean paused) {
        boolean effective = paused && !status.terminal();
        if (effective == this.paused) {
            return this;
        }
        return new ExecutionOutcome(processInstanceId, traversalId, status, payload, visitedNodes,
                defaultedNodes, bypassedNodes, handledFailureNodes, untakenEdges, effective);
    }
}
