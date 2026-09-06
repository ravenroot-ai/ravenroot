package ai.ravenroot.api.persistence;

/**
 * Immutable embedded-confirmation limits pinned with one durable Human Task registration.
 * @param maxPromptUtf8Bytes maximum UTF-8 bytes in the presentation prompt
 * @param maxActionLabelUtf8Bytes maximum UTF-8 bytes in one action label
 * @param maxCommentUtf8Bytes maximum UTF-8 bytes in separate decision metadata
 */
public record HumanTaskConfirmationLimits(int maxPromptUtf8Bytes, int maxActionLabelUtf8Bytes,
                                          int maxCommentUtf8Bytes) {
    /** Compatibility limits for a classic task that cannot accept embedded confirmation metadata. */
    public static final HumanTaskConfirmationLimits CLASSIC = new HumanTaskConfirmationLimits(1, 1, 1);

    /** Validates the persisted limits against the protocol's technical ceilings. */
    public HumanTaskConfirmationLimits {
        bounded(maxPromptUtf8Bytes, 1, HumanTaskPolicy.Confirmation.HARD_MAX_PROMPT_UTF8_BYTES,
                "maxPromptUtf8Bytes");
        bounded(maxActionLabelUtf8Bytes, 1,
                HumanTaskPolicy.Confirmation.HARD_MAX_ACTION_LABEL_UTF8_BYTES,
                "maxActionLabelUtf8Bytes");
        bounded(maxCommentUtf8Bytes, 1, HumanTaskPolicy.Confirmation.HARD_MAX_COMMENT_UTF8_BYTES,
                "maxCommentUtf8Bytes");
    }

    private static void bounded(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
    }
}
