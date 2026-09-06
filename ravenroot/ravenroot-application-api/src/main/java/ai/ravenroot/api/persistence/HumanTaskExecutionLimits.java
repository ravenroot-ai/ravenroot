package ai.ravenroot.api.persistence;

import ai.ravenroot.api.payload.PayloadLimits;

import java.util.Objects;

/**
 * Recovery-sensitive limits captured when a durable human task is registered.
 *
 * <p>These values travel with the task because a later server configuration must not make a
 * previously accepted response contract fail differently after restart.</p>
 *
 * @param responsePayload structured-envelope budgets used for validation and recovery
 * @param decisionBodyMaxBytes raw HTTP envelope bytes accepted before parsing
 * @param writeAttempts maximum optimistic store attempts for one transition
 */
public record HumanTaskExecutionLimits(PayloadLimits responsePayload, int decisionBodyMaxBytes,
                                       int writeAttempts) {
    /**
     * Returns the historical recovery contract used before task limits were persisted explicitly.
     *
     * @param maxEncodedBytes positive encoded-response byte limit captured by the historical task
     * @return historical parser, decision-body, and three-attempt write limits
     */
    public static HumanTaskExecutionLimits legacy(int maxEncodedBytes) {
        return new HumanTaskExecutionLimits(new PayloadLimits(maxEncodedBytes,
                32, 1_024, 4_096, 16 * 1_024, 256),
                Math.max(256 * 1_024, maxEncodedBytes), 3);
    }

    /**
     * Validates a complete recovery-sensitive execution contract.
     *
     * @param responsePayload structured response limits pinned when the task is registered
     * @param decisionBodyMaxBytes inclusive raw HTTP decision-body byte limit pinned with the task
     * @param writeAttempts inclusive maximum optimistic persistence attempts pinned with the task
     */
    public HumanTaskExecutionLimits {
        responsePayload = Objects.requireNonNull(responsePayload, "responsePayload");
        if (decisionBodyMaxBytes < responsePayload.maxEncodedBytes()
                || decisionBodyMaxBytes > PayloadLimits.HARD_MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException("decisionBodyMaxBytes must cover the response payload");
        }
        if (writeAttempts < 1 || writeAttempts > HumanTaskPolicy.HARD_MAX_WRITE_ATTEMPTS) {
            throw new IllegalArgumentException("writeAttempts must be between 1 and "
                    + HumanTaskPolicy.HARD_MAX_WRITE_ATTEMPTS);
        }
    }

    /**
     * Compatibility constructor for callers that do not distinguish transport and payload caps.
     * The raw decision-body cap is set to the encoded response-payload cap.
     *
     * @param responsePayload structured response limits to pin with the task
     * @param writeAttempts inclusive maximum optimistic persistence attempts to pin with the task
     */
    public HumanTaskExecutionLimits(PayloadLimits responsePayload, int writeAttempts) {
        this(responsePayload, responsePayload.maxEncodedBytes(), writeAttempts);
    }
}
