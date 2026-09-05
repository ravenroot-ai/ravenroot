package ai.ravenroot.extensions.websocket;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.node.service.OutboundCall;
import ai.ravenroot.api.node.service.OutboundWebSocketListener;
import ai.ravenroot.api.node.service.OutboundWebSocketRequest;
import ai.ravenroot.api.node.service.OutboundWebSocketSession;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** One non-retryable managed write. The graph never supplies an endpoint or handshake authority. */
public final class WebSocketSendNodeBehavior implements NodeBehavior {
    public static final String BEHAVIOR = "websocket.send";

    private final WebSocketProfileResolver profiles;
    private final WebSocketAdmissionRegistry admission;

    public WebSocketSendNodeBehavior() {
        this(new EnvironmentWebSocketProfileResolver(), WebSocketAdmissionRegistry.global());
    }

    WebSocketSendNodeBehavior(WebSocketProfileResolver profiles) {
        this(profiles, new WebSocketAdmissionRegistry());
    }

    WebSocketSendNodeBehavior(WebSocketProfileResolver profiles, WebSocketAdmissionRegistry admission) {
        this.profiles = java.util.Objects.requireNonNull(profiles);
        this.admission = java.util.Objects.requireNonNull(admission);
    }

    @Override public Set<NodePackageCapability> requiredServices() {
        return Set.of(NodePackageCapability.OUTBOUND_WEBSOCKET);
    }

    @Override public NodeTypeDescriptor descriptor() {
        return new NodeTypeDescriptor(BEHAVIOR, "Send WebSocket frame", "WebSocket",
                "Writes one bounded text or binary frame through an operator-owned WSS profile.",
                "actor", false, List.of(
                NodePropertyDescriptor.required("websocketProfile", "WebSocket profile", NodePropertyType.STRING,
                        "Opaque operator profile; origin, path and credentials never come from GraphML."),
                optional("maxMessageBytes", "Maximum message bytes"),
                optional("maxFragments", "Maximum fragments"),
                optional("timeoutMs", "Deadline (ms)")),
                Set.of("network", "credential-reference", "side-effect"));
    }

    private static NodePropertyDescriptor optional(String name, String label) {
        return NodePropertyDescriptor.optional(name, label, NodePropertyType.INTEGER,
                "May only tighten the operator profile ceiling.", "");
    }

    @Override public NodeAction create(NodeConfiguration configuration) {
        return create(configuration, NodePackageServices.unavailable());
    }

    @Override public NodeAction create(NodeConfiguration configuration, NodePackageServices services) {
        WebSocketSettings settings = WebSocketSettings.compile(configuration, profiles);
        return message -> send(message, services, settings);
    }

    private CompletionStage<NodeResult> send(NodeMessage message, NodePackageServices services,
                                              WebSocketSettings settings) {
        Frame frame;
        try {
            frame = Frame.parse(message.payload(), settings.maximumMessageBytes());
        } catch (RuntimeException invalid) {
            return CompletableFuture.failedFuture(WebSocketException.of(WebSocketException.Code.INVALID_INPUT));
        }
        WebSocketAdmissionRegistry.Lease lease;
        try {
            lease = admission.tryAcquire(message.tenantId(), settings.profile().name(),
                    settings.profile().maxConcurrency());
        } catch (RuntimeException invalid) {
            return CompletableFuture.failedFuture(map(invalid, false));
        }
        if (lease == null) {
            return CompletableFuture.failedFuture(
                    WebSocketException.of(WebSocketException.Code.CAPACITY_UNAVAILABLE));
        }

        TerminalListener terminal = new TerminalListener();
        OutboundCall<OutboundWebSocketSession> opening;
        try {
            opening = services.outboundWebSocket().open(message, request(settings), terminal);
        } catch (RuntimeException failure) {
            lease.close();
            return CompletableFuture.failedFuture(map(failure, false));
        }
        SendFuture result = new SendFuture(opening, lease, frame);
        terminal.terminal.whenComplete((ignored, failure) -> result.transportTerminated());
        opening.completion().whenComplete(result::opened);
        return result;
    }

    static OutboundWebSocketRequest request(WebSocketSettings settings) {
        return new OutboundWebSocketRequest(settings.profile().destination(), settings.profile().headers(),
                settings.profile().subprotocols(), java.time.Duration.ofMillis(settings.timeoutMs()),
                settings.profile().credential().orElse(null), (long) settings.maximumMessageBytes(),
                settings.maximumFragments());
    }

