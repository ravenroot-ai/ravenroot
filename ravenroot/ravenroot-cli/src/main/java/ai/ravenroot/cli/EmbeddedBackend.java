package ai.ravenroot.cli;

import ai.ravenroot.api.application.AuthorizedRavenrootApplication;
import ai.ravenroot.api.application.ExecutionLookup;
import ai.ravenroot.api.application.ExecutionPolicy;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.security.RequestContext;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Today's in-process path (API-05), unchanged in substance: every method here is exactly what
 * {@link RavenrootCli} previously called on {@link AuthorizedRavenrootApplication} directly.
 * Selected whenever {@code --server} is absent, by construction (see {@link CliBackend}).
 */
final class EmbeddedBackend implements CliBackend {
    /** Matches {@code ReadinessConfiguration.DEFAULT_DRAIN_GRACE_PERIOD} (6s). Duplicated rather
     * than shared because ravenroot-cli does not depend on ravenroot-server. */
    private static final java.time.Duration DEFAULT_DRAIN_BOUND = java.time.Duration.ofSeconds(6);

    private final AuthorizedRavenrootApplication application;
    private final RequestContext requestContext;

    EmbeddedBackend(AuthorizedRavenrootApplication application, RequestContext requestContext) {
        this.application = Objects.requireNonNull(application, "application");
        this.requestContext = Objects.requireNonNull(requestContext, "requestContext");
    }

    @Override
    public StatusView status() {
        var status = application.status(requestContext);
        return new StatusView(status.state(), status.executionEngine(),
                status.capabilities().stream().sorted().toList());
    }

    @Override
    public RuntimeView runtime() {
        var snapshot = application.runtimeSnapshot(requestContext);
        return new RuntimeView(snapshot.activeExecutions(), snapshot.activeNodeInstances());
    }

    @Override
    public List<NodeTypeView> nodeTypes() {
        return application.nodeTypes(requestContext).stream()
                .map(type -> new NodeTypeView(type.behavior(), type.category(),
                        type.agentic() ? "agentic" : type.visualType(), type.description()))
                .toList();
    }

    @Override
    public InspectView inspect(byte[] graphMl) {
        var summary = application.inspectGraphMl(requestContext, new ByteArrayInputStream(graphMl));
        return new InspectView(summary.nodes(), summary.edges(), summary.startNodes(), summary.endNodes(),
                summary.valid(), summary.violations());
    }

    /**
     * The {@code executionPolicy} this reports is not inferred and not a default assumed on the
     * application's behalf: the three-argument {@code startGraphMl(RequestContext, InputStream, Object)}
     * overload called below takes no policy parameter and hard-codes {@link ExecutionPolicy#STANDARD},
     * so that is what this call runs under, and that is the constant named below. The contract suite
     * asserts it against what the remote transport reads off the wire, so if the two ever stop agreeing
     * a test says so rather than an operator finding out from a run that did nothing.
     *
     * <p>Stated precisely because the imprecise version is tempting and wrong: this is a property of
     * <em>the call</em>, not of the application API. A policy is expressible from here — the
     * five-argument {@code startGraphMl(..., PayloadEnvelope, PayloadLimits, ExecutionPolicy)} overload
     * is public — but reaching it means moving this method onto the envelope payload path. Previously,
     * that would also have changed the payload contract this call gets — the envelope overload enforced
     * {@code PayloadLimits} and walked the payload tree for reserved keys where the {@code Object}
     * overload below did neither. The {@code Object} overload now closes that gap, so the
     * two now enforce the identical budget and the identical tree-walked reserved-key check; moving
     * this call onto the envelope overload today would change nothing about what payload this method
     * accepts. What still blocks the move is only the policy: reaching the five-argument overload means
     * choosing one, and that is a payload-contract-adjacent decision that belongs to whoever designs a
     * CLI Test verb; see {@link ai.ravenroot.cli.remote.RemoteBackend#run} for the whole argument.</p>
     */
    @Override
    public RunView run(byte[] graphMl, String payload) {
        ExecutionPolicy policy = ExecutionPolicy.STANDARD;
        var submission = application.startGraphMl(requestContext, new ByteArrayInputStream(graphMl),
                (Object) payload);
        return new RunView(submission.processInstanceId().toString(), submission.traversalId().toString(),
                submission.executionId().toString(), submission.graphVersion(), policy.name());
    }

