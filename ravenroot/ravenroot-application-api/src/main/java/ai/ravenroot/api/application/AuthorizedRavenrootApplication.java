package ai.ravenroot.api.application;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.payload.PayloadEnvelope;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.programming.ArtifactTestResult;
import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.programming.ProgramLanguageDescriptor;
import ai.ravenroot.api.programming.ArtifactEvidence;
import ai.ravenroot.api.programming.ArtifactLifecycleAuditEvent;
import ai.ravenroot.api.programming.ArtifactLifecycleAuditSink;
import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.AuthorizationService;
import ai.ravenroot.api.security.ProtectedResource;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.SecurityContext;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Mandatory reference-monitor facade for untrusted adapters.
 * Embedded hosts may deliberately use the raw {@link RavenrootApplication} as a trusted API.
 */
public final class AuthorizedRavenrootApplication {
/** Tenant-ownership key injected into artifact metadata by this reference monitor. */
    public static final String OWNER_TENANT_METADATA = "ravenroot.security.ownerTenant";
/** Creator-identity key injected into artifact metadata before it is audited. */
    public static final String CREATOR_METADATA = "ravenroot.security.creator";
/** Evidence key reserved for the identity of an artifact approver. */
    public static final String APPROVER_METADATA = "evidence.approved.approver";
/** Default maximum number of execution identifiers retained for ownership checks. */
    public static final int DEFAULT_EXECUTION_OWNERSHIP_LIMIT = 4_096;
    /** The tenant a drain resource is checked against -- only a "platform" tenant or an explicit
 * {@code PLATFORM_ADMIN} role passes {@code DefaultAuthorizationService}'s cross-tenant check, which
 * is what keeps a single tenant's {@code OPERATOR} from draining the whole server even though
 * {@code EXECUTION_CONTROL} is the same action cancel uses. */
    public static final String DRAIN_RESOURCE_TENANT = "platform";
    private final RavenrootApplication delegate;
    private final AuthorizationService authorization;
    private final ExecutionOwnershipRegistry executionOwners;
    private final ArtifactLifecycleAuditSink artifactAudit;
    private final ExecutionControlAuditSink controlAudit;
    private final boolean dualControl;
    private final Supplier<UUID> executionIds;

/**
 * Wraps the delegate with authorization and audit dependencies for public use cases.
 * @param delegate underlying application implementation whose operations this facade protects.
 * @param authorization policy service used to decide each requested action.
 * @param artifactAudit sink that records artifact lifecycle attempts and failures.
 * @param dualControl whether creators must be distinct from approvers and activators.
 */
    public AuthorizedRavenrootApplication(RavenrootApplication delegate, AuthorizationService authorization,
                                          ArtifactLifecycleAuditSink artifactAudit, boolean dualControl) {
        this(delegate, authorization, artifactAudit, dualControl, DEFAULT_EXECUTION_OWNERSHIP_LIMIT);
    }

/**
 * Wraps the delegate with authorization and audit dependencies for public use cases.
 * @param delegate underlying application implementation whose operations this facade protects.
 * @param authorization policy service used to decide each requested action.
 * @param artifactAudit sink that records artifact lifecycle attempts and failures.
 * @param dualControl whether creators must be distinct from approvers and activators.
 * @param executionOwnershipLimit the execution ownership limit constraint applied while processing the request.
 */
    public AuthorizedRavenrootApplication(RavenrootApplication delegate, AuthorizationService authorization,
                                          ArtifactLifecycleAuditSink artifactAudit, boolean dualControl,
                                          int executionOwnershipLimit) {
        this(delegate, authorization, artifactAudit, dualControl, executionOwnershipLimit, UUID::randomUUID);
    }

    AuthorizedRavenrootApplication(RavenrootApplication delegate, AuthorizationService authorization,
                                   ArtifactLifecycleAuditSink artifactAudit, boolean dualControl,
                                   int executionOwnershipLimit, Supplier<UUID> executionIds) {
        // Every constructor without an execution-control audit sink defaults it to a no-op, exactly
        // as RavenrootServer's own layered constructors default each newly added sink -- none of these
        // narrower constructors' existing callers call cancelExecution/drain, so none of them need to
        // supply a real one. RavenrootServer and RavenrootCliMain use the widest constructor below.
        this(delegate, authorization, artifactAudit, dualControl, executionOwnershipLimit, executionIds,
                event -> { });
    }

    /**
 * Widest public constructor: additionally accepts the execution-control audit sink, so SEC-13
 * can route CONTROL-category cancel/drain records into the durable audit trail instead of the no-op
 * every narrower constructor above defaults to. Mirrors {@code artifactAudit}'s own threading.
 * @param delegate underlying application implementation whose operations this facade protects.
 * @param authorization policy service used to decide each requested action.
 * @param artifactAudit sink that records artifact lifecycle attempts and failures.
 * @param dualControl whether creators must be distinct from approvers and activators.
 * @param executionOwnershipLimit the execution ownership limit constraint applied while processing the request.
 * @param controlAudit sink that records cancel and drain control events.
 */
    public AuthorizedRavenrootApplication(RavenrootApplication delegate, AuthorizationService authorization,
                                          ArtifactLifecycleAuditSink artifactAudit, boolean dualControl,
                                          int executionOwnershipLimit, ExecutionControlAuditSink controlAudit) {
        this(delegate, authorization, artifactAudit, dualControl, executionOwnershipLimit, UUID::randomUUID,
                controlAudit);
    }

    AuthorizedRavenrootApplication(RavenrootApplication delegate, AuthorizationService authorization,
                                   ArtifactLifecycleAuditSink artifactAudit, boolean dualControl,
                                   int executionOwnershipLimit, Supplier<UUID> executionIds,
                                   ExecutionControlAuditSink controlAudit) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.artifactAudit = Objects.requireNonNull(artifactAudit, "artifactAudit");
        this.controlAudit = Objects.requireNonNull(controlAudit, "controlAudit");
        this.dualControl = dualControl;
        this.executionOwners = new ExecutionOwnershipRegistry(executionOwnershipLimit);
        this.executionIds = Objects.requireNonNull(executionIds, "executionIds");
    }

/**
 * Returns application status only after read authorization succeeds.
 * @param context authenticated request context used for authorization and audit attribution.
 * @return the delegate's current service status after the caller has passed {@code STATUS_READ}.
 */
    public ApplicationStatus status(RequestContext context) {
        require(context, AuthorizationAction.STATUS_READ, collection("service", context));
        return delegate.status();
    }

/**
 * Returns a runtime snapshot after read authorization succeeds.
 * @param context authenticated request context used for authorization and audit attribution.
 * @return the runtime snapshot visible to a caller authorised for {@code RUNTIME_OBSERVE}.
 */
    public RuntimeSnapshot runtimeSnapshot(RequestContext context) {
        require(context, AuthorizationAction.RUNTIME_OBSERVE, collection("runtime", context));
        return delegate.runtimeSnapshot();
    }

/**
 * Lists trusted node type descriptors after catalog-read authorization.
 * @param context authenticated request context used for authorization and audit attribution.
 * @return trusted node descriptors from the catalog; callers do not receive untrusted adapter types.
 */
    public List<NodeTypeDescriptor> nodeTypes(RequestContext context) {
        require(context, AuthorizationAction.CATALOG_READ, collection("node-catalog", context));
        return delegate.nodeTypes();
    }

/**
 * Lists trusted node catalog provenance after catalog-read authorization.
 * @param context authenticated request context used for authorization and audit attribution.
 * @return the catalog source recorded for each visible node-type key.
 */
    public java.util.Map<String, ai.ravenroot.api.catalog.NodeCatalogSource> nodeTypeSources(RequestContext context) {
        require(context, AuthorizationAction.CATALOG_READ, collection("node-catalog", context));
        return delegate.nodeTypeSources();
    }

/**
 * Lists visible programmable artifacts after artifact-read authorization.
 * @param context authenticated request context used for authorization and audit attribution.
 * @return artifacts owned by the caller's tenant, unless the caller is a platform administrator.
 */
    public List<GeneratedArtifact> programArtifacts(RequestContext context) {
        require(context, AuthorizationAction.ARTIFACT_LIST, collection("program-artifacts", context));
        boolean platform = context.roles().contains(ai.ravenroot.api.security.Role.PLATFORM_ADMIN);
        return delegate.programArtifacts().stream()
                .filter(artifact -> ownerTenant(artifact) != null)
                .filter(artifact -> platform || context.tenantId().equals(ownerTenant(artifact)))
                .toList();
    }

    /**
 * The languages the composed runtime accepts, so the editor's workbench can populate a
 * selector instead of hard-coding one. Gated the same way {@link #nodeTypes} is -- both are a
 * read of a trusted, tenant-independent capability catalog, not tenant-scoped data -- rather than
 * introducing a new {@link AuthorizationAction} for a single additional read.
 * @param context authenticated request context used for authorization and audit attribution.
 * @return the runtime's tenant-independent language catalog after catalog-read authorisation.
 */
    public List<ProgramLanguageDescriptor> supportedProgramLanguages(RequestContext context) {
        require(context, AuthorizationAction.CATALOG_READ, collection("program-languages", context));
        return delegate.supportedProgramLanguages();
    }