    static WebSocketException map(Throwable failure, boolean handedOff) {
        Throwable value = failure;
        while ((value instanceof CompletionException || value instanceof ExecutionException)
                && value.getCause() != null) value = value.getCause();
        if (value instanceof WebSocketException safe) return safe;
        if (value instanceof NodePackageServiceException service) {
            WebSocketException.Code code = switch (service.reason()) {
                case CREDENTIAL_UNAVAILABLE -> WebSocketException.Code.CREDENTIAL_UNAVAILABLE;
                case DESTINATION_FORBIDDEN, RESOLUTION_REFUSED, PROTOCOL_REFUSED ->
                        WebSocketException.Code.DESTINATION_REFUSED;
                case TLS_REFUSED -> WebSocketException.Code.TLS_REFUSED;
                case REQUEST_TOO_LARGE -> WebSocketException.Code.REQUEST_TOO_LARGE;
                case RESPONSE_TOO_LARGE -> WebSocketException.Code.RESPONSE_TOO_LARGE;
                case DEADLINE_EXCEEDED, CANCELLED -> WebSocketException.Code.DEADLINE_EXCEEDED;
                case ADMISSION_REFUSED, SERVICE_UNAVAILABLE, BUDGET_EXHAUSTED ->
                        WebSocketException.Code.CAPACITY_UNAVAILABLE;
                case TRANSPORT_FAILED, EFFECT_OUTCOME_INDETERMINATE ->
                        WebSocketException.Code.TRANSPORT_UNAVAILABLE;
            };
            if (handedOff && (service.reason() == NodePackageServiceException.Reason.TRANSPORT_FAILED
                    || service.reason() == NodePackageServiceException.Reason.CANCELLED
                    || service.reason() == NodePackageServiceException.Reason.DEADLINE_EXCEEDED)) {
                code = WebSocketException.Code.AMBIGUOUS;
            }
            return WebSocketException.of(code);
        }
        return WebSocketException.of(handedOff
                ? WebSocketException.Code.AMBIGUOUS : WebSocketException.Code.TRANSPORT_UNAVAILABLE);
    }

    record Frame(String encoding, String text, byte[] binary) {
        static Frame parse(Object value, int maximum) {
            if (!(value instanceof Map<?, ?> raw) || raw.size() != 3
                    || !(raw.get("version") instanceof String version)
                    || !"websocket.send.v1".equals(version)
                    || !(raw.get("encoding") instanceof String encoding)
                    || !(raw.get("data") instanceof String data)) throw new IllegalArgumentException();
            if ("text".equals(encoding)) {
                if (strictUtf8Length(data) > maximum) throw new IllegalArgumentException();
                return new Frame(encoding, data, null);
            }
            if ("base64".equals(encoding)) {
                byte[] bytes = Base64.getDecoder().decode(data);
                if (!Base64.getEncoder().encodeToString(bytes).equals(data) || bytes.length > maximum) {
                    throw new IllegalArgumentException();
                }
                return new Frame(encoding, null, bytes);
            }
            throw new IllegalArgumentException();
        }

        private static int strictUtf8Length(String value) {
            try {
                ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .encode(java.nio.CharBuffer.wrap(value));
                return encoded.remaining();
            } catch (CharacterCodingException invalid) {
                throw new IllegalArgumentException(invalid);
            }
        }
    }

    private static final class TerminalListener implements OutboundWebSocketListener {
        private final CompletableFuture<Void> terminal = new CompletableFuture<>();

        @Override public void onClosed(int statusCode, String reason) { terminal.complete(null); }
        @Override public void onFailure(NodePackageServiceException failure) { terminal.complete(null); }
    }

    private static final class SendFuture extends CompletableFuture<NodeResult> {
        private enum Phase {
            OPENING,
            HANDED_OFF,
            RESULT_COMPLETED,
            RESULT_FAILED,
            CANCELLED_BEFORE_OPEN,
            CANCELLED_AFTER_HANDOFF,
            TERMINAL
        }

        private final OutboundCall<OutboundWebSocketSession> opening;
        private final WebSocketAdmissionRegistry.Lease lease;
        private final Frame frame;
        private final Object handoff = new Object();
        private Phase phase = Phase.OPENING;
        private final AtomicBoolean released = new AtomicBoolean();
        private OutboundWebSocketSession session;

