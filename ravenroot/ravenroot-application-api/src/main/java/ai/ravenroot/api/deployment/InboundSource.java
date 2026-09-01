package ai.ravenroot.api.deployment;

import java.util.concurrent.CompletionStage;

/**
 * A deployment-scoped inbound source: the lifecycle a {@code NodeBehavior} implements to poll,
 * subscribe or listen for unsolicited external events for the life of a {@link GraphDeployment}
 * alongside {@link GraphDeployment} and {@link TrustedIngress}.
 *
 * <h2>Obtained only where a node action is obtained</h2>
 * <p>A source instance exists only because a specific deployment's graph named a node whose behavior
 * declares one, and only from the same per-node construction step that already builds that node's
 * {@code NodeAction} while the graph is being spawned inside {@link GraphDeployment#start}. Loading a
 * node package — instantiating it, reading its declared behaviors into a catalog — never reaches this
 * interface at all; a package sitting in the catalog, unused by any graph, creates nothing and starts
 * nothing.</p>
 *
 * <h2>Same shape as {@link GraphDeployment} on purpose</h2>
 * <p>{@link #start}, {@link #stop} and {@link #restart} are asynchronous, idempotent and expected to
 * be single-flight in the same sense {@link GraphDeployment}'s own methods are — a source is driven by
 * exactly one deployment, so there is one caller, but an implementation must still tolerate being
 * asked to stop twice or start twice without duplicating work. This mirrors the proven contract rather
 * than inventing a second one.</p>
 *
 * <h2>Every hook here must return within its own bound</h2>
 * <p>A deployment's {@code stop()} waits for every source's {@link #stop} individually, not for all
 * of them together, so one slow source cannot silently spend another's time. But each wait is still
 * finite: an implementation whose underlying resource cannot be interrupted (a blocking read with no
 * cancellation path, for instance) cannot honor this contract and must not be shipped as one — that is
 * a fact about the resource, not a timeout to paper over it with. Source count is now a factor inside
 * a deployment's own close time; the shutdown-budget arithmetic must account
 * for it.</p>
 */
public interface InboundSource {
    /**
     * Starts this source and completes when it is actually ready to receive events — not merely when
     * an asynchronous connection attempt has been issued. Completes exceptionally if startup fails;
     * the owning deployment rolls the whole activation back, calling {@link #rollback} on any sibling
     * source that had already reached readiness.
 * @param context deployment-issued identity, ingress, and health-reporting capabilities.
 * @return stage completing only when the source can receive external events.
     */
    CompletionStage<Void> start(InboundSourceContext context);

/**
 * Stops this source: closes its own admission, drains what it already accepted, releases resources.
 * @return stage completing after admission closes, accepted work drains, and resources release.
 */
    CompletionStage<Void> stop();

/**
 * A completed {@link #stop} followed by a {@link #start}. Default composition, like {@link GraphDeployment#restart}.
 * @param context new lifecycle context supplied after the preceding stop completes.
 * @return stage completing when the source is ready after the restart.
 */
    default CompletionStage<Void> restart(InboundSourceContext context) {
        return stop().thenCompose(ignored -> start(context));
    }

    /**
     * Called instead of an ordinary {@link #stop} when this source had already reached readiness but
     * a sibling node's startup failed, forcing the whole deployment activation to roll back. Nothing
     * this source did was ever actually served, so there is nothing to drain — only to release.
     * Defaults to {@link #stop} for a source with no meaningful distinction to make.
 * @return stage completing after resources from failed deployment activation are released.
     */
    default CompletionStage<Void> rollback() {
        return stop();
    }

    /**
     * Called instead of {@link #stop} when <b>this deployment will never run again in this process</b>
     * — as opposed to being stopped or restarted, after which it can run again with a fresh source.
     *
     * <p>Two callers reach it, and the condition is the same one in both: the owning application is
     * shutting down, or this one deployment is being undeployed through
     * {@code DELETE /v1/deployments/{id}}, which removes its registration so that no later start is
     * possible. Note what the second one means: <b>the process keeps running, and sibling deployments
     * in it keep serving.</b> Do not read this call as "the JVM is ending".</p>
     *
     * <h4>What to release here, and what must not be released here</h4>
     * <p>This hook exists for what a source deliberately <em>keeps</em> across a {@link #stop},
     * because its own deployment could start again — a lazily built client or pool that this source
     * owns and reuses across its deployment's restarts is the case it is for. Once no restart is
     * coming, keeping it leaks, and there is no later opportunity: after undeploy, nothing holds a
     * reference to this source any more.</p>
     * <p><b>Never release anything shared beyond this deployment.</b> A connection pool, client,
     * cache, thread pool or registry shared with sibling deployments — anything reached through a
     * static field, a singleton, or a handle the host process owns — must be left untouched, because
     * this call routinely happens while those siblings are still serving. Closing one there would
     * break deployments that nothing asked to stop. Process-wide resources are the host's to release,
     * not this source's; if a source is the only user of one today, that is a fact about the current
     * deployment set, not a licence to close it here.</p>
     *
     * <p>Defaults to {@link #stop}, which is correct for any source with nothing kept across its own
     * restarts — the case for every source shipped in-tree.</p>
     * @return stage completing after this source's own cross-restart resources are released.
     */
    default CompletionStage<Void> shutdown() {
        return stop();
    }
}
