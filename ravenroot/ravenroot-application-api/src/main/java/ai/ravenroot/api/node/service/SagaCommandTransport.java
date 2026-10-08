package ai.ravenroot.api.node.service;

import ai.ravenroot.api.persistence.SagaCommandIntent;

import java.util.concurrent.CompletionStage;

/**
 * Trusted participant adapter used by the governed saga application-command outbox.
 *
 * <p>The adapter receives only a command already admitted by {@link SagaCommandAuthority}. It must
 * preserve the stable operation identity, use a cooperating idempotency/inbox protocol and return
 * business completion only from a participant-owned receipt bound to the exact operation and
 * fingerprint. A broker acknowledgement or an unknown participant outcome is not business
 * completion.</p>
 */
public interface SagaCommandTransport {
    /**
     * Delivers one stable participant command at least once and waits for transport acceptance.
     *
     * @param intent immutable command carrying the stable message identity
     * @return confirmed transport result; a failed stage is treated as retryable delivery failure
     */
    CompletionStage<BrokerResult> publish(SagaCommandIntent intent);

    /**
     * Looks up the participant completion receipt after transport acceptance.
     *
     * @param intent command whose business completion is being reconciled
     * @return whether the receiving participant has atomically recorded its inbox receipt and effect
     */
    CompletionStage<Boolean> businessCompleted(SagaCommandIntent intent);

    /**
     * Transport-accepted delivery result.
     * @param accepted whether the cooperating participant transport accepted it
     * @param safeDetail redacted diagnostic
     */
    record BrokerResult(boolean accepted, String safeDetail) {
        /** Validates the bounded, log-safe result. */
        public BrokerResult {
            safeDetail = safeDetail == null ? "" : safeDetail;
            if (safeDetail.length() > 512) throw new IllegalArgumentException("safeDetail is too long");
        }
    }
}
