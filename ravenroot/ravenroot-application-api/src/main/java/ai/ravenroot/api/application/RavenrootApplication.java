package ai.ravenroot.api.application;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.catalog.NodeCatalogSource;
import ai.ravenroot.api.programming.ArtifactTestResult;
import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.programming.ProgramLanguageDescriptor;
import ai.ravenroot.api.programming.ProgramBuildResult;
import ai.ravenroot.api.security.SecurityContext;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Use cases shared by HTTP, CLI and future UI adapters. */
public interface RavenrootApplication extends AutoCloseable {
/**
 * Reports whether the application can accept work and its current lifecycle state.
 * @return an immutable application status snapshot
 */
    ApplicationStatus status();

/**
 * Captures runtime-owned state for operator inspection without changing execution.
 * @return the current runtime snapshot
 */
    RuntimeSnapshot runtimeSnapshot();

/**
 * Catalog used by CLI, HTTP and visual adapters to build consistent node editors.
 * @return immutable descriptors for node types available to graph authors
 */
    List<NodeTypeDescriptor> nodeTypes();

/**
 * Runtime-owned provenance keyed by behavior; absent entries are treated as installed bundles.
 * @return provenance by behavior for entries whose source is known to the runtime
 */
    default Map<String, NodeCatalogSource> nodeTypeSources() { return Map.of(); }

/**
 * Lists generated program artifacts visible to this application boundary.
 * @return immutable artifact metadata in application order
 */
    List<GeneratedArtifact> programArtifacts();

    /**
 * The program languages the composed {@code ProgramRuntime} declares support for, each with
 * an id an editor sends straight back to {@link #createProgramArtifact}. Default delegates to
 * nothing and answers empty, matching {@code ProgramRuntime#supportedLanguages()}'s own default --
 * an implementation that has not composed a runtime aware of this method must say so rather than
 * imply one language silently.
 * @return language descriptors accepted by the configured program runtime
 */
    default List<ProgramLanguageDescriptor> supportedProgramLanguages() {
        return List.of();
    }

/**
 * Creates a pending program artifact from source submitted by a trusted adapter.
 * @param language ID of a language advertised by {@link #supportedProgramLanguages()}
 * @param source program text to validate and store
 * @param metadata caller-provided non-secret metadata associated with the artifact
 * @return the newly created artifact in its pre-validation lifecycle state
 */
    GeneratedArtifact createProgramArtifact(String language, String source, Map<String, String> metadata);

/**
 * Validates one stored artifact asynchronously against its language runtime.
 * @param id identifier of the artifact to validate
 * @return a stage resolving to the artifact with its validation evidence
 */
    CompletionStage<GeneratedArtifact> validateProgramArtifact(String id);

/**
 * Runs the artifact in its test channel with a supplied payload.
 * @param id identifier of the artifact to execute
 * @param payload test input made available to the artifact
 * @return a stage resolving to the bounded test result
 */
    CompletionStage<ArtifactTestResult> testProgramArtifact(String id, Object payload);

/**
 * Marks a validated artifact approved with evidence supplied by a trusted policy boundary.
 * @param id identifier of the artifact to approve
 * @param trustedEvidence evidence used to support the approval decision
 * @return the artifact after the approval state transition
 */
    GeneratedArtifact approveProgramArtifact(String id, Map<String, String> trustedEvidence);

/**
 * Activates an approved artifact for execution.
 * @param id identifier of the artifact to activate
 * @param trustedEvidence authorization evidence for the activation decision
 * @return the artifact after activation
 */
    GeneratedArtifact activateProgramArtifact(String id, Map<String, String> trustedEvidence);

/**
 * Retires an artifact so it cannot be selected for new execution.
 * @param id identifier of the artifact to retire
 * @param trustedEvidence authorization evidence for the retirement decision
 * @return the artifact after retirement
 */
    GeneratedArtifact retireProgramArtifact(String id, Map<String, String> trustedEvidence);

    /**
 * Resolves or builds one tenant-scoped program content identity through the complete server-owned
 * qualification pipeline. Older embedders fail closed until they implement the operation.
* @param nodeId graph node identity associated with the operation or event
* @param tenantId authenticated tenant that owns the operation or event
* @param language runtime language identifier used to interpret the source
* @param source exact source text used to build the artifact
* @param testPayload value supplied to qualification smoke testing
* @param dualControl whether activation requires an independent approval
* @param trustedMetadata immutable server-established metadata retained with the build
* @return stage yielding the qualified program artifact
 */
    default CompletionStage<ProgramBuildResult> buildProgramArtifact(
            String nodeId, String tenantId, String language, String source, Object testPayload,
            boolean dualControl, Map<String, String> trustedMetadata) {
        return java.util.concurrent.CompletableFuture.failedFuture(
                new UnsupportedOperationException("content-addressed program builds are not supported"));
    }

