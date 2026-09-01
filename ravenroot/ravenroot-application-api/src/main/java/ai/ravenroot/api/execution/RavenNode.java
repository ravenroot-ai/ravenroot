package ai.ravenroot.api.execution;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Pure-Java processing unit wrapped by an execution-engine adapter. */
public interface RavenNode {
/**
 * Invoked once after the engine has assigned the node its execution context.
 *
 * @param context the engine services and identity available for this node's lifetime
 */
    default void onStart(NodeContext context) {
    }

/**
 * Handles one admitted message.
 *
 * @param message the command, data or lifecycle message delivered by the engine
 * @param context the services and cancellation signal for this node
 * @return a stage whose successful result is routed to the message sender; failure is supervised
 */
    CompletionStage<NodeResult> onMessage(NodeMessage message, NodeContext context);

/**
 * Invoked once when graceful stop or cancellation has ended message admission.
 *
 * @param context the node context that remains available while shutdown hooks run
 */
    default void onStop(NodeContext context) {
    }
}