/**
 * Creates a programmable artifact after management authorization and audit.
 * @param context authenticated request context used for authorization and audit attribution.
 * @param language declared language of the program artifact source.
 * @param source program source submitted for artifact creation.
 * @param metadata artifact metadata supplied by the caller; reserved security and evidence keys are rejected.
 * @return the created artifact with ownership and creator evidence added by this facade.
 */
    public GeneratedArtifact createProgramArtifact(RequestContext context, String language, String source,
                                                   Map<String, String> metadata) {
        require(context, AuthorizationAction.ARTIFACT_CREATE, collection("program-artifacts", context));
        var ownedMetadata = new LinkedHashMap<>(Objects.requireNonNull(metadata, "metadata"));
        if (ownedMetadata.keySet().stream().anyMatch(key -> key.startsWith("ravenroot.security.")
                || key.startsWith("evidence."))) {
            throw new IllegalArgumentException("reserved security or evidence metadata must not be supplied");
        }
        ownedMetadata.put(OWNER_TENANT_METADATA, context.tenantId());
        ownedMetadata.put(CREATOR_METADATA, qualifiedIdentity(context));
        audit(context, AuthorizationAction.ARTIFACT_CREATE, null);
        return recordingFailure(context, AuthorizationAction.ARTIFACT_CREATE, null,
                () -> delegate.createProgramArtifact(language, source, ownedMetadata));
    }

    /**
 * One authorized, server-orchestrated content build used by graph readiness and explicit rebuild.
* @param context authenticated request context authorizing the operation
* @param nodeId graph node identity associated with the operation or event
* @param language runtime language identifier used to interpret the source
* @param source exact source text used to build the artifact
* @param testPayload value supplied to qualification smoke testing
* @return stage yielding the qualified program artifact
 */
    public CompletionStage<ai.ravenroot.api.programming.ProgramBuildResult> buildProgramArtifact(
            RequestContext context, String nodeId, String language, String source, Object testPayload) {
        require(context, AuthorizationAction.ARTIFACT_CREATE, collection("program-artifacts", context));
        var trusted = Map.of(
                OWNER_TENANT_METADATA, context.tenantId(),
                CREATOR_METADATA, qualifiedIdentity(context),
                "ravenroot.program.requestId", context.requestId());
        audit(context, AuthorizationAction.ARTIFACT_CREATE, null);
        return recordingAsyncFailure(context, AuthorizationAction.ARTIFACT_CREATE, null,
                () -> delegate.buildProgramArtifact(nodeId, context.tenantId(), language, source,
                        testPayload, dualControl, trusted));
    }

    /**
 * Starts or rejoins an authorized durable graph-level program build.
* @param context authenticated request context authorizing the operation
* @param programs program descriptors that form the durable graph-level build
* @return stage yielding the durable graph-build snapshot
 */
    public CompletionStage<ai.ravenroot.api.programming.ProgramBuildSnapshot> startProgramBuild(
            RequestContext context, List<ai.ravenroot.api.programming.ProgramBuildRequest> programs) {
        require(context, AuthorizationAction.ARTIFACT_CREATE, collection("program-artifacts", context));
        var trusted = Map.of(
                OWNER_TENANT_METADATA, context.tenantId(),
                CREATOR_METADATA, qualifiedIdentity(context),
                "ravenroot.program.requestId", context.requestId());
        audit(context, AuthorizationAction.ARTIFACT_CREATE, null);
        return recordingAsyncFailure(context, AuthorizationAction.ARTIFACT_CREATE, null,
                () -> delegate.startProgramBuild(context.tenantId(), programs, dualControl, trusted));
    }

    /**
 * Reads only the caller tenant's durable build status; absence is intentionally nondisclosing.
* @param context authenticated request context authorizing the operation
* @param buildId tenant-scoped durable build identifier
* @return owned build snapshot, or empty when it is not observable
 */
    public java.util.Optional<ai.ravenroot.api.programming.ProgramBuildSnapshot> observeProgramBuild(
            RequestContext context, String buildId) {
        require(context, AuthorizationAction.ARTIFACT_LIST, collection("program-artifacts", context));
        audit(context, AuthorizationAction.ARTIFACT_LIST, null);
        return delegate.observeProgramBuild(context.tenantId(), buildId);
    }

    /**
 * One graph-level approval operation; authorization and maker-checker checks remain per artifact.
* @param context authenticated request context authorizing the operation
* @param artifactIds artifact identifiers to approve as one authorized operation
* @param reason operator-safe reason recorded with the authorized operation
* @return approved artifact snapshots in request order
 */
    public List<GeneratedArtifact> approveProgramArtifacts(
            RequestContext context, List<String> artifactIds, String reason) {
        if (artifactIds == null || artifactIds.isEmpty() || artifactIds.size() > 256) {
            throw new IllegalArgumentException("one to 256 artifact ids are required");
        }
        var approved = new java.util.ArrayList<GeneratedArtifact>(artifactIds.size());
        for (String id : artifactIds.stream().distinct().toList()) {
            GeneratedArtifact artifact = approveProgramArtifact(context, id, reason);
            delegate.beginProgramBuildActivation(context.tenantId(), artifact.id());
            GeneratedArtifact active = activateProgramArtifact(context, artifact.id());
            delegate.completeProgramBuildActivation(context.tenantId(), active.id());
            approved.add(active);
        }
        return List.copyOf(approved);
    }

/**
 * Validates a programmable artifact after management authorization and audit.
 * @param context authenticated request context used for authorization and audit attribution.
 * @param id the stable id used to identify the requested resource.
 * @return a stage that completes with the artifact after validation records its lifecycle outcome.
 */
    public CompletionStage<GeneratedArtifact> validateProgramArtifact(RequestContext context, String id) {
        require(context, AuthorizationAction.ARTIFACT_VALIDATE, artifact(id));
        GeneratedArtifact validating = findArtifact(id);
        audit(context, AuthorizationAction.ARTIFACT_VALIDATE, validating);
        return recordingAsyncFailure(context, AuthorizationAction.ARTIFACT_VALIDATE, validating,
                () -> delegate.validateProgramArtifact(id));
    }

    /**
 * Known, untreated gap: unlike every {@code startGraphMl} overload, this method
 * applies neither {@link PayloadLimits} nor any reserved-key check to {@code payload} -- not even
 * a top-level scan. It violates the same published contract {@code startGraphMl(RequestContext,
 * InputStream, Object)}'s own Javadoc cites (payload-and-error-contract.md §4). This gap is distinct
 * from the {@code run}/{@code startGraphMl} divergence measured below. See the documented contract for the full account and the
 * exposure this leaves (bounded by a 4 KiB request-shape guard from {@code RavenrootServer}'s
 * {@code test} artifact operation; unbounded from an embedded host).
 * @param context authenticated request context used for authorization and audit attribution.
 * @param id the stable id used to identify the requested resource.
 * @param payload input passed to the program or graph traversal.
 * @return a stage for the test result, including compilation or runtime evidence from the artifact.
 */
    public CompletionStage<ArtifactTestResult> testProgramArtifact(RequestContext context, String id, Object payload) {
        require(context, AuthorizationAction.ARTIFACT_TEST, artifact(id));
        GeneratedArtifact testing = findArtifact(id);
        audit(context, AuthorizationAction.ARTIFACT_TEST, testing);
        return recordingAsyncFailure(context, AuthorizationAction.ARTIFACT_TEST, testing,
                () -> delegate.testProgramArtifact(id, payload));
    }

/**
 * Approves an artifact after approval authorization and audit.
 * @param context authenticated request context used for authorization and audit attribution.
 * @param id the stable id used to identify the requested resource.
 * @param reason nonblank explanation recorded with the approval or retirement.
 * @return the approved artifact carrying the supplied nonblank approval reason.
 */
    public GeneratedArtifact approveProgramArtifact(RequestContext context, String id, String reason) {
        require(context, AuthorizationAction.ARTIFACT_APPROVE, artifact(id));
        GeneratedArtifact artifact = findArtifact(id);
        audit(context, AuthorizationAction.ARTIFACT_APPROVE, artifact);
        try {
            enforceIndependentActor(context, artifact);
        } catch (ai.ravenroot.api.security.AuthorizationDeniedException denied) {
            auditDenied(context, AuthorizationAction.ARTIFACT_APPROVE, artifact);
            throw denied;
        }
        return recordingFailure(context, AuthorizationAction.ARTIFACT_APPROVE, artifact,
                () -> delegate.approveProgramArtifact(id, Map.of(
                        "reason", requireText(reason, "approval reason"),
                        "approver", qualifiedIdentity(context),
                        "requestId", context.requestId())));
    }

