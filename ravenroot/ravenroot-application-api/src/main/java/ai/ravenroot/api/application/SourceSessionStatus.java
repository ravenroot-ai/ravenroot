package ai.ravenroot.api.application;

import java.util.Objects;
import java.util.Optional;

/**
 * Bounded observation of one local inbound-source session.
 *
 * <p>{@code deploymentId} is the component that makes a listening session observable. A session
 * produces an unbounded series of traversals whose ids nobody can know in advance, so a client that
 * can only be told one execution id can never attribute the session's events to the graph it
 * started. {@link ExecutionEvent#deploymentId()} is the identity every one of those events already
 * carries, and this component is the other half of that join: the long-lived thing the session runs
 * under, named on the way out so a client has something stable to match against.</p>
 *
 * <p>It is an identity, not an authorization: observability is decided by
 * {@link AuthorizedRavenrootApplication} from its own ownership record, exactly as for every other
 * field here.</p>
 *
 * @param sessionId caller-supplied idempotency identity within the authenticated tenant
 * @param deploymentId identity of the long-lived deployment hosting this session's traversals; the
 * same value {@link ExecutionEvent#deploymentId()} carries on every event they produce
 * @param state current process-local lifecycle state
 * @param sourceCount number of effective SOURCE nodes validated from the submitted graph
 * @param diagnostic fixed, bounded operator-safe explanation for degraded or failed state
 */
public record SourceSessionStatus(String sessionId, String deploymentId, SourceSessionState state,
                                  int sourceCount, Optional<String> diagnostic) {
    /** Honest ownership label returned on the wire; intentionally makes no multi-replica claim. */
    public static final String SCOPE = "LOCAL_PROCESS";
    /** Defense in depth for implementations other than the reference implementation. */
    public static final int MAX_DIAGNOSTIC_CHARACTERS = 192;

    /** Validates and bounds the process-local session observation. */
public SourceSessionStatus {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (deploymentId == null || deploymentId.isBlank()) {
            throw new IllegalArgumentException("deploymentId must not be blank");
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
 * Creates a session status for an implementation that hosts a session as its own deployment.
 *
 * <p>The reference implementation registers a source session under a local-deployment key whose
 * deployment id <em>is</em> the session id, so naming the session twice states exactly what it
 * does. An implementation whose sessions run under a separately named deployment must use
 * {@link #of(String, String, SourceSessionState, int)} rather than this one: a client matches
 * events on {@code deploymentId}, and answering with the wrong one shows it nothing.</p>
 *
 * @param sessionId caller-supplied idempotency identity within the authenticated tenant
 * @param state current process-local source-session state
 * @param sourceCount positive number of effective inbound sources
 * @return validated status without a diagnostic, deployed under the session's own id
 */
public static SourceSessionStatus of(String sessionId, SourceSessionState state, int sourceCount) {
        return of(sessionId, sessionId, state, sourceCount);
    }

    /**
 * Creates a session status naming the deployment its traversals belong to.
 * @param sessionId caller-supplied idempotency identity within the authenticated tenant
 * @param deploymentId identity carried by every event this session's traversals produce
 * @param state current process-local source-session state
 * @param sourceCount positive number of effective inbound sources
 * @return validated status without a diagnostic
 */
public static SourceSessionStatus of(String sessionId, String deploymentId,
                                         SourceSessionState state, int sourceCount) {
        return new SourceSessionStatus(sessionId, deploymentId, state, sourceCount, Optional.empty());
    }

    /**
 * Creates a session status with an operator-safe diagnostic, deployed under the session's own id.
 * @param sessionId caller-supplied idempotency identity within the authenticated tenant
 * @param state current process-local source-session state
 * @param sourceCount positive number of effective inbound sources
 * @param safeDiagnostic bounded operator-safe diagnostic, or {@code null} when absent
 * @return validated status carrying the optional diagnostic
 */
public static SourceSessionStatus of(String sessionId, SourceSessionState state, int sourceCount,
                                         String safeDiagnostic) {
        return of(sessionId, sessionId, state, sourceCount, safeDiagnostic);
    }

    /**
 * Creates a session status with an operator-safe diagnostic and an explicit deployment identity.
 * @param sessionId caller-supplied idempotency identity within the authenticated tenant
 * @param deploymentId identity carried by every event this session's traversals produce
 * @param state current process-local source-session state
 * @param sourceCount positive number of effective inbound sources
 * @param safeDiagnostic bounded operator-safe diagnostic, or {@code null} when absent
 * @return validated status carrying the optional diagnostic
 */
public static SourceSessionStatus of(String sessionId, String deploymentId, SourceSessionState state,
                                         int sourceCount, String safeDiagnostic) {
        return new SourceSessionStatus(sessionId, deploymentId, state, sourceCount,
                Optional.ofNullable(safeDiagnostic));
    }
}
