package ai.ravenroot.extensions.teams;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.CancellationSignal;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.service.ExternalIoLimits;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.node.service.OutboundCall;
import ai.ravenroot.api.node.service.OutboundHttpRepresentationPolicy;
import ai.ravenroot.api.node.service.OutboundHttpRequest;
import ai.ravenroot.api.node.service.OutboundHttpResponse;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded Teams Workflow sender using only the managed HTTP service. */
public final class TeamsSendBehavior implements NodeBehavior {
    private static final Set<String> PAYLOAD_FIELDS = Set.of("version", "channelId", "text", "correlationId");
    private final TeamsRuntime runtime;

    TeamsSendBehavior(TeamsRuntime runtime) { this.runtime = runtime; }

    @Override public Set<NodePackageCapability> requiredServices() {
        return Set.of(NodePackageCapability.OUTBOUND_HTTP);
    }
    @Override public NodeTypeDescriptor descriptor() { return TeamsBehaviorDescriptors.send(); }
    @Override public NodeAction create(NodeConfiguration configuration) {
        return create(configuration, NodePackageServices.unavailable());
    }
    @Override public NodeAction create(NodeConfiguration configuration, NodePackageServices services) {
        String profileName = TeamsBehaviorDescriptors.profile(configuration);
        String configuredChannel = property(configuration, "channelId");
        Map<String, String> tightening = new LinkedHashMap<>();
        for (String name : List.of("requestTimeoutMs", "maxTextChars", "maxConcurrency"))
            tightening.put(name, property(configuration, name));
        return new Action(runtime, services, profileName, configuredChannel, Map.copyOf(tightening));
    }

    private static final class Action implements NodeAction {
        private final TeamsRuntime runtime;
        private final NodePackageServices services;
        private final String profileName;
        private final String configuredChannel;
        private final Map<String, String> tightening;
        private final AtomicReference<Semaphore> nodeGate = new AtomicReference<>();

        Action(TeamsRuntime runtime, NodePackageServices services, String profileName,
               String configuredChannel, Map<String, String> tightening) {
            this.runtime = runtime; this.services = services; this.profileName = profileName;
            this.configuredChannel = configuredChannel; this.tightening = tightening;
        }

        @Override public CompletionStage<NodeResult> handle(NodeMessage message) {
            return handle(message, new NeverCancelled());
        }

        @Override public CompletionStage<NodeResult> handle(NodeMessage message, CancellationSignal cancellation) {
            final Settings settings;
            final Payload payload;
            try {
                settings = Settings.from(runtime.profile(message.tenantId(), profileName), configuredChannel, tightening);
                payload = Payload.from(message.payload(), settings);
            } catch (RuntimeException failure) { return CompletableFuture.failedFuture(sanitize(failure, false)); }
            Semaphore local = nodeGate.updateAndGet(existing -> existing == null
                    ? new Semaphore(settings.maxConcurrency) : existing);
            Semaphore profile = runtime.gate(settings.profile);
            if (!local.tryAcquire()) return CompletableFuture.completedFuture(result("capacity", payload, 0,
                    settings.profile.maxResponseBytes(), "local-capacity"));
            if (!profile.tryAcquire()) {
                local.release();
                return CompletableFuture.completedFuture(result("capacity", payload, 0,
                        settings.profile.maxResponseBytes(), "profile-capacity"));
            }
            String rateKey = message.tenantId() + "\u0000" + settings.profile.name();
            if (!runtime.rates.allow(rateKey, settings.profile.maxPerSecond())) {
                profile.release(); local.release();
                return CompletableFuture.completedFuture(result("rate-limited", payload, 429,
                        settings.profile.maxResponseBytes(), "local-rate-limit"));
            }
            CompletableFuture<NodeResult> result = new CompletableFuture<>();
            AtomicReference<OutboundCall<OutboundHttpResponse>> active = new AtomicReference<>();
            AtomicBoolean cancelled = new AtomicBoolean(cancellation.cancelled());
            cancellation.onCancel(() -> {
                cancelled.set(true);
                OutboundCall<?> call = active.get();
                if (call != null) call.cancel();
            });
            Thread.startVirtualThread(() -> {
                NodeResult outcome = null;
                RuntimeException failure = null;
                try { outcome = send(message, settings, payload, cancelled, active); }
                catch (RuntimeException caught) { failure = sanitize(caught, true); }
                finally { active.set(null); profile.release(); local.release(); }
                if (failure == null) result.complete(outcome); else result.completeExceptionally(failure);
            });
            return result;
        }

