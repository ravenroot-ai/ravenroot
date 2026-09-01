package ai.ravenroot.extensions.storage;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.node.service.OutboundCall;
import ai.ravenroot.api.node.service.OutboundHttpRequest;
import ai.ravenroot.api.node.service.OutboundHttpResponse;
import ai.ravenroot.api.node.service.OutboundHttpSigning;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

final class StorageRuntime {
    private static final Set<NodePackageServiceException.Reason> UNCERTAIN = Set.of(
            NodePackageServiceException.Reason.DEADLINE_EXCEEDED,
            NodePackageServiceException.Reason.CANCELLED,
            NodePackageServiceException.Reason.TRANSPORT_FAILED);
    final StorageProfileResolver profiles;
    private final StorageAdmission admission = new StorageAdmission();

    StorageRuntime(StorageProfileResolver profiles) { this.profiles = java.util.Objects.requireNonNull(profiles); }

    CompletionStage<NodeResult> execute(NodeMessage message, NodePackageServices services, StorageSettings settings,
                                        Semaphore actionGate, byte[] body, Map<String, List<String>> headers) {
        StorageAdmission.Result admitted = admission.acquire(message.tenantId() + "\u0000" + settings.profile().name(),
                settings.profile().maxConcurrency(), settings.profile().maxRequestsPerSecond(), System.nanoTime());
        if (admitted.refusal() != null) {
            return CompletableFuture.failedFuture(StorageException.of(admitted.refusal() == StorageAdmission.Refusal.RATE
                    ? StorageException.Code.RATE_LIMITED : StorageException.Code.CAPACITY_UNAVAILABLE));
        }
        if (!actionGate.tryAcquire()) {
            admitted.lease().close();
            return CompletableFuture.failedFuture(StorageException.of(StorageException.Code.CAPACITY_UNAVAILABLE));
        }
        OutboundCall<OutboundHttpResponse> call;
        try {
            call = services.outboundHttp().execute(message, new OutboundHttpRequest(settings.destination(),
                    settings.operation().name(), headers, body, Duration.ofMillis(settings.timeoutMs()), null,
                    new OutboundHttpSigning(settings.profile().signingBindingId())));
        } catch (RuntimeException failure) {
            actionGate.release(); admitted.lease().close();
            return CompletableFuture.failedFuture(map(failure, false));
        }
        boolean put = settings.operation() == StorageProfile.Operation.PUT;
        OutcomeFuture outcome = new OutcomeFuture(call, put);
        call.completion().whenComplete((response, failure) -> {
            NodeResult projected = null;
            RuntimeException terminal = null;
            try {
                if (failure != null) terminal = map(failure, put);
                else projected = project(settings, body, response);
            } catch (RuntimeException invalid) {
                terminal = invalid instanceof StorageException ? invalid
                        : StorageException.of(StorageException.Code.RESPONSE_INVALID);
            } finally {
                actionGate.release(); admitted.lease().close();
            }
            if (terminal != null) outcome.completeExceptionally(terminal);
            else outcome.complete(projected);
        });
        return outcome;
    }

    int admissionEntries() { return admission.size(); }

    private static NodeResult project(StorageSettings settings, byte[] submitted, OutboundHttpResponse response) {
        int status = response.statusCode();
        if (status >= 300 && status < 400) throw StorageException.of(StorageException.Code.REDIRECT_REFUSED);
        if (status == 404) throw StorageException.of(StorageException.Code.NOT_FOUND);
        boolean get = settings.operation() == StorageProfile.Operation.GET;
        if (get ? status != 200 : !(status == 200 || status == 201 || status == 204)) {
            throw StorageException.of(StorageException.Code.REMOTE_REJECTED);
        }
        byte[] responseBody = response.body();
        if (responseBody.length > settings.maxBytes()) {
            throw StorageException.of(StorageException.Code.RESPONSE_TOO_LARGE);
        }
        String etag = singleHeader(response.headers(), "etag", true);
        String versionId = singleHeader(response.headers(), "x-amz-version-id", false);
        Map<String, Object> output = new LinkedHashMap<>();
        if (get) {
            output.put("version", "object.get.result.v1");
            output.put("encoding", settings.encoding());
            if (settings.encoding().equals("text")) output.put("text", strictText(responseBody));
            else output.put("base64", Base64.getEncoder().encodeToString(responseBody));
            output.put("bytes", (long) responseBody.length);
            output.put("sha256", sha256(responseBody));
        } else {
            if (responseBody.length != 0) throw StorageException.of(StorageException.Code.RESPONSE_INVALID);
            output.put("version", "object.put.result.v1");
            output.put("bytes", (long) submitted.length);
        }
        output.put("etag", etag);
        if (versionId != null) output.put("versionId", versionId);
        return NodeResult.continueWith(Map.copyOf(output));
    }