    /**
 * Starts or rejoins one durable graph-level program build.
* @param tenantId authenticated tenant that owns the operation or event
* @param programs program descriptors that form the durable graph-level build
* @param dualControl whether activation requires an independent approval
* @param trustedMetadata immutable server-established metadata retained with the build
* @return stage yielding the new or rejoined durable build snapshot
 */
    default CompletionStage<ai.ravenroot.api.programming.ProgramBuildSnapshot> startProgramBuild(
            String tenantId, List<ai.ravenroot.api.programming.ProgramBuildRequest> programs,
            boolean dualControl, Map<String, String> trustedMetadata) {
        return java.util.concurrent.CompletableFuture.failedFuture(
                new UnsupportedOperationException("durable program builds are not supported"));
    }

    /**
 * Returns a tenant-scoped durable build snapshot and resumes it when incomplete.
* @param tenantId authenticated tenant that owns the operation or event
* @param buildId tenant-scoped durable build identifier
* @return tenant-owned build snapshot, or empty when absent
 */
    default java.util.Optional<ai.ravenroot.api.programming.ProgramBuildSnapshot> observeProgramBuild(
            String tenantId, String buildId) {
        return java.util.Optional.empty();
    }

    /**
 * Persists ACTIVATE before an authorized dual-control operation executes that phase.
* @param tenantId authenticated tenant that owns the operation or event
* @param artifactId stable identifier of the generated artifact, or an empty string before creation
 */
    default void beginProgramBuildActivation(String tenantId, String artifactId) { }

    /**
 * Persists terminal readiness after an authorized dual-control activation completes.
* @param tenantId authenticated tenant that owns the operation or event
* @param artifactId stable identifier of the generated artifact, or an empty string before creation
 */
    default void completeProgramBuildActivation(String tenantId, String artifactId) { }

/**
 * Parses GraphML into a summary without starting an execution.
 * @param graphMl readable GraphML input; ownership remains with the caller
 * @return the graph summary used by editors and validation adapters
 */
    GraphSummary inspectGraphMl(InputStream graphMl);

    /**
 * Trusted start contract for adapters that must establish security state before execution events
 * can be published. Implementations must use exactly {@code executionId} or fail before starting.
 *
 * <p>{@code security} is mandatory and there is deliberately <strong>no convenience overload that
 * omits it</strong>. The previous {@code startGraphMl(InputStream, Object)} default
 * minted an execution identifier out of thin air; an equivalent that minted an <em>identity</em>
 * would be a supported way to run work as nobody, and every caller reaching for the shorter
 * signature would silently opt out of tenant scoping. Callers that genuinely have no request
 * behind them — an embedded host, a test — must say whose authority they are asserting.</p>
 * @param security ingress-established tenant and principal for the execution
 * @param executionId caller-chosen id used by events and subsequent control operations
 * @param graphMl readable graph definition; ownership remains with the adapter
 * @param payload initial data passed to the graph entry node
 * @return submission handle that identifies the started execution and exposes its completion
 */
    ExecutionSubmission startGraphMl(SecurityContext security, UUID executionId, InputStream graphMl, Object payload);

    /**
 * Starts an inline traversal under an explicit server-selected policy.
 *
 * <p>The default preserves existing implementations for {@link ExecutionPolicy#STANDARD} and
 * fails closed for every stronger policy. An implementation that has not explicitly implemented
 * TEST_PASSTHROUGH must never silently execute the graph with production behavior semantics.</p>
 * @param security ingress-established tenant and principal for the execution
 * @param executionId caller-chosen id used by events and subsequent control operations
 * @param graphMl readable graph definition; ownership remains with the adapter
 * @param payload initial data passed to the graph entry node
 * @param policy explicit execution policy; unsupported stronger policies fail closed
 * @return submission handle that identifies the started execution and exposes its completion
 */
    default ExecutionSubmission startGraphMl(SecurityContext security, UUID executionId, InputStream graphMl,
                                             Object payload, ExecutionPolicy policy) {
        if (java.util.Objects.requireNonNull(policy, "policy") != ExecutionPolicy.STANDARD) {
            throw new UnsupportedOperationException("Execution policy is not implemented: " + policy);
        }
        return startGraphMl(security, executionId, graphMl, payload);
    }