/**
 * Activates an approved artifact after activation authorization and audit.
 * @param context authenticated request context used for authorization and audit attribution.
 * @param id the stable id used to identify the requested resource.
 * @return the artifact after the delegate has accepted its activation transition.
 */
    public GeneratedArtifact activateProgramArtifact(RequestContext context, String id) {
        require(context, AuthorizationAction.ARTIFACT_ACTIVATE, artifact(id));
        GeneratedArtifact artifact = findArtifact(id);
        audit(context, AuthorizationAction.ARTIFACT_ACTIVATE, artifact);
        try {
            enforceIndependentActor(context, artifact);
            if (dualControl && (artifact.metadata().get(APPROVER_METADATA) == null
                    || artifact.metadata().get(APPROVER_METADATA).isBlank())) {
                throw new ai.ravenroot.api.security.AuthorizationDeniedException(
                        "trusted approval evidence is absent");
            }
        } catch (ai.ravenroot.api.security.AuthorizationDeniedException denied) {
            auditDenied(context, AuthorizationAction.ARTIFACT_ACTIVATE, artifact);
            throw denied;
        }
        return recordingFailure(context, AuthorizationAction.ARTIFACT_ACTIVATE, artifact,
                () -> delegate.activateProgramArtifact(id, Map.of(
                        "activatedBy", qualifiedIdentity(context), "requestId", context.requestId())));
    }

/**
 * Retires an artifact after retirement authorization and audit.
 * @param context authenticated request context used for authorization and audit attribution.
 * @param id the stable id used to identify the requested resource.
 * @param reason nonblank explanation recorded with the approval or retirement.
 * @return the retired artifact carrying the supplied nonblank retirement reason.
 */
    public GeneratedArtifact retireProgramArtifact(RequestContext context, String id, String reason) {
        require(context, AuthorizationAction.ARTIFACT_RETIRE, artifact(id));
        GeneratedArtifact artifact = findArtifact(id);
        audit(context, AuthorizationAction.ARTIFACT_RETIRE, artifact);
        return recordingFailure(context, AuthorizationAction.ARTIFACT_RETIRE, artifact,
                () -> delegate.retireProgramArtifact(id, Map.of(
                        "reason", requireText(reason, "retirement reason"),
                        "retiredBy", qualifiedIdentity(context), "requestId", context.requestId())));
    }

/**
 * Inspects GraphML after graph-read authorization.
 * @param context authenticated request context used for authorization and audit attribution.
 * @param graphMl GraphML document to inspect or execute.
 * @return the GraphML summary, including semantic violations that prevent execution.
 */
    public GraphSummary inspectGraphMl(RequestContext context, InputStream graphMl) {
        require(context, AuthorizationAction.GRAPH_READ, collection("graphs", context));
        return delegate.inspectGraphMl(graphMl);
    }

    /**
 * Starts a traversal with a structured payload.
 *
 * <p>This is the surface an adapter should prefer. The {@code Object} overload below remains for
 * the pre-API-01 textual payload and is not deprecated: it is the contract the CLI, the UI and
 * every embedded host already call, and {@link PayloadEnvelope#legacyText(String)} states exactly
 * what that call is equivalent to.</p>
 *
 * <p>Two checks happen here rather than in the delegate, because this class is the reference
 * monitor and the delegate is not. The limits are enforced again even though a transport that
 * decoded the payload has already enforced them, since an embedded caller that built the envelope
 * in memory never passed a decoder. The reserved-key check is a tree walk, matching the
 * {@code Object} overload below. That overload's own Javadoc explains why the two used to disagree
 * on this point and no longer do.</p>
 * @param context authenticated request context used for authorization and audit attribution.
 * @param graphMl GraphML document to inspect or execute.
 * @param payload input passed to the program or graph traversal.
 * @return the submission handle for the authorised traversal using default payload limits.
 */
    public ExecutionSubmission startGraphMl(RequestContext context, InputStream graphMl,
                                            PayloadEnvelope payload) {
        return startGraphMl(context, graphMl, payload, PayloadLimits.DEFAULTS);
    }

/**
 * Starts a GraphML traversal after execution authorization and audit.
 * @param context authenticated request context used for authorization and audit attribution.
 * @param graphMl GraphML document to inspect or execute.
 * @param payload input passed to the program or graph traversal.
 * @param limits payload limits enforced before submitting the traversal.
 * @return the submission handle after the supplied payload has passed the requested limits.
 */
    public ExecutionSubmission startGraphMl(RequestContext context, InputStream graphMl,
                                            PayloadEnvelope payload, PayloadLimits limits) {
        return startGraphMl(context, graphMl, payload, limits, ExecutionPolicy.STANDARD);
    }

