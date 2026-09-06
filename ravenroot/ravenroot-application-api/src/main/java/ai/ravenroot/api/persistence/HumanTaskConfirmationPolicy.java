package ai.ravenroot.api.persistence;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Immutable operator-owned bounds for embedded Human Task confirmations and their reconciliation.
 *
 * <p>It is a companion to {@link HumanTaskPolicy}: keeping it separate preserves the established
 * {@code HumanTaskPolicy} record constructor while making every new presentation value explicit and
 * restart-owned. A graph may select a smaller prompt or label; it cannot expand these limits.</p>
 */
public record HumanTaskConfirmationPolicy(int maxPromptUtf8Bytes, int maxActionLabelUtf8Bytes,
                                          int maxCommentUtf8Bytes, int pollAfterMillis,
                                          int pollBackoffMaxMillis) {
    public static final int HARD_MAX_PROMPT_UTF8_BYTES =
            HumanTaskConfirmationPresentation.HARD_MAX_PROMPT_UTF8_BYTES;
    public static final int HARD_MAX_ACTION_LABEL_UTF8_BYTES =
            HumanTaskConfirmationPresentation.HARD_MAX_ACTION_LABEL_UTF8_BYTES;
    public static final int HARD_MAX_COMMENT_UTF8_BYTES = 64 * 1024 * 1024;
    public static final int MIN_POLL_MILLIS = 250;
    public static final int HARD_MAX_POLL_MILLIS = 300_000;

    /** Default confirmation policy used when no environment override is supplied. */
    public static final HumanTaskConfirmationPolicy DEFAULTS = new HumanTaskConfirmationPolicy(
            4 * 1024, 64, 4 * 1024, 1_000, 10_000);

    /** Validates independent bounds and the reconciliation relationship. */
    public HumanTaskConfirmationPolicy {
        bounded(maxPromptUtf8Bytes, 1, HARD_MAX_PROMPT_UTF8_BYTES, "maxPromptUtf8Bytes");
        bounded(maxActionLabelUtf8Bytes, 1, HARD_MAX_ACTION_LABEL_UTF8_BYTES,
                "maxActionLabelUtf8Bytes");
        bounded(maxCommentUtf8Bytes, 1, HARD_MAX_COMMENT_UTF8_BYTES, "maxCommentUtf8Bytes");
        bounded(pollAfterMillis, MIN_POLL_MILLIS, HARD_MAX_POLL_MILLIS, "pollAfterMillis");
        bounded(pollBackoffMaxMillis, pollAfterMillis, HARD_MAX_POLL_MILLIS,
                "pollBackoffMaxMillis");
    }

    /** Validates a graph-authored effective embedded presentation against this deployment policy. */
    public void requirePresentation(HumanTaskConfirmationPresentation presentation) {
        presentation = Objects.requireNonNull(presentation, "presentation");
        if (!presentation.embedded()) return;
        requireBytes(presentation.prompt(), maxPromptUtf8Bytes, "confirmation prompt");
        for (HumanTaskConfirmationAction action : presentation.actions()) {
            requireBytes(presentation.label(action), maxActionLabelUtf8Bytes,
                    "confirmation action label");
        }
    }

    /** Normalizes and validates one transport decision comment without treating it as payload. */
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
            if (Character.isISOControl(comment.charAt(index)) && comment.charAt(index) != '\n'
                    && comment.charAt(index) != '\t') {
                throw new IllegalArgumentException("decision comment contains a control character");
            }
        }
        requireBytes(comment, maxCommentUtf8Bytes, "decision comment");
        return comment;
    }

    private static void requireBytes(String value, int maximum, String name) {
        if (value.getBytes(StandardCharsets.UTF_8).length > maximum) {
            throw new IllegalArgumentException(name + " exceeds active policy byte limit");
        }
    }

    private static void bounded(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
    }
}
