package ai.ravenroot.extensions.websocket;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Controllable reconnect timer; production uses the JDK delayed executor. */
@FunctionalInterface
interface WebSocketReconnectScheduler {
    WebSocketReconnectScheduler SYSTEM = (task, delayMillis) ->
            CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS).execute(task);

    void schedule(Runnable task, long delayMillis);
}
