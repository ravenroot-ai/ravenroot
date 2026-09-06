package ai.ravenroot.api.persistence;

import ai.ravenroot.api.payload.PayloadEnvelope;
import ai.ravenroot.api.payload.PayloadLimits;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * One immutable operator-owned policy for Human Task authoring, HTTP, persistence, and recovery.
 * Graph-authored values may narrow this policy; they never widen it.
 *
 * @param defaultResponseBytes default maximum UTF-8 bytes for a complete encoded response envelope
 * @param maxResponseBytes inclusive maximum UTF-8 bytes a graph may allow for an encoded response envelope
 * @param defaultEscalationSeconds default seconds until escalation, or zero to disable escalation
 * @param maxEscalationSeconds inclusive maximum seconds until escalation; always below the maximum expiry
 * @param defaultExpirySeconds default positive seconds until task expiry
 * @param maxExpirySeconds inclusive maximum positive seconds until task expiry
 * @param maxTitleUtf8Bytes inclusive maximum UTF-8 bytes in a task title
 * @param maxDescriptionUtf8Bytes inclusive maximum UTF-8 bytes in a task description
 * @param maxResponseSchemaUtf8Bytes inclusive maximum UTF-8 bytes in the graph-authored response schema label
 * @param maxAuthorizationTokens inclusive maximum token count for each of the required-role and required-scope sets
 * @param maxAuthorizationTokenUtf8Bytes inclusive maximum UTF-8 bytes in each authorization token
 * @param decisionBodyMaxBytes inclusive maximum raw HTTP decision-body bytes accepted before parsing
 * @param inboxDefaultPageSize default number of task projections returned by an inbox request
 * @param inboxMaxPageSize inclusive maximum number of task projections returned by an inbox request
 * @param responseMaxDepth inclusive maximum nesting depth of a structured response
 * @param responseMaxCollectionSize inclusive maximum members in one response collection
 * @param responseMaxValueCount inclusive maximum total structured values in a response
 * @param responseMaxTextLength inclusive maximum UTF-16 code units in a response text value
 * @param responseMaxKeyLength inclusive maximum UTF-16 code units in a response object key
 * @param writeAttempts inclusive maximum optimistic persistence attempts for one task transition
 * @param confirmation embedded confirmation presentation and attention controls
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
        int writeAttempts,
        Confirmation confirmation) {

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

    /** The authoritative Human Task policy used when no operator override is supplied. */
    public static final HumanTaskPolicy DEFAULTS = new HumanTaskPolicy(
            64 * 1_024, 256 * 1_024,
            0, Duration.ofDays(30).toSeconds() - 1,
            Duration.ofDays(7).toSeconds(), Duration.ofDays(30).toSeconds(),
            256, 4 * 1_024, HARD_MAX_RESPONSE_SCHEMA_UTF8_BYTES,
            16, HandlerRegistration.MAX_KEY_UTF8_BYTES,
            256 * 1_024, 50, 100,
            32, 1_024, 4_096, 16 * 1_024, 256, 3, Confirmation.DEFAULTS);

    /**
     * Compatibility constructor retaining the established operational-policy shape.
     *
     * <p>Embedded confirmation controls default to {@link Confirmation#DEFAULTS}; callers that
     * need an operator-specific value use the canonical constructor.</p>
     *
     * @param defaultResponseBytes default encoded response bytes
     * @param maxResponseBytes maximum encoded response bytes
     * @param defaultEscalationSeconds default escalation delay
     * @param maxEscalationSeconds maximum escalation delay
     * @param defaultExpirySeconds default expiry delay
     * @param maxExpirySeconds maximum expiry delay
     * @param maxTitleUtf8Bytes maximum title bytes
     * @param maxDescriptionUtf8Bytes maximum description bytes
     * @param maxResponseSchemaUtf8Bytes maximum response-schema bytes
     * @param maxAuthorizationTokens maximum roles or scopes
     * @param maxAuthorizationTokenUtf8Bytes maximum bytes in one role or scope
     * @param decisionBodyMaxBytes maximum raw decision-body bytes
     * @param inboxDefaultPageSize default classic inbox page size
     * @param inboxMaxPageSize maximum classic inbox page size
     * @param responseMaxDepth maximum structured-response depth
     * @param responseMaxCollectionSize maximum collection members
     * @param responseMaxValueCount maximum total structured values
     * @param responseMaxTextLength maximum response text length
     * @param responseMaxKeyLength maximum response key length
     * @param writeAttempts maximum optimistic write attempts
     */
    public HumanTaskPolicy(int defaultResponseBytes, int maxResponseBytes,
                           long defaultEscalationSeconds, long maxEscalationSeconds,
                           long defaultExpirySeconds, long maxExpirySeconds,
                           int maxTitleUtf8Bytes, int maxDescriptionUtf8Bytes,
                           int maxResponseSchemaUtf8Bytes, int maxAuthorizationTokens,
                           int maxAuthorizationTokenUtf8Bytes, int decisionBodyMaxBytes,
                           int inboxDefaultPageSize, int inboxMaxPageSize, int responseMaxDepth,
                           int responseMaxCollectionSize, int responseMaxValueCount,
                           int responseMaxTextLength, int responseMaxKeyLength, int writeAttempts) {
        this(defaultResponseBytes, maxResponseBytes, defaultEscalationSeconds,
                maxEscalationSeconds, defaultExpirySeconds, maxExpirySeconds, maxTitleUtf8Bytes,
                maxDescriptionUtf8Bytes, maxResponseSchemaUtf8Bytes, maxAuthorizationTokens,
                maxAuthorizationTokenUtf8Bytes, decisionBodyMaxBytes, inboxDefaultPageSize,
                inboxMaxPageSize, responseMaxDepth, responseMaxCollectionSize,
                responseMaxValueCount, responseMaxTextLength, responseMaxKeyLength, writeAttempts,
                Confirmation.DEFAULTS);
    }

    /**
     * Validates individual inclusive bounds and the relationships between defaults, maxima,
     * escalation, expiry, response parsing, and raw-body capacity.
     */
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
        confirmation = Objects.requireNonNull(confirmation, "confirmation");
    }

    /**
     * Resolves the persisted structured-response contract for one graph-authored byte ceiling.
     *
     * @param graphResponseBytes positive encoded-envelope byte ceiling, no greater than the active policy maxima
     * @return recovery-sensitive parser, body, and write-attempt limits to pin with the task
     */
    public HumanTaskExecutionLimits executionLimits(int graphResponseBytes) {
        if (graphResponseBytes < 1 || graphResponseBytes > maxResponseBytes
                || graphResponseBytes > decisionBodyMaxBytes) {
            throw new IllegalArgumentException("graphResponseBytes is outside the Human Task policy");
        }
        return new HumanTaskExecutionLimits(new PayloadLimits(graphResponseBytes,
                responseMaxDepth, responseMaxCollectionSize, responseMaxValueCount,
                responseMaxTextLength, responseMaxKeyLength), decisionBodyMaxBytes, writeAttempts);
    }

    /**
     * Applies the complete active policy to a registration that is about to become durable.
     *
     * <p>Callers must perform exact durable deduplication first. That ordering lets a replay of the
     * same logical request remain idempotent after policy changes, while every new
     * registration passes through this single authority.</p>
     *
     * @param registration new durable registration to validate against this complete policy
     * @param now authoritative instant used to measure its remaining escalation and expiry durations
     */
    public void requireNewRegistration(HumanTaskRegistration registration, Instant now) {
        Objects.requireNonNull(registration, "registration");
        Objects.requireNonNull(now, "now");
        requireUtf8(registration.metadata().title(), maxTitleUtf8Bytes, "title");
        requireUtf8(registration.metadata().description(), maxDescriptionUtf8Bytes, "description");
        int responseBytes = registration.responseSchema().maxBytes();
        if (responseBytes < 1 || responseBytes > maxResponseBytes) {
            throw invalidRegistration("response max bytes exceed active policy");
        }
        requireUtf8(registration.responseSchema().schema(), maxResponseSchemaUtf8Bytes,
                "response schema");
        if (!PayloadEnvelope.isValidLabel(registration.responseSchema().schema())) {
            throw invalidRegistration("response schema violates the payload label protocol");
        }
        if (!PayloadEnvelope.isValidLabel(registration.responseSchema().schemaVersion())) {
            throw invalidRegistration("response schema version violates the payload label protocol");
        }
        requireTokens(registration.responderRequirements().requiredRoles(), "required roles");
        requireTokens(registration.responderRequirements().requiredScopes(), "required scopes");
        requireDelay(registration.expiresAt(), now, maxExpirySeconds, "expiry");
        registration.escalateAt().ifPresent(deadline ->
                requireDelay(deadline, now, maxEscalationSeconds, "escalation"));
        HumanTaskExecutionLimits expected = executionLimits(responseBytes);
        if (!expected.equals(registration.executionLimits())) {
            throw invalidRegistration("pinned execution limits do not match active policy");
        }
        if (registration.confirmationPresentation().embedded()) {
            confirmation.requirePresentation(registration.confirmationPresentation());
            HumanTaskResponseSchema response = registration.responseSchema();
            if (!HumanTaskConfirmationPresentation.RESPONSE_CONTENT_TYPE.equals(response.contentType())
                    || !HumanTaskConfirmationPresentation.RESPONSE_SCHEMA.equals(response.schema())
                    || !HumanTaskConfirmationPresentation.RESPONSE_SCHEMA_VERSION.equals(response.schemaVersion())
                    || response.kind() != ai.ravenroot.api.payload.PayloadKind.SCALAR
                    || response.maxBytes() < HumanTaskConfirmationPresentation.responseBytes().length
                    || confirmation.maximumJsonBodyBytes() > registration.executionLimits()
                    .decisionBodyMaxBytes()) {
                throw invalidRegistration("embedded confirmation contract exceeds its pinned transport budgets");
            }
            if (!confirmationLimits().equals(registration.confirmationLimits())) {
                throw invalidRegistration("pinned confirmation limits do not match active policy");
            }
        } else if (!HumanTaskConfirmationLimits.CLASSIC.equals(registration.confirmationLimits())) {
            throw invalidRegistration("classic registration carries embedded confirmation limits");
        }
    }

    private void requireTokens(Set<String> tokens, String name) {
        if (tokens.size() > maxAuthorizationTokens) {
            throw invalidRegistration(name + " exceed active policy count");
        }
        for (String token : tokens) {
            requireUtf8(token, maxAuthorizationTokenUtf8Bytes, name + " token");
        }
    }

    private static void requireUtf8(String value, int maximum, String name) {
        if (value.getBytes(StandardCharsets.UTF_8).length > maximum) {
            throw invalidRegistration(name + " exceeds active policy byte limit");
        }
    }

    private static void requireDelay(Instant deadline, Instant now, long maximumSeconds,
                                     String name) {
        Duration remaining = Duration.between(now, deadline);
        if (remaining.isZero() || remaining.isNegative()
                || remaining.compareTo(Duration.ofSeconds(maximumSeconds)) > 0) {
            throw invalidRegistration(name + " is outside active policy");
        }
    }

    /**
     * Returns the immutable embedded-confirmation limits to pin with a newly admitted task.
     *
     * @return current presentation and comment limits.
     */
    public HumanTaskConfirmationLimits confirmationLimits() {
        return new HumanTaskConfirmationLimits(confirmation.maxPromptUtf8Bytes(),
                confirmation.maxActionLabelUtf8Bytes(), confirmation.maxCommentUtf8Bytes());
    }

    private static IllegalArgumentException invalidRegistration(String reason) {
        return new IllegalArgumentException("human-task registration refused: " + reason);
    }

    private static void positive(int value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
    }

    private static void bounded(long value, long minimum, long maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
    }

    /**
     * Immutable operator-owned controls for embedded Human Task confirmations.
     *
     * @param maxPromptUtf8Bytes maximum prompt bytes.
     * @param maxActionLabelUtf8Bytes maximum bytes in one action label.
     * @param maxCommentUtf8Bytes maximum decision-comment bytes.
     * @param pollAfterMillis initial attention refresh delay.
     * @param pollBackoffMaxMillis maximum attention refresh delay.
     * @param attentionDefaultPageSize default attention page size.
     * @param attentionMaxPageSize maximum attention page size.
     */
    public record Confirmation(int maxPromptUtf8Bytes, int maxActionLabelUtf8Bytes,
                               int maxCommentUtf8Bytes, int pollAfterMillis,
                               int pollBackoffMaxMillis, int attentionDefaultPageSize,
                               int attentionMaxPageSize) {
        public static final int HARD_MAX_PROMPT_UTF8_BYTES =
                HumanTaskConfirmationPresentation.HARD_MAX_PROMPT_UTF8_BYTES;
        public static final int HARD_MAX_ACTION_LABEL_UTF8_BYTES =
                HumanTaskConfirmationPresentation.HARD_MAX_ACTION_LABEL_UTF8_BYTES;
        /** Comments are never projected in inbox rows; this bounds one durable decision record. */
        public static final int HARD_MAX_COMMENT_UTF8_BYTES = 16 * 1024;
        public static final int MIN_POLL_MILLIS = 250;
        public static final int HARD_MAX_POLL_MILLIS = 300_000;
        /** A selected-node page carries presentation copy; cap it at 100 rows for a 6.4 MiB prompt bound. */
        public static final int HARD_MAX_ATTENTION_PAGE_SIZE = 100;
        /** Matches the GraphML parser's supported node ceiling. */
        public static final int HARD_MAX_ATTENTION_NODE_COUNTS = 1_000_000;
        /** Conservative bytes outside the comment in {@code {"schemaVersion":1,"comment":""}}. */
        public static final int JSON_BODY_OVERHEAD_BYTES = 32;
        /** Static default rendered when a graph opts into presentation version one. */
        public static final String DEFAULT_PROMPT = "Confirm this task.";
        /** Default decision metadata rule for the built-in presentation. */
        public static final HumanTaskCommentRequirement DEFAULT_COMMENT_REQUIREMENT =
                HumanTaskCommentRequirement.OPTIONAL;
        /** Ordered built-in actions for presentation version one. */
        public static final java.util.List<HumanTaskConfirmationAction> DEFAULT_ACTIONS =
                java.util.List.of(HumanTaskConfirmationAction.values());
        public static final String DEFAULT_RESOLVE_LABEL = "Confirm";
        public static final String DEFAULT_DENY_LABEL = "Deny";
        public static final String DEFAULT_CANCEL_LABEL = "Cancel";
        public static final Confirmation DEFAULTS = new Confirmation(4 * 1024, 64,
                4 * 1024, 1_000, 10_000, 20, 100);

        /**
         * Compatibility constructor for the initial five confirmation controls.
         *
         * @param maxPromptUtf8Bytes maximum prompt bytes.
         * @param maxActionLabelUtf8Bytes maximum bytes in one action label.
         * @param maxCommentUtf8Bytes maximum decision-comment bytes.
         * @param pollAfterMillis initial attention refresh delay.
         * @param pollBackoffMaxMillis maximum attention refresh delay.
         */
        public Confirmation(int maxPromptUtf8Bytes, int maxActionLabelUtf8Bytes,
                            int maxCommentUtf8Bytes, int pollAfterMillis,
                            int pollBackoffMaxMillis) {
            this(maxPromptUtf8Bytes, maxActionLabelUtf8Bytes, maxCommentUtf8Bytes,
                    pollAfterMillis, pollBackoffMaxMillis, DEFAULTS.attentionDefaultPageSize(),
                    DEFAULTS.attentionMaxPageSize());
        }

        /** Validates technical ceilings and relationships between defaults and maxima. */
        public Confirmation {
            confirmationBounded(maxPromptUtf8Bytes, 1, HARD_MAX_PROMPT_UTF8_BYTES, "maxPromptUtf8Bytes");
            confirmationBounded(maxActionLabelUtf8Bytes, 1, HARD_MAX_ACTION_LABEL_UTF8_BYTES,
                    "maxActionLabelUtf8Bytes");
            confirmationBounded(maxCommentUtf8Bytes, 1, HARD_MAX_COMMENT_UTF8_BYTES, "maxCommentUtf8Bytes");
            confirmationBounded(pollAfterMillis, MIN_POLL_MILLIS, HARD_MAX_POLL_MILLIS, "pollAfterMillis");
            confirmationBounded(pollBackoffMaxMillis, pollAfterMillis, HARD_MAX_POLL_MILLIS,
                    "pollBackoffMaxMillis");
            confirmationBounded(attentionDefaultPageSize, 1, HARD_MAX_ATTENTION_PAGE_SIZE,
                    "attentionDefaultPageSize");
            confirmationBounded(attentionMaxPageSize, 1, HARD_MAX_ATTENTION_PAGE_SIZE,
                    "attentionMaxPageSize");
            if (attentionDefaultPageSize > attentionMaxPageSize) {
                throw new IllegalArgumentException(
                        "attentionDefaultPageSize cannot exceed attentionMaxPageSize");
            }
        }

        /**
         * Validates the graph-authored display contract against this deployment policy.
         *
         * @param presentation graph-authored presentation to validate.
         */
        public void requirePresentation(HumanTaskConfirmationPresentation presentation) {
            presentation = Objects.requireNonNull(presentation, "presentation");
            if (!presentation.embedded()) return;
            requireBytes(presentation.prompt(), maxPromptUtf8Bytes, "confirmation prompt");
            requireBytes(presentation.resolveLabel(), maxActionLabelUtf8Bytes,
                    "confirmation resolve label");
            requireBytes(presentation.denyLabel(), maxActionLabelUtf8Bytes,
                    "confirmation deny label");
            requireBytes(presentation.cancelLabel(), maxActionLabelUtf8Bytes,
                    "confirmation cancel label");
            var visibleLabels = new HashSet<String>();
            for (HumanTaskConfirmationAction action : presentation.actions()) {
                String visible = normalizedVisibleLabel(presentation.label(action));
                if (visible.isEmpty() || !visibleLabels.add(visible)) {
                    throw new IllegalArgumentException(
                            "active confirmation action labels must have distinct visible names");
                }
            }
        }

        /**
         * Produces the locale-independent comparison key used only for active visible action labels.
         * NFKC folds compatibility forms; Unicode whitespace and space separators collapse to one
         * ASCII space before trimming and lowercasing. The authored label itself is never changed.
         */
        private static String normalizedVisibleLabel(String label) {
            String normalized = Normalizer.normalize(label, Normalizer.Form.NFKC);
            var visible = new StringBuilder(normalized.length());
            boolean pendingSpace = false;
            for (int offset = 0; offset < normalized.length();) {
                int codePoint = normalized.codePointAt(offset);
                offset += Character.charCount(codePoint);
                if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                    pendingSpace = visible.length() != 0;
                } else {
                    if (pendingSpace) visible.append(' ');
                    visible.appendCodePoint(codePoint);
                    pendingSpace = false;
                }
            }
            return visible.toString().toLowerCase(Locale.ROOT);
        }

        /**
         * Normalizes bounded decision metadata, deliberately separate from execution payload.
         *
         * @param comment untrusted decision comment.
         * @param requirement pinned decision-comment rule.
         * @return stripped, validated comment.
         */
        public String normalizeComment(String comment, HumanTaskCommentRequirement requirement) {
            requirement = Objects.requireNonNull(requirement, "requirement");
            comment = comment == null ? "" : comment.strip();
            if (requirement == HumanTaskCommentRequirement.DISALLOWED && !comment.isEmpty()) {
                throw new IllegalArgumentException("decision comment is not allowed by this presentation");
            }
            if (requirement == HumanTaskCommentRequirement.REQUIRED && comment.isEmpty()) {
                throw new IllegalArgumentException("decision comment is required by this presentation");
            }
            for (int index = 0; index < comment.length(); index++) {
                char unit = comment.charAt(index);
                if (Character.isHighSurrogate(unit)) {
                    if (index + 1 == comment.length()
                            || !Character.isLowSurrogate(comment.charAt(index + 1))) {
                        throw new IllegalArgumentException("decision comment contains malformed Unicode");
                    }
                    index++;
                    continue;
                }
                if (Character.isLowSurrogate(unit)) {
                    throw new IllegalArgumentException("decision comment contains malformed Unicode");
                }
                if (Character.isISOControl(unit) && unit != '\n' && unit != '\t') {
                    throw new IllegalArgumentException("decision comment contains a control character");
                }
            }
            requireBytes(comment, maxCommentUtf8Bytes, "decision comment");
            return comment;
        }

        /**
         * Maximum JSON request bytes needed to carry any accepted comment, including six-byte
         * Unicode escapes for every single-byte character.
         * @return inclusive worst-case request size
         */
        public long maximumJsonBodyBytes() {
            return JSON_BODY_OVERHEAD_BYTES + 6L * maxCommentUtf8Bytes;
        }

        private static void requireBytes(String value, int maximum, String name) {
            if (value.getBytes(StandardCharsets.UTF_8).length > maximum) {
                throw new IllegalArgumentException(name + " exceeds active policy byte limit");
            }
        }

        private static void confirmationBounded(long value, long minimum, long maximum, String name) {
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
            }
        }
    }
}