        private SendFuture(OutboundCall<OutboundWebSocketSession> opening,
                           WebSocketAdmissionRegistry.Lease lease, Frame frame) {
            this.opening = opening;
            this.lease = lease;
            this.frame = frame;
        }

        private void opened(OutboundWebSocketSession opened, Throwable failure) {
            if (failure != null) {
                openingFailed(failure);
                return;
            }
            CompletionStage<Void> sent;
            boolean cancelLate = false;
            synchronized (handoff) {
                if (phase != Phase.OPENING) {
                    cancelLate = true;
                    sent = null;
                } else {
                    session = opened;
                    // This publication and the actual send invocation are one critical section.
                    // Cancellation therefore linearizes either wholly before the frame handoff or
                    // wholly after it; it can never observe HANDED_OFF and race a not-yet-called send.
                    phase = Phase.HANDED_OFF;
                    try {
                        sent = frame.text == null ? opened.sendBinary(frame.binary) : opened.sendText(frame.text);
                    } catch (RuntimeException failureDuringHandoff) {
                        phase = Phase.RESULT_FAILED;
                        completeExceptionally(map(failureDuringHandoff, true));
                        sent = null;
                        cancelLate = true;
                    }
                }
            }
            if (cancelLate) {
                try { opened.cancel(); } catch (RuntimeException ignored) { }
                return;
            }
            sent.whenComplete((sentValue, writeFailure) -> {
                boolean cancelSession = false;
                boolean closeSession = false;
                synchronized (handoff) {
                    if (phase != Phase.HANDED_OFF) return;
                    if (writeFailure != null) {
                        phase = Phase.RESULT_FAILED;
                        completeExceptionally(map(writeFailure, true));
                        cancelSession = true;
                    } else {
                        phase = Phase.RESULT_COMPLETED;
                        complete(NodeResult.continueWith(Map.of("version", "websocket.send.result.v1",
                                "outcome", "written", "encoding", frame.encoding)));
                        closeSession = true;
                    }
                }
                if (cancelSession) {
                    try { opened.cancel(); } catch (RuntimeException ignored) { }
                } else if (closeSession) {
                    try {
                        opened.close(1000, "complete").whenComplete((closed, closeFailure) -> {
                            if (closeFailure != null) opened.cancel();
                        });
                    } catch (RuntimeException closeFailure) {
                        opened.cancel();
                    }
                }
            });
        }

        private void openingFailed(Throwable failure) {
            synchronized (handoff) {
                if (phase == Phase.OPENING) {
                    phase = Phase.TERMINAL;
                    completeExceptionally(map(failure, false));
                } else if (phase == Phase.CANCELLED_BEFORE_OPEN) {
                    phase = Phase.TERMINAL;
                } else {
                    return;
                }
            }
            release();
        }

        private void transportTerminated() {
            synchronized (handoff) {
                Phase previous = phase;
                phase = Phase.TERMINAL;
                if (previous == Phase.OPENING) {
                    completeExceptionally(WebSocketException.of(WebSocketException.Code.TRANSPORT_UNAVAILABLE));
                } else if (previous == Phase.HANDED_OFF) {
                    completeExceptionally(WebSocketException.of(WebSocketException.Code.AMBIGUOUS));
                }
            }
            release();
        }

        private void release() {
            if (released.compareAndSet(false, true)) lease.close();
        }

        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelOpening = false;
            OutboundWebSocketSession cancelSession = null;
            boolean accepted;
            synchronized (handoff) {
                if (phase == Phase.OPENING) {
                    phase = Phase.CANCELLED_BEFORE_OPEN;
                    accepted = completeExceptionally(
                            WebSocketException.of(WebSocketException.Code.DEADLINE_EXCEEDED));
                    cancelOpening = accepted;
                } else if (phase == Phase.HANDED_OFF) {
                    phase = Phase.CANCELLED_AFTER_HANDOFF;
                    accepted = completeExceptionally(WebSocketException.of(WebSocketException.Code.AMBIGUOUS));
                    if (accepted) cancelSession = session;
                } else {
                    return false;
                }
            }
            if (cancelOpening) try { opening.cancel(); } catch (RuntimeException ignored) { }
            if (cancelSession != null) try { cancelSession.cancel(); } catch (RuntimeException ignored) { }
            return accepted;
        }
    }
}