    @Override
    public ResultView result(String executionId) throws IOException {
        UUID id;
        try {
            id = UUID.fromString(executionId);
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Not an execution id: " + executionId);
        }
        var lookup = application.executionResult(requestContext, id);
        return switch (lookup) {
            case ExecutionLookup.Found found -> {
                var outcome = found.outcome();
                String payload = outcome.payload() == null ? null
                        : PayloadJson.write(PayloadValue.fromJava(outcome.payload(), PayloadLimits.DEFAULTS));
                // BypassedNodes and handledFailureNodes are carried on the outcome
                // for both transports, and this projection used to drop both. Sorted like the two
                // lists above, and like RavenrootServer#stringArrayJson, so the same execution renders
                // identically whichever backend answered. UntakenEdges gets the same treatment,
                // for the same reason -- ExecutionOutcome already carries it, so dropping it here
                // would be this projection's own gap, not the remote transport's.
                yield new ResultView(outcome.executionId().toString(), outcome.status().toString(),
                        outcome.paused(), outcome.degraded(), outcome.visitedNodes().stream().sorted().toList(),
                        outcome.defaultedNodes().stream().sorted().toList(),
                        outcome.bypassedNodes().stream().sorted().toList(),
                        outcome.handledFailure(),
                        outcome.handledFailureNodes().stream().sorted().toList(),
                        outcome.untakenEdges().stream().sorted().toList(), payload,
                        // The raw enum name, matching RavenrootServer#executionOutcomeJson's own wire
                        // shape -- CliBackend carries no dependency on ExecutionTerminationReason itself.
                        outcome.terminationReason() == null ? null : outcome.terminationReason().name());
            }
            // These two IOException messages must match, verbatim, what RemoteBackend.renderError
            // produces for the equivalent 410/404 server responses -- see ErrorCode.EXECUTION_RESULT_EXPIRED
            // and ErrorCode.UNKNOWN_EXECUTION -- so both transports fail the same way for the same caller.
            case ExecutionLookup.Expired expired ->
                    throw new IOException("410 EXECUTION_RESULT_EXPIRED: the execution result is no longer retained");
            case ExecutionLookup.Unknown unknown -> throw new IOException("404 UNKNOWN_EXECUTION: unknown execution");
        };
    }

    /** Tenant scoping is the same {@code requestContext} pass-through every other verb here
     * uses -- see {@link CliBackend#live}'s own Javadoc for why that is deliberate. */
    @Override
    public List<LiveView> live() {
        return application.liveExecutions(requestContext).stream()
                .map(execution -> new LiveView(execution.processInstanceId().toString(),
                        execution.traversalId().toString(), execution.executionId().toString(),
                        execution.graphVersion(), execution.startedAt().toString(), execution.paused()))
                .toList();
    }

    /** The per-request page size this backend pages {@link #inventory()} with. Matches
     * {@code ProcessInventoryQuery.Builder}'s own default, so a tenant small enough to fit one page
     * behaves exactly as before this method paged to completion -- see {@link CliBackend#inventory}'s
     * own Javadoc for why pagination is internal rather than left to the caller. */
    private static final int INVENTORY_PAGE_SIZE = 50;

    /** Tenant scoping is the same {@code requestContext} pass-through every other verb here
     * uses -- see {@link CliBackend#inventory}'s own Javadoc for why this loops to completion. */
    @Override
    public InventoryListing inventory() throws IOException {
        try {
            var items = new java.util.ArrayList<InventoryView>();
            var query = ai.ravenroot.api.persistence.ProcessInventoryQuery.everything(INVENTORY_PAGE_SIZE);
            while (true) {
                var page = application.processInventory(requestContext, query);
                page.items().forEach(entry -> items.add(inventoryView(entry)));
                if (page.nextCursor().isEmpty()) {
                    return new InventoryListing(items, page.retainedFrom().toString());
                }
                query = query.after(page.nextCursor().get());
            }
        } catch (IllegalStateException unavailable) {
            throw new IOException("501 PROCESS_INVENTORY_UNAVAILABLE: " + unavailable.getMessage());
        }
    }

