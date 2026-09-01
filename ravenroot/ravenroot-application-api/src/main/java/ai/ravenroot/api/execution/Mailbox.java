package ai.ravenroot.api.execution;

/** Runtime-neutral count of messages accepted by an engine but not yet completed by a node. */
public interface Mailbox {
/**
 * Reports queued work that has been admitted but not yet delivered to the node behavior.
 * @return the current mailbox depth, intended for supervision and diagnostics
 */
    int pendingMessages();
}
