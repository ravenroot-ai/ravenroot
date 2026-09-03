package ai.ravenroot.cli;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * What {@link RavenrootCli} needs, independent of how it is served (API-05).
 *
 * <p>Two implementations: {@link EmbeddedBackend} (today's in-process path, unchanged in substance)
 * and {@link ai.ravenroot.cli.remote.RemoteBackend}. {@code --server &lt;url&gt;} present selects
 * the remote backend; absent selects the embedded one — the existing mode is preserved by this
 * structural choice, not by a flag anyone could forget to check.</p>
 */
public interface CliBackend {
    StatusView status() throws IOException;

    RuntimeView runtime() throws IOException;

    List<NodeTypeView> nodeTypes() throws IOException;

    InspectView inspect(byte[] graphMl) throws IOException;

    RunView run(byte[] graphMl, String payload) throws IOException;

    /**
     * Reads back what {@link #run} started: the terminal (or not-yet-terminal) outcome of an
     * execution, keyed by the {@code executionId} a {@link RunView} returned earlier.
     */
    ResultView result(String executionId) throws IOException;

    /**
     * This tenant's live executions -- accepted and not yet terminal -- with the identifiers
     * {@link #cancel} needs. The API exposes it through {@code GET /v1/executions/live}; before this
     * method existed nothing on the CLI could reach it, so {@code cancel <traversal-id>}
     * took an argument this façade gave no way to discover. Tenant scoping is structural, the same
     * mechanism {@link #result} and every other verb here already use: {@link EmbeddedBackend} passes
     * the constructor's {@code RequestContext} straight through, {@link ai.ravenroot.cli.remote.RemoteBackend}
     * relies on the server resolving the tenant from the bearer token. Neither backend accepts a tenant
     * as a parameter a caller could substitute.
     *
     * <p>Read directly from the runtime's own active-execution bookkeeping on both transports (never
     * derived from the event stream), so a stalled execution -- one whose behavior has stopped
     * publishing anything -- still appears. That property is the entire reason this method exists;
     * see {@code LiveExecution}'s own Javadoc for why an event-derived listing would silently omit
     * exactly the execution an operator needs this for.</p>
     */
    List<LiveView> live() throws IOException;

    /**
     * This tenant's durable process inventory (issue 154): what this deployment's own persisted
     * record says exists, surviving a restart -- distinct from {@link #live}, which is unchanged and
     * remains the process-local live view. The API exposes it through {@code GET
     * /v1/executions/inventory}; see that route's own Javadoc, and
     * {@code ai.ravenroot.api.application.RavenrootApplication#processInventoryAvailable()}, for the
     * full distinction between the two. Tenant scoping is structural, the same mechanism {@link #live}
     * already documents.
     *
     * <p>Unfiltered, first page only, terminal rows <strong>included</strong> --
     * {@code ProcessInventoryQuery.everything()} on both transports. Deliberately the opposite
     * default from the HTTP route's own ({@code includeTerminal=false}): an operator running this
     * verb right after {@code ravenroot run} needs to keep seeing the instance once it finishes, or
     * the verb would go silent on the exact work it was just used to start. Filtering and pagination
     * are the query surface's own job and are not exposed as CLI flags yet; this is deliberately the
     * smallest useful verb rather than a second console.</p>
     * @throws IOException if this deployment has no durable inventory-capable execution store composed
     */
    List<InventoryView> inventory() throws IOException;

    /**
     * One durable process instance's traversals from the inventory (issue 154). {@code processInstanceId}
     * is a process instance id, not the traversal/execution id {@link #cancel} and {@link #result}
     * take -- see {@code GET /v1/executions/{id}/traversals}'s own Javadoc for why the two id spaces
     * are deliberately distinct.
     * @throws IOException if the instance is absent, belongs to another tenant, was purged past its
     * terminal retention window (all three indistinguishable by design), or this deployment has no
     * durable inventory-capable execution store composed
     */
    List<TraversalInventoryView> traversals(String processInstanceId) throws IOException;

    /** API-02. Cancel and server drain; pause/resume and handler triggers remain out of scope. */
    CancelView cancel(String traversalId) throws IOException;

    DrainView drain() throws IOException;

    /**
     * This tenant's process-local deployments, in the same order the API returns them (id
     * order). Empty for a tenant that has registered none -- there is no distinguishable "you have
     * none" versus "you have none I can show you", matching {@link #live}'s own convention.
     */
    List<DeploymentView> deployments() throws IOException;

    /**
     * Registers an immutable graph version under {@code deploymentId}, tenant-scoped. Starts
     * nothing: the returned view is {@code REGISTERED} and {@link #startDeployment} is the separate
     * call that serves it. Unlike a source session, a graph with no effective SOURCE is accepted --
     * {@code sourceCount} legitimately reads {@code 0}. Re-registering the same id with the same graph
     * is a no-op that returns the current status; with a different graph both transports refuse it
     * (mapped to the server's 409 CONFLICT on the remote transport, to {@code LocalDeploymentException}
     * on the embedded one).
     */
    DeploymentView registerDeployment(String deploymentId, byte[] graphMl) throws IOException;

    /**
     * Observes one of this tenant's deployments. An id never registered and a sibling tenant's
     * id are the identical nondisclosing failure on both transports -- neither this method nor its
     * caller can tell them apart, by construction.
     */
    DeploymentView deployment(String deploymentId) throws IOException;

    /**
     * Starts a registered deployment and answers only once it has reached readiness (or the
     * truthful {@code FAILED} state if startup rolled back) -- the command blocks rather than leaving
     * the caller to poll, matching the HTTP route's own bounded wait.
     */
    DeploymentView startDeployment(String deploymentId) throws IOException;

    /**
     * Stops a deployment and leaves it registered and re-startable -- distinct from
     * {@link #undeployDeployment}, which also removes the registration. Answers with the authoritative
     * reached {@code STOPPED} state, not merely an acknowledgement that a stop was requested.
     */
    DeploymentView stopDeployment(String deploymentId) throws IOException;

    /** A completed stop followed by a start, never the two overlapping. */
    DeploymentView restartDeployment(String deploymentId) throws IOException;

    /**
     * Stops the deployment and then removes its registration. A second undeploy of the same id,
     * like every other lookup here, reports the same nondisclosing failure as an id never registered.
     */
    DeploymentView undeployDeployment(String deploymentId) throws IOException;

    /**
     * The public credential-management contract requires the CLI and the interface to use the same
     * API. Lists the caller's own stored credentials --
     * exactly {@code GET /v1/credentials}' own scoping, never another tenant's or another subject's.
     *
     * <p>Never a value, on either transport: see {@link CredentialView}'s own Javadoc for why no field
     * on this interface could carry one even if a server response offered it.</p>
     */
    List<CredentialView> credentials() throws IOException;

    /**
     * Stores one credential value and returns the same view a subsequent {@link #credentials}
     * call would show for it -- reference, label, scheme, username, createdAt.
     *
     * @param value the secret text. Reaches this method as an ordinary parameter, not a command-line
     *              argument -- see {@code CredentialAddArgs}, this CLI's own parser for the verb that
     *              calls this, for why {@code --value} does not exist as a flag (Rule 29, the same rule
     *              {@code ai.ravenroot.cli.remote.CliToken} states for the bearer token).
     */
    CredentialView addCredential(String label, String scheme, String username, String value) throws IOException;

    /**
     * The CLI's own mirror of one stored credential -- a separate declaration, not a reuse, for
     * the reason {@link RuntimeView} states: {@link CliBackend} must not depend on the server's types.
     *
     * <p><b>There is no field here for a value, and there is no server field to fill one from.</b>
     * {@code GET /v1/credentials} and the {@code POST /v1/credentials} response both answer this exact
     * shape and neither carries a value in any form -- see
     * {@code ai.ravenroot.server.credential.UserCredentialWire#writeCredential}'s own Javadoc for why
     * that response has no branch that could emit one.</p>
     */
    record CredentialView(String reference, String label, String scheme, String username, String createdAt) {
    }

    record StatusView(String state, String executionEngine, List<String> capabilities) {
    }

    /**
     * The CLI's own mirror of {@code RuntimeSnapshot} (API-05) — a separate declaration, not a
     * reuse, because {@link CliBackend} must not depend on the server's application-api types.
     *
     * @param activeNodeInstances same misnomer as {@code RuntimeSnapshot.activeNodeInstances}:
     *                             arrivals in flight per node, not instances. Kept under this
     *                             name because it is the field {@link ai.ravenroot.cli.remote.RemoteBackend}
     *                             reads off the wire; see {@code RuntimeSnapshot}'s own Javadoc for the
     *                             full account and the survey of where this caveat is repeated.
     */
    record RuntimeView(int activeExecutions, Map<String, Integer> activeNodeInstances) {
    }

    record NodeTypeView(String behavior, String category, String visualOrAgentic, String description) {
    }

    /**
     * {@code valid}/{@code violations} distinguish validity from the four structural counts.
     * Previously this view carried only the counts, so {@code ravenroot inspect} and
     * {@code POST /v1/graphs/inspect} reported the identical shape for a sound graph and one an
     * unknown node kind or a missing/surplus terminal
     * made unrunnable. Mirrors {@code GraphSummary}, which both {@link EmbeddedBackend} and
     * {@link ai.ravenroot.cli.remote.RemoteBackend} now read this pair from.
     *
     * <p>{@code violations} is defensively copied. Both producers today already pass an
     * immutable list ({@code GraphSummary.violations()} on the embedded path, a freshly built
     * {@code .toList()} on the remote one), so this changes nothing observable yet -- but the guarantee
     * a public record of this interface makes should hold by type, not by every future caller
     * remembering what today's two callers happen to do.</p>
     */
    record InspectView(int nodes, int edges, int startNodes, int endNodes,
                        boolean valid, List<String> violations) {
        public InspectView {
            violations = List.copyOf(violations);
        }
    }

    /**
     * @param executionPolicy the policy the submission actually ran under, as the backend that
     *                        performed it reports it — {@code STANDARD} or {@code TEST_PASSTHROUGH}.
     *                        The remote server states this in its 202 body. Previously,
     *                        {@link ai.ravenroot.cli.remote.RemoteBackend} discarded it, which is
     *                        how a CLI could submit under Play semantics, print a clean success and
     *                        leave the operator no way to find out. It is a String rather than the
     *                        {@code ExecutionPolicy} enum for the reason {@link ResultView#payload()}
     *                        is a String: it is whatever the transport was told, and this interface
     *                        does not commit to the server's application-api types.
     */
    record RunView(String processInstanceId, String traversalId, String executionId, String graphVersion,
                    String executionPolicy) {
    }

    /**
     * {@code payload} is carried as raw JSON text, not a parsed object model, and that is
     * deliberate: text is the one representation the embedded transport (which has the full
     * {@code PayloadValue}/Java object) and the remote transport (which only ever sees the server's
     * JSON response body) can both produce identically, without this interface's public contract
     * having to commit to a JSON object model of its own. It is {@code null} when the execution has
     * no payload yet (still {@code RUNNING}, or {@code FAILED} before producing one).
     *
     * <p>{@code defaultedNodes} being non-empty means the run is DEGRADED: those nodes executed as
     * unresolved pass-through defaults rather than running their real behavior. Surfacing that list is
     * the entire reason this field exists on {@code ResultView} -- a caller must be able to see it
     * without going back to the server for the full outcome.</p>
     *
     * <h2>Three more fields, for exactly that reason</h2>
     * <p>{@code defaultedNodes} was carried and the other three node-fidelity signals were not, so the
     * sentence above was true of one of them and false of the rest.</p>
     *
     * <p><b>And the gap was this interface's, not the remote transport's.</b> The server declared
     * information that the HTTP client discarded, but that was only half the problem.
     * {@code ExecutionOutcome} carries {@code bypassedNodes} and {@code handledFailureNodes}, so
     * {@link EmbeddedBackend#result}, which holds that
     * object <em>in process</em> and needs no wire at all, was projecting two of its four node lists
     * and dropping the other two. The remote client was not reading what the server sent; the embedded
     * client was not reading what the runtime handed it. Both ended at the same place because the
     * narrowing was here, in the record they both fill in — which is why widening it is the fix rather
     * than patching {@link ai.ravenroot.cli.remote.RemoteBackend} alone.</p>
     * <ul>
     *   <li>{@code bypassedNodes}: nodes the traversal visited without constructing or invoking their
     *       behavior. <b>Two decisions land a node here and this list does not distinguish them</b>:
     *       the traversal was not executing behaviours (a Play/Test pass-through, or an edge carrying
     *       the {@code passthrough} command, which is sticky downstream), or the graph's author
     *       switched that one node off with {@code execution.bypass} while the rest of the run
     *       executed for real. Either way the run reports {@code COMPLETED},
     *       {@code degraded=false}, which is why the list is carried at all.
     *       <p>This entry used to end "a run where this is the whole visited set performed nothing at
     *       all". That inference was sound while one decision could populate the list and is not
     *       sound now — nor is its weaker cousin, "a non-empty list means the run was a rehearsal".
     *       An authored bypass fills this list from a fully executing production run. The cause is
     *       carried by the {@code NODE_BYPASSED} events' {@code publicReason}
     *       ({@code command.passthrough} / {@code authored}) and by nothing on this view, so a caller
     *       that needs it must read the event stream rather than reason from set membership.</p></li>
     *   <li>{@code handledFailure} / {@code handledFailureNodes}: nodes whose invocation failed inside
     *       a traversal the author routed around, which likewise completes clean. See
     *       {@code ExecutionOutcome#handledFailure()}.</li>
     * </ul>
     * <p>Neither is derivable from anything else here, which is the test for whether a field belongs
     * on this view: {@code degraded} says nothing about either, and the status says success for both.</p>
     *
     * <h2>{@code untakenEdges}, and it fails that same test differently than the other four</h2>
     * <p>Not a node list: each entry names one outgoing edge of a node this run bypassed --
     * {@code "<source>-><target> [outcome=<outcome>]"} -- that the node's own hardcoded {@code
     * "continue"} outcome could never select. {@code bypassedNodes} says WHICH nodes were bypassed;
     * this says what each one's bypass cost in edges that could never fire, which
     * {@code bypassedNodes} alone does not -- a node with a plain single {@code continue} edge and
     * one with a branch point behind only custom outcomes are indistinguishable in that list, and only this
     * field tells them apart.</p>
     */
    record ResultView(String executionId, String status, boolean degraded, List<String> visitedNodes,
                       List<String> defaultedNodes, List<String> bypassedNodes, boolean handledFailure,
                       List<String> handledFailureNodes, List<String> untakenEdges, String payload) {
        public ResultView {
            visitedNodes = List.copyOf(visitedNodes);
            defaultedNodes = List.copyOf(defaultedNodes);
            bypassedNodes = List.copyOf(bypassedNodes);
            handledFailureNodes = List.copyOf(handledFailureNodes);
            untakenEdges = List.copyOf(untakenEdges);
        }
    }

    /**
     * Mirrors {@code ai.ravenroot.api.application.LiveExecution}. {@code startedAt} is
     * carried as the transport's own string form ({@code Instant.toString()} on the embedded path,
     * whatever the wire sent on the remote one) rather than parsed back into an {@code Instant} --
     * this interface has no reason to commit to that type, and every caller so far only prints it.
     */
    record LiveView(String processInstanceId, String traversalId, String executionId, String graphVersion,
                     String startedAt) {
    }

    /**
     * Mirrors {@code ai.ravenroot.api.persistence.ProcessInventoryEntry} (issue 154), bounded to
     * non-secret fields -- no payloads, no opaque blobs, exactly the wire route's own contract.
     * {@code deploymentId}, {@code workloadId} and {@code correlationId} are {@code null} when
     * absent, the same nullable-string convention {@link CredentialView} already uses for a field a
     * transport may not carry.
     * @param deploymentId hosting deployment, or {@code null} for a transient submission
     */
    record InventoryView(String processInstanceId, String status, String disposition, String graphVersion,
                         String deploymentId, String workloadId, String correlationId, int traversalCount,
                         String createdAt, String updatedAt) {
    }

    /** Mirrors {@code ai.ravenroot.api.persistence.TraversalInventoryEntry} (issue 154). */
    record TraversalInventoryView(String traversalId, int position, String ingressNodeId, String status,
                                  String disposition, int invocationCount, int parkedAttemptCount) {
    }

    /** Mirrors {@code ai.ravenroot.api.application.CancelResult}: {@code note} is the operator-facing
     * statement that already-issued effects may persist -- see that record's Javadoc for why it is
     * data here, not merely a comment on the class that produced it, and is carried all the way to the
     * CLI's own printed output. */
    record CancelView(String outcome, String traversalId, String note) {
    }

    record DrainView(String outcome) {
    }

    /**
     * Mirrors {@code ai.ravenroot.api.application.LocalDeploymentStatus} -- a separate
     * declaration, not a reuse, for the reason {@link RuntimeView} states: {@link CliBackend} must
     * not depend on the server's application-api types.
     *
     * @param scope always {@code "LOCAL_PROCESS"} on both transports, as documented: this
     *              interface exposes only the single-process deployment lifecycle, and the field is
     *              carried through rather than assumed so every printed line states the same
     *              guarantee the HTTP wire states, in the same word.
     * @param sourceCount effective inbound SOURCE nodes validated from the registered graph;
     *                    legitimately {@code 0} -- a graph with no SOURCE is registrable and
     *                    controllable as a deployment, which is what separates this surface from a
     *                    source session.
     * @param diagnostic fixed, bounded, operator-safe explanation; {@code null} except for a
     *                    {@code DEGRADED} or {@code FAILED} deployment.
     */
    record DeploymentView(String deploymentId, String state, int sourceCount, String scope,
                          String diagnostic) {
    }
}