    /**
 * Structured-payload form of the trusted start contract.
 *
 * <p>It is a {@code default} method that projects the envelope onto the {@code Object} contract
 * above, and that is the design rather than a shortcut: the payload type model is a
 * <em>boundary</em> contract, so the engine, the behaviours and the program runtime keep carrying
 * the same interior objects they always carried. Every existing implementation of this interface
 * therefore gains the structured surface without being recompiled, and none of them can drift from
 * the textual one, because there is only one implementation to drift from.</p>
 *
 * <p>{@code security} is mandatory here for exactly the reason it is mandatory above;
 * a structured payload is still a payload and confers no identity.</p>
 * @param security ingress-established tenant and principal for the execution
 * @param executionId caller-chosen id used by events and subsequent control operations
 * @param graphMl readable graph definition; ownership remains with the adapter
 * @param payload validated structured input projected onto the legacy object boundary
 * @return submission handle that identifies the started execution and exposes its completion
 */
    default ExecutionSubmission startGraphMl(SecurityContext security, UUID executionId, InputStream graphMl,
                                             ai.ravenroot.api.payload.PayloadEnvelope payload) {
        java.util.Objects.requireNonNull(payload, "payload");
        return startGraphMl(security, executionId, graphMl, payload.toJava());
    }

/**
 * Structured-payload form of the explicit-policy contract.
 * @param security ingress-established tenant and principal for the execution
 * @param executionId caller-chosen id used by events and subsequent control operations
 * @param graphMl readable graph definition; ownership remains with the adapter
 * @param payload validated structured input projected onto the legacy object boundary
 * @param policy explicit execution policy; unsupported stronger policies fail closed
 * @return submission handle that identifies the started execution and exposes its completion
 */
    default ExecutionSubmission startGraphMl(SecurityContext security, UUID executionId, InputStream graphMl,
                                             ai.ravenroot.api.payload.PayloadEnvelope payload,
                                             ExecutionPolicy policy) {
        java.util.Objects.requireNonNull(payload, "payload");
        return startGraphMl(security, executionId, graphMl, payload.toJava(), policy);
    }

    /**
 * Starts or idempotently rejoins one process-local inbound-source session.
 *
 * <p>The submitted graph must contain at least one effective SOURCE after validation against the
 * trusted catalog. Implementations must not start a traversal or fabricate an initial payload;
 * later events enter through the deployment's trusted ingress and each create their own
 * traversal. The default fails closed for older embedders.</p>
* @param security ingress-established tenant and principal
* @param sessionId caller-supplied idempotency identity within the authenticated tenant
* @param graphMl readable GraphML input whose ownership remains with the caller
* @return status of the started or rejoined source session
 */
    default SourceSessionStatus startSourceSession(SecurityContext security, String sessionId,
                                                   InputStream graphMl) {
        return unsupportedSourceSessions();
    }

    /**
 * Tenant-scoped local observation; empty is intentionally nondisclosing.
* @param tenantId authenticated tenant that owns the operation or event
* @param sessionId caller-supplied idempotency identity within the authenticated tenant
* @return tenant-owned source-session status, or empty when absent
 */
    default java.util.Optional<SourceSessionStatus> sourceSession(String tenantId, String sessionId) {
        return java.util.Optional.empty();
    }

