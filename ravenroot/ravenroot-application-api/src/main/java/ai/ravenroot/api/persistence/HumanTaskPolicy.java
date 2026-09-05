package ai.ravenroot.api.persistence;

import ai.ravenroot.api.payload.PayloadLimits;

import java.time.Duration;

/**
 * One immutable operator-owned policy for Human Task authoring, HTTP, persistence, and recovery.
 * Graph-authored values may narrow this policy; they never widen it.
 */
public record HumanTaskPolicy(
        int defaultResponseBytes,
        int maxResponseBytes,
        long defaultEscalationSeconds,
        long maxEscalationSeconds,
        long defaultExpirySeconds,
        long maxExpirySeconds,
        int maxTitleUtf8Bytes,
        int maxDescriptionUtf8Bytes,
        int maxResponseSchemaUtf8Bytes,
        int maxAuthorizationTokens,
        int maxAuthorizationTokenUtf8Bytes,
        int decisionBodyMaxBytes,
        int inboxDefaultPageSize,
        int inboxMaxPageSize,
        int responseMaxDepth,
        int responseMaxCollectionSize,
        int responseMaxValueCount,
        int responseMaxTextLength,
        int responseMaxKeyLength,
        int writeAttempts) {

    public static final int HARD_MAX_RESPONSE_BYTES = PayloadLimits.HARD_MAX_ENCODED_BYTES;
    /** Timer seconds stay in the positive signed 32-bit range used by catalog and deployment tooling. */
    public static final long HARD_MAX_DELAY_SECONDS = Integer.MAX_VALUE;
    public static final int HARD_MAX_TITLE_UTF8_BYTES = PayloadLimits.HARD_MAX_TEXT_LENGTH;
    public static final int HARD_MAX_DESCRIPTION_UTF8_BYTES = PayloadLimits.HARD_MAX_TEXT_LENGTH;
    /** Existing ASCII schema-label wire invariant; bytes and UTF-16 units are equal for its grammar. */
    public static final int HARD_MAX_RESPONSE_SCHEMA_UTF8_BYTES =
            ai.ravenroot.api.payload.PayloadEnvelope.MAX_LABEL_LENGTH;
    /**
     * Each role and scope axis may contain this many tokens. Together with the 4 KiB per-token
     * ceiling this bounds the two persisted authorization sets to 2 MiB of raw token text before
     * collection and encoding overhead, while leaving sixteen times the historical default.
     */
    public static final int HARD_MAX_AUTHORIZATION_TOKENS = 256;
    public static final int HARD_MAX_AUTHORIZATION_TOKEN_UTF8_BYTES = PayloadLimits.HARD_MAX_KEY_LENGTH;
    /** Bounds the rows and full task projections materialized for one inbox request. */
    public static final int HARD_MAX_INBOX_PAGE_SIZE = 1_000;
    /** Bounds synchronous load-and-apply work performed by one conflict-path settlement request. */
    public static final int HARD_MAX_WRITE_ATTEMPTS = 32;

    public static final HumanTaskPolicy DEFAULTS = new HumanTaskPolicy(
            64 * 1_024, 256 * 1_024,
            0, Duration.ofDays(30).toSeconds() - 1,
            Duration.ofDays(7).toSeconds(), Duration.ofDays(30).toSeconds(),
            256, 4 * 1_024, HARD_MAX_RESPONSE_SCHEMA_UTF8_BYTES,
            16, HandlerRegistration.MAX_KEY_UTF8_BYTES,
            256 * 1_024, 50, 100,
            32, 1_024, 4_096, 16 * 1_024, 256, 3);

    public HumanTaskPolicy {
        positive(defaultResponseBytes, "defaultResponseBytes");
        bounded(maxResponseBytes, 1, HARD_MAX_RESPONSE_BYTES, "maxResponseBytes");
        if (defaultResponseBytes > maxResponseBytes) {
            throw new IllegalArgumentException("defaultResponseBytes cannot exceed maxResponseBytes");
        }
        bounded(defaultExpirySeconds, 1, HARD_MAX_DELAY_SECONDS, "defaultExpirySeconds");
        bounded(maxExpirySeconds, 1, HARD_MAX_DELAY_SECONDS, "maxExpirySeconds");
        if (defaultExpirySeconds > maxExpirySeconds) {
            throw new IllegalArgumentException("defaultExpirySeconds cannot exceed maxExpirySeconds");
        }
        bounded(defaultEscalationSeconds, 0, HARD_MAX_DELAY_SECONDS - 1,
                "defaultEscalationSeconds");
        bounded(maxEscalationSeconds, 0, HARD_MAX_DELAY_SECONDS - 1,
                "maxEscalationSeconds");
        if (defaultEscalationSeconds > maxEscalationSeconds) {
            throw new IllegalArgumentException("defaultEscalationSeconds cannot exceed maxEscalationSeconds");
        }
        if (defaultEscalationSeconds >= defaultExpirySeconds && defaultEscalationSeconds != 0) {
            throw new IllegalArgumentException("defaultEscalationSeconds must be zero or below defaultExpirySeconds");
        }
        if (maxEscalationSeconds >= maxExpirySeconds) {
            throw new IllegalArgumentException("maxEscalationSeconds must be below maxExpirySeconds");
        }
        bounded(maxTitleUtf8Bytes, 1, HARD_MAX_TITLE_UTF8_BYTES, "maxTitleUtf8Bytes");
        bounded(maxDescriptionUtf8Bytes, 1, HARD_MAX_DESCRIPTION_UTF8_BYTES,
                "maxDescriptionUtf8Bytes");
        bounded(maxResponseSchemaUtf8Bytes, 1, HARD_MAX_RESPONSE_SCHEMA_UTF8_BYTES,
                "maxResponseSchemaUtf8Bytes");
        bounded(maxAuthorizationTokens, 1, HARD_MAX_AUTHORIZATION_TOKENS,
                "maxAuthorizationTokens");
        bounded(maxAuthorizationTokenUtf8Bytes, 1, HARD_MAX_AUTHORIZATION_TOKEN_UTF8_BYTES,
                "maxAuthorizationTokenUtf8Bytes");
        bounded(decisionBodyMaxBytes, 1, PayloadLimits.HARD_MAX_ENCODED_BYTES,
                "decisionBodyMaxBytes");
        // maxResponseBytes measures the complete encoded PayloadEnvelope received as the HTTP body,
        // including JSON structure and escaping. There is no hidden base64 wrapper at this route;
        // requiring the transport cap to cover that same encoded byte count therefore accounts for
        // the envelope overhead rather than comparing it with a decoded semantic payload size.
        if (maxResponseBytes > decisionBodyMaxBytes) {
            throw new IllegalArgumentException("maxResponseBytes cannot exceed decisionBodyMaxBytes");
        }
        bounded(inboxDefaultPageSize, 1, HARD_MAX_INBOX_PAGE_SIZE, "inboxDefaultPageSize");
        bounded(inboxMaxPageSize, 1, HARD_MAX_INBOX_PAGE_SIZE, "inboxMaxPageSize");
        if (inboxDefaultPageSize > inboxMaxPageSize) {
            throw new IllegalArgumentException("inboxDefaultPageSize cannot exceed inboxMaxPageSize");
        }
        // PayloadLimits supplies the representability and allocation ceilings shared by every
        // structured payload surface. Constructing it here validates all five configured budgets.
        new PayloadLimits(maxResponseBytes, responseMaxDepth, responseMaxCollectionSize,
                responseMaxValueCount, responseMaxTextLength, responseMaxKeyLength);
        bounded(writeAttempts, 1, HARD_MAX_WRITE_ATTEMPTS, "writeAttempts");
    }

    /** Resolves the persisted structured-response contract for one graph-authored byte ceiling. */
    public HumanTaskExecutionLimits executionLimits(int graphResponseBytes) {
        if (graphResponseBytes < 1 || graphResponseBytes > maxResponseBytes
                || graphResponseBytes > decisionBodyMaxBytes) {
            throw new IllegalArgumentException("graphResponseBytes is outside the Human Task policy");
        }
        return new HumanTaskExecutionLimits(new PayloadLimits(graphResponseBytes,
                responseMaxDepth, responseMaxCollectionSize, responseMaxValueCount,
                responseMaxTextLength, responseMaxKeyLength), decisionBodyMaxBytes, writeAttempts);
    }

    private static void positive(int value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
    }

    private static void bounded(long value, long minimum, long maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
    }
}