/**
 * Starts a GraphML traversal after execution authorization and audit.
 * @param context authenticated request context used for authorization and audit attribution.
 * @param graphMl GraphML document to inspect or execute.
 * @param payload input passed to the program or graph traversal.
 * @param limits payload limits enforced before submitting the traversal.
 * @param policy execution policy applied to the submitted traversal.
 * @return the submission handle whose scheduling follows {@code policy}.
 */
    public ExecutionSubmission startGraphMl(RequestContext context, InputStream graphMl,
                                            PayloadEnvelope payload, PayloadLimits limits,
                                            ExecutionPolicy policy) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(policy, "policy");
        // Authorization first, and it must stay first. Validating the payload before deciding whether
        // this principal may start anything would answer a limits question for a caller that is not
        // allowed to ask it, and would do the validating work on its behalf.
        require(context, AuthorizationAction.EXECUTION_START, collection("executions", context));
        limits.enforce(payload.value());
        PayloadValue.requireNoReservedKeys(payload.value());
        return startAuthorized(context, graphMl, payload.toJava(), policy);
    }

    /**
     * This {@code startGraphMl} overload follows the contract published by
     * {@code docs/architecture/payload-and-error-contract.md} §4, which states that
     * {@code AuthorizedRavenrootApplication} "re-enforces the budgets after authorization even when
     * a transport already did, because the embedded surface has no transport". The
     * {@link PayloadEnvelope} overload has applied that rule since API-01; this overload applies it
     * too.
     *
     * <p>The boundary matters here because {@code ai.ravenroot.cli.EmbeddedBackend} passes a bare CLI
     * argument through this overload. An operator's {@code payload} argument is bounded only by the
     * OS's {@code ARG_MAX}, which can be on the order of a megabyte, while
     * {@link PayloadLimits#DEFAULTS} bounds a single text value to 32 KiB. Without this check, a local
     * run could submit a payload that the equivalent remote command would refuse.</p>
     *
     * <p>The payload is routed through the same conversion and checks as the
     * {@link PayloadEnvelope} overload above. {@link PayloadValue#fromJava} builds the tree while
     * counting depth, collection size and total value count, and rejects a text value over
     * {@link PayloadLimits#maxTextLength()} as it goes. It also serialises the converted tree once to
     * enforce the encoded-byte budget. {@link PayloadValue#requireNoReservedKeys} then walks the whole
     * tree instead of applying the former top-level-only check. Ordinary CLI strings remain
     * unaffected, while text beyond 32 KiB and reserved keys nested in map payloads are refused before
     * traversal starts. The remote transport's tighter wire-level ceiling is documented by
     * {@code ai.ravenroot.cli.remote.RemoteBackend#run}.</p>
     *
     * <p><b>Related boundary not covered here.</b> {@link #testProgramArtifact} accepts an
     * {@code Object} payload through the same reference monitor but applies neither
     * {@link PayloadLimits} nor a reserved-key check. It is reachable from {@code RavenrootServer}'s
     * {@code test} artifact operation, where a 4 KiB request-shape guard bounds the exposure, and from
     * an embedded host with no equivalent bound. The {@code run}/{@code startGraphMl} path enforces
     * matching limits here; {@code testProgramArtifact} remains a separate instance of the same defect
     * class, documented in the payload and error contract.</p>
     *
     * @param context authenticated request context used for authorization and audit attribution.
     * @param graphMl GraphML document to inspect or execute.
     * @param payload input passed to the program or graph traversal.
     * @return the submission handle after converting the Java payload with default limits.
     */
    public ExecutionSubmission startGraphMl(RequestContext context, InputStream graphMl, Object payload) {
        require(context, AuthorizationAction.EXECUTION_START, collection("executions", context));
        PayloadValue value = PayloadValue.fromJava(payload, PayloadLimits.DEFAULTS);
        PayloadValue.requireNoReservedKeys(value);
        return startAuthorized(context, graphMl, payload, ExecutionPolicy.STANDARD);
    }

    /** Everything after the reference-monitor decision, shared by both payload representations. */
    private ExecutionSubmission startAuthorized(RequestContext context, InputStream graphMl, Object payload,
                                                ExecutionPolicy policy) {
        UUID executionId = Objects.requireNonNull(executionIds.get(), "generated execution identifier");
        var ownership = executionOwners.reserve(executionId, context.tenantId());
        try {
            // The identity handed to the delegate is derived from the authenticated RequestContext and
            // from nothing else. It is not read from the payload, and the GraphML has not been parsed
            // yet, so no graph content can have participated in producing it.
            ExecutionSubmission submission = delegate.startGraphMl(SecurityContext.of(context), executionId,
                    graphMl, payload, policy);
            if (submission == null || !executionId.equals(submission.executionId())) {
                throw new IllegalStateException("execution submission identifier does not match reservation");
            }
            executionOwners.commit(ownership);
            return submission;
        } catch (RuntimeException | Error startupFailure) {
            executionOwners.rollback(ownership);
            throw startupFailure;
        }
    }

    /**
 * Starts or rejoins exactly one source session inside the authenticated tenant boundary.
* @param context authenticated request context authorizing the operation
* @param sessionId caller-supplied idempotency identity within the authenticated tenant
* @param graphMl readable GraphML input whose ownership remains with the caller
* @return status of the started or rejoined source session
 */
    public SourceSessionStatus startSourceSession(RequestContext context, String sessionId, InputStream graphMl) {
        require(context, AuthorizationAction.EXECUTION_START, collection("source-sessions", context));
        return delegate.startSourceSession(SecurityContext.of(context), sessionId, graphMl);
    }

    /**
 * Observes only the caller tenant's process-local session; absence discloses no sibling.
* @param context authenticated request context authorizing the operation
* @param sessionId caller-supplied idempotency identity within the authenticated tenant
* @return owned source-session status, or empty when undisclosed
 */
    public java.util.Optional<SourceSessionStatus> sourceSession(RequestContext context, String sessionId) {
        require(context, AuthorizationAction.EXECUTION_READ,
                ProtectedResource.owned("source-session", requireText(sessionId, "session id"), context.tenantId()));
        return delegate.sourceSession(context.tenantId(), sessionId);
    }

    /**
 * Stops only the caller tenant's named session and never the shared execution engine.
* @param context authenticated request context authorizing the operation
* @param sessionId caller-supplied idempotency identity within the authenticated tenant
* @return stage yielding the stopped session status when it existed
 */
    public CompletionStage<java.util.Optional<SourceSessionStatus>> stopSourceSession(
            RequestContext context, String sessionId) {
        require(context, AuthorizationAction.EXECUTION_CONTROL,
                ProtectedResource.owned("source-session", requireText(sessionId, "session id"), context.tenantId()));
        return delegate.stopSourceSession(context.tenantId(), sessionId);
    }

    /**
 * The tenant boundary for the process-local deployment lifecycle.
 *
 * <h4>Why the isolation is structural rather than a check</h4>
 * <p>Same shape the source-session wrappers above established, and the same shape
 * {@link #liveExecutions} and {@link #executionResult} use: the tenant is read from the
 * authenticated {@code RequestContext} and from nothing else. <b>None of these seven signatures
 * accepts a tenant as a parameter</b>, so there is no argument an adapter could get wrong and no
 * filter it could omit — a caller cannot name a tenant, therefore a caller cannot name another
 * tenant. The delegate keys its registry by (tenant, id), so a sibling's deployment is never
 * fetched and then discarded; it is never reachable.</p>
 *
 * <h4>Non-disclosure</h4>
 * <p>The lookup and control methods return empty for an id this tenant never registered and for
 * an id a sibling tenant holds. The two are the same value, produced by the same path, so nothing
 * an adapter can observe — status, body, or timing beyond a map lookup — separates them.</p>
 *
 * <p>Registration takes {@link AuthorizationAction#EXECUTION_START} against the collection, as
 * starting a source session does; reads take {@link AuthorizationAction#EXECUTION_READ} and
 * lifecycle commands {@link AuthorizationAction#EXECUTION_CONTROL}, each against the deployment's
 * own tenant-owned resource.</p>
 *
 * @param context authenticated request context; supplies the owning tenant and nothing else does
 * @param deploymentId caller-supplied id, unique within the caller's tenant
 * @param graphMl readable graph definition; ownership remains with the adapter
 * @return the registration's current process-local status
 */
    public LocalDeploymentStatus registerLocalDeployment(RequestContext context, String deploymentId,
                                                         InputStream graphMl) {
        require(context, AuthorizationAction.EXECUTION_START, collection("deployments", context));
        return delegate.registerLocalDeployment(SecurityContext.of(context), deploymentId, graphMl);
    }

    /**
 * Lists only the caller tenant's process-local deployments.
 * @param context authenticated request context supplying the owning tenant
 * @return that tenant's deployments; never another tenant's, by construction
 */
    public List<LocalDeploymentStatus> localDeployments(RequestContext context) {
        require(context, AuthorizationAction.EXECUTION_READ, collection("deployments", context));
        return delegate.localDeployments(context.tenantId());
    }

    /**
 * Observes one of the caller tenant's deployments; absence discloses no sibling.
 * @param context authenticated request context supplying the owning tenant
 * @param deploymentId the deployment being observed
 * @return its current status, or empty when this tenant holds no such id
 */
    public java.util.Optional<LocalDeploymentStatus> localDeployment(RequestContext context, String deploymentId) {
        require(context, AuthorizationAction.EXECUTION_READ,
                ProtectedResource.owned("deployment", requireText(deploymentId, "deployment id"), context.tenantId()));
        return delegate.localDeployment(context.tenantId(), deploymentId);
    }

    /**
 * Starts one of the caller tenant's deployments under the caller's own identity.
 * @param context authenticated request context supplying the owning tenant and serving identity
 * @param deploymentId the deployment to start
 * @return the status at readiness, or empty when this tenant holds no such id
 */
    public CompletionStage<java.util.Optional<LocalDeploymentStatus>> startLocalDeployment(
            RequestContext context, String deploymentId) {
        require(context, AuthorizationAction.EXECUTION_CONTROL,
                ProtectedResource.owned("deployment", requireText(deploymentId, "deployment id"), context.tenantId()));
        return delegate.startLocalDeployment(SecurityContext.of(context), deploymentId);
    }

    /**
 * Stops only the caller tenant's named deployment and never the shared execution engine.
 * @param context authenticated request context supplying the owning tenant
 * @param deploymentId the deployment to stop
 * @return the stopped status, or empty when this tenant holds no such id
 */
    public CompletionStage<java.util.Optional<LocalDeploymentStatus>> stopLocalDeployment(
            RequestContext context, String deploymentId) {
        require(context, AuthorizationAction.EXECUTION_CONTROL,
                ProtectedResource.owned("deployment", requireText(deploymentId, "deployment id"), context.tenantId()));
        return delegate.stopLocalDeployment(context.tenantId(), deploymentId);
    }

    /**
 * Restarts only the caller tenant's named deployment, without duplicating its subscriptions.
 * @param context authenticated request context supplying the owning tenant and serving identity
 * @param deploymentId the deployment to restart
 * @return the restarted status, or empty when this tenant holds no such id
 */
    public CompletionStage<java.util.Optional<LocalDeploymentStatus>> restartLocalDeployment(
            RequestContext context, String deploymentId) {
        require(context, AuthorizationAction.EXECUTION_CONTROL,
                ProtectedResource.owned("deployment", requireText(deploymentId, "deployment id"), context.tenantId()));
        return delegate.restartLocalDeployment(SecurityContext.of(context), deploymentId);
    }

    /**
 * Stops and then deregisters only the caller tenant's named deployment.
 * @param context authenticated request context supplying the owning tenant
 * @param deploymentId the deployment to stop and deregister
 * @return the final status observed before deregistration, or empty when this tenant holds no
 * such id — which is also what a second undeploy of the same id returns
 */
    public CompletionStage<java.util.Optional<LocalDeploymentStatus>> undeployLocalDeployment(
            RequestContext context, String deploymentId) {
        require(context, AuthorizationAction.EXECUTION_CONTROL,
                ProtectedResource.owned("deployment", requireText(deploymentId, "deployment id"), context.tenantId()));
        return delegate.undeployLocalDeployment(context.tenantId(), deploymentId);
    }

    /**
 * Cancels the traversal identified by {@code traversalId}. Requires
 * {@link AuthorizationAction#EXECUTION_CONTROL} against the traversal's own resolved ownership --
 * unknown ownership (never reserved, or evicted past {@link ExecutionOwnershipRegistry}'s bound)
 * fails closed here exactly as it already does for {@link #canObserve}, before this method's own
 * three-way outcome logic ever runs, matching the registry's existing rule that evicted ownership
 * becomes unknown and fails closed.
 * @param context authenticated request context used for authorization and audit attribution.
 * @param traversalId the stable traversal id used to identify the requested resource.
 * @return a cancellation outcome that distinguishes an active traversal from an absent or terminal one.
 */
    public CancelResult cancelExecution(RequestContext context, UUID traversalId) {
        Objects.requireNonNull(traversalId, "traversalId");
        requireControl(context, traversalId);
        auditControl(context, "cancel", "execution", traversalId.toString());
        try {
            CancelResult result = performCancel(context, traversalId);
            auditControlSucceeded(context, "cancel", "execution", traversalId.toString(), result.outcome().name());
            return result;
        } catch (RuntimeException failed) {
            auditControlFailed(context, "cancel", "execution", traversalId.toString());
            throw failed;
        }
    }

    /**
 * The three-way distinction the contract specifies: an active traversal is stopped; a
 * previously cancelled one is idempotently reported as such and is not an error; one no longer
 * active for any other reason (it ran to completion) is a distinguishable outcome, not a silent
 * success. {@link #executionOwners}'s bounded map is what supplies the "already cancelled" memory
 * once the traversal has left the delegate's own active-execution tracking -- see
 * {@link ExecutionOwnershipRegistry#markCancelled}'s own Javadoc.
 */
    private CancelResult performCancel(RequestContext context, UUID traversalId) {
        if (executionOwners.isCancelled(traversalId)) {
            // Answered from this process's own memory, before the delegate is asked anything. So a
            // second cancellation of a traversal this process already cancelled does not reach the
            // durable path, and a hold row left behind by a settlement the store refused is
            // typically cleared only after a restart, when this registry no longer remembers the
            // cancellation. That is harmless -- the row is inert, reports nothing because a terminal
            // traversal is never held, and is settled by the next cancellation that does reach the
            // store -- and it is worth knowing before reading "a later cancellation settles it" as a
            // promise about the very next call.
            return CancelResult.alreadyCancelled(traversalId);
        }
        // Tenant-scoped, so a traversal this process is not running but is durably holding is still
        // cancellable. Without it a hold that outlived its process could never be given up, and the
        // caller would be told the execution had already completed -- which is exactly the false
        // report this method's own re-check exists to avoid.
        boolean stopped = delegate.cancelTraversal(context.tenantId(), traversalId);
        if (stopped) {
            executionOwners.markCancelled(traversalId);
            return CancelResult.cancelled(traversalId);
        }
        // Not active. Either it ran to completion, or a concurrent cancel call just won the race above:
        // re-checking narrows that window (it does not close it) without this method ever reporting
        // a fresh cancellation twice.
        if (executionOwners.isCancelled(traversalId)) {
            return CancelResult.alreadyCancelled(traversalId);
        }
        return CancelResult.alreadyCompleted(traversalId);
    }

    /**
 * Pauses the traversal identified by {@code traversalId}: the node in flight finishes and
 * nothing after it is dispatched until {@link #resumeExecution}.
 *
 * <p>Under {@link AuthorizationAction#EXECUTION_CONTROL} against the traversal's own resolved
 * ownership, and audited, for the same reasons {@link #cancelExecution} is: holding somebody
 * else's execution is a control action over their work, and an unknown or evicted ownership fails
 * closed here before any outcome logic runs.</p>
 *
 * <h4>{@code ALREADY_PAUSED} is read from the hold itself, not inferred from liveness</h4>
 * <p>{@link RavenrootApplication#pauseTraversal} answers a boolean, so "already paused" and "not
 * running here" arrive identically and something else has to separate them.
 * {@link RavenrootApplication#executionPaused} is that something: it reads the very bookkeeping
 * {@code pauseTraversal} mutates, so the outcome this method words and the {@code paused} qualifier
 * on {@link LiveExecution} and {@link ExecutionOutcome} are one fact rather than three projections
 * that can disagree.</p>
 *
 * <p><strong>Liveness used to stand in for it, and liveness is not the same question.</strong>
 * Reading {@code false} as "already paused" is sound only if {@code false} can mean nothing except
 * "a hold is already in place", and it cannot. It also means the traversal has been asked to stop,
 * and — since a traversal that has begun to end now refuses a new hold, so that no pause can be
 * published after its terminal event — it also means the traversal is closing. Both of those are
 * listed live while they happen, so the old inference answered {@code ALREADY_PAUSED} over
 * executions that were holding nothing: an operator pausing during a shutdown already in progress
 * was told their request had changed nothing because a hold was in place, when there was none and
 * the run was ending. Asking about the hold directly retires that residual rather than narrowing
 * it, and both cases now answer {@code NOT_ACTIVE}, which is what they are.</p>
 *
 * <p>The window between the two delegate calls is real and is the same one
 * {@link #cancelExecution}'s own re-check narrows without closing: a traversal that finishes inside
 * it is reported as {@code NOT_ACTIVE}, which is what it is by the time the caller reads the
 * answer.</p>
 *
 * <p>The hold is read without a tenant scope, and that is safe here rather than by omission:
 * {@link #requireControl} has already resolved this traversal's ownership and required
 * {@link AuthorizationAction#EXECUTION_CONTROL} over it, failing closed on unknown ownership, so
 * this line is reached only by a caller already entitled to hold this exact traversal. Learning
 * whether it is holding is strictly less than the control that was just granted.</p>
 * @param context authenticated request context used for authorization and audit attribution.
 * @param traversalId the stable traversal id used to identify the requested resource.
 * @return a pause outcome that states whether the owned traversal was newly held, already held, or inactive.
 */
    public PauseResult pauseExecution(RequestContext context, UUID traversalId) {
        Objects.requireNonNull(traversalId, "traversalId");
        requireControl(context, traversalId);
        auditControl(context, "pause", "execution", traversalId.toString());
        try {
            PauseResult result = delegate.pauseTraversal(traversalId)
                    ? PauseResult.paused(traversalId)
                    : delegate.executionPaused(context.tenantId(), traversalId)
                            ? PauseResult.alreadyPaused(traversalId)
                            : PauseResult.notActive(traversalId);
            auditControlSucceeded(context, "pause", "execution", traversalId.toString(),
                    result.outcome().name());
            return result;
        } catch (RuntimeException failed) {
            auditControlFailed(context, "pause", "execution", traversalId.toString());
            throw failed;
        }
    }

    /**
 * Resumes a traversal paused by {@link #pauseExecution}, under the same authority and the same
 * audit.
 *
 * <h4>Why this still separates its outcomes by liveness while {@link #pauseExecution} no longer
 * does</h4>
 * <p>The two are not symmetric, and the asymmetry is in what each delegate's {@code false} means.
 * {@link RavenrootApplication#resumeTraversal} answers {@code false} only when it found no hold to
 * release, so "is it holding?" is already settled here and the one question left is whether the
 * traversal exists at all — which is exactly what {@link #stillLive} answers. {@code NOT_PAUSED} is
 * a live traversal that was not holding; {@code NOT_ACTIVE} is one this process is not running.</p>
 *
 * <p>{@link #pauseExecution} had the opposite problem: its delegate's {@code false} conflated
 * "already holding" with "cannot be held", so liveness could not tell those apart and it asks
 * {@link RavenrootApplication#executionPaused} instead. Reading that same method here would answer a
 * question this path has already answered, and would not distinguish the two outcomes it does need
 * to separate.
 * @param context authenticated request context used for authorization and audit attribution.
 * @param traversalId the stable traversal id used to identify the requested resource.
 * @return a resume outcome that states whether the owned traversal resumed, was not held, or was inactive.
 */
    public ResumeResult resumeExecution(RequestContext context, UUID traversalId) {
        Objects.requireNonNull(traversalId, "traversalId");
        requireControl(context, traversalId);
        auditControl(context, "resume", "execution", traversalId.toString());
        try {
            ResumeResult result = delegate.resumeTraversal(context.tenantId(), traversalId)
                    ? ResumeResult.resumed(traversalId)
                    : stillLive(context, traversalId)
                            ? ResumeResult.notPaused(traversalId)
                            : ResumeResult.notActive(traversalId);
            auditControlSucceeded(context, "resume", "execution", traversalId.toString(),
                    result.outcome().name());
            return result;
        } catch (RuntimeException failed) {
            auditControlFailed(context, "resume", "execution", traversalId.toString());
            throw failed;
        }
    }

    /**
 * The ownership-resolved {@link AuthorizationAction#EXECUTION_CONTROL} decision, extracted from
 * {@link #cancelExecution} so pause and resume cannot drift from it. Unknown ownership -- never
 * reserved, or evicted past {@link ExecutionOwnershipRegistry}'s bound -- fails closed here.
 */
    private void requireControl(RequestContext context, UUID traversalId) {
        String owner = executionOwners.owner(traversalId);
        if (owner == null) {
            owner = durableHoldOwner(context, traversalId);
        }
        ProtectedResource resource = owner == null
                ? ProtectedResource.unknownOwnership("execution", traversalId.toString())
                : ProtectedResource.owned("execution", traversalId.toString(), owner);
        require(context, AuthorizationAction.EXECUTION_CONTROL, resource);
    }

    /**
     * Resolves ownership of a traversal this process never ran but is durably holding.
     *
     * <h4>Why this is needed at all</h4>
     * <p>{@link ExecutionOwnershipRegistry} is process-local and bounded, so after a restart it
     * knows nothing about a traversal held before the restart, and {@link #requireControl} fails
     * closed. That is the right default for an unknown traversal and the wrong answer for the one
     * case a durable hold exists to create: an operator who deliberately stopped a traversal, and
     * who after a restart could then neither resume nor cancel it — the hold would have made the
     * work permanently unreachable instead of recoverable.</p>
     *
     * <h4>Why it cannot widen anyone's authority</h4>
     * <p>It resolves ownership to the caller's <em>own</em> tenant and to nothing else, and only when
     * the store confirms that tenant is holding that traversal. The confirming read is tenant-scoped
     * at the port, so a caller asking about another tenant's held traversal is told "not held", the
     * owner stays unknown, and the request fails closed exactly as before. What the caller then
     * receives is an ordinary {@link AuthorizationAction#EXECUTION_CONTROL} decision over a resource
     * owned by its own tenant — the same decision it would have received before the restart.</p>
     *
     * @return the caller's tenant when it durably holds this traversal, otherwise {@code null}
     */
    private String durableHoldOwner(RequestContext context, UUID traversalId) {
        try {
            return delegate.executionPaused(context.tenantId(), traversalId) ? context.tenantId() : null;
        } catch (RuntimeException unavailable) {
            // An unreadable store is not evidence of ownership. Failing closed here keeps a store
            // outage from becoming an authorization outcome.
            return null;
        }
    }

    /**
 * Whether the runtime still lists this traversal as live for this caller's own tenant.
 *
 * <p>Reads the delegate directly rather than {@link #liveExecutions}, because that method
 * requires {@link AuthorizationAction#EXECUTION_READ} and this is not a read the caller asked
 * for: it is how a control action already authorized under {@code EXECUTION_CONTROL} words its
 * own answer. Requiring the read permission here would make an operator's ability to be told
 * <em>why</em> nothing was paused depend on a second, unrelated grant. The tenant still comes
 * from the authenticated context and from nothing else, so no other tenant's execution can be
 * observed through it.</p>
 */
    private boolean stillLive(RequestContext context, UUID traversalId) {
        return delegate.liveExecutions(context.tenantId()).stream()
                .anyMatch(execution -> traversalId.equals(execution.traversalId()));
    }

    /**
 * Drains the server: ADR 0012's engine-wide drain, exposed as an operator command
 * using ADR 0023 section 6's sequencing shape (stop accepting, enter drain, bounded wait). Scoped to
 * platform operators rather than any tenant's own {@code OPERATOR}, even though
 * {@link AuthorizationAction#EXECUTION_CONTROL} is the very same action {@link #cancelExecution}
 * uses for a single tenant-owned traversal -- see {@link #DRAIN_RESOURCE_TENANT}'s own Javadoc for
 * the mechanism that narrows drain's blast radius through the resource rather than the role.
 * @param context authenticated request context used for authorization and audit attribution.
 * @param bound maximum time to wait for cooperative drain completion.
 * @return the cooperative-drain outcome, including a timeout when the bound expires first.
 */
    public DrainResult drain(RequestContext context, java.time.Duration bound) {
        Objects.requireNonNull(bound, "bound");
        ProtectedResource resource = ProtectedResource.owned("server", "cluster", DRAIN_RESOURCE_TENANT);
        require(context, AuthorizationAction.EXECUTION_CONTROL, resource);
        auditControl(context, "drain", "server", "cluster");
        try {
            boolean drained = delegate.drain(bound);
            DrainResult result = new DrainResult(
                    drained ? DrainResult.Outcome.DRAINED : DrainResult.Outcome.TIMED_OUT);
            auditControlSucceeded(context, "drain", "server", "cluster", result.outcome().name());
            return result;
        } catch (RuntimeException failed) {
            auditControlFailed(context, "drain", "server", "cluster");
            throw failed;
        }
    }

    /**
 * This caller's own tenant's live executions: read directly from the runtime's own
 * live bookkeeping, so a stalled execution -- the one an operator most needs to find -- still
 * appears, exactly like {@link #cancelExecution} still reaches it.
 *
 * <h4>Why the tenant scoping is structural here, not a check</h4>
 * <p>Same shape as {@link #executionResult} and {@link #durableEventsAfter}: the tenant is read
 * from the authenticated {@code RequestContext} and passed straight through as the delegate's
 * {@code tenantId}. This is the only signature reachable from an external adapter, and it never
 * accepts a tenant scope as a bare string a caller could substitute. The delegate filters by
 * tenant while building the list, so another tenant's execution is never fetched and then
 * excluded -- it is a row the query never asks for.</p>
 *
 * <p>{@link #executionOwners}'s bounded, evictable ownership map is deliberately not consulted
 * here either, for the same reason {@link #durableEventsAfter} does not consult it: it would
 * reintroduce a check that could be forgotten, evicted or wrong, on a path this method exists to
 * make free of exactly that.</p>
 * @param context authenticated request context used for authorization and audit attribution.
 * @return currently live executions owned by the caller's tenant.
 */
    public List<LiveExecution> liveExecutions(RequestContext context) {
        require(context, AuthorizationAction.EXECUTION_READ, collection("executions", context));
        return delegate.liveExecutions(context.tenantId());
    }