    private static String singleHeader(Map<String, List<String>> headers, String wanted, boolean required) {
        List<String> found = null;
        for (var entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(wanted)) {
                if (found != null) throw StorageException.of(StorageException.Code.RESPONSE_INVALID);
                found = entry.getValue();
            }
        }
        if (found == null) {
            if (required) throw StorageException.of(StorageException.Code.RESPONSE_INVALID);
            return null;
        }
        if (found.size() != 1 || found.getFirst().isEmpty() || found.getFirst().length() > 512
                || found.getFirst().chars().anyMatch(value -> value < 0x20 || value == 0x7f)) {
            throw StorageException.of(StorageException.Code.RESPONSE_INVALID);
        }
        return found.getFirst();
    }

    static byte[] strictTextBytes(String value) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()]; encoded.get(bytes); return bytes;
        } catch (CharacterCodingException invalid) {
            throw StorageException.of(StorageException.Code.INVALID_INPUT);
        }
    }

    private static String strictText(byte[] value) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString();
        } catch (CharacterCodingException invalid) {
            throw StorageException.of(StorageException.Code.RESPONSE_INVALID);
        }
    }

    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception impossible) { throw new AssertionError(impossible); }
    }

    static RuntimeException map(Throwable raw, boolean ambiguous) {
        Throwable failure = raw;
        while ((failure instanceof CompletionException || failure instanceof java.util.concurrent.ExecutionException)
                && failure.getCause() != null) failure = failure.getCause();
        if (failure instanceof StorageException safe) return safe;
        if (failure instanceof java.util.concurrent.CancellationException) {
            return StorageException.of(ambiguous ? StorageException.Code.AMBIGUOUS
                    : StorageException.Code.DEADLINE_EXCEEDED);
        }
        if (failure instanceof NodePackageServiceException service) {
            if (ambiguous && UNCERTAIN.contains(service.reason())) return StorageException.of(StorageException.Code.AMBIGUOUS);
            return StorageException.of(switch (service.reason()) {
                case CREDENTIAL_UNAVAILABLE -> StorageException.Code.CREDENTIAL_UNAVAILABLE;
                case DESTINATION_FORBIDDEN, RESOLUTION_REFUSED, PROTOCOL_REFUSED -> StorageException.Code.DESTINATION_REFUSED;
                case TLS_REFUSED -> StorageException.Code.TLS_REFUSED;
                case REQUEST_TOO_LARGE -> StorageException.Code.REQUEST_TOO_LARGE;
                case RESPONSE_TOO_LARGE -> StorageException.Code.RESPONSE_TOO_LARGE;
                case DEADLINE_EXCEEDED, CANCELLED -> StorageException.Code.DEADLINE_EXCEEDED;
                case ADMISSION_REFUSED, SERVICE_UNAVAILABLE -> StorageException.Code.CAPACITY_UNAVAILABLE;
                case TRANSPORT_FAILED -> StorageException.Code.TRANSPORT_UNAVAILABLE;
            });
        }
        return StorageException.of(ambiguous ? StorageException.Code.AMBIGUOUS
                : StorageException.Code.TRANSPORT_UNAVAILABLE);
    }

    private static final class OutcomeFuture extends CompletableFuture<NodeResult> {
        private final OutboundCall<?> call;
        private final boolean ambiguous;
        private final AtomicBoolean cancellation = new AtomicBoolean();
        OutcomeFuture(OutboundCall<?> call, boolean ambiguous) { this.call = call; this.ambiguous = ambiguous; }
        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            if (isDone() || !cancellation.compareAndSet(false, true)) return false;
            call.cancel();
            completeExceptionally(StorageException.of(ambiguous ? StorageException.Code.AMBIGUOUS
                    : StorageException.Code.DEADLINE_EXCEEDED));
            return true;
        }
    }
}
