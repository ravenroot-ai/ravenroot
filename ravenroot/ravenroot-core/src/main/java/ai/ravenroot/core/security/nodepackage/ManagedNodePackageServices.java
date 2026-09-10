package ai.ravenroot.core.security.nodepackage;

import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.node.service.CredentialLease;
import ai.ravenroot.api.node.service.ExternalIoLimits;
import ai.ravenroot.api.node.service.NodeCredentialService;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.node.service.NodePackageEgressCapacityProfile;
import ai.ravenroot.api.node.service.OutboundCall;
import ai.ravenroot.api.node.service.OutboundCredentialBinding;
import ai.ravenroot.api.node.service.OutboundHttpRequest;
import ai.ravenroot.api.node.service.OutboundHttpResponse;
import ai.ravenroot.api.node.service.OutboundHttpService;
import ai.ravenroot.api.node.service.OutboundHttpSigning;
import ai.ravenroot.api.node.service.OutboundWebSocketListener;
import ai.ravenroot.api.node.service.OutboundWebSocketRequest;
import ai.ravenroot.api.node.service.OutboundWebSocketService;
import ai.ravenroot.api.node.service.OutboundWebSocketSession;
import ai.ravenroot.api.node.service.ToolCallAuthorization;
import ai.ravenroot.api.node.service.ToolCallAuthorizationService;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.security.SecretValue;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.api.security.ToolCallAuditEvent;
import ai.ravenroot.api.security.ToolCallAuditSink;
import ai.ravenroot.api.security.ToolDecision;
import ai.ravenroot.api.security.ToolInvocation;
import ai.ravenroot.api.security.ToolPolicy;
import ai.ravenroot.core.approval.ToolApprovalService;
import ai.ravenroot.core.approval.ToolApprovalSettings;
import ai.ravenroot.core.approval.ToolApprovalResult;
import ai.ravenroot.core.security.egress.BoundedBodyHandlers;
import ai.ravenroot.core.security.egress.EgressAddressGuard;
import ai.ravenroot.core.security.egress.EgressHttpClients;
import ai.ravenroot.core.security.egress.ReservedNetwork;

import javax.net.ssl.SSLException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Core-owned implementation of the public package service view.
 *
 * <p>The view is bound to one exact package id. It exposes neither the JDK clients nor the trusted
 * resolver/policy. Every action operation derives tenant identity from the delivered
 * {@link NodeMessage}; every source operation derives it from the delivered
 * {@link InboundSourceContext}. Both acquire admission before starting transport work and map
 * failures to stable sanitized reasons.</p>
 */