/**
 * Reports whether the durable process inventory is available.
 * @see RavenrootApplication#processInventoryAvailable()
 * @return {@code true} when the delegate can answer {@link #processInventory}, {@link #processInstance}
 * and {@link #processInstanceTraversals} from a real durable store.
 */
    public boolean processInventoryAvailable() {
        return delegate.processInventoryAvailable();
    }

/**
 * The largest page {@link #processInventory} will return in one call. Not tenant data and not
 * gated by authorization for the same reason {@link #processInventoryAvailable()} and
 * {@link #durableEventJournalAvailable()} are not: it is a fact about this deployment's composed
 * store, published so an external caller building its own pagination loop can read the bound
 * instead of discovering it by bisection.
 * @see RavenrootApplication#processInventoryMaxPageSize()
 * @return the maximum page size the composed store accepts, or zero when unavailable.
 */
    public int processInventoryMaxPageSize() {
        return delegate.processInventoryMaxPageSize();
    }

    /**
 * This caller's own tenant's page of the durable process inventory.
 *
 * <h4>Why the tenant scoping is structural here, not a check</h4>
 * <p>Same shape as {@link #liveExecutions}, {@link #executionResult} and
 * {@link #durableEventsAfter}: the tenant is read from the authenticated {@link RequestContext} and
 * passed straight through as the delegate's {@code tenantId}, never accepted as a bare string a
 * caller could substitute. The store filters by tenant while building the page, so another
 * tenant's instance is never fetched and then excluded — it is a row the query never asks for.</p>
 * @param context authenticated request context used for authorization and audit attribution.
 * @param query the page to return: filters, cursor and limit.
 * @return one deterministic page of the caller's own tenant's durable process instances.
 */
    public ai.ravenroot.api.persistence.ProcessInventoryPage processInventory(
            RequestContext context, ai.ravenroot.api.persistence.ProcessInventoryQuery query) {
        require(context, AuthorizationAction.EXECUTION_READ, collection("executions", context));
        Objects.requireNonNull(query, "query");
        return delegate.processInventory(context.tenantId(), query);
    }

    /**
 * Reads one of the caller's own tenant's durable inventory rows directly.
 *
 * <h4>Authorization before existence disclosure</h4>
 * <p>Authorization is checked first and unconditionally, before {@code processInstanceId} is used
 * for anything — the same ordering {@link #executionResult} uses and for the same reason: a
 * principal that may not read the inventory must not be able to learn whether an id exists by
 * comparing how a denial and an absence differ. Once authorized, the tenant is read from the
 * authenticated {@code RequestContext} and passed straight through as the delegate's
 * {@code tenantId} — this signature never accepts a tenant scope a caller could substitute — so a
 * cross-tenant id and one that never existed both come back as an empty {@link java.util.Optional},
 * indistinguishable by design, exactly as {@link RavenrootApplication#processInstance} documents.</p>
 * @param context authenticated request context used for authorization and audit attribution.
 * @param processInstanceId the durable process instance to read.
 * @return the instance's inventory row, or empty when absent or not visible to the caller's tenant.
 */
    public java.util.Optional<ai.ravenroot.api.persistence.ProcessInventoryEntry> processInstance(
            RequestContext context, UUID processInstanceId) {
        require(context, AuthorizationAction.EXECUTION_READ, collection("executions", context));
        Objects.requireNonNull(processInstanceId, "processInstanceId");
        return delegate.processInstance(context.tenantId(), processInstanceId);
    }

    /**
 * Lists one of the caller's own tenant's instances' traversals from the durable inventory, under the
 * same authorization-before-existence-disclosure ordering {@link #processInstance} uses.
 * @param context authenticated request context used for authorization and audit attribution.
 * @param processInstanceId the durable process instance whose traversals are listed.
 * @return the instance's traversals, in insertion order.
 * @throws ai.ravenroot.api.persistence.ExecutionStoreException wrapping
 * {@code ExecutionStoreFailure.NotFound} when the instance is absent or not visible to the caller's
 * tenant — indistinguishable, exactly as {@link RavenrootApplication#processInstanceTraversals} documents.
 */
    public List<ai.ravenroot.api.persistence.TraversalInventoryEntry> processInstanceTraversals(
            RequestContext context, UUID processInstanceId) {
        require(context, AuthorizationAction.EXECUTION_READ, collection("executions", context));
        Objects.requireNonNull(processInstanceId, "processInstanceId");
        return delegate.processInstanceTraversals(context.tenantId(), processInstanceId);
    }

    /**
 * The caller's own tenant's durable inventory retention floor, under the same read permission
 * {@link #processInventory} requires — it is what lets a caller tell "never existed" from "expired
 * by policy" for a row {@link #processInstance} or {@link #processInstanceTraversals} could not find.
 * @param context authenticated request context used for authorization and audit attribution.
 * @return the earliest instant from which the caller's own tenant's terminal inventory is complete.
 */
    public java.time.Instant processInventoryRetainedFrom(RequestContext context) {
        require(context, AuthorizationAction.EXECUTION_READ, collection("executions", context));
        return delegate.processInventoryRetainedFrom(context.tenantId());
    }

