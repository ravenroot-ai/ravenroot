package ai.ravenroot.extensions.matrix;

import ai.ravenroot.api.deployment.SourceStartFailureCode;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Compile-time-closed Matrix source-start failure vocabulary. */
enum MatrixSourceStartFailure implements SourceStartFailureCode {
    MATRIX_DURABLE_INGRESS_REQUIRED("matrix-durable-ingress-required"),
    MATRIX_INGRESS_AMBIGUOUS("matrix-ingress-ambiguous"),
    MATRIX_INGRESS_REFUSED("matrix-ingress-refused"),
    MATRIX_SYNC_AUTHENTICATION("matrix-sync-authentication"),
    MATRIX_SYNC_CANCELLED("matrix-sync-cancelled"),
    MATRIX_SYNC_CAPACITY("matrix-sync-capacity"),
    MATRIX_SYNC_EVENT_LIMIT("matrix-sync-event-limit"),
    MATRIX_SYNC_GAP("matrix-sync-gap"),
    MATRIX_SYNC_PROVIDER_STATUS("matrix-sync-provider-status"),
    MATRIX_SYNC_RATE_LIMIT("matrix-sync-rate-limit"),
    MATRIX_SYNC_TRANSPORT("matrix-sync-transport");

    private final String code;
    MatrixSourceStartFailure(String code) { this.code = SourceStartFailureCode.requireValid(code); }
    @Override public String code() { return code; }
    static Set<String> codes() {
        return Arrays.stream(values()).map(MatrixSourceStartFailure::code)
                .collect(Collectors.toUnmodifiableSet());
    }
}