public final class ManagedNodePackageServices implements NodePackageServices {
    private static final PayloadLimits TOOL_ARGUMENT_LIMITS =
            new PayloadLimits(64 * 1024, 32, 1024, 4096, 16 * 1024, 256);
    private static final int MAX_HEADER_NAMES = 64;
    private static final int MAX_HEADER_VALUES = 256;
    private static final int MAX_HEADER_CHARACTERS = 16 * 1024;
    private static final int MAX_TRACKED_TENANTS = 4096;
    /** RFC 6455 §5.5: control-frame payloads never exceed 125 octets. */
    private static final int MAX_WEBSOCKET_CONTROL_PAYLOAD_BYTES = 125;
    private static final Set<String> FORBIDDEN_CALLER_HEADERS = Set.of(
            "authorization", "cookie", "proxy-authorization", "proxy-connection", "host",
            "content-length", "connection", "upgrade", "transfer-encoding", "te", "trailer",
            "x-amz-date", "x-amz-content-sha256", "x-amz-security-token",
            "sec-websocket-key", "sec-websocket-accept", "sec-websocket-version",
            "sec-websocket-protocol", "sec-websocket-extensions");
    private static final ScheduledExecutorService DEADLINES = java.util.concurrent.Executors
            .newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ravenroot-node-package-service-deadlines");
                thread.setDaemon(true);
                return thread;
            });

    private final String packageId;
    private final NodePackageEgressPolicy policy;
    private final TenantCredentialResolver credentials;
    private final Set<NodePackageCapability> capabilities;
    private final java.util.function.Supplier<HttpClient> clientFactory;
    private final Clock clock;
    private final ToolPolicy toolPolicy;
    private final ToolCallAuditSink toolAuditSink;
    private final ToolApprovalService toolApprovalService;
    private final ToolApprovalSettings toolApprovalSettings;
    private final ai.ravenroot.api.node.service.AgentResourceService agentResources;
    private final AgentAuthorityBudgetService agentAuthorityBudgets;
    private volatile HttpClient client;
    private final AdmissionController admission;
    private final NodePackageServices unavailable = NodePackageServices.unavailable();
    private final AtomicReference<java.util.function.Function<NodeMessage,
            ai.ravenroot.api.persistence.ResolvedOperationalPolicy>> executionPolicies =
            new AtomicReference<>();

    private ManagedNodePackageServices(Builder builder) {
        packageId = safePackageId(builder.packageId);
        policy = Objects.requireNonNull(builder.policy, "policy");
        credentials = Objects.requireNonNull(builder.credentials, "credentials");
        capabilities = Set.copyOf(builder.capabilities);
        clientFactory = Objects.requireNonNull(builder.clientFactory, "clientFactory");
        clock = Objects.requireNonNull(builder.clock, "clock");
        toolPolicy = Objects.requireNonNull(builder.toolPolicy, "toolPolicy");
        toolAuditSink = Objects.requireNonNull(builder.toolAuditSink, "toolAuditSink");
        toolApprovalService = builder.toolApprovalService;
        toolApprovalSettings = builder.toolApprovalSettings;
        agentResources = builder.agentResources;
        agentAuthorityBudgets = builder.agentAuthorityBudgets;
        admission = new AdmissionController(policy.maximumConcurrentOperations(),
                policy.maximumConcurrentPerTenant());
    }

    public static Builder builder(String packageId, NodePackageEgressPolicy policy,
                                  TenantCredentialResolver credentials) {
        return new Builder(packageId, policy, credentials);
    }

    @Override
    public Set<NodePackageCapability> capabilities() {
        return capabilities;
    }

    @Override
    public Optional<NodePackageEgressCapacityProfile> egressCapacityProfile() {
        return Optional.of(NodePackageEgressCapacityProfile.bounded(
                policy.maximumRequestBytes(), policy.maximumResponseBytes(),
                policy.maximumWebSocketMessageBytes(), policy.maximumWebSocketFragments(),
                policy.maximumConcurrentOperations(), policy.maximumConcurrentPerTenant(),
                policy.maximumQueuedWebSocketSends(), policy.maximumDeadline(),
                policy.maximumWebSocketLifetime(), policy.maximumWebSocketIdle()));
    }

    /** Installs the core-owned active-execution lookup once, before package behavior can run. */
    public void bindExecutionPolicyResolver(java.util.function.Function<NodeMessage,
            ai.ravenroot.api.persistence.ResolvedOperationalPolicy> resolver) {
        if (!executionPolicies.compareAndSet(null, Objects.requireNonNull(resolver, "resolver"))) {
            throw new IllegalStateException("execution policy resolver is already bound");
        }
    }

    @Override
    public NodeCredentialService credentials() {
        if (!capabilities.contains(NodePackageCapability.CREDENTIAL_RESOLUTION)) {
            return unavailable.credentials();
        }
        return new NodeCredentialService() {
            @Override public OutboundCall<CredentialLease> resolve(NodeMessage message, String reference,
                                                                   Duration deadline) {
                return resolveCredential(messageIdentity(message), reference, deadline);
            }

            @Override public OutboundCall<CredentialLease> resolve(InboundSourceContext context,
                                                                   String reference, Duration deadline) {
                return resolveCredential(() -> Objects.requireNonNull(context, "deliveredContext").identity(),
                        reference, deadline);
            }
        };
    }

    @Override
    public OutboundHttpService outboundHttp() {
        if (!capabilities.contains(NodePackageCapability.OUTBOUND_HTTP)) {
            return unavailable.outboundHttp();
        }
        return new OutboundHttpService() {
            @Override public OutboundCall<OutboundHttpResponse> execute(NodeMessage message,
                                                                        OutboundHttpRequest request) {
                return executeHttp(messageIdentity(message), request);
            }

            @Override public OutboundCall<OutboundHttpResponse> execute(InboundSourceContext context,
                                                                        OutboundHttpRequest request) {
                return executeHttp(() -> Objects.requireNonNull(context, "deliveredContext").identity(), request);
            }
        };
    }

    @Override
    public OutboundWebSocketService outboundWebSocket() {
        if (!capabilities.contains(NodePackageCapability.OUTBOUND_WEBSOCKET)) {
            return unavailable.outboundWebSocket();
        }
        return new OutboundWebSocketService() {
            @Override public OutboundCall<OutboundWebSocketSession> open(NodeMessage message,
                                                                         OutboundWebSocketRequest request,
                                                                         OutboundWebSocketListener listener) {
                return openWebSocket(messageIdentity(message), request, listener);
            }

            @Override public OutboundCall<OutboundWebSocketSession> open(InboundSourceContext context,
                                                                         OutboundWebSocketRequest request,
                                                                         OutboundWebSocketListener listener) {
                return openWebSocket(() -> Objects.requireNonNull(context, "deliveredContext").identity(),
                        request, listener);
            }
        };
    }

    @Override
    public ToolCallAuthorizationService toolAuthorization() {
        if (!capabilities.contains(NodePackageCapability.TOOL_AUTHORIZATION)) {
            return unavailable.toolAuthorization();
        }
        return this::authorizeToolCall;
    }

    @Override
    public ai.ravenroot.api.node.service.AgentResourceService agentResources() {
        if (!capabilities.contains(NodePackageCapability.AGENT_RESOURCES)
                || agentResources == null) {
            return unavailable.agentResources();
        }
        return agentResources;
    }

    /**
     * Parses, canonicalizes, decides and audits one model-requested call before its effect can run.
     * The package receives the canonical bytes, not the unchecked bytes the model emitted.
     */
    private ToolCallAuthorization authorizeToolCall(NodeMessage message, String rawTool,
                                                     byte[] rawArguments) {
        NodeMessage delivered = Objects.requireNonNull(message, "deliveredMessage");
        String tool = safeToolName(rawTool);
        UUID callId = UUID.randomUUID();
        if ("invalid-tool".equals(tool)) {
            auditAndChargeDenied(delivered, callId, tool, "", "TOOL_INVALID");
            return new ManagedToolCall(callId, ToolCallAuthorization.Disposition.DENY, "", new byte[0],
                    delivered, tool, false);
        }
        PayloadValue.MapValue arguments;
        byte[] canonical;
        try {
            byte[] supplied = rawArguments == null ? new byte[0] : rawArguments;
            if (blankJson(supplied)) {
                supplied = "{}".getBytes(StandardCharsets.UTF_8);
            }
            PayloadValue parsed = PayloadJson.read(supplied, TOOL_ARGUMENT_LIMITS);
            if (!(parsed instanceof PayloadValue.MapValue object)) {
                throw new IllegalArgumentException("tool arguments are not an object");
            }
            arguments = object;
            canonical = PayloadJson.write(arguments).getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException invalid) {
            auditAndChargeDenied(delivered, callId, tool, "", "ARGUMENTS_INVALID");
            return new ManagedToolCall(callId, ToolCallAuthorization.Disposition.DENY, "", new byte[0],
                    delivered, tool, false);
        }

        String digest = digest(canonical);
        ToolDecision decision;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> policyArguments = (Map<String, Object>) arguments.toJava();
            decision = Objects.requireNonNull(toolPolicy.evaluate(new ToolInvocation(delivered.security(),
                    delivered.processInstanceId(), delivered.nodeId(), tool, policyArguments)),
                    "toolPolicy decision");
        } catch (RuntimeException policyFailure) {
            auditAndChargeDenied(delivered, callId, tool, digest, "POLICY_UNAVAILABLE");
            return new ManagedToolCall(callId, ToolCallAuthorization.Disposition.DENY, digest, canonical,
                    delivered, tool, false);
        }

        ToolCallAuthorization.Disposition disposition = switch (decision.disposition()) {
            case ALLOW -> ToolCallAuthorization.Disposition.ALLOW;
            case DENY -> ToolCallAuthorization.Disposition.DENY;
            case REQUIRE_APPROVAL -> ToolCallAuthorization.Disposition.REQUIRE_APPROVAL;
        };
        ToolCallAuditEvent.Disposition auditDisposition = switch (disposition) {
            case ALLOW -> ToolCallAuditEvent.Disposition.ATTEMPT;
            case DENY -> ToolCallAuditEvent.Disposition.DENIED;
            case REQUIRE_APPROVAL -> ToolCallAuditEvent.Disposition.APPROVAL_REQUIRED;
        };
        String reason = switch (disposition) {
            case ALLOW -> "POLICY_ALLOWED";
            case DENY -> "POLICY_DENIED";
            case REQUIRE_APPROVAL -> "APPROVAL_REQUIRED";
        };
        AgentAuthorityBudgetService.ToolReservation reservation = null;
        if (disposition == ToolCallAuthorization.Disposition.DENY) {
            auditAndChargeDenied(delivered, callId, tool, digest, reason);
        } else {
            recordTool(delivered, callId, tool, digest, auditDisposition, reason);
            if (disposition == ToolCallAuthorization.Disposition.ALLOW) {
                try {
                    reservation = reserveTool(delivered, callId);
                } catch (RuntimeException accountingRefusal) {
                    try {
                        recordTool(delivered, callId, tool, digest,
                                ToolCallAuditEvent.Disposition.FAILED, "BUDGET_REFUSED");
                    } catch (RuntimeException auditFailure) {
                        accountingRefusal.addSuppressed(auditFailure);
                    }
                    var terminal = new NodePackageServiceException(
                            NodePackageServiceException.Reason.BUDGET_EXHAUSTED);
                    terminal.addSuppressed(accountingRefusal);
                    throw terminal;
                }
            }
        }
        return new ManagedToolCall(callId, disposition, digest, canonical, delivered, tool,
                disposition == ToolCallAuthorization.Disposition.ALLOW, reservation);
    }

    private AgentAuthorityBudgetService.ToolReservation reserveTool(NodeMessage message, UUID callId) {
        if (!capabilities.contains(NodePackageCapability.AGENT_RESOURCES)
                || agentAuthorityBudgets == null) return null;
        return agentAuthorityBudgets.reserveDirectTool(message, callId);
    }

    private void chargeDeniedTool(NodeMessage message, UUID callId) {
        if (capabilities.contains(NodePackageCapability.AGENT_RESOURCES)
                && agentAuthorityBudgets != null) {
            agentAuthorityBudgets.chargeDeniedTool(message, callId);
        }
    }

    private void auditAndChargeDenied(NodeMessage message, UUID callId, String tool,
                                      String digest, String reason) {
        RuntimeException failure = null;
        try {
            recordTool(message, callId, tool, digest, ToolCallAuditEvent.Disposition.DENIED, reason);
        } catch (RuntimeException auditFailure) {
            failure = auditFailure;
        }
        try {
            chargeDeniedTool(message, callId);
        } catch (RuntimeException accountingFailure) {
            if (failure == null) failure = accountingFailure;
            else failure.addSuppressed(accountingFailure);
        }
        if (failure != null) {
            throw new NodePackageServiceException(NodePackageServiceException.Reason.SERVICE_UNAVAILABLE);
        }
    }

    private void recordTool(NodeMessage message, UUID callId, String tool, String digest,
                            ToolCallAuditEvent.Disposition disposition, String reason) {
        SecurityContext identity = message.security();
        toolAuditSink.record(new ToolCallAuditEvent(clock.instant(), identity.requestId(), identity.tenantId(),
                identity.qualifiedIdentity(), message.processInstanceId(), message.traversalId(),
                message.invocationId(), message.attemptId(), callId, tool, digest, disposition, reason));
    }

    private static String safeToolName(String raw) {
        String tool = raw == null ? "" : raw.strip();
        if (!tool.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")) {
            return "invalid-tool";
        }
        return tool;
    }

    private static boolean blankJson(byte[] document) {
        for (byte value : document) {
            if (value != ' ' && value != '\t' && value != '\n' && value != '\r') {
                return false;
            }
        }
        return true;
    }

    private static String digest(byte[] canonical) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(canonical);
            return "sha256:" + java.util.HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required for tool authorization", impossible);
        }
    }

    private final class ManagedToolCall implements ToolCallAuthorization {
        private final UUID callId;
        private final Disposition disposition;
        private final String argumentsDigest;
        private final byte[] canonicalArguments;
        private final NodeMessage message;
        private final String tool;
        private final boolean terminalExpected;
        private final AgentAuthorityBudgetService.ToolReservation budgetReservation;
        private final AtomicBoolean completed = new AtomicBoolean();
        private final UUID approvalId = UUID.randomUUID();

        private ManagedToolCall(UUID callId, Disposition disposition, String argumentsDigest,
                                byte[] canonicalArguments, NodeMessage message, String tool,
                                boolean terminalExpected) {
            this(callId, disposition, argumentsDigest, canonicalArguments, message, tool,
                    terminalExpected, null);
        }

        private ManagedToolCall(UUID callId, Disposition disposition, String argumentsDigest,
                                byte[] canonicalArguments, NodeMessage message, String tool,
                                boolean terminalExpected,
                                AgentAuthorityBudgetService.ToolReservation budgetReservation) {
            this.callId = callId;
            this.disposition = disposition;
            this.argumentsDigest = argumentsDigest;
            this.canonicalArguments = canonicalArguments.clone();
            this.message = message;
            this.tool = tool;
            this.terminalExpected = terminalExpected;
            this.budgetReservation = budgetReservation;
        }

        @Override public UUID callId() { return callId; }
        @Override public Disposition disposition() { return disposition; }
        @Override public String argumentsDigest() { return argumentsDigest; }
        @Override public byte[] canonicalArguments() { return canonicalArguments.clone(); }

        @Override
        public RuntimeException suspend(int continuationVersion, byte[] continuation) {
            if (continuationVersion < 1) {
                throw new IllegalArgumentException("continuationVersion must be positive");
            }
            byte[] checkpoint = Objects.requireNonNull(continuation, "continuation").clone();
            if (disposition != Disposition.REQUIRE_APPROVAL
                    || toolApprovalService == null || toolApprovalSettings == null) {
                return ToolCallAuthorization.super.suspend(continuationVersion, checkpoint);
            }
            ToolApprovalResult result = toolApprovalService.suspend(message, approvalId, callId, tool,
                    canonicalArguments,
                    argumentsDigest, toolApprovalSettings, continuationVersion, checkpoint);
            if (result.code() != ToolApprovalResult.Code.CREATED
                    && result.code() != ToolApprovalResult.Code.ALREADY_APPLIED) {
                return new NodePackageServiceException(NodePackageServiceException.Reason.SERVICE_UNAVAILABLE);
            }
            return new DurableToolApprovalSuspension(approvalId);
        }

        @Override
        public void complete(Outcome outcome) {
            Objects.requireNonNull(outcome, "outcome");
            if (!terminalExpected || !completed.compareAndSet(false, true)) return;
            boolean failed = false;
            try {
                recordTool(message, callId, tool, argumentsDigest,
                        outcome == Outcome.SUCCEEDED ? ToolCallAuditEvent.Disposition.SUCCEEDED
                                : ToolCallAuditEvent.Disposition.FAILED,
                        outcome == Outcome.SUCCEEDED ? "EFFECT_SUCCEEDED" : "EFFECT_FAILED");
            } catch (RuntimeException auditFailure) {
                failed = true;
            }
            try {
                if (budgetReservation != null) budgetReservation.settle();
            } catch (RuntimeException accountingFailure) {
                failed = true;
            }
            if (failed) {
                throw new NodePackageServiceException(
                        NodePackageServiceException.Reason.EFFECT_OUTCOME_INDETERMINATE);
            }
        }
    }

    private OutboundCall<CredentialLease> resolveCredential(IdentitySource identity, String reference,
                                                             Duration requestedDeadline) {
        String tenant;
        String safeReference;
        Duration deadline;
        NodePackageEgressCapacityProfile.Limits capacity;
        try {
            tenant = tenant(identity.identity());
            safeReference = safeReference(reference);
            capacity = capacityFor(identity);
            deadline = deadline(requestedDeadline, capacity.maximumDeadline());
        } catch (NodePackageServiceException refused) {
            return OutboundCall.failed(refused);
        } catch (IllegalArgumentException refused) {
            return failed(NodePackageServiceException.Reason.DEADLINE_EXCEEDED);
        } catch (RuntimeException refused) {
            return failed(NodePackageServiceException.Reason.CREDENTIAL_UNAVAILABLE);
        }
        AdmissionController.Lease lease = admission.tryAcquire(tenant,
                capacity.maximumConcurrentOperations(), capacity.maximumConcurrentPerTenant());
        if (lease == null) return failed(NodePackageServiceException.Reason.ADMISSION_REFUSED);
        return submit(deadline, lease, () -> {
            try (SecretValue secret = credentials.resolve(packageId, tenant, safeReference)
                    .orElseThrow(() -> refusal(NodePackageServiceException.Reason.CREDENTIAL_UNAVAILABLE))) {
                char[] copied = secret.copy();
                try {
                    return new CredentialLease(copied);
                } finally {
                    java.util.Arrays.fill(copied, '\0');
                }
            }
        });
    }

    private OutboundCall<OutboundHttpResponse> executeHttp(IdentitySource identity,
                                                           OutboundHttpRequest request) {
        String tenant;
        URI destination;
        try {
            tenant = tenant(identity.identity());
            Objects.requireNonNull(request, "request");
            destination = request.destination();
            requireDestination(destination, false);
        } catch (NodePackageServiceException refused) {
            return OutboundCall.failed(refused);
        } catch (RuntimeException refused) {
            return failed(NodePackageServiceException.Reason.PROTOCOL_REFUSED);
        }
        String method = request.method().toUpperCase(Locale.ROOT);
        if (!policy.httpMethods().contains(method)) {
            return failed(NodePackageServiceException.Reason.PROTOCOL_REFUSED);
        }
        Map<String, List<String>> headers;
        byte[] body = request.body();
        Duration deadline;
        ExternalIoLimits limits;
        NodePackageEgressCapacityProfile.Limits capacity;
        try {
            capacity = capacityFor(identity);
            headers = validateHeaders(request.headers());
            if (request.credential().isPresent() && request.signing().isPresent()) {
                return failed(NodePackageServiceException.Reason.PROTOCOL_REFUSED);
            }
            request.signing().ifPresent(signing -> {
                AwsSigV4Signer.requireTransportStableTarget(destination);
                requireSigningGrant(signing, destination);
            });
            ExternalIoLimits authority = new ExternalIoLimits(capacity.maximumRequestBytes(),
                    capacity.maximumResponseBytes(), capacity.maximumResponseBytes(),
                    capacity.maximumResponseBytes(), request.limits().maximumDecompressionRatio(),
                    capacity.maximumDeadline(),
                    Duration.ofSeconds(2), Set.of(), Set.of("identity", "gzip"));
            limits = request.limits().intersect(authority);
            if (body.length > limits.maximumRequestBytes()) {
                return failed(NodePackageServiceException.Reason.REQUEST_TOO_LARGE);
            }
            deadline = deadline(request.deadline(), capacity.maximumDeadline());
            if (limits.maximumDuration().compareTo(deadline) < 0) deadline = limits.maximumDuration();
        } catch (NodePackageServiceException refused) {
            return OutboundCall.failed(refused);
        } catch (IllegalArgumentException invalid) {
            return failed(NodePackageServiceException.Reason.PROTOCOL_REFUSED);
        }
        AdmissionController.Lease lease = admission.tryAcquire(tenant,
                capacity.maximumConcurrentOperations(), capacity.maximumConcurrentPerTenant());
        if (lease == null) return failed(NodePackageServiceException.Reason.ADMISSION_REFUSED);
        Duration effectiveDeadline = deadline;
        return submit(effectiveDeadline, lease, () -> {
            requireResolvable(destination);
            Map<String, List<String>> outgoingHeaders = request.signing()
                    .map(signing -> signRequest(tenant, destination, method, headers, body, signing))
                    .orElse(headers);
            HttpRequest.Builder builder = HttpRequest.newBuilder(destination).timeout(effectiveDeadline);
            outgoingHeaders.forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
            request.credential().ifPresent(binding -> injectCredential(builder, tenant, destination, binding));
            builder.method(method, body.length == 0
                    ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            HttpResponse<byte[]> response;
            try {
                response = client().send(builder.build(),
                        BoundedBodyHandlers.withLimits(limits, request.representationPolicy()));
            } catch (Throwable failure) {
                throw mapFailure(failure);
            }
            return new OutboundHttpResponse(response.statusCode(), allowedResponseHeaders(response),
                    response.body(), limits.maximumOutputBytes());
        });
    }

    private OutboundCall<OutboundWebSocketSession> openWebSocket(IdentitySource identity,
                                                                 OutboundWebSocketRequest request,
                                                                 OutboundWebSocketListener listener) {
        String tenant;
        URI destination;
        try {
            tenant = tenant(identity.identity());
            Objects.requireNonNull(request, "request");
            destination = request.destination();
            requireDestination(destination, true);
        } catch (NodePackageServiceException refused) {
            return OutboundCall.failed(refused);
        } catch (RuntimeException refused) {
            return failed(NodePackageServiceException.Reason.PROTOCOL_REFUSED);
        }
        OutboundWebSocketListener safeListener = listener == null ? new OutboundWebSocketListener() { } : listener;
        Map<String, List<String>> headers;
        Duration deadline;
        WebSocketLimits limits;
        NodePackageEgressCapacityProfile.Limits capacity;
        try {
            capacity = capacityFor(identity);
            headers = validateHeaders(request.headers());
            if (request.subprotocols().size() > 16
                    || request.subprotocols().stream().distinct().count() != request.subprotocols().size()
                    || !policy.webSocketSubprotocols().containsAll(request.subprotocols())) {
                return failed(NodePackageServiceException.Reason.PROTOCOL_REFUSED);
            }
            deadline = deadline(request.deadline(), capacity.maximumDeadline());
            // Re-validated here, not only in the request type: this is the bridge's own guarantee
            // that no session is ever opened with a limit it would not enforce, and it runs before
            // admission and before the handshake so an out-of-range limit costs no transport.
            limits = WebSocketLimits.of(capacity, request.maximumMessageBytes(), request.maximumFragments());
        } catch (IllegalArgumentException invalid) {
            return failed(NodePackageServiceException.Reason.PROTOCOL_REFUSED);
        }
        AdmissionController.Lease admissionLease = admission.tryAcquire(tenant,
                capacity.maximumConcurrentOperations(), capacity.maximumConcurrentPerTenant());
        if (admissionLease == null) return failed(NodePackageServiceException.Reason.ADMISSION_REFUSED);

        ManagedCall<OutboundWebSocketSession> call = new ManagedCall<>();
        AtomicBoolean transferred = new AtomicBoolean();
        Runnable release = releaseOnce(admissionLease);
        WebSocketBridge bridge = new WebSocketBridge(safeListener, limits, release);
        Thread worker = Thread.ofVirtual().name("ravenroot-package-websocket-open").unstarted(() -> {
            CompletableFuture<WebSocket> opening = null;
            try {
                requireResolvable(destination);
                WebSocket.Builder builder = client().newWebSocketBuilder().connectTimeout(deadline);
                headers.forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
                request.credential().ifPresent(binding -> injectCredential(builder, tenant, destination, binding));
                if (!request.subprotocols().isEmpty()) {
                    builder.subprotocols(request.subprotocols().get(0),
                            request.subprotocols().subList(1, request.subprotocols().size()).toArray(String[]::new));
                }
                opening = builder.buildAsync(destination, bridge);
                call.transportFuture(opening);
                WebSocket socket = opening.get(deadline.toNanos(), TimeUnit.NANOSECONDS);
                ManagedWebSocketSession session = new ManagedWebSocketSession(socket, bridge, capacity,
                        limits, release);
                bridge.attach(session);
                if (bridge.isTerminal()) {
                    session.terminal();
                    call.fail(refusal(NodePackageServiceException.Reason.TRANSPORT_FAILED));
                } else if (call.complete(session)) {
                    transferred.set(true);
                } else {
                    session.cancel();
                }
            } catch (Throwable failure) {
                call.fail(mapFailure(failure));
            } finally {
                if (!transferred.get()) release.run();
            }
        });
        call.worker(worker);
        call.deadline(DEADLINES.schedule(call::expire, deadline.toNanos(), TimeUnit.NANOSECONDS));
        worker.start();
        return call;
    }

    private void injectCredential(HttpRequest.Builder builder, String tenant, URI destination,
                                  OutboundCredentialBinding binding) {
        NodePackageEgressPolicy.CredentialPlacement placement;
        try {
            placement = policy.requireCredentialPlacement(binding.bindingId(), destination);
        } catch (IllegalArgumentException forbidden) {
            throw refusal(NodePackageServiceException.Reason.DESTINATION_FORBIDDEN);
        }
        try (SecretValue secret = credentials.resolve(packageId, tenant, binding.reference())
                .orElseThrow(() -> refusal(NodePackageServiceException.Reason.CREDENTIAL_UNAVAILABLE))) {
            char[] copied = secret.copy();
            try {
                builder.header(placement.headerName(), placement.prefix() + new String(copied));
            } finally {
                java.util.Arrays.fill(copied, '\0');
            }
        }
    }

    private NodePackageEgressPolicy.AwsSigV4SigningGrant requireSigningGrant(OutboundHttpSigning signing,
                                                                             URI destination) {
        try {
            return policy.requireAwsSigV4SigningGrant(signing.bindingId(), destination);
        } catch (IllegalArgumentException forbidden) {
            throw refusal(NodePackageServiceException.Reason.DESTINATION_FORBIDDEN);
        }
    }

    private Map<String, List<String>> signRequest(String tenant, URI destination, String method,
                                                   Map<String, List<String>> headers, byte[] body,
                                                   OutboundHttpSigning signing) {
        NodePackageEgressPolicy.AwsSigV4SigningGrant grant = requireSigningGrant(signing, destination);
        try (SecretValue secret = credentials.resolve(packageId, tenant, grant.credentialReference())
                .orElseThrow(() -> refusal(NodePackageServiceException.Reason.CREDENTIAL_UNAVAILABLE))) {
            char[] copied = secret.copy();
            try {
                return AwsSigV4Signer.sign(method, destination, headers, body, clock.instant(), copied,
                        grant.region(), grant.service()).headers();
            } catch (IllegalArgumentException invalidCredential) {
                throw refusal(NodePackageServiceException.Reason.CREDENTIAL_UNAVAILABLE);
            } finally {
                java.util.Arrays.fill(copied, '\0');
            }
        }
    }

    private void injectCredential(WebSocket.Builder builder, String tenant, URI destination,
                                  OutboundCredentialBinding binding) {
        NodePackageEgressPolicy.CredentialPlacement placement;
        try {
            placement = policy.requireCredentialPlacement(binding.bindingId(), destination);
        } catch (IllegalArgumentException forbidden) {
            throw refusal(NodePackageServiceException.Reason.DESTINATION_FORBIDDEN);
        }
        try (SecretValue secret = credentials.resolve(packageId, tenant, binding.reference())
                .orElseThrow(() -> refusal(NodePackageServiceException.Reason.CREDENTIAL_UNAVAILABLE))) {
            char[] copied = secret.copy();
            try {
                builder.header(placement.headerName(), placement.prefix() + new String(copied));
            } finally {
                java.util.Arrays.fill(copied, '\0');
            }
        }
    }

    private Map<String, List<String>> validateHeaders(Map<String, List<String>> submitted) {
        if (submitted.size() > MAX_HEADER_NAMES) throw new IllegalArgumentException("headers");
        int valuesSeen = 0;
        int characters = 0;
        Map<String, List<String>> safe = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : submitted.entrySet()) {
            String name = entry.getKey();
            String lower = name.toLowerCase(Locale.ROOT);
            NodePackageEgressPolicy.requireNoCrLf(name, "header");
            if (!policy.requestHeaders().contains(lower) || FORBIDDEN_CALLER_HEADERS.contains(lower)) {
                throw new IllegalArgumentException("header");
            }
            List<String> values = new ArrayList<>();
            for (String value : entry.getValue()) {
                if (value == null) throw new IllegalArgumentException("header");
                NodePackageEgressPolicy.requireNoCrLf(value, "header");
                if (++valuesSeen > MAX_HEADER_VALUES || (characters += name.length() + value.length())
                        > MAX_HEADER_CHARACTERS) {
                    throw new IllegalArgumentException("headers");
                }
                values.add(value);
            }
            if (safe.putIfAbsent(lower, List.copyOf(values)) != null) {
                throw new IllegalArgumentException("duplicate header");
            }
        }
        return Map.copyOf(safe);
    }

    private Map<String, List<String>> allowedResponseHeaders(HttpResponse<?> response) {
        Map<String, List<String>> allowed = new LinkedHashMap<>();
        int valuesSeen = 0;
        int characters = 0;
        for (Map.Entry<String, List<String>> entry : response.headers().map().entrySet()) {
            String name = entry.getKey();
            if (!policy.responseHeaders().contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (allowed.size() >= MAX_HEADER_NAMES) {
                throw refusal(NodePackageServiceException.Reason.RESPONSE_TOO_LARGE);
            }
            List<String> values = entry.getValue();
            for (String value : values) {
                if (++valuesSeen > MAX_HEADER_VALUES
                        || (characters += name.length() + value.length()) > MAX_HEADER_CHARACTERS) {
                    throw refusal(NodePackageServiceException.Reason.RESPONSE_TOO_LARGE);
                }
            }
            allowed.put(name, List.copyOf(values));
        }
        return Map.copyOf(allowed);
    }

    private void requireDestination(URI destination, boolean webSocket) {
        try {
            policy.requireAllowed(destination, webSocket);
            requireLiteralNotReserved(destination.getHost());
        } catch (RuntimeException refused) {
            throw refusal(NodePackageServiceException.Reason.DESTINATION_FORBIDDEN);
        }
    }

    private static void requireLiteralNotReserved(String host) {
        String candidate = host != null && host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : String.valueOf(host);
        if (!looksLikeLiteral(candidate)) return;
        try {
            InetAddress address = InetAddress.getByName(candidate);
            if (!EgressAddressGuard.policy().permits(candidate, address)) {
                throw new SecurityException("reserved " + ReservedNetwork.of(address));
            }
        } catch (UnknownHostException ignored) {
            throw new SecurityException("invalid literal");
        }
    }

    private static boolean looksLikeLiteral(String host) {
        if (host.isEmpty()) return false;
        if (host.indexOf(':') >= 0) return true;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if ((c < '0' || c > '9') && c != '.') return false;
        }
        return true;
    }

    /**
     * Runs the JVM-wide rebinding guard before credential resolution and transport construction.
     * The client resolves again when connecting; that second resolution is guarded by the same
     * provider, so a change to reserved space is still refused at the socket boundary.
     */
    private static void requireResolvable(URI destination) {
        String host = destination.getHost();
        if (looksLikeLiteral(host)) return;
        try {
            InetAddress.getAllByName(host);
        } catch (UnknownHostException refused) {
            throw refusal(NodePackageServiceException.Reason.RESOLUTION_REFUSED);
        }
    }

    private <T> OutboundCall<T> submit(Duration deadline, AdmissionController.Lease lease,
                                       ThrowingSupplier<T> work) {
        ManagedCall<T> call = new ManagedCall<>();
        Thread worker = Thread.ofVirtual().name("ravenroot-node-package-service").unstarted(() -> {
            try {
                T value = work.get();
                if (!call.complete(value) && value instanceof AutoCloseable closeable) {
                    try {
                        closeable.close();
                    } catch (Exception ignored) {
                        // The cancelled caller cannot observe cleanup; sensitive leases still get
                        // their best-effort erase without replacing the already-terminal outcome.
                    }
                }
            } catch (Throwable failure) {
                call.fail(mapFailure(failure));
            } finally {
                lease.close();
            }
        });
        call.worker(worker);
        call.deadline(DEADLINES.schedule(call::expire, deadline.toNanos(), TimeUnit.NANOSECONDS));
        worker.start();
        return call;
    }

    private static NodePackageServiceException mapFailure(Throwable failure) {
        Throwable root = failure;
        while ((root instanceof ExecutionException || root instanceof java.util.concurrent.CompletionException)
                && root.getCause() != null) root = root.getCause();
        if (findCause(root, NodePackageServiceException.class) instanceof NodePackageServiceException typed) {
            return typed;
        }
        if (findCause(root, BoundedBodyHandlers.ResponseTooLargeException.class) != null) {
            return refusal(NodePackageServiceException.Reason.RESPONSE_TOO_LARGE);
        }
        if (findCause(root, BoundedBodyHandlers.ResponseMediaTypeException.class) != null
                || findCause(root, BoundedBodyHandlers.ResponseEncodingException.class) != null) {
            return refusal(NodePackageServiceException.Reason.PROTOCOL_REFUSED);
        }
        if (root instanceof HttpTimeoutException || root instanceof java.util.concurrent.TimeoutException) {
            return refusal(NodePackageServiceException.Reason.DEADLINE_EXCEEDED);
        }
        if (findCause(root, SSLException.class) != null) {
            return refusal(NodePackageServiceException.Reason.TLS_REFUSED);
        }
        if (findCause(root, UnknownHostException.class) != null) {
            return refusal(NodePackageServiceException.Reason.RESOLUTION_REFUSED);
        }
        if (root instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return refusal(NodePackageServiceException.Reason.CANCELLED);
        }
        if (root instanceof IOException) return refusal(NodePackageServiceException.Reason.TRANSPORT_FAILED);
        return refusal(NodePackageServiceException.Reason.TRANSPORT_FAILED);
    }

    private static Throwable findCause(Throwable failure, Class<? extends Throwable> type) {
        Throwable cursor = failure;
        for (int depth = 0; cursor != null && depth < 16; depth++, cursor = cursor.getCause()) {
            if (type.isInstance(cursor)) return cursor;
        }
        return null;
    }

    private static NodePackageServiceException refusal(NodePackageServiceException.Reason reason) {
        return new NodePackageServiceException(reason);
    }

    private static <T> OutboundCall<T> failed(NodePackageServiceException.Reason reason) {
        return OutboundCall.failed(refusal(reason));
    }

    private static String tenant(SecurityContext identity) {
        String tenant = Objects.requireNonNull(identity, "identity").tenantId();
        if (tenant == null || tenant.isBlank() || tenant.length() > 256) {
            throw refusal(NodePackageServiceException.Reason.CREDENTIAL_UNAVAILABLE);
        }
        return tenant;
    }

    private static String safeReference(String reference) {
        String safe = reference == null ? "" : reference.strip();
        if (safe.isEmpty() || safe.length() > 256) {
            throw refusal(NodePackageServiceException.Reason.CREDENTIAL_UNAVAILABLE);
        }
        for (int i = 0; i < safe.length(); i++) {
            if (Character.isISOControl(safe.charAt(i))) {
                throw refusal(NodePackageServiceException.Reason.CREDENTIAL_UNAVAILABLE);
            }
        }
        return safe;
    }

    private static String safePackageId(String packageId) {
        String safe = packageId == null ? "" : packageId.strip();
        if (safe.isEmpty() || safe.length() > 200
                || !safe.matches("[a-z0-9](?:[a-z0-9._-]*[a-z0-9])?")) {
            throw new IllegalArgumentException("invalid package id");
        }
        return safe;
    }

    private static Runnable releaseOnce(AdmissionController.Lease lease) {
        AtomicBoolean released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) lease.close();
        };
    }

    /** Lazily creates transport state only after invocation admission, never during composition. */
    private HttpClient client() {
        HttpClient existing = client;
        if (existing != null) return existing;
        synchronized (this) {
            if (client == null) client = Objects.requireNonNull(clientFactory.get(), "clientFactory result");
            return client;
        }
    }

    public static final class Builder {
        private final String packageId;
        private final NodePackageEgressPolicy policy;
        private final TenantCredentialResolver credentials;
        private final Set<NodePackageCapability> capabilities = java.util.EnumSet.noneOf(NodePackageCapability.class);
        private java.util.function.Supplier<HttpClient> clientFactory = EgressHttpClients::create;
        private Clock clock = Clock.systemUTC();
        private ToolPolicy toolPolicy = ToolPolicy.denyAll();
        private ToolCallAuditSink toolAuditSink = ToolCallAuditSink.discarding();
        private ToolApprovalService toolApprovalService;
        private ToolApprovalSettings toolApprovalSettings;
        private ai.ravenroot.api.node.service.AgentResourceService agentResources;
        private AgentAuthorityBudgetService agentAuthorityBudgets;

        private Builder(String packageId, NodePackageEgressPolicy policy, TenantCredentialResolver credentials) {
            this.packageId = packageId;
            this.policy = policy;
            this.credentials = credentials;
        }

        public Builder grant(NodePackageCapability capability) {
            capabilities.add(Objects.requireNonNull(capability, "capability"));
            return this;
        }

        /** Installs the inseparable decision-and-audit pair used by tool authorization. */
        public Builder toolAuthorization(ToolPolicy policy, ToolCallAuditSink auditSink) {
            this.toolPolicy = Objects.requireNonNull(policy, "policy");
            this.toolAuditSink = Objects.requireNonNull(auditSink, "auditSink");
            return this;
        }

        /** Enables durable suspension for {@code REQUIRE_APPROVAL} decisions. */
        public Builder durableToolApprovals(ToolApprovalService service, ToolApprovalSettings settings) {
            this.toolApprovalService = Objects.requireNonNull(service, "service");
            this.toolApprovalSettings = Objects.requireNonNull(settings, "settings");
            return this;
        }

        /** Installs mandatory finite agent accounting for packages explicitly granted it. */
        public Builder agentAuthorityBudgets(AgentAuthorityBudgetService service) {
            this.agentAuthorityBudgets = Objects.requireNonNull(service, "service");
            this.agentResources = service;
            return this;
        }

        /** Installs an invocation mediator; primarily useful for constrained embedding adapters. */
        public Builder agentResources(ai.ravenroot.api.node.service.AgentResourceService service) {
            this.agentResources = Objects.requireNonNull(service, "service");
            return this;
        }

        Builder clientFactory(java.util.function.Supplier<HttpClient> clientFactory) {
            this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
            return this;
        }

        Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        public ManagedNodePackageServices build() { return new ManagedNodePackageServices(this); }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> { T get() throws Exception; }

    private IdentitySource messageIdentity(NodeMessage message) {
        NodeMessage delivered = Objects.requireNonNull(message, "deliveredMessage");
        return new MessageIdentity(delivered);
    }

    private NodePackageEgressCapacityProfile.Limits capacityFor(IdentitySource identity) {
        ai.ravenroot.api.persistence.ResolvedOperationalPolicy pinned = identity instanceof MessageIdentity message
                ? trustedPolicy(message.message()) : null;
        if (pinned == null) return currentCapacity();
        return pinned.nodePackages().stream()
                .filter(entry -> packageId.equals(entry.packageId())).findFirst()
                .flatMap(entry -> entry.capacity().limits())
                .orElseThrow(() -> refusal(NodePackageServiceException.Reason.SERVICE_UNAVAILABLE));
    }

    private ai.ravenroot.api.persistence.ResolvedOperationalPolicy trustedPolicy(NodeMessage message) {
        var resolver = executionPolicies.get();
        return resolver == null ? null : resolver.apply(message);
    }

    private NodePackageEgressCapacityProfile.Limits currentCapacity() {
        return egressCapacityProfile().orElseThrow().limits().orElseThrow();
    }

    private static NodePackageEgressCapacityProfile.Limits capacityOf(NodePackageEgressPolicy policy) {
        return NodePackageEgressCapacityProfile.bounded(policy.maximumRequestBytes(),
                policy.maximumResponseBytes(), policy.maximumWebSocketMessageBytes(),
                policy.maximumWebSocketFragments(), policy.maximumConcurrentOperations(),
                policy.maximumConcurrentPerTenant(), policy.maximumQueuedWebSocketSends(),
                policy.maximumDeadline(), policy.maximumWebSocketLifetime(),
                policy.maximumWebSocketIdle()).limits().orElseThrow();
    }

    private static Duration deadline(Duration requested, Duration maximum) {
        if (requested == null || requested.isZero() || requested.isNegative()) {
            throw new IllegalArgumentException("deadline must be positive");
        }
        return requested.compareTo(maximum) > 0 ? maximum : requested;
    }

    @FunctionalInterface
    private interface IdentitySource {
        SecurityContext identity();
    }

    private record MessageIdentity(NodeMessage message) implements IdentitySource {
        private MessageIdentity { Objects.requireNonNull(message, "message"); }
        @Override public SecurityContext identity() { return message.security(); }
    }

    private static final class ManagedCall<T> implements OutboundCall<T> {
        private enum State { ACTIVE, COMPLETED, CANCELLED, EXPIRED }
        private final CompletableFuture<T> result = new CompletableFuture<>();
        private final AtomicReference<State> state = new AtomicReference<>(State.ACTIVE);
        private volatile Thread worker;
        private volatile Future<?> transportFuture;
        private volatile ScheduledFuture<?> deadline;

        @Override public CompletionStage<T> completion() { return result.minimalCompletionStage(); }

        @Override
        public boolean cancel() {
            if (!state.compareAndSet(State.ACTIVE, State.CANCELLED)) return false;
            cancelWork();
            result.completeExceptionally(refusal(NodePackageServiceException.Reason.CANCELLED));
            return true;
        }

        void expire() {
            if (!state.compareAndSet(State.ACTIVE, State.EXPIRED)) return;
            cancelWork();
            result.completeExceptionally(refusal(NodePackageServiceException.Reason.DEADLINE_EXCEEDED));
        }

        boolean complete(T value) {
            if (!state.compareAndSet(State.ACTIVE, State.COMPLETED)) return false;
            cancelDeadline();
            result.complete(value);
            return true;
        }

        void fail(Throwable failure) {
            if (!state.compareAndSet(State.ACTIVE, State.COMPLETED)) return;
            cancelDeadline();
            result.completeExceptionally(failure);
        }

        void worker(Thread value) { worker = value; }
        void transportFuture(Future<?> value) {
            transportFuture = value;
            if (state.get() != State.ACTIVE) {
                value.cancel(true);
            }
        }
        void deadline(ScheduledFuture<?> value) { deadline = value; }

        private void cancelWork() {
            Future<?> transport = transportFuture;
            if (transport != null) transport.cancel(true);
            Thread thread = worker;
            if (thread != null) thread.interrupt();
            cancelDeadline();
        }

        private void cancelDeadline() {
            ScheduledFuture<?> task = deadline;
            if (task != null) task.cancel(false);
        }
    }

    private static final class AdmissionController {
        private int packageActive;
        private final Map<String, Integer> tenantActive = new ConcurrentHashMap<>();

        AdmissionController(int packageMaximum, int tenantMaximum) {
            // Constructor values remain validation of the startup policy; each request supplies the
            // immutable values pinned for its own execution.
            if (packageMaximum < 1 || tenantMaximum < 1 || tenantMaximum > packageMaximum) {
                throw new IllegalArgumentException("invalid admission limits");
            }
        }

        synchronized Lease tryAcquire(String tenant, int packageMaximum, int tenantMaximum) {
            int active = tenantActive.getOrDefault(tenant, 0);
            if (packageActive >= packageMaximum || active >= tenantMaximum) return null;
            if (active == 0 && tenantActive.size() >= MAX_TRACKED_TENANTS) return null;
            packageActive++;
            tenantActive.put(tenant, active + 1);
            return new Lease(this, tenant);
        }

        private synchronized void release(String tenant) {
            int remaining = tenantActive.getOrDefault(tenant, 0) - 1;
            if (remaining <= 0) tenantActive.remove(tenant);
            else tenantActive.put(tenant, remaining);
            packageActive--;
        }

        static final class Lease implements AutoCloseable {
            private final AdmissionController owner;
            private final String tenant;
            private final AtomicBoolean closed = new AtomicBoolean();
            Lease(AdmissionController owner, String tenant) {
                this.owner = owner; this.tenant = tenant;
            }
            @Override public void close() {
                if (closed.compareAndSet(false, true)) owner.release(tenant);
            }
        }
    }

    /**
     * Effective WebSocket ceilings for one session: the narrower of the operator policy and the
     * optional per-request limits.
     *
     * <p>The narrowing is a {@code min}, so a package can only tighten what the operator granted.
     * Widening is not refused, it is structurally impossible: there is no branch in which a request
     * value larger than the policy value is the one enforced. An absent request value yields the
     * policy value unchanged, which is why "no per-request limit" and "policy behaviour" are the
     * same code path rather than two behaviours that have to be kept in agreement.</p>
     */
    record WebSocketLimits(long maximumMessageBytes, int maximumFragments) {
        WebSocketLimits {
            if (maximumMessageBytes <= 0 || maximumMessageBytes > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("maximumMessageBytes");
            }
            if (maximumFragments <= 0) throw new IllegalArgumentException("maximumFragments");
        }

        /** Operator authority alone, used by callers that carry no per-request limits. */
        static WebSocketLimits of(NodePackageEgressCapacityProfile.Limits capacity) {
            return new WebSocketLimits(capacity.maximumWebSocketMessageBytes(),
                    capacity.maximumWebSocketFragments());
        }

        /** Compatibility overload for direct transport tests and unmanifested embedding. */
        static WebSocketLimits of(NodePackageEgressPolicy policy) {
            return of(capacityOf(policy));
        }

        /**
         * Fail-closed derivation applied before the handshake: an out-of-range request value throws
         * rather than being clamped, so a package cannot express a nonsensical limit and silently
         * receive a working session.
         */
        static WebSocketLimits of(NodePackageEgressCapacityProfile.Limits capacity, OptionalLong requestedBytes,
                                  OptionalInt requestedFragments) {
            long bytes = capacity.maximumWebSocketMessageBytes();
            if (requestedBytes.isPresent()) {
                long requested = requestedBytes.getAsLong();
                if (requested <= 0 || requested > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("maximumMessageBytes");
                }
                bytes = Math.min(bytes, requested);
            }
            int fragments = capacity.maximumWebSocketFragments();
            if (requestedFragments.isPresent()) {
                int requested = requestedFragments.getAsInt();
                if (requested <= 0) throw new IllegalArgumentException("maximumFragments");
                fragments = Math.min(fragments, requested);
            }
            return new WebSocketLimits(bytes, fragments);
        }

        /** Compatibility overload for direct transport tests and unmanifested embedding. */
        static WebSocketLimits of(NodePackageEgressPolicy policy, OptionalLong requestedBytes,
                                  OptionalInt requestedFragments) {
            return of(capacityOf(policy), requestedBytes, requestedFragments);
        }
    }

    /** Internal session; package-visible solely for deterministic core transport tests. */
    static final class ManagedWebSocketSession implements OutboundWebSocketSession {
        private final WebSocket socket;
        private final WebSocketBridge bridge;
        private final NodePackageEgressCapacityProfile.Limits capacity;
        private final WebSocketLimits limits;
        private final Runnable release;
        private final Semaphore sendQueue;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean cancelRequested = new AtomicBoolean();
        private final AtomicReference<ScheduledFuture<?>> idle = new AtomicReference<>();
        private final ScheduledFuture<?> lifetime;

        /** Policy-only overload: the effective ceilings are the operator ceilings. */
        ManagedWebSocketSession(WebSocket socket, WebSocketBridge bridge,
                                NodePackageEgressPolicy policy, Runnable release) {
            this(socket, bridge, capacityOf(policy), WebSocketLimits.of(capacityOf(policy)), release);
        }

        /** Compatibility overload with request-narrowed limits. */
        ManagedWebSocketSession(WebSocket socket, WebSocketBridge bridge,
                                NodePackageEgressPolicy policy, WebSocketLimits limits,
                                Runnable release) {
            this(socket, bridge, capacityOf(policy), limits, release);
        }

        ManagedWebSocketSession(WebSocket socket, WebSocketBridge bridge,
                                NodePackageEgressCapacityProfile.Limits capacity, WebSocketLimits limits,
                                Runnable release) {
            this.socket = socket;
            this.bridge = bridge;
            this.capacity = capacity;
            this.limits = limits;
            this.release = release;
            this.sendQueue = new Semaphore(capacity.maximumQueuedWebSocketSends(), true);
            lifetime = DEADLINES.schedule(this::forceAbort, capacity.maximumWebSocketLifetime().toNanos(),
                    TimeUnit.NANOSECONDS);
            touch();
        }

        @Override public CompletionStage<Void> sendText(String text) {
            byte[] bytes = String.valueOf(text).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > limits.maximumMessageBytes()) return tooLarge();
            return send(() -> socket.sendText(String.valueOf(text), true));
        }

        @Override public CompletionStage<Void> sendBinary(byte[] bytes) {
            byte[] copy = bytes == null ? new byte[0] : bytes.clone();
            if (copy.length > limits.maximumMessageBytes()) return tooLarge();
            return send(() -> socket.sendBinary(ByteBuffer.wrap(copy), true));
        }

        private CompletionStage<Void> send(java.util.function.Supplier<CompletableFuture<WebSocket>> operation) {
            if (closed.get()) return CompletableFuture.failedFuture(refusal(
                    NodePackageServiceException.Reason.CANCELLED));
            if (!sendQueue.tryAcquire()) return CompletableFuture.failedFuture(refusal(
                    NodePackageServiceException.Reason.ADMISSION_REFUSED));
            if (!touch()) {
                sendQueue.release();
                return CompletableFuture.failedFuture(refusal(NodePackageServiceException.Reason.CANCELLED));
            }
            CompletableFuture<Void> result = new CompletableFuture<>();
            try {
                operation.get().whenComplete((ignored, failure) -> {
                    sendQueue.release();
                    if (failure == null) result.complete(null);
                    else result.completeExceptionally(mapFailure(failure));
                });
            } catch (RuntimeException failure) {
                sendQueue.release();
                result.completeExceptionally(mapFailure(failure));
            }
            return result;
        }

        @Override public CompletionStage<Void> close(int statusCode, String reason) {
            String safe = reason == null ? "" : reason;
            if ((statusCode != 1000 && (statusCode < 3000 || statusCode > 4999))
                    || safe.getBytes(StandardCharsets.UTF_8).length > 123
                    || safe.indexOf('\r') >= 0 || safe.indexOf('\n') >= 0) {
                return CompletableFuture.failedFuture(refusal(NodePackageServiceException.Reason.PROTOCOL_REFUSED));
            }
            if (!closed.compareAndSet(false, true)) return CompletableFuture.completedFuture(null);
            try {
                return socket.sendClose(statusCode, safe).handle((ignored, failure) -> {
                    if (failure != null) {
                        bridge.failOnce(mapFailure(failure));
                        throw mapFailure(failure);
                    }
                    return null;
                });
            } catch (RuntimeException failure) {
                bridge.failOnce(mapFailure(failure));
                return CompletableFuture.failedFuture(mapFailure(failure));
            }
        }

        @Override public boolean cancel() {
            if (bridge.isTerminal() || !cancelRequested.compareAndSet(false, true)) return false;
            closed.set(true);
            forceAbort();
            return true;
        }

        void terminal() {
            closed.set(true);
            cancelTimers();
            release.run();
        }

        boolean touch() {
            if (closed.get()) return false;
            ScheduledFuture<?> next = DEADLINES.schedule(this::forceAbort,
                    capacity.maximumWebSocketIdle().toNanos(),
                    TimeUnit.NANOSECONDS);
            ScheduledFuture<?> previous = idle.getAndSet(next);
            if (previous != null) previous.cancel(false);
            if (closed.get() && idle.compareAndSet(next, null)) {
                next.cancel(false);
                return false;
            }
            return true;
        }

        private void cancelTimers() {
            lifetime.cancel(false);
            ScheduledFuture<?> current = idle.getAndSet(null);
            if (current != null) current.cancel(false);
        }

        /** The same terminal path scheduled for idle/lifetime expiry, exposed to core tests as a controllable seam. */
        void forceAbort() {
            closed.set(true);
            cancelTimers();
            socket.abort();
            bridge.failOnce(refusal(NodePackageServiceException.Reason.CANCELLED));
            release.run();
        }

        private static CompletionStage<Void> tooLarge() {
            return CompletableFuture.failedFuture(refusal(NodePackageServiceException.Reason.REQUEST_TOO_LARGE));
        }
    }

    /** Internal transport bridge; package-visible solely for deterministic core transport tests. */
    static final class WebSocketBridge implements WebSocket.Listener {
        private final OutboundWebSocketListener listener;
        private final WebSocketLimits limits;
        private final Runnable release;
        private final AtomicBoolean terminal = new AtomicBoolean();
        /**
         * Serializes transition to terminal with the next-frame demand. The JDK serializes listener
         * calls, but cancellation and idle expiry are independent threads and must not race a Pong
         * completion into requesting a frame after the managed session is terminal.
         */
        private final Object lifecycle = new Object();
        private final StringBuilder text = new StringBuilder();
        private final ByteArrayOutputStream binary = new ByteArrayOutputStream();
        private int fragments;
        private volatile ManagedWebSocketSession session;

        /** Policy-only overload: the effective ceilings are the operator ceilings. */
        WebSocketBridge(OutboundWebSocketListener listener, NodePackageEgressPolicy policy, Runnable release) {
            this(listener, WebSocketLimits.of(capacityOf(policy)), release);
        }

        WebSocketBridge(OutboundWebSocketListener listener, WebSocketLimits limits, Runnable release) {
            this.listener = listener; this.limits = limits; this.release = release;
        }

        void attach(ManagedWebSocketSession value) { session = value; }

        @Override public void onOpen(WebSocket webSocket) { touchAndRequest(webSocket); }

        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (!chargeFragment()) { webSocket.abort(); return failedStage(NodePackageServiceException.Reason.RESPONSE_TOO_LARGE); }
            String fragment = data.toString();
            long combinedBytes = utf8Length(text, fragment, limits.maximumMessageBytes());
            if (combinedBytes > limits.maximumMessageBytes()) {
                return failAndAbort(webSocket, NodePackageServiceException.Reason.RESPONSE_TOO_LARGE);
            }
            text.append(fragment);
            if (last) {
                String complete = text.toString(); text.setLength(0); fragments = 0;
                return dispatch(webSocket, () -> listener.onText(complete));
            }
            touchAndRequest(webSocket);
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            if (!chargeFragment()) { webSocket.abort(); return failedStage(NodePackageServiceException.Reason.RESPONSE_TOO_LARGE); }
            int remaining = data.remaining();
            if ((long) binary.size() + remaining > limits.maximumMessageBytes()) {
                return failAndAbort(webSocket, NodePackageServiceException.Reason.RESPONSE_TOO_LARGE);
            }
            byte[] bytes = new byte[remaining]; data.get(bytes);
            binary.writeBytes(bytes);
            if (last) {
                byte[] complete = binary.toByteArray(); binary.reset(); fragments = 0;
                return dispatch(webSocket, () -> listener.onBinary(complete));
            }
            touchAndRequest(webSocket);
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            byte[] payload;
            try {
                payload = copyControlPayload(message);
            } catch (IllegalArgumentException invalid) {
                return failAndAbort(webSocket, NodePackageServiceException.Reason.PROTOCOL_REFUSED);
            }
            synchronized (lifecycle) {
                if (terminal.get() || !touch()) return CompletableFuture.completedFuture(null);
                try {
                    return webSocket.sendPong(ByteBuffer.wrap(payload)).handle((ignored, failure) -> {
                        if (failure != null) {
                            failAndAbort(webSocket, mapFailure(failure));
                            throw new CompletionException(mapFailure(failure));
                        }
                        touchAndRequest(webSocket);
                        return null;
                    });
                } catch (RuntimeException failure) {
                    return failAndAbort(webSocket, mapFailure(failure));
                }
            }
        }
        @Override public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            try {
                copyControlPayload(message);
            } catch (IllegalArgumentException invalid) {
                return failAndAbort(webSocket, NodePackageServiceException.Reason.PROTOCOL_REFUSED);
            }
            touchAndRequest(webSocket); return CompletableFuture.completedFuture(null);
        }

        @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (markTerminal()) {
                terminalSession();
                return dispatchTerminal(() -> listener.onClosed(statusCode, sanitizeCloseReason(reason)));
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override public void onError(WebSocket webSocket, Throwable error) { failOnce(mapFailure(error)); }

        void failOnce(NodePackageServiceException failure) {
            if (markTerminal()) {
                terminalSession();
                dispatchTerminal(() -> listener.onFailure(failure));
            }
        }

        boolean isTerminal() { return terminal.get(); }

        private boolean chargeFragment() {
            if (++fragments <= limits.maximumFragments()) return true;
            failOnce(refusal(NodePackageServiceException.Reason.RESPONSE_TOO_LARGE));
            return false;
        }

        private CompletionStage<?> failAndAbort(WebSocket socket, NodePackageServiceException.Reason reason) {
            failOnce(refusal(reason)); socket.abort(); return failedStage(reason);
        }

        private CompletionStage<?> failAndAbort(WebSocket socket, NodePackageServiceException failure) {
            failOnce(failure); socket.abort(); return CompletableFuture.failedFuture(failure);
        }

        private void touchAndRequest(WebSocket socket) {
            synchronized (lifecycle) {
                if (terminal.get() || !touch()) return;
                socket.request(1);
            }
        }

        private boolean touch() {
            ManagedWebSocketSession current = session;
            return current == null || current.touch();
        }

        private boolean markTerminal() {
            synchronized (lifecycle) {
                return terminal.compareAndSet(false, true);
            }
        }

        private static byte[] copyControlPayload(ByteBuffer message) {
            if (message == null || message.remaining() > MAX_WEBSOCKET_CONTROL_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("invalid WebSocket control payload");
            }
            ByteBuffer copied = message.duplicate();
            byte[] payload = new byte[copied.remaining()];
            copied.get(payload);
            return payload;
        }

        private static CompletionStage<?> failedStage(NodePackageServiceException.Reason reason) {
            return CompletableFuture.failedFuture(refusal(reason));
        }

        private CompletionStage<?> dispatch(WebSocket socket, Runnable callback) {
            CompletableFuture<Void> completion = new CompletableFuture<>();
            Thread.ofVirtual().name("ravenroot-package-websocket-callback").start(() -> {
                try {
                    callback.run();
                    touchAndRequest(socket);
                    completion.complete(null);
                } catch (Throwable failure) {
                    failOnce(refusal(NodePackageServiceException.Reason.TRANSPORT_FAILED));
                    socket.abort();
                    completion.completeExceptionally(refusal(
                            NodePackageServiceException.Reason.TRANSPORT_FAILED));
                }
            });
            return completion;
        }

        private CompletionStage<?> dispatchTerminal(Runnable callback) {
            CompletableFuture<Void> completion = new CompletableFuture<>();
            Thread.ofVirtual().name("ravenroot-package-websocket-terminal").start(() -> {
                try {
                    callback.run();
                    completion.complete(null);
                } catch (Throwable ignored) {
                    completion.complete(null);
                }
            });
            return completion;
        }

        private void terminalSession() {
            ManagedWebSocketSession current = session;
            if (current != null) current.terminal(); else release.run();
        }

        private static String sanitizeCloseReason(String reason) {
            if (reason == null || reason.isEmpty()) return "";
            return reason.length() <= 123 ? reason.replace('\r', ' ').replace('\n', ' ')
                    : reason.substring(0, 123).replace('\r', ' ').replace('\n', ' ');
        }

        /** Exact UTF-8 size of the concatenation, stopping as soon as the configured ceiling is crossed. */
        private static long utf8Length(CharSequence first, CharSequence second, long ceiling) {
            int firstLength = first.length();
            long total = (long) firstLength + second.length();
            long bytes = 0;
            for (long index = 0; index < total; index++) {
                char value = index < firstLength ? first.charAt((int) index)
                        : second.charAt((int) (index - firstLength));
                if (value < 0x80) {
                    bytes++;
                } else if (value < 0x800) {
                    bytes += 2;
                } else if (Character.isHighSurrogate(value) && index + 1 < total) {
                    char next = index + 1 < firstLength
                            ? first.charAt((int) (index + 1))
                            : second.charAt((int) (index + 1 - firstLength));
                    if (Character.isLowSurrogate(next)) {
                        bytes += 4;
                        index++;
                    } else {
                        bytes++;
                    }
                } else if (Character.isSurrogate(value)) {
                    bytes++;
                } else {
                    bytes += 3;
                }
                if (bytes > ceiling) return bytes;
            }
            return bytes;
        }
    }
}