        private NodeResult send(NodeMessage message, Settings settings, Payload payload, AtomicBoolean cancelled,
                                AtomicReference<OutboundCall<OutboundHttpResponse>> active) {
            if (cancelled.get()) throw new TeamsException(TeamsException.Code.CANCELLED);
            byte[] body = payload.body(settings.profile);
            if (body.length > settings.profile.maxRequestBytes()) throw new TeamsException(TeamsException.Code.INVALID_INPUT);
            OutboundHttpRequest request = new OutboundHttpRequest(settings.profile.workflowEndpoint(), "POST",
                    Map.of("accept", List.of("application/json"),
                            "content-type", List.of("application/json; charset=utf-8"),
                            "user-agent", List.of("ravenroot-teams/1")),
                    body, Duration.ofMillis(settings.timeoutMs), settings.profile.credential(), null,
                    ExternalIoLimits.compressedHttp(settings.profile.maxRequestBytes(),
                            settings.profile.maxResponseBytes(), settings.profile.maxResponseBytes(),
                            settings.profile.maxResponseBytes(), 100, Duration.ofMillis(settings.timeoutMs),
                            Set.of("application/json", "text/plain")),
                    OutboundHttpRepresentationPolicy.ALL_STATUSES);
            final OutboundCall<OutboundHttpResponse> call;
            try { call = services.outboundHttp().execute(message, request); }
            catch (RuntimeException preDispatch) { throw sanitize(preDispatch, false); }
            active.set(call);
            if (cancelled.get()) call.cancel();
            try {
                OutboundHttpResponse response = call.completion().toCompletableFuture()
                        .get(settings.timeoutMs, TimeUnit.MILLISECONDS);
                if (response.statusCode() >= 200 && response.statusCode() < 300)
                    return result("sent", payload, response.statusCode(),
                            response.effectiveMaximumOutputBytes(), "workflow-accepted");
                String status = response.statusCode() == 401 ? "authentication-failed"
                        : response.statusCode() == 403 ? "forbidden"
                        : response.statusCode() == 429 ? "rate-limited"
                        : response.statusCode() >= 500 ? "indeterminate" : "rejected";
                return result(status, payload, response.statusCode(),
                        response.effectiveMaximumOutputBytes(), "provider-status");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt(); call.cancel(); throw new TeamsException(TeamsException.Code.CANCELLED);
            } catch (TimeoutException failure) {
                call.cancel(); throw new TeamsException(TeamsException.Code.INDETERMINATE);
            } catch (ExecutionException failure) { throw sanitize(failure.getCause(), true); }
            finally { active.compareAndSet(call, null); }
        }
    }

    private record Settings(TeamsProfile profile, String channel, int timeoutMs, int maxTextChars,
                            int maxConcurrency) {
        static Settings from(TeamsProfile profile, String channel, Map<String, String> values) {
            if (!channel.isEmpty() && !profile.permitsChannel(TeamsProfile.providerId(channel, 160)))
                throw new TeamsException(TeamsException.Code.FORBIDDEN);
            return new Settings(profile, channel,
                    tighten(values.get("requestTimeoutMs"), profile.requestTimeoutMs(), 100),
                    tighten(values.get("maxTextChars"), profile.maxTextChars(), 1),
                    tighten(values.get("maxConcurrency"), profile.maxConcurrency(), 1));
        }

        private static int tighten(String raw, int ceiling, int minimum) {
            if (raw == null || raw.isBlank()) return ceiling;
            try {
                int value = Integer.parseInt(raw);
                if (value < minimum || value > ceiling) throw new NumberFormatException();
                return value;
            } catch (NumberFormatException failure) { throw new TeamsException(TeamsException.Code.CONFIGURATION); }
        }
    }