/**
 * Reads execution events after an exclusive sequence position.
 * @param context authenticated request context used for authorization and audit attribution.
 * @param sequence monotonically increasing position of the execution event.
 * @return authorised events strictly after {@code sequence}, filtered to the caller's tenant.
 */
    public List<ExecutionEvent> executionEventsAfter(RequestContext context, long sequence) {
        authorizeExecutionEvents(context);
        return delegate.executionEventsAfter(sequence).stream()
                .filter(event -> canObserve(context, event))
                .toList();
    }

    /**
 * The in-memory retention floor behind {@link #executionEventsAfter}, under the same permission that
 * method requires. This reports a <em>cursor position</em>, never event content,
 * so it discloses nothing {@code detail}-bearing.
 *
 * <p>Deliberately not tenant-narrowed, because it is not tenant-scoped data: the floor is a property
 * of the shared ring, not of anyone's events. Narrowing it would be worse than useless — a floor
 * computed from only the events this caller may observe would sit <em>above</em> the true eviction
 * point and would under-report gaps, which is the failure direction that looks healthy.</p>
 * @param context authenticated request context used for authorization and audit attribution.
 * @return the global journal's oldest retained sequence, or empty when no event remains.
 */
    public java.util.OptionalLong oldestRetainedEventSequence(RequestContext context) {
        authorizeExecutionEvents(context);
        return delegate.oldestRetainedEventSequence();
    }

