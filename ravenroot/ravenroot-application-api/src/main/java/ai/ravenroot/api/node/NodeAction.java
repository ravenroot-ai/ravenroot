package ai.ravenroot.api.node;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;

import java.util.concurrent.CompletionStage;

/**
 * What a third-party node does when a traversal reaches it.
 *
 * <p>One instance per graph node, produced by {@link NodeBehavior#create(NodeConfiguration)}, so a
 * behavior's per-node configuration and any state it derives are never shared between nodes by
 * accident.</p>
 *
 * <h2>It is invoked CONCURRENTLY, and must be re-entrant (ADR 0024 §3)</h2>
 * <p>One instance per node is not one invocation at a time. The runtime creates a separate runtime
 * instance for each logical invocation of a node, so several traversals reaching the same node run at
 * the same time, on different threads, through <strong>this same object</strong>. An action that
 * keeps mutable state in a field — a counter, a collection, a {@code StringBuilder}, a lazily
 * assigned field, a reusable buffer — is racy, and the race is reachable from ordinary graph content.
 *
 * <p><b>The previous behaviour was never contractual.</b> Previously each
 * logical node was backed by one actor, and an actor processes one message at a time, so every action
 * in existence was serialised as a side effect of how nodes happened to be represented. ADR 0024
 * removes that representation deliberately: serialising naturally parallel work behind one mailbox is
 * the bottleneck it exists to remove, and an author who wants serialisation should be able to ask for
 * it rather than receive it by accident. Code written against the old behaviour still compiles and
 * still passes single-threaded tests; it is simply no longer protected.
 *
 * <p><b>Where state that cannot be shared belongs.</b> In
 * {@link NodeBehavior#create(NodeConfiguration)}, which is still called once per node — derive it
 * there, make it immutable, and capture it. State that must be mutable and shared across a node's
 * invocations is the action's own responsibility to synchronise; the runtime will not do it. State
 * that belongs to one invocation belongs in local variables of {@link #handle(NodeMessage)}, which is
 * where it is safe by construction.
 *
 * <p>Asserted by {@code NodeBehaviorContract}, so a package that breaks under concurrent invocation
 * fails its own conformance run rather than failing in production under load.</p>
 *
 * <h2>Returning a stage, and failing through it</h2>
 * <p>A node that cannot proceed must return a stage that completes exceptionally rather than
 * throwing synchronously. The runtime's terminal bookkeeping for a traversal — its failure event,
 * its state transition and its join release — is attached to the returned stage, so a synchronous
 * throw can escape the traversal's own accounting and leave it non-terminal with nothing recorded.
 * Whether it does depends on which engine adapter is installed, which is precisely the kind of
 * behaviour that must not vary between adapters.</p>
 *
 * <h2>What a result may not carry</h2>
 * <p>{@link NodeResult} has no identity component and none may be added: a node influences payload,
 * outcome and attributes, never who it is running as. Attributes in the reserved
 * {@code ravenroot.} namespace are the runtime's own operative state — anything a node returns
 * under that prefix is discarded unread before the result is used, so a node can neither forge nor
 * suppress runtime state such as the synthetic-provenance marker.</p>
 */
@FunctionalInterface
public interface NodeAction {

    /**
     * Handles one message.
     *
     * @param message the delivered message; its security context was fixed at ingress and is not
     *                derived from graph content or from any upstream node's output
     * @return the node's result; never {@code null}, and exceptionally completed when the node fails
     */
    CompletionStage<NodeResult> handle(NodeMessage message);
}