    private record Payload(String channelId, String text, String correlationId) {
        static Payload from(Object raw, Settings settings) {
            Map<String, Object> value = TeamsValues.object(raw); TeamsValues.exact(value, PAYLOAD_FIELDS);
            if (!"teams.message.v1".equals(TeamsValues.string(value.get("version"), 64))) throw TeamsValues.invalid();
            String channel = TeamsProfile.providerId(TeamsValues.string(value.get("channelId"), 160), 160);
            if (!settings.profile.permitsChannel(channel)
                    || (!settings.channel.isEmpty() && !settings.channel.equals(channel)))
                throw new TeamsException(TeamsException.Code.FORBIDDEN);
            String text = TeamsValues.string(value.get("text"), settings.maxTextChars);
            if (text.codePointCount(0, text.length()) > settings.maxTextChars) throw TeamsValues.invalid();
            return new Payload(channel, text, TeamsValues.optionalString(value.get("correlationId"), 128));
        }

        byte[] body(TeamsProfile profile) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("version", "teams.message.v1");
            value.put("microsoftTenantId", profile.microsoftTenantId());
            value.put("teamId", profile.teamId());
            value.put("channelId", channelId); value.put("text", text); value.put("correlationId", correlationId);
            return TeamsValues.jsonBytes(value);
        }
    }

    private static NodeResult result(String status, Payload payload, int code, long maximumBytes, String evidence) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("version", "teams.message.result.v1"); value.put("status", status);
        value.put("channelId", payload.channelId); value.put("correlationId", payload.correlationId);
        value.put("attempt", 1L); value.put("code", (long) code); value.put("evidence", evidence);
        return TeamsValues.result("continue", value, maximumBytes);
    }

    private static RuntimeException sanitize(Throwable raw, boolean dispatched) {
        Throwable failure = raw;
        while ((failure instanceof CompletionException || failure instanceof ExecutionException) && failure.getCause() != null)
            failure = failure.getCause();
        if (failure instanceof TeamsException teams) return teams;
        if (failure instanceof CancellationException) return new TeamsException(TeamsException.Code.CANCELLED);
        if (failure instanceof NodePackageServiceException service) return new TeamsException(switch (service.reason()) {
            case CREDENTIAL_UNAVAILABLE -> TeamsException.Code.AUTHENTICATION_FAILED;
            case DESTINATION_FORBIDDEN, RESOLUTION_REFUSED, TLS_REFUSED, PROTOCOL_REFUSED -> TeamsException.Code.FORBIDDEN;
            case REQUEST_TOO_LARGE, RESPONSE_TOO_LARGE -> TeamsException.Code.RESPONSE_INVALID;
            case ADMISSION_REFUSED, SERVICE_UNAVAILABLE, BUDGET_EXHAUSTED -> TeamsException.Code.CAPACITY;
            case CANCELLED -> TeamsException.Code.CANCELLED;
            case EFFECT_OUTCOME_INDETERMINATE -> TeamsException.Code.INDETERMINATE;
            case DEADLINE_EXCEEDED, TRANSPORT_FAILED -> dispatched
                    ? TeamsException.Code.INDETERMINATE : TeamsException.Code.TRANSPORT;
        });
        return new TeamsException(dispatched ? TeamsException.Code.INDETERMINATE : TeamsException.Code.TRANSPORT);
    }

    private static String property(NodeConfiguration configuration, String name) {
        return configuration.property(name).orElse("");
    }

    private static final class NeverCancelled implements CancellationSignal {
        @Override public boolean cancelled() { return false; }
        @Override public void onCancel(Runnable listener) { }
    }
}
