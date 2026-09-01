package ai.ravenroot.server;

import ai.ravenroot.api.execution.ExecutionEngine;

import java.time.Duration;

/**
 * The shutdown sequence, extracted from {@code RavenrootServerMain}'s
 * shutdown-hook registration so it can be driven directly by a test rather than only by an actual
 * JVM exit -- see {@code DrainSequencingTest}, which calls {@link #run} on a background thread and
 * observes {@code /ready} and in-flight HTTP work while it executes, exactly as a real shutdown
 * would be observed from outside the process.
 *
 * <h2>Why the order matters</h2>
 * <p>Calling
 * {@code server.close()} (which stopped the HTTP listener with no wait, {@code HttpServer.stop(0)})
 * <strong>before</strong> the hook's separate, explicit {@code engine.close()}. Two consequences
 * followed from that order alone: the socket was gone before
 * {@link ai.ravenroot.server.readiness.ReadinessState#DRAINING} could ever be observed through
 * {@code /ready}, and an HTTP request already accepted when shutdown began was cut off immediately
 * rather than given a chance to finish.</p>
 *
 * <p><strong>{@code server.close()} itself already reaches the engine, through a longer chain than
 * it looks: {@code RavenrootServer.close()} calls {@code application.close()}
 * ({@code DefaultRavenrootApplication.close()}), which closes every still-tracked
 * {@code ActiveExecution} -- an execution leaves that tracking only on completion, so one still
 * running at shutdown is still there -- and {@code ActiveExecution.close()} closes its
 * {@code GraphRunner}, whose own {@code close()} calls {@code awaitTermination(engine::stop)},
 * escalating to {@code awaitTermination(engine::cancel)} if a node has not stopped within that
 * runner's {@code shutdownBound} (default 10s, {@code GraphRunner.DEFAULT_SHUTDOWN_BOUND}).</strong>
 * That per-execution stop-then-cancel is what protects a message already being handled when
 * shutdown begins: {@code ExecutionEngine.stop(NodeRef)} "refuses new messages and lets accepted
 * ones finish" (its own contract), so a node mid-message when {@code server.close()} reaches it is
 * allowed to complete rather than being cut off. This runs <em>sequentially</em> over
 * {@code activeExecutions.values()}, so several in-flight executions are stopped one at a time, not
 * in parallel -- worth knowing before assuming {@code server.close()} returns quickly with more than
 * one execution outstanding.</p>
 *
 * <p>This sequence avoids both consequences above by construction, not by reordering the same three
 * calls: {@link ExecutionEngine#drain()} runs <strong>first</strong>, while the HTTP listener is
 * still fully up -- {@code /ready} (which reads live engine state through
 * {@code application.status()}) starts reporting
 * {@link ai.ravenroot.server.readiness.ReadinessState#DRAINING} immediately, and every other route
 * keeps answering normally. A grace period follows, sized so a readiness poller gets at least one
 * chance to see the unready response before anything else changes (see {@code
 * ReadinessConfiguration#DEFAULT_DRAIN_GRACE_PERIOD}'s Javadoc). Only then does {@link
 * RavenrootServer#close()} stop the listener -- with a real bound, not zero -- and cascade into the
 * per-execution stop-then-cancel described above, so an exchange already in flight, or one accepted
 * during the grace period before a load balancer caught up, is given time to complete rather than
 * being severed. {@link ExecutionEngine#close()} runs last: by the time it runs, {@code
 * server.close()}'s cascade has already stopped or cancelled every execution it was tracking, so its
 * job here is the broader, idempotent one its own contract describes -- escalating and releasing
 * whatever that cascade did not (an execution the tracking map itself had already lost, if any),
 * not the primary mechanism that protects in-flight work.</p>
 */
final class GracefulShutdown {
    private GracefulShutdown() {
    }

    static void run(ExecutionEngine engine, RavenrootServer server, Duration drainGracePeriod) {
        // The returned stage is not awaited here: it completes when node work has stopped, but
        // the grace period and server.close()'s own bound below are what this sequence actually
        // relies on to give in-flight work time, and engine.close() (idempotent) finishes the job
        // regardless of whether this stage has settled yet.
        engine.drain();
        sleepUninterruptibly(drainGracePeriod);
        server.close();
        engine.close();
    }

    private static void sleepUninterruptibly(Duration duration) {
        if (duration.isZero()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