    @Override
    public TraversalListing traversals(String processInstanceId) throws IOException {
        UUID id;
        try {
            id = UUID.fromString(processInstanceId);
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Not a process instance id: " + processInstanceId);
        }
        try {
            var traversals = application.processInstanceTraversals(requestContext, id).stream()
                    .map(entry -> new TraversalInventoryView(entry.traversalId().toString(), entry.position(),
                            entry.ingressNodeId(), entry.status().name(), entry.disposition().name(),
                            entry.invocationCount(), entry.parkedAttemptCount(),
                            entry.terminationReason() == null ? null : entry.terminationReason().name()))
                    .toList();
            // Fetched only once the traversal read itself succeeded, so an absent/cross-tenant id
            // stays a plain 404 rather than acquiring a retainedFrom value that a closed-vocabulary
            // error body has nowhere to carry.
            String retainedFrom = application.processInventoryRetainedFrom(requestContext).toString();
            return new TraversalListing(traversals, retainedFrom);
        } catch (IllegalStateException unavailable) {
            throw new IOException("501 PROCESS_INVENTORY_UNAVAILABLE: " + unavailable.getMessage());
        } catch (ai.ravenroot.api.persistence.ExecutionStoreException storeFailure) {
            if (storeFailure.failure() instanceof ai.ravenroot.api.persistence.ExecutionStoreFailure.NotFound) {
                throw new IOException("404 UNKNOWN_PROCESS_INSTANCE: unknown process instance");
            }
            throw new IOException(storeFailure);
        }
    }

    private static InventoryView inventoryView(ai.ravenroot.api.persistence.ProcessInventoryEntry entry) {
        return new InventoryView(entry.key().processInstanceId().toString(), entry.status().name(),
                entry.disposition().name(), entry.graphVersionPin().reference(),
                entry.deploymentId().orElse(null), entry.workloadId().orElse(null),
                entry.correlationId().orElse(null), entry.traversalCount(),
                entry.createdAt().toString(), entry.updatedAt().toString(),
                entry.terminationReason() == null ? null : entry.terminationReason().name());
    }

    @Override
    public CancelView cancel(String traversalId) {
        var result = application.cancelExecution(requestContext, java.util.UUID.fromString(traversalId));
        return new CancelView(result.outcome().name(), result.traversalId().toString(), result.note());
    }

    @Override
    public DrainView drain() {
        var result = application.drain(requestContext, DEFAULT_DRAIN_BOUND);
        return new DrainView(result.outcome().name());
    }

    /** Matches {@link #DEFAULT_DRAIN_BOUND}'s own reasoning -- a little above the deployment
     * command's own 35s server-side bound, so a command that genuinely times out is reported as an
     * interrupted wait rather than this call blocking indefinitely. */
    private static final java.time.Duration DEFAULT_DEPLOYMENT_COMMAND_BOUND = java.time.Duration.ofSeconds(40);

    @Override
    public List<DeploymentView> deployments() {
        return application.localDeployments(requestContext).stream()
                .map(EmbeddedBackend::deploymentView).toList();
    }

    @Override
    public DeploymentView registerDeployment(String deploymentId, byte[] graphMl) {
        var status = application.registerLocalDeployment(requestContext, deploymentId,
                new ByteArrayInputStream(graphMl));
        return deploymentView(status);
    }

    @Override
    public DeploymentView deployment(String deploymentId) throws IOException {
        return application.localDeployment(requestContext, deploymentId)
                .map(EmbeddedBackend::deploymentView)
                .orElseThrow(EmbeddedBackend::unknownDeployment);
    }

    @Override
    public DeploymentView startDeployment(String deploymentId) throws IOException {
        return awaitDeployment(application.startLocalDeployment(requestContext, deploymentId));
    }