/**
 * Subscribes an authorized caller to future execution events.
 * @param context authenticated request context used for authorization and audit attribution.
 * @param listener consumer notified about execution events visible to the caller.
 * @return a closeable subscription; closing it stops callbacks for this listener.
 */
    public AutoCloseable subscribeToExecutionEvents(RequestContext context, Consumer<ExecutionEvent> listener) {
        authorizeExecutionEvents(context);
        Objects.requireNonNull(listener, "listener");
        return delegate.subscribeToExecutionEvents(event -> {
            if (canObserve(context, event)) {
                listener.accept(event);
            }
        });
    }

/**
 * Re-evaluates the current collection-level permission for an existing event-stream lease.
 * @param context authenticated request context used for authorization and audit attribution.
 */
    public void authorizeExecutionEvents(RequestContext context) {
        require(context, AuthorizationAction.EXECUTION_READ, collection("execution-events", context));
    }

/**
 * Reports whether durable event-journal replay is available.
 * @see RavenrootApplication#durableEventJournalAvailable()
 * @return {@code true} when the delegate can replay durable execution events.
 */
    public boolean durableEventJournalAvailable() {
        return delegate.durableEventJournalAvailable();
    }

    /**
 * Durable, bounded replay of the caller's own tenant's event journal — what a
 * reconnecting SSE client resumes from after a restart, when {@link #executionEventsAfter}'s
 * in-memory cursor has already reset.
 *
 * <h4>Why tenant scoping is structural here, not a filter</h4>
 * <p>{@link #executionEventsAfter} and {@link #subscribeToExecutionEvents} read every tenant's
 * events from {@link RavenrootApplication#executionEventsAfter} unfiltered, then narrow the
 * result with {@link #canObserve}, which resolves ownership through
 * {@link #executionOwners} — a bounded, in-process, evictable map populated only for executions
 * <em>this</em> process itself started. That map is empty after a restart, which is exactly the
 * moment a durable replay is asked to prove it still works. Reusing it here would not merely be
 * redundant; it would be wrong in precisely the case this method exists for.</p>
 *
 * <p>This method instead supplies {@code context.tenantId()} — read from the authenticated
 * {@link RequestContext} and from nothing else; this is the only signature reachable from an
 * external adapter that accepts a tenant scope, and it accepts it only wrapped in a
 * {@code RequestContext}, never as a bare string a caller could substitute — directly as the
 * {@code tenantId} parameter {@link RavenrootApplication#durableEventsAfter} passes to
 * {@code ExecutionStore.readJournal}. Every row the store returns is thereby a row that query
 * asked for by that tenant, not a row filtered down from a mixed result afterward: there is no
 * step at which a record belonging to another tenant is even fetched, let alone has to be
 * correctly excluded by a check that could be forgotten, evicted or wrong.</p>
 *
 * <p>The registry's ownership notion was tenant-only to begin with — see
 * {@code ExecutionOwnershipRegistry}'s own {@code Ownership} record, which carries a tenant id
 * and nothing finer — so this trades that mechanism for a structurally equivalent, durable one
 * rather than narrowing what was actually enforced before.</p>
 * @param context authenticated request context used for authorization and audit attribution.
 * @param afterOffset exclusive journal offset from which durable events are read.
 * @param limit the limit constraint applied while processing the request.
 * @return at most {@code limit} tenant-scoped durable events with offsets greater than {@code afterOffset}.
 */
    public List<DurableExecutionEvent> durableEventsAfter(RequestContext context, long afterOffset, int limit) {
        authorizeExecutionEvents(context);
        return delegate.durableEventsAfter(context.tenantId(), afterOffset, limit);
    }

