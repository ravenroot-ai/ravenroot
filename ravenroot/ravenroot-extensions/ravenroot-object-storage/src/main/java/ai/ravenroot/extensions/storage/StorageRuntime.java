package ai.ravenroot.extensions.storage;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.node.service.OutboundCall;
import ai.ravenroot.api.node.service.OutboundHttpRequest;
import ai.ravenroot.api.node.service.OutboundHttpResponse;
import ai.ravenroot.api.node.service.OutboundHttpSigning;

import java.net.URI;
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
import java.util.concurrent.atomic.AtomicReference;

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
        boolean put = settings.operation() == StorageProfile.Operation.PUT;
        return execute(message, services, settings.profile(), actionGate, new Request(settings.destination(),
                settings.operation().name(), headers, body, settings.timeoutMs(), settings.maxBytes(), 0,
                put ? Semantics.MUTATION : Semantics.READ,
                response -> project(settings, body, response)));
    }

    CompletionStage<NodeResult> execute(NodeMessage message, NodePackageServices services, StorageProfile profile,
                                        Semaphore actionGate, Request request) {
        StorageAdmission.Result admitted = admission.acquire(message.tenantId() + "\u0000" + profile.name(),
                profile.maxConcurrency(), profile.maxRequestsPerSecond(), System.nanoTime());
        if (admitted.refusal() != null) {
            return CompletableFuture.failedFuture(StorageException.of(admitted.refusal() == StorageAdmission.Refusal.RATE
                    ? StorageException.Code.RATE_LIMITED : StorageException.Code.CAPACITY_UNAVAILABLE));
        }
        if (!actionGate.tryAcquire()) {
            admitted.lease().close();
            return CompletableFuture.failedFuture(StorageException.of(StorageException.Code.CAPACITY_UNAVAILABLE));
        }
        OutcomeFuture outcome = new OutcomeFuture(request.semantics().ambiguous);
        outcome.whenComplete((ignored, failure) -> {
            actionGate.release();
            admitted.lease().close();
        });
        long now = System.nanoTime();
        long timeoutNanos = Duration.ofMillis(request.timeoutMs()).toNanos();
        long deadline = now > Long.MAX_VALUE - timeoutNanos ? Long.MAX_VALUE : now + timeoutNanos;
        new Attempt(message, services, profile, request, outcome, deadline, admitted.lease()).start(0);
        return outcome;
    }

    @FunctionalInterface
    interface ResponseProjector { NodeResult project(OutboundHttpResponse response); }

    enum Semantics {
        READ(false, false), RETRYABLE_READ(false, true), MUTATION(true, false);
        final boolean ambiguous;
        final boolean retryable;
        Semantics(boolean ambiguous, boolean retryable) {
            this.ambiguous = ambiguous;
            this.retryable = retryable;
        }
    }

    record Request(URI destination, String method, Map<String, List<String>> headers, byte[] body,
                   int timeoutMs, int maxResponseBytes, int retries, Semantics semantics,
                   ResponseProjector projector) {
        Request {
            java.util.Objects.requireNonNull(destination);
            java.util.Objects.requireNonNull(method);
            headers = Map.copyOf(headers);
            body = body.clone();
            if (timeoutMs < 1 || maxResponseBytes < 0 || retries < 0 || retries > 3) {
                throw StorageException.of(StorageException.Code.CONFIGURATION);
            }
            java.util.Objects.requireNonNull(semantics);
            java.util.Objects.requireNonNull(projector);
        }
        @Override public byte[] body() { return body.clone(); }
    }

    private static final class Attempt {
        private final NodeMessage message;
        private final NodePackageServices services;
        private final StorageProfile profile;
        private final Request request;
        private final OutcomeFuture outcome;
        private final long deadline;
        private final StorageAdmission.Lease admission;

        Attempt(NodeMessage message, NodePackageServices services, StorageProfile profile, Request request,
                OutcomeFuture outcome, long deadline, StorageAdmission.Lease admission) {
            this.message = message;
            this.services = services;
            this.profile = profile;
            this.request = request;
            this.outcome = outcome;
            this.deadline = deadline;
            this.admission = admission;
        }

        void start(int number) {
            if (outcome.isDone()) return;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                outcome.completeExceptionally(StorageException.of(request.semantics().ambiguous
                        ? StorageException.Code.AMBIGUOUS : StorageException.Code.DEADLINE_EXCEEDED));
                return;
            }
            OutboundCall<OutboundHttpResponse> call;
            try {
                call = services.outboundHttp().execute(message, new OutboundHttpRequest(request.destination(),
                        request.method(), request.headers(), request.body(), Duration.ofNanos(remaining), null,
                        new OutboundHttpSigning(profile.signingBindingId())));
            } catch (RuntimeException failure) {
                failed(number, failure);
                return;
            }
            if (!outcome.install(call)) return;
            call.completion().whenComplete((response, failure) -> {
                outcome.clear(call);
                if (outcome.isDone()) return;
                if (failure != null) {
                    failed(number, failure);
                    return;
                }
                try {
                    if (response.body().length > request.maxResponseBytes()) {
                        throw StorageException.of(StorageException.Code.RESPONSE_TOO_LARGE);
                    }
                    if (canRetry(number) && retryableStatus(response.statusCode())) {
                        retry(number);
                        return;
                    }
                    outcome.complete(request.projector().project(response));
                } catch (RuntimeException invalid) {
                    outcome.completeExceptionally(invalid instanceof StorageException ? invalid
                            : StorageException.of(StorageException.Code.RESPONSE_INVALID));
                }
            });
        }

        private void failed(int number, Throwable failure) {
            if (canRetry(number) && retryable(failure) && deadline - System.nanoTime() > 0) {
                retry(number);
            } else {
                outcome.completeExceptionally(map(failure, request.semantics().ambiguous));
            }
        }

        private boolean canRetry(int number) {
            return request.semantics().retryable && request.retries() > number;
        }

        private void retry(int number) {
            if (!admission.retry(System.nanoTime())) {
                outcome.completeExceptionally(StorageException.of(StorageException.Code.RATE_LIMITED));
            } else {
                start(number + 1);
            }
        }

        private static boolean retryable(Throwable raw) {
            Throwable failure = raw;
            while ((failure instanceof CompletionException
                    || failure instanceof java.util.concurrent.ExecutionException)
                    && failure.getCause() != null) failure = failure.getCause();
            return failure instanceof NodePackageServiceException service
                    && service.reason() == NodePackageServiceException.Reason.TRANSPORT_FAILED;
        }

        private static boolean retryableStatus(int status) {
            return status == 500 || status == 502 || status == 503 || status == 504;
        }
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
        private final AtomicReference<OutboundCall<?>> call = new AtomicReference<>();
        private final boolean ambiguous;
        private final AtomicBoolean cancellation = new AtomicBoolean();
        OutcomeFuture(boolean ambiguous) { this.ambiguous = ambiguous; }
        boolean install(OutboundCall<?> current) {
            if (isDone() || cancellation.get()) {
                current.cancel();
                return false;
            }
            call.set(current);
            if ((isDone() || cancellation.get()) && call.compareAndSet(current, null)) {
                current.cancel();
                return false;
            }
            return true;
        }
        void clear(OutboundCall<?> current) { call.compareAndSet(current, null); }
        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            if (isDone() || !cancellation.compareAndSet(false, true)) return false;
            OutboundCall<?> active = call.getAndSet(null);
            if (active != null) active.cancel();
            completeExceptionally(StorageException.of(ambiguous ? StorageException.Code.AMBIGUOUS
                    : StorageException.Code.DEADLINE_EXCEEDED));
            return true;
        }
    }
}
