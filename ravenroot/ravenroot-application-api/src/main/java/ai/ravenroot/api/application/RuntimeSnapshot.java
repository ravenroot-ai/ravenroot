package ai.ravenroot.api.application;

import java.util.Map;

/**
 * A non-durable point-in-time read of what the runtime is doing.
 *
 * @param activeExecutions how many traversals are currently in flight.
 * @param activeNodeInstances <strong>arrivals in flight per node, not instances</strong>.
 *                             The name is a misnomer this class is stuck with: it is a cross-process
 *                             wire field — the CLI's {@code RuntimeView} and the remote backend's JSON
 *                             both read it by this name — so renaming it is a separate, deliberately
 *                             out-of-scope decision. What it actually counts is the
 *                             queue depth in front of each node's actor(s): started and not yet
 *                             settled. For a node whose nature is resident, ten concurrent arrivals
 *                             waiting on the one shared actor read 10 here while exactly one instance
 *                             exists — that instance count is a different number, reported per event by
 *                             {@link ExecutionEvent#activeInstances()}, and unavailable at snapshot time
 *                             because the instance registry is not visible from the producer of this
 *                             record. See {@code ExecutionMonitor#snapshot()} for the full account of
 *                             why this class cannot report the other number instead.
 *                             <p><b>Where the caveat is repeated, and where it is deliberately not</b>.
 *                             Every surface from which a reader could form a belief
 *                             about the number from carries this explanation or one that links back
 *                             here: {@code RavenrootServer}'s {@code GET /v1/runtime} handler,
 *                             {@code AssistantInternalContext.runtimeJson} (the number the assistant
 *                             reports to a request for load), {@code RavenrootCli.runtime}'s console
 *                             output, and {@code CliBackend.RuntimeView}, the CLI's own mirror of this
 *                             record. Two surfaces that also carry the name are deliberately left
 *                             silent, not overlooked: {@code RemoteBackend} and {@code EmbeddedBackend}
 *                             only copy the value between this record and {@code RuntimeView} and make
 *                             no claim about what it means, so there is nothing there to be wrong; and
 *                             {@code docs/getting-started/deploy-locale.md}'s troubleshooting note
 *                             ("if {@code activeExecutions} is nonzero with {@code activeNodeInstances}
 *                             empty, there is an unreachable traversal") only tests the map for
 *                             <em>emptiness</em>, which holds under either reading of the field —
 *                             nothing in that sentence depends on whether the number counts arrivals or
 *                             instances, so it does not need this caveat to stay correct.
 */
public record RuntimeSnapshot(int activeExecutions, Map<String, Integer> activeNodeInstances) {
/**
 * Copies runtime snapshot fields so callers observe a stable state view.
 */
    public RuntimeSnapshot {
        activeNodeInstances = activeNodeInstances == null ? Map.of() : Map.copyOf(activeNodeInstances);
    }
}