/**
 * Reports whether execution results are retained for later retrieval.
 * @see RavenrootApplication#executionResultsRetained()
 * @return {@code true} when completed execution results can be looked up later.
 */
    public boolean executionResultsRetained() {
        return delegate.executionResultsRetained();
    }

    /**
 * Reads one of the caller's <em>own</em> tenant's execution results.
 *
 * <h4>Why the tenant scoping is structural here, not a check</h4>
 * <p>The tenant is read from the authenticated {@link RequestContext} and passed straight through
 * as the delegate's {@code tenantId}. This is the only signature reachable from an external
 * adapter, and it accepts a tenant scope only wrapped in a {@code RequestContext} — never as a
 * bare string a caller could substitute. The registry behind the delegate is keyed by
 * {@code (tenantId, executionId)} together, so another tenant's result is never fetched and then
 * excluded: it is a row the lookup never asks for. There is no ordering of the code in which the
 * exclusion could be forgotten, because there is no exclusion step.</p>
 *
 * <p>{@link #canObserve}'s ownership-registry mechanism is deliberately <em>not</em> reused. That
 * map is bounded and evictable, and it is populated only for executions this process started;
 * consulting it would reintroduce exactly the "check that could be evicted" this signature
 * exists to avoid — the same argument {@link #durableEventsAfter} makes at more length.</p>
 *
 * <p>A cross-tenant id therefore returns {@link ExecutionLookup.Unknown}, not a denial. That is
 * the intended answer: a distinct "exists but not yours" would be an existence oracle a caller
 * could enumerate another tenant's ids through.</p>
 * @param context authenticated request context used for authorization and audit attribution.
 * @param executionId the stable execution id used to identify the requested resource.
 * @return an authorised lookup result that never exposes another tenant's execution identity or state.
 */
    public ExecutionLookup executionResult(RequestContext context, UUID executionId) {
        // Authorization first and unconditionally, before the id is used for anything. A principal
        // that may not read executions must not be able to learn whether an id exists by comparing
        // how long the refusal took or which of the three answers came back.
        require(context, AuthorizationAction.EXECUTION_READ, collection("executions", context));
        Objects.requireNonNull(executionId, "executionId");
        return delegate.executionResult(context.tenantId(), executionId);
    }

    private boolean canObserve(RequestContext context, ExecutionEvent event) {
        if (event == null) {
            return false;
        }
        if (event.executionId() == null) {
            return authorization.decide(context, AuthorizationAction.EXECUTION_READ,
                    ProtectedResource.unknownOwnership("execution", "missing-execution-id")).allowed();
        }
        String owner = executionOwners.owner(event.executionId());
        ProtectedResource resource = owner == null
                ? ProtectedResource.unknownOwnership("execution", event.executionId().toString())
                : ProtectedResource.owned("execution", event.executionId().toString(), owner);
        return authorization.decide(context, AuthorizationAction.EXECUTION_READ, resource).allowed();
    }

    private ProtectedResource artifact(String id) {
        return delegate.programArtifacts().stream().filter(candidate -> candidate.id().equals(id)).findFirst()
                .map(candidate -> {
                    String owner = ownerTenant(candidate);
                    return owner == null
                            ? ProtectedResource.unknownOwnership("program-artifact", id)
                            : ProtectedResource.owned("program-artifact", id, owner);
                })
                .orElseGet(() -> ProtectedResource.unknownOwnership("program-artifact", id));
    }

    private static String ownerTenant(GeneratedArtifact artifact) {
        String owner = artifact.metadata().get(OWNER_TENANT_METADATA);
        return owner == null || owner.isBlank() ? null : owner;
    }

    private GeneratedArtifact findArtifact(String id) {
        return delegate.programArtifacts().stream().filter(candidate -> candidate.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Artifact is unavailable"));
    }

    private void enforceIndependentActor(RequestContext context, GeneratedArtifact artifact) {
        if (dualControl && qualifiedIdentity(context).equals(artifact.metadata().get(CREATOR_METADATA))) {
            throw new ai.ravenroot.api.security.AuthorizationDeniedException(
                    "artifact creator cannot approve or activate the artifact");
        }
    }

    private void audit(RequestContext context, AuthorizationAction action, GeneratedArtifact artifact) {
        var event = new ArtifactLifecycleAuditEvent(java.time.Instant.now(), context.requestId(), context.subject(),
                context.tenantId(), action.name(), artifact == null ? "pending" : artifact.id(),
                artifact == null ? "" : artifact.sha256(), artifact == null ? null : artifact.state(),
                ArtifactLifecycleAuditEvent.Disposition.ATTEMPT,
                artifact == null ? 0L : artifact.revision(),
                artifact == null ? "" : ArtifactEvidence.of(artifact).hex());
        try {
            artifactAudit.record(event);
        } catch (RuntimeException unavailable) {
            throw new ai.ravenroot.api.security.AuthorizationDeniedException(
                    "artifact lifecycle audit unavailable");
        }
    }

    private void auditDenied(RequestContext context, AuthorizationAction action, GeneratedArtifact artifact) {
        var event = new ArtifactLifecycleAuditEvent(java.time.Instant.now(), context.requestId(), context.subject(),
                context.tenantId(), action.name(), artifact.id(), artifact.sha256(), artifact.state(),
                ArtifactLifecycleAuditEvent.Disposition.DENIED,
                artifact == null ? 0L : artifact.revision(),
                artifact == null ? "" : ArtifactEvidence.of(artifact).hex());
        try {
            artifactAudit.record(event);
        } catch (RuntimeException ignored) {
            // The mutation is already denied. Preserve the authorization result without exposing audit internals.
        }
    }

    /**
 * Closes an {@code ATTEMPT} record whose mutation then failed.
 *
 * <p>Without this the attempt was the trail's last word on the operation, and an operation that
 * threw was indistinguishable from one that succeeded. The audit failure is swallowed for the same
 * reason {@link #auditDenied} swallows it: the caller's own exception is the one that matters, and
 * replacing it with an audit-infrastructure error would hide the actual fault.
 */
    /**
 * Closes an {@code ATTEMPT} record whose mutation then succeeded.
 *
 * <p>Until this existed the trail had no positive record of success at all. An {@code ATTEMPT}
 * closed by neither {@code DENIED} nor {@code FAILED} was the only evidence that an approval or
 * an activation had taken effect — an inference from absence, and absence is also what a process
 * killed mid-activation leaves behind. SEC-12 binds approver and activation immutably to an
 * artifact; that cannot rest on a record nobody wrote.
 *
 * <p>The artifact passed here is the one the mutation <em>produced</em> where it produced one, so
 * the terminal record carries the state actually reached rather than the state on entry.
 *
 * <p>The audit failure is swallowed for the same reason {@link #auditDenied} swallows it: the
 * mutation has already succeeded and its result is what the caller must receive. This is a real
 * asymmetry with {@link #audit}, which fails closed <em>before</em> the mutation runs — that one
 * can still prevent an unrecorded change, and this one cannot.
 */
    private void auditSucceeded(RequestContext context, AuthorizationAction action, GeneratedArtifact artifact) {
        var event = new ArtifactLifecycleAuditEvent(java.time.Instant.now(), context.requestId(), context.subject(),
                context.tenantId(), action.name(), artifact == null ? "pending" : artifact.id(),
                artifact == null ? "" : artifact.sha256(), artifact == null ? null : artifact.state(),
                ArtifactLifecycleAuditEvent.Disposition.SUCCEEDED,
                artifact == null ? 0L : artifact.revision(),
                artifact == null ? "" : ArtifactEvidence.of(artifact).hex());
        try {
            artifactAudit.record(event);
        } catch (RuntimeException ignored) {
            // See the Javadoc: the mutation already took effect and cannot be unwound here.
        }
    }

    private void auditFailed(RequestContext context, AuthorizationAction action, GeneratedArtifact artifact) {
        var event = new ArtifactLifecycleAuditEvent(java.time.Instant.now(), context.requestId(), context.subject(),
                context.tenantId(), action.name(), artifact == null ? "pending" : artifact.id(),
                artifact == null ? "" : artifact.sha256(), artifact == null ? null : artifact.state(),
                ArtifactLifecycleAuditEvent.Disposition.FAILED,
                artifact == null ? 0L : artifact.revision(),
                artifact == null ? "" : ArtifactEvidence.of(artifact).hex());
        try {
            artifactAudit.record(event);
        } catch (RuntimeException ignored) {
            // See above: never mask the failure being reported.
        }
    }

    /**
 * Opens a {@code CONTROL}-category {@code ATTEMPT} record for a cancel/drain request, before the
 * mutation runs -- same reasoning as {@link #audit}: a process killed mid-cancel still leaves
 * evidence the operator asked. Fails closed, also like {@link #audit}: this runs after
 * {@link #require} has already allowed the request, so an audit failure here still prevents an
 * unrecorded change from taking effect.
 */
    private void auditControl(RequestContext context, String action, String resourceType, String resourceId) {
        var event = new ExecutionControlAuditEvent(java.time.Instant.now(), context.requestId(), context.subject(),
                context.tenantId(), action, resourceType, resourceId,
                ExecutionControlAuditEvent.Disposition.ATTEMPT, "");
        try {
            controlAudit.record(event);
        } catch (RuntimeException unavailable) {
            throw new ai.ravenroot.api.security.AuthorizationDeniedException(
                    "execution control audit unavailable");
        }
    }

    /** Closes the {@code ATTEMPT} record with the outcome the mutation actually produced. */
    private void auditControlSucceeded(RequestContext context, String action, String resourceType,
                                       String resourceId, String detail) {
        var event = new ExecutionControlAuditEvent(java.time.Instant.now(), context.requestId(), context.subject(),
                context.tenantId(), action, resourceType, resourceId,
                ExecutionControlAuditEvent.Disposition.SUCCEEDED, detail);
        try {
            controlAudit.record(event);
        } catch (RuntimeException ignored) {
            // See auditSucceeded's own Javadoc above: the mutation already took effect and cannot be
            // unwound here, so the caller's result is what must get through, not an audit-sink failure.
        }
    }

    /** Closes an {@code ATTEMPT} record whose mutation then failed. */
    private void auditControlFailed(RequestContext context, String action, String resourceType,
                                    String resourceId) {
        var event = new ExecutionControlAuditEvent(java.time.Instant.now(), context.requestId(), context.subject(),
                context.tenantId(), action, resourceType, resourceId,
                ExecutionControlAuditEvent.Disposition.FAILED, "");
        try {
            controlAudit.record(event);
        } catch (RuntimeException ignored) {
            // Never mask the failure being reported.
        }
    }

    /** Runs an audited mutation, recording a terminal FAILED record if it throws. */
    private <T> T recordingFailure(RequestContext context, AuthorizationAction action,
                                   GeneratedArtifact artifact, java.util.function.Supplier<T> mutation) {
        T outcome;
        try {
            outcome = mutation.get();
        } catch (RuntimeException failed) {
            auditFailed(context, action, artifact);
            throw failed;
        }
        auditSucceeded(context, action, outcome instanceof GeneratedArtifact produced ? produced : artifact);
        return outcome;
    }

    /**
 * The asynchronous counterpart: the mutation returns before it has succeeded or failed, so the
 * terminal record is attached to its completion rather than to the return.
 */
    private <T> CompletionStage<T> recordingAsyncFailure(RequestContext context, AuthorizationAction action,
                                                         GeneratedArtifact artifact,
                                                         java.util.function.Supplier<CompletionStage<T>> mutation) {
        CompletionStage<T> started;
        try {
            started = mutation.get();
        } catch (RuntimeException failed) {
            auditFailed(context, action, artifact);
            throw failed;
        }
        return started.whenComplete((result, failure) -> {
            if (failure != null) {
                auditFailed(context, action, artifact);
            } else {
                auditSucceeded(context, action, result instanceof GeneratedArtifact produced ? produced : artifact);
            }
        });
    }

    private static String qualifiedIdentity(RequestContext context) {
        return context.issuer() + "|" + context.principalType().name() + "|" + context.subject();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.strip();
    }

    private void require(RequestContext context, AuthorizationAction action, ProtectedResource resource) {
        authorization.requireAllowed(context, action, resource);
    }

    private static ProtectedResource collection(String type, RequestContext context) {
        return ProtectedResource.collection(type, context.tenantId());
    }
}