    @Override
    public DeploymentView stopDeployment(String deploymentId) throws IOException {
        return awaitDeployment(application.stopLocalDeployment(requestContext, deploymentId));
    }

    @Override
    public DeploymentView restartDeployment(String deploymentId) throws IOException {
        return awaitDeployment(application.restartLocalDeployment(requestContext, deploymentId));
    }

    @Override
    public DeploymentView undeployDeployment(String deploymentId) throws IOException {
        return awaitDeployment(application.undeployLocalDeployment(requestContext, deploymentId));
    }

    /**
     * Every lifecycle command answers a {@code CompletionStage<Optional<LocalDeploymentStatus>>};
     * this is the one place that waits for it, matching {@code RavenrootServer#awaitDeploymentCommand}'s
     * own bound and its own reading of an empty result as "this tenant holds no such id" -- the same
     * nondisclosing answer an unknown id and a sibling tenant's id both produce.
     */
    private DeploymentView awaitDeployment(java.util.concurrent.CompletionStage<java.util.Optional<
            ai.ravenroot.api.application.LocalDeploymentStatus>> command) throws IOException {
        java.util.Optional<ai.ravenroot.api.application.LocalDeploymentStatus> settled;
        try {
            settled = command.toCompletableFuture()
                    .get(DEFAULT_DEPLOYMENT_COMMAND_BOUND.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted waiting for the deployment command", interrupted);
        } catch (java.util.concurrent.ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException("Deployment command failed", cause);
        } catch (java.util.concurrent.TimeoutException timeout) {
            throw new IOException("Deployment command timed out", timeout);
        }
        return settled.map(EmbeddedBackend::deploymentView).orElseThrow(EmbeddedBackend::unknownDeployment);
    }

    /** Matches what {@code RemoteBackend}'s error rendering produces for the server's own 404
     * {@code UNKNOWN_RESOURCE}, so a caller sees the same message whichever transport served it. */
    private static IOException unknownDeployment() {
        return new IOException("404 UNKNOWN_RESOURCE: unknown resource");
    }

    private static DeploymentView deploymentView(ai.ravenroot.api.application.LocalDeploymentStatus status) {
        return new DeploymentView(status.deploymentId(), status.state().name(), status.sourceCount(),
                ai.ravenroot.api.application.LocalDeploymentStatus.SCOPE, status.diagnostic().orElse(null));
    }

    /**
     * Refused on this transport, loudly, and this is the honest answer rather than a gap.
     *
     * <p>A stored credential lives in a running server's own store
     * ({@code ai.ravenroot.server.credential.UserCredentialStore}), and the embedded path composes
     * {@code BehaviorEnvironment.safeDefaults()}, which has no such store at all. Three shapes were
     * available: an empty list, a private in-process store, and this refusal. An empty list is the
     * worst -- it is indistinguishable from an author who genuinely has none stored, so someone who
     * forgot {@code --server} is told, in a well-formed answer, that they have nothing. A private
     * in-process store would be a second credential namespace no server ever reads, which is worse
     * still. Failing with a message that names the flag is the only one that cannot mislead.
     *
     * <p>The same three shapes and the same conclusion applied to the {@code model-providers} verbs,
     * which this method's Javadoc used to point at; they left the CLI with the configuration
     * plane they spoke to, so the reasoning is stated here rather than referred to.</p>
     */
    @Override
    public List<CredentialView> credentials() throws IOException {
        throw new IOException(NEEDS_SERVER_CREDENTIALS);
    }

    /** Refused for the reason {@link #credentials()} states -- there is nowhere here to
     * store the value. */
    @Override
    public CredentialView addCredential(String label, String scheme, String username, String value)
            throws IOException {
        throw new IOException(NEEDS_SERVER_CREDENTIALS);
    }

    /** One sentence, one place, so the two refusals above cannot drift apart. */
    private static final String NEEDS_SERVER_CREDENTIALS = "credentials require --server: stored "
            + "credentials live in a running server; pass --server <url> (with RAVENROOT_TOKEN or "
            + "--token-file) to reach one";
}
