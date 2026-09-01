package ai.ravenroot.api.application;

import java.util.Objects;
import java.util.Optional;

/**
 * Bounded observation of one local inbound-source session.
 *
 * @param sessionId caller-supplied idempotency identity within the authenticated tenant
 * @param state current process-local lifecycle state
 * @param sourceCount number of effective SOURCE nodes validated from the submitted graph
 * @param diagnostic fixed, bounded operator-safe explanation for degraded or failed state
 */
public record SourceSessionStatus(String sessionId, SourceSessionState state, int sourceCount,
                                  Optional<String> diagnostic) {
    /** Honest ownership label returned on the wire; intentionally makes no multi-replica claim. */
    public static final String SCOPE = "LOCAL_PROCESS";
    /** Defense in depth for implementations other than the reference implementation. */
    public static final int MAX_DIAGNOSTIC_CHARACTERS = 192;

    /** Validates and bounds the process-local session observation. */
public SourceSessionStatus {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        Objects.requireNonNull(state, "state");
        if (sourceCount < 1) throw new IllegalArgumentException("sourceCount must be positive");
        diagnostic = diagnostic == null ? Optional.empty() : diagnostic
                .map(String::trim).filter(text -> !text.isEmpty())
                .map(text -> text.substring(0, Math.min(text.length(), MAX_DIAGNOSTIC_CHARACTERS)));
        if (diagnostic.isPresent() && state != SourceSessionState.DEGRADED
                && state != SourceSessionState.FAILED) {
            throw new IllegalArgumentException("only degraded and failed sessions carry diagnostics");
        }
    }

    /**
 * Creates a session status without a diagnostic.
 * @param sessionId caller-supplied idempotency identity within the authenticated tenant
 * @param state current process-local source-session state
 * @param sourceCount positive number of effective inbound sources
 * @return validated status without a diagnostic
 */
public static SourceSessionStatus of(String sessionId, SourceSessionState state, int sourceCount) {
        return new SourceSessionStatus(sessionId, state, sourceCount, Optional.empty());
    }

    /**
 * Creates a session status with an operator-safe diagnostic.
 * @param sessionId caller-supplied idempotency identity within the authenticated tenant
 * @param state current process-local source-session state
 * @param sourceCount positive number of effective inbound sources
 * @param safeDiagnostic bounded operator-safe diagnostic, or {@code null} when absent
 * @return validated status carrying the optional diagnostic
 */
public static SourceSessionStatus of(String sessionId, SourceSessionState state, int sourceCount,
                                         String safeDiagnostic) {
        return new SourceSessionStatus(sessionId, state, sourceCount, Optional.ofNullable(safeDiagnostic));
    }
}