    /**
 * Stops only the named tenant session; empty is intentionally nondisclosing.
* @param tenantId authenticated tenant that owns the operation or event
* @param sessionId caller-supplied idempotency identity within the authenticated tenant
* @return stage yielding the stopped session status when it existed
 */
    default CompletionStage<java.util.Optional<SourceSessionStatus>> stopSourceSession(
            String tenantId, String sessionId) {
        return java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.empty());
    }

    private static SourceSessionStatus unsupportedSourceSessions() {
        throw new UnsupportedOperationException("process-local source sessions are not supported");
    }

    /**
 * Registers an immutable graph version under a tenant-scoped deployment id.
 *
 * <h4>Register is not start</h4>
 * <p>This reserves the identity and validates the graph against the trusted catalog. It starts
 * nothing: the returned status is {@link LocalDeploymentState#REGISTERED} and no domain, node,
 * listener or traversal exists yet. {@link #startLocalDeployment} is what begins serving, so a
 * caller can register a version and choose when it runs, and so a failed start does not destroy
 * the registration it failed under.</p>
 *
 * <h4>Idempotency</h4>
 * <p>Re-registering an id this tenant already holds with the <em>same</em> graph is a no-op that
 * returns the deployment's current status — whatever state it has since reached. With a different
 * graph it is refused with {@link LocalDeploymentException.Reason#GRAPH_CONFLICT}: a registered
 * version is immutable, and silently replacing it would change what a running deployment is
 * without anyone asking for a restart.</p>
 *
 * <h4>A graph with no SOURCE is registrable, and that is the point</h4>
 * <p>Unlike {@link #startSourceSession}, this surface does not require an effective SOURCE. A
 * source-less graph deployed here is a long-lived, addressable, controllable unit — not a
 * transient traversal that cancellation would end — which is exactly the capability this surface
 * exposes. A graph that <em>does</em> name a SOURCE still has that source validated here, so an
 * unbindable one is refused at registration rather than discovered at start.</p>
 *
 * <h4>Scope</h4>
 * <p>Every status returned by this family carries {@link LocalDeploymentStatus#SCOPE}. This is a
 * single-process contract: no durability, no lease, no fencing, no failover, no cross-host claim.
 * A durable deployment registry is expected to replace this contract rather than
 * introduce a second, incompatible local lifecycle beside it.</p>
 *
 * <p>The scope is <em>reported</em> here and never <em>requested</em>: this method takes no scope
 * argument, because there is exactly one implemented scope and a parameter with one legal value
 * would be ceremony. An adapter that does let a caller name a scope — the built-in HTTP one
 * accepts a {@code scope} query parameter on {@code /v1/deployments} — refuses a mismatch at its
 * own boundary, which is why {@link LocalDeploymentException} has no scope reason to throw.</p>
 *
 * <p>The default fails closed for embedders that predate this method.</p>
 *
 * @param security ingress-established tenant and principal; the tenant owns the registration
 * @param deploymentId caller-supplied id, unique within the tenant
 * @param graphMl readable graph definition; ownership remains with the adapter
 * @return the registration's current process-local status
 * @throws LocalDeploymentException for an invalid id, a graph conflict or an unbindable source
 */
    default LocalDeploymentStatus registerLocalDeployment(SecurityContext security, String deploymentId,
                                                          InputStream graphMl) {
        throw new UnsupportedOperationException("process-local deployments are not supported");
    }

    /**
 * Every deployment registered by {@code tenantId} in this process, in id order.
 *
 * <p>Tenant-scoped by construction rather than by a filter the caller applies: another tenant's
 * deployment is never fetched and then removed, so no count, no timing and no error distinguishes
 * "none" from "none of yours". The default is empty for an implementation that registers none.</p>
 *
 * @param tenantId tenant whose registrations are listed
 * @return that tenant's process-local deployments
 */
    default List<LocalDeploymentStatus> localDeployments(String tenantId) {
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        return List.of();
    }

    /**
 * Observes one of {@code tenantId}'s deployments; empty is intentionally nondisclosing.
 *
 * <p>An id this tenant never registered and an id another tenant holds produce the identical
 * empty result, so an adapter mapping empty to a 404 discloses nothing about a sibling.</p>
 *
 * @param tenantId tenant whose registration is read
 * @param deploymentId the deployment id being observed
 * @return the deployment's current status, or empty when this tenant holds no such id
 */
    default java.util.Optional<LocalDeploymentStatus> localDeployment(String tenantId, String deploymentId) {
        return java.util.Optional.empty();
    }

    /**
 * Starts one of this tenant's registered deployments and completes at readiness.
 *
 * <p>Idempotent and single-flight, inheriting {@code GraphDeployment.start}'s own contract:
 * starting one that is already {@link LocalDeploymentState#READY} completes immediately with its
 * current status, and concurrent starts observe one attempt rather than racing to duplicate
 * listeners. A start that fails part-way rolls back atomically and reports
 * {@link LocalDeploymentState#FAILED} with a sanitized diagnostic; the registration survives.</p>
 *
 * <p>A {@link SecurityContext} is mandatory for the same reason it is on
 * {@code GraphDeployment.start}: a deployment that ran as nobody would give every event
 * it ingests an authority nobody granted.</p>
 *
 * @param security ingress-established identity under which the deployment serves
 * @param deploymentId the deployment to start, within {@code security}'s tenant
 * @return the status at readiness, or empty when this tenant holds no such id
 */
    default CompletionStage<java.util.Optional<LocalDeploymentStatus>> startLocalDeployment(
            SecurityContext security, String deploymentId) {
        return java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.empty());
    }

    /**
 * Stops one of this tenant's deployments and <b>leaves it registered</b>.
 *
 * <p>This is the operation {@link #undeployLocalDeployment} is deliberately distinct from: the id
 * remains reserved, the graph version remains registered, and {@link #startLocalDeployment} can
 * start it again. Ordering is the deployment's own: admission and inbound sources close first,
 * accepted work is then handled per the local deployment contract, and only that deployment's
 * domain is released — never a sibling's, and never the shared engine.</p>
 *
 * @param tenantId tenant whose deployment is stopped
 * @param deploymentId the deployment to stop
 * @return the stopped status, or empty when this tenant holds no such id
 */
    default CompletionStage<java.util.Optional<LocalDeploymentStatus>> stopLocalDeployment(
            String tenantId, String deploymentId) {
        return java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.empty());
    }

    /**
 * A completed stop followed by a start, never the two overlapping.
 *
 * <p>The no-duplicate-subscription guarantee is structural rather than a promise: the start is
 * composed onto the stop's completion, so every source this graph names has been retired before
 * any of them is bound again.</p>
 *
 * @param security ingress-established identity under which the replacement lifecycle serves
 * @param deploymentId the deployment to restart, within {@code security}'s tenant
 * @return the restarted status, or empty when this tenant holds no such id
 */
    default CompletionStage<java.util.Optional<LocalDeploymentStatus>> restartLocalDeployment(
            SecurityContext security, String deploymentId) {
        return java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.empty());
    }

    /**
 * Stops one of this tenant's deployments and then removes its local registration.
 *
 * <p>Strictly ordered: nothing is deregistered until the stop has completed, so an undeploy never
 * abandons a running domain. Afterwards the id is no longer registered — a subsequent lookup,
 * stop or undeploy is empty, indistinguishable from an id this tenant never held and from a
 * sibling tenant's id. Registering the id again is a fresh registration, not a resurrection.</p>
 *
 * @param tenantId tenant whose deployment is removed
 * @param deploymentId the deployment to stop and deregister
 * @return the final stopped status observed before deregistration, or empty when this tenant
 * holds no such id
 */
    default CompletionStage<java.util.Optional<LocalDeploymentStatus>> undeployLocalDeployment(
            String tenantId, String deploymentId) {
        return java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.empty());
    }

    /**
 * Cancels the traversal identified by {@code traversalId} if it is currently active (API-02;
 * ADR 0012 fixes the traversal as the control unit, since a node is shared by many traversals). Stops
 * future work on the traversal; effects it already issued before this call are not, and cannot be,
 * undone (ADR 0023's cooperative-cancellation concession) -- see
 * {@link AuthorizedRavenrootApplication#cancelExecution} for the outcome this feeds and for the
 * idempotency this method's own boolean does not need to carry.
 *
 * <p>Default {@code false} (no active traversal found) for implementations that do not track active
 * executions at all -- an honest "nothing was active under this id," never a false claim of effect.
 * The engine-backed production implementation overrides this with the real mechanism.</p>
 *
 * @return {@code true} if an active traversal was found under this id and asked to stop,
 * {@code false} if none was active (already completed, or never started in this process)
 * @param traversalId the stable traversal id used to identify the requested resource.
 */
    default boolean cancelTraversal(UUID traversalId) {
        return false;
    }

    /**
 * Asks the traversal identified by {@code traversalId} to hold before its next node (ADR 0023).
 * The node in flight finishes and publishes its completion; nothing after it is
 * dispatched until {@link #resumeTraversal} is called.
 *
 * <p>A pause is not a cancel and does not decay into one: a paused traversal keeps its state,
 * its parked join branches and its identity, which is the whole reason to prefer it — a run that
 * has gone wrong is more useful frozen than destroyed, because it can still be inspected. It
 * therefore holds resources for as long as it is paused, and {@link #cancelTraversal} remains
 * the operation that ends one.</p>
 *
 * <p>Default {@code false} (nothing was paused) for implementations that do not track active
 * executions, matching {@link #cancelTraversal}'s own default: an honest "nothing here is
 * holding", never a false claim of effect.</p>
 *
 * @return {@code true} if an active traversal was found under this id and asked to hold,
 * {@code false} if none was active or it was already paused
 * @param traversalId the stable traversal id used to identify the requested resource.
 */
    default boolean pauseTraversal(UUID traversalId) {
        return false;
    }

    /**
 * Releases a traversal paused by {@link #pauseTraversal}, which resumes dispatching from the hop
 * it was holding at.
 *
 * <p>Resume is an operation of its own rather than the withdrawal of a pause, and the difference
 * is observable: a caller that never paused this traversal gets {@code false} here rather than a
 * success that changed nothing.</p>
 *
 * @return {@code true} if this traversal was paused and is now running again, {@code false} if
 * it was not paused (never was, already resumed, cancelled, or never active here)
 * @param traversalId the stable traversal id used to identify the requested resource.
 */
    default boolean resumeTraversal(UUID traversalId) {
        return false;
    }

    /**
 * Whether a hold is currently in place on the traversal identified by {@code traversalId}.
 *
 * <p>The read counterpart of {@link #pauseTraversal}, and the authority behind the {@code paused}
 * qualifier on both read surfaces — {@link LiveExecution#paused()} and
 * {@link ExecutionOutcome#paused()}. It reads the same bookkeeping {@code pauseTraversal} mutates,
 * so a listing that says a traversal is holding and a pause command that answers
 * {@code ALREADY_PAUSED} are one fact rather than two projections that can disagree.</p>
 *
 * <h4>Why the outcome vocabulary needs this and could not be derived without it</h4>
 * <p>{@link #pauseTraversal} answers a boolean, so "already holding" and "nothing here to hold"
 * arrive identically. They used to be separated by asking whether the traversal was still listed
 * live, which is a sound inference only while {@code false} can mean nothing except "a hold is
 * already in place" — and it cannot. A traversal that has been asked to stop, and one that has
 * begun to end, both answer {@code false} while still being listed, and both were therefore
 * reported as already paused with nothing holding them. This method answers the question directly,
 * so each outcome is read from the state it names.</p>
 *
 * <p>Default {@code false} for implementations that track no active executions, matching
 * {@link #pauseTraversal}'s own default: never a claim that something is being held.</p>
 *
 * @param traversalId the stable traversal id used to identify the requested resource.
 * @return {@code true} while this implementation is holding that traversal, {@code false} when it
 * is running normally, has ended, or was never active here
 */
    default boolean executionPaused(UUID traversalId) {
        return false;
    }

    /**
 * ADR 0012's engine-wide drain, exposed as an operator command: refuses further
 * execution starts and stops every running node cooperatively, then waits up to {@code bound} for
 * every node to terminate. The underlying runtime is left up either way -- this is drain, not
 * shutdown; {@link #close()} remains the separate, explicit act that terminates it.
 *
 * <p>Default {@code false} (bound not confirmed met) for implementations with no engine-wide drain
 * of their own -- the conservative answer, never a false claim that draining completed. The
 * engine-backed production implementation overrides this with the real mechanism.</p>
 *
 * @return {@code true} if every node terminated within {@code bound}, {@code false} if the bound
 * elapsed with work still outstanding
 * @param bound maximum time to wait for the cooperative drain before reporting an incomplete result
 */
    default boolean drain(java.time.Duration bound) {
        return false;
    }

    /**
 * This tenant's live executions: traversals currently accepted and not yet terminal,
 * read directly from the runtime's own active-execution bookkeeping rather than derived from
 * the event stream. A stalled traversal — one whose behavior has deadlocked and has therefore
 * stopped publishing events entirely — is exactly the case an operator needs this for, and it
 * still appears here, because this reads the same bookkeeping {@link #cancelTraversal} mutates
 * rather than a projection of what was recently observed.
 *
 * <p><strong>{@code tenantId} is mechanism, not policy</strong>, exactly as it is on
 * {@link #executionResult} and {@link #durableEventsAfter}: this is the delegate layer,
 * tenant-oblivious by design, and the caller supplying the id is answerable for where it came
 * from. {@link AuthorizedRavenrootApplication#liveExecutions} is the only signature reachable
 * from an external adapter, and it resolves the tenant from an authenticated
 * {@code RequestContext} and from nothing else — so a caller can express only its own tenant's
 * read, never another's, even incorrectly.
 *
 * <p>The default returns an empty list, for implementations that track no active executions at
 * all — consistent with {@link #cancelTraversal}'s own default.
 *
 * <p><strong>Relationship to {@link #processInventory}:</strong> this method remains the
 * process-local live view and is not superseded by the durable inventory. See
 * {@link #processInventoryAvailable()} for the full distinction; in short, this answers "what is
 * this process running right now" and forgets everything on restart, while the durable inventory
 * answers "what does the durable record say exists" and survives one. Prefer the durable inventory
 * as the authoritative source for API, CLI, UI, audit and recovery callers.
 * @param tenantId tenant whose visible active executions are requested
 * @return current live executions visible to the supplied tenant scope
 */
    default List<LiveExecution> liveExecutions(String tenantId) {
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        return List.of();
    }

    /**
     * Whether the durable, tenant-scoped process and traversal inventory is available:
     * an {@code ExecutionStore} is composed and declares
     * {@code StoreCapability.PROCESS_INVENTORY}.
     *
     * <h4>How this relates to {@link #liveExecutions}</h4>
     * <p>{@link #liveExecutions} answers a different question than this inventory does, and neither
     * supersedes the other. {@link #liveExecutions} is the <strong>process-local live view</strong>:
     * traversals this one process currently has runners for, read from an in-memory map that forgets
     * everything on restart and knows nothing this process itself did not accept. This inventory is
     * the <strong>durable, authoritative view</strong>: what the store's own persisted record says
     * exists for a tenant, read fresh on every call rather than cached, and it survives a restart —
     * an instance this process never touched, recorded by another process before a crash, is visible
     * here and invisible to {@link #liveExecutions}. An operator chasing a traversal that looks
     * stuck <em>right now</em> wants {@link #liveExecutions}; an API, CLI, UI, audit or recovery
     * caller establishing what durably exists — including after a restart — wants this inventory.
     * {@link #liveExecutions} is not deleted, redefined, or demoted by this method's addition; the
     * two are complementary answers to different questions, not competing answers to the same one.</p>
     * @return whether {@link #processInventory}, {@link #processInstance} and
     * {@link #processInstanceTraversals} are backed by a real durable store rather than refusing outright
     */
    default boolean processInventoryAvailable() {
        return false;
    }

    /**
     * The largest page {@link #processInventory} will return in one call, delegating to
     * {@link ai.ravenroot.api.persistence.ExecutionStore#maxInventoryPageSize()} — published rather
     * than left for a caller to discover by bisection, which is exactly the failure
     * {@link ai.ravenroot.api.persistence.ExecutionStore#maxInventoryPageSize()}'s own Javadoc argues
     * against for the store itself. This bound is adapter- and deployment-configurable (both shipped
     * adapters default it to the same value, but an operator may change it), so it is stated here as
     * a fact this implementation reads back from its composed store rather than a literal anyone could
     * cite as universal.
     * @return the maximum page size the composed store accepts, or zero when
     * {@link #processInventoryAvailable()} is {@code false}
     */
    default int processInventoryMaxPageSize() {
        return 0;
    }

    /**
     * Lists one page of {@code tenantId}'s durable process instances, delegating
     * directly to {@link ai.ravenroot.api.persistence.ExecutionStore#listProcessInstances}.
     *
     * <p><strong>{@code tenantId} is mechanism, not policy</strong>, exactly as it is on
     * {@link #liveExecutions}, {@link #executionResult} and {@link #durableEventsAfter}: this is the
     * delegate layer, tenant-oblivious by design, and the caller supplying the id is answerable for
     * where it came from. {@link AuthorizedRavenrootApplication#processInventory} is the only
     * signature reachable from an external adapter, and it resolves the tenant from an authenticated
     * {@code RequestContext} and from nothing else.</p>
     * @throws IllegalStateException if {@link #processInventoryAvailable()} is {@code false}
     * @param tenantId tenant whose durable process inventory page is requested
     * @param query the page to return: filters, cursor and limit
     * @return one deterministic page of the tenant's durable process instances
     */
    default ai.ravenroot.api.persistence.ProcessInventoryPage processInventory(
            String tenantId, ai.ravenroot.api.persistence.ProcessInventoryQuery query) {
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(query, "query");
        throw new IllegalStateException("durable process inventory unavailable");
    }

    /**
     * Reads one instance's durable inventory row directly, delegating to
     * {@link ai.ravenroot.api.persistence.ExecutionStore#findProcessInstance}.
     *
     * <p>Empty for an instance that does not exist and for one belonging to another tenant alike —
     * indistinguishable by design, the same rule {@link #executionResult} follows for the identical
     * reason: a distinguishable denial would make this delegate a cross-tenant existence oracle.
     * {@code tenantId} is mechanism, not policy, exactly as on {@link #processInventory}.</p>
     * @throws IllegalStateException if {@link #processInventoryAvailable()} is {@code false}
     * @param tenantId tenant whose instance is requested
     * @param processInstanceId the durable process instance to read
     * @return the instance's inventory row, or empty when absent or not visible to this tenant
     */
    default java.util.Optional<ai.ravenroot.api.persistence.ProcessInventoryEntry> processInstance(
            String tenantId, UUID processInstanceId) {
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(processInstanceId, "processInstanceId");
        throw new IllegalStateException("durable process inventory unavailable");
    }

    /**
     * Lists one instance's traversals from the durable inventory, delegating to
     * {@link ai.ravenroot.api.persistence.ExecutionStore#listTraversals}.
     *
     * <p>Fails the way the store does when the instance is absent or belongs to another tenant —
     * indistinguishable, exactly as {@link #processInstance} is. {@code tenantId} is mechanism, not
     * policy, exactly as on {@link #processInventory}.</p>
     * @throws IllegalStateException if {@link #processInventoryAvailable()} is {@code false}
     * @param tenantId tenant whose instance's traversals are requested
     * @param processInstanceId the durable process instance whose traversals are listed
     * @return the instance's traversals, in insertion order
     */
    default List<ai.ravenroot.api.persistence.TraversalInventoryEntry> processInstanceTraversals(
            String tenantId, UUID processInstanceId) {
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(processInstanceId, "processInstanceId");
        throw new IllegalStateException("durable process inventory unavailable");
    }

    /**
     * The per-tenant inventory retention floor, delegating to
     * {@link ai.ravenroot.api.persistence.ExecutionStore#inventoryRetainedFrom}. {@link java.time.Instant#MIN}
     * for an implementation with no durable inventory at all, which is the honest answer: nothing has
     * ever been purged because nothing durable exists to purge.
     * @param tenantId tenant whose inventory retention floor is requested
     * @return the earliest instant from which this tenant's terminal inventory is complete
     */
    default java.time.Instant processInventoryRetainedFrom(String tenantId) {
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        return java.time.Instant.MIN;
    }

/**
 * Bounded replay used by reconnecting event-stream adapters.
 * @param sequence exclusive event sequence from which replay should resume
 * @return retained execution events strictly newer than that sequence
 */
    List<ExecutionEvent> executionEventsAfter(long sequence);

    /**
 * The lowest {@link ExecutionEvent#sequence()} {@link #executionEventsAfter} can still return, or
 * empty when this implementation retains no events or cannot say what its floor is.
 *
 * <p>Declared for the same reason {@link #durableEventJournalAvailable()} and
 * {@link #executionResultsRetained()} are: a bounded buffer that silently drops its oldest entries
 * turns "your cursor is older than anything I kept" into a short answer indistinguishable from "no
 * events occurred". A polling reader given only {@link #executionEventsAfter} cannot separate
 * absence-because-none from absence-despite-existence; given this floor it can.</p>
 *
 * <p>The durable side already refuses to answer that question dishonestly — see
 * {@link #durableEventsAfter}, which throws {@code JournalTruncated} rather than return a short,
 * gap-bearing list. This is the in-memory side's equivalent, expressed as a fact to compare against
 * rather than a failure, because the in-memory ring has no per-tenant floor to key a failure on.</p>
 *
 * <p><strong>The default is empty, and that is a refusal to guess.</strong> An implementation that
 * does not track a floor must not claim one; a caller reading empty must treat the retained window
 * as unknown and must not report continuity it cannot establish.</p>
 * @return the earliest retained sequence, or empty when replay state is unavailable
 */
    default java.util.OptionalLong oldestRetainedEventSequence() {
        return java.util.OptionalLong.empty();
    }

    /**
 * Registers a process-local, best-effort live listener. Closing the returned handle removes it.
 *
 * <p>Delivery is synchronous on the thread that publishes the event. A listener therefore observes
 * the event before the publishing call returns and must return promptly; this contract provides no
 * queue, retry, backpressure or durability. Implementations isolate a listener's
 * {@link RuntimeException} from graph execution and from the remaining listeners. Consumers that
 * require durable or retry-safe delivery must use the execution journal/outbox rather than treating
 * this observation surface as a message bus.</p>
 * @param listener observer notified in event order while the subscription remains open
 * @return closeable subscription that stops future notifications
 */
    AutoCloseable subscribeToExecutionEvents(Consumer<ExecutionEvent> listener);

    /**
 * Whether a durable, replayable event journal is available at all: an
 * {@code ExecutionStore} is composed and declares {@code StoreCapability.EVENT_JOURNAL}.
 *
 * <p>An embedder that runs with no store, or with a store that does not declare the
 * capability, keeps the pre-API-03 in-memory-only behaviour exactly as it was — that
 * degradation is declared through this method rather than left for
 * {@link #durableEventsAfter} to discover by throwing, so a caller can choose its own
 * fallback instead of using exceptions for control flow.</p>
 * @return whether this implementation can replay a durable execution-event journal
 */
    boolean durableEventJournalAvailable();

    /**
 * Bounded, durable replay of {@code tenantId}'s event journal strictly after
 * {@code afterOffset}, in {@code JournalRecord.journalOffset()} order — the durable
 * counterpart of {@link #executionEventsAfter}, and what a reconnecting SSE client resumes
 * from after a process restart, when {@link #executionEventsAfter}'s in-memory counter has
 * already reset to zero.
 *
 * <p><strong>{@code tenantId} is mechanism, not policy.</strong> This method performs no
 * authorization of its own — same division of labour as every other method on this
 * interface — and takes a bare tenant id for the same reason
 * {@code ExecutionStore.readJournal} does: this is the delegate layer, tenant-oblivious by
 * design, and the caller supplying that id is who is answerable for where it came from.
 * {@link ai.ravenroot.api.application.AuthorizedRavenrootApplication#durableEventsAfter} is
 * the only method reachable from an external adapter that resolves it, from an authenticated
 * {@code RequestContext} and nothing else — see that method's own Javadoc for why that
 * placement is what makes tenant scoping structural rather than a filter a caller could
 * omit.</p>
 *
 * @throws IllegalStateException if {@link #durableEventJournalAvailable()} is {@code false}
 * @throws ai.ravenroot.api.persistence.ExecutionStoreException wrapping
 * {@code ExecutionStoreFailure.JournalTruncated} when {@code afterOffset} is older
 * than this tenant's retained floor — a declared failure a caller must handle, never
 * a short, gap-bearing answer (the same principle {@code ExecutionStore.readJournal}
 * states for itself)
 * @param tenantId tenant whose durable events are being read
 * @param afterOffset exclusive durable offset from which the page begins
 * @param limit maximum number of events to return in the page
 * @return a page of retained tenant-scoped durable events after the requested offset
 */
    List<DurableExecutionEvent> durableEventsAfter(String tenantId, long afterOffset, int limit);

    /**
 * Whether this implementation retains execution results at all.
 *
 * <p>Declared for the same reason {@link #durableEventJournalAvailable()} is declared: an
 * embedder that retains nothing must be able to say so, rather than let a caller discover it by
 * receiving {@link ExecutionLookup.Unknown} for every id it ever submits and concluding the ids
 * are wrong. A caller that gets {@code false} here knows the read is unavailable; a caller that
 * gets {@code true} and then {@code Unknown} knows something about <em>that id</em>.</p>
 * @return whether completed execution outcomes remain queryable after completion
 */
    default boolean executionResultsRetained() {
        return false;
    }

    /**
 * Reads one execution's status and result, or says precisely why it cannot.
 *
 * <p>Never null and never an empty answer: the return type is sealed, and its three cases are
 * exhaustively documented on {@link ExecutionLookup}, including what a caller observes past the
 * retention horizon.</p>
 *
 * <p><strong>{@code tenantId} is mechanism, not policy</strong>, exactly as it is on
 * {@link #durableEventsAfter}: this is the delegate layer, tenant-oblivious by design, and the
 * caller supplying the id is answerable for where it came from.
 * {@link AuthorizedRavenrootApplication#executionResult} is the only signature reachable from an
 * external adapter, and it resolves the tenant from an authenticated {@code RequestContext} and
 * from nothing else.</p>
 *
 * <p>The default returns {@link ExecutionLookup.Unknown}, which is the correct answer for an
 * implementation that retains nothing and is why it is paired with
 * {@link #executionResultsRetained()} rather than left to stand alone.</p>
 * @param tenantId the stable tenant id used to identify the requested resource.
 * @param executionId execution whose retained outcome is requested
 * @return retained completion outcome, or empty when absent or no longer retained
 */
    default ExecutionLookup executionResult(String tenantId, UUID executionId) {
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        return new ExecutionLookup.Unknown(java.util.Objects.requireNonNull(executionId, "executionId"));
    }

    @Override
    void close();
}
