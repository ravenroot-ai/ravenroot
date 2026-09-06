package ai.ravenroot.api.persistence;

import ai.ravenroot.api.payload.PayloadEnvelope;
import ai.ravenroot.api.payload.PayloadValue;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Static, graph-authored embedded confirmation contract pinned with a durable Human Task.
 *
 * <p>The value is display data and action policy only. It deliberately carries no HTML, URL,
 * callback, credential, execution payload, or third-party protocol. Version zero denotes the
 * classic Human Task experience and preserves rows created before embedded confirmations existed.</p>
 *
 * <p>This structural value remains able to represent historical rows. Current admission additionally
 * applies {@link HumanTaskPolicy.Confirmation#requirePresentation(HumanTaskConfirmationPresentation)},
 * which requires distinct normalized visible labels for the enabled actions.</p>
 *
 * @param version presentation wire version, zero for classic tasks.
 * @param prompt bounded plain-text confirmation prompt.
 * @param commentRequirement decision-comment rule.
 * @param actions unique actions in authored display order.
 * @param resolveLabel label for the resolve action.
 * @param denyLabel label for the deny action.
 * @param cancelLabel label for the cancel action.
 */
public record HumanTaskConfirmationPresentation(
        int version,
        String prompt,
        HumanTaskCommentRequirement commentRequirement,
        List<HumanTaskConfirmationAction> actions,
        String resolveLabel,
        String denyLabel,
        String cancelLabel) {

    /** First and currently only embedded presentation wire version. */
    public static final int VERSION_1 = 1;
    /** Exact content type of the server-authored confirmation response. */
    public static final String RESPONSE_CONTENT_TYPE = "application/json";
    /** Exact schema of the server-authored confirmation response. */
    public static final String RESPONSE_SCHEMA = "ravenroot.human-task.confirmation";
    /** Exact schema version of the server-authored confirmation response. */
    public static final String RESPONSE_SCHEMA_VERSION = "1";
    /** Technical ceiling; the server policy normally configures a smaller bound. */
    public static final int HARD_MAX_PROMPT_UTF8_BYTES = 64 * 1024;
    /** Technical ceiling; the server policy normally configures a smaller bound. */
    public static final int HARD_MAX_ACTION_LABEL_UTF8_BYTES = 256;

    private static final HumanTaskConfirmationPresentation NONE =
            new HumanTaskConfirmationPresentation(0, "", HumanTaskCommentRequirement.DISALLOWED,
                    List.of(), "", "", "");

    /** Validates the structural, versioned presentation shape. */
    public HumanTaskConfirmationPresentation {
        if (version < 0 || version > VERSION_1) {
            throw new IllegalArgumentException("unsupported human-task confirmation presentation version");
        }
        prompt = requireText(prompt, "prompt", HARD_MAX_PROMPT_UTF8_BYTES, version != 0);
        commentRequirement = Objects.requireNonNull(commentRequirement, "commentRequirement");
        actions = List.copyOf(actions == null ? List.of() : actions);
        if (actions.stream().anyMatch(Objects::isNull)
                || new HashSet<>(actions).size() != actions.size()) {
            throw new IllegalArgumentException("embedded confirmation actions must be unique");
        }
        resolveLabel = requireText(resolveLabel, "resolveLabel", HARD_MAX_ACTION_LABEL_UTF8_BYTES, false);
        denyLabel = requireText(denyLabel, "denyLabel", HARD_MAX_ACTION_LABEL_UTF8_BYTES, false);
        cancelLabel = requireText(cancelLabel, "cancelLabel", HARD_MAX_ACTION_LABEL_UTF8_BYTES, false);
        if (version == 0) {
            if (!prompt.isEmpty() || commentRequirement != HumanTaskCommentRequirement.DISALLOWED
                    || !actions.isEmpty() || !resolveLabel.isEmpty() || !denyLabel.isEmpty()
                    || !cancelLabel.isEmpty()) {
                throw new IllegalArgumentException("classic human-task presentation cannot carry embedded fields");
            }
        } else {
            if (actions.isEmpty()) {
                throw new IllegalArgumentException("embedded confirmation requires an action");
            }
            for (HumanTaskConfirmationAction action : actions) {
                String label = switch (action) {
                    case RESOLVE -> resolveLabel;
                    case DENY -> denyLabel;
                    case CANCEL -> cancelLabel;
                };
                if (label.isBlank()) {
                    throw new IllegalArgumentException("embedded confirmation action label cannot be blank");
                }
            }
        }
    }

    /**
     * Compatibility constructor for callers that supplied the former unordered action set.
     *
     * <p>The enum declaration order is the only deterministic order an unordered set can express.
     * New graph authoring paths use the canonical list constructor and retain authored order.</p>
     *
     * @param version presentation wire version.
     * @param prompt bounded confirmation prompt.
     * @param commentRequirement decision-comment rule.
     * @param actions unordered action subset.
     * @param resolveLabel label for resolve.
     * @param denyLabel label for deny.
     * @param cancelLabel label for cancel.
     */
    public HumanTaskConfirmationPresentation(int version, String prompt,
                                             HumanTaskCommentRequirement commentRequirement,
                                             Set<HumanTaskConfirmationAction> actions,
                                             String resolveLabel, String denyLabel,
                                             String cancelLabel) {
        this(version, prompt, commentRequirement,
                actions == null ? List.of() : java.util.Arrays.stream(
                        HumanTaskConfirmationAction.values()).filter(actions::contains).toList(),
                resolveLabel, denyLabel, cancelLabel);
    }

    /**
     * Returns the exact label pinned for one supported action.
     *
     * @param action action whose label is requested.
     * @return immutable pinned label.
     */
    public String label(HumanTaskConfirmationAction action) {
        return switch (Objects.requireNonNull(action, "action")) {
            case RESOLVE -> resolveLabel;
            case DENY -> denyLabel;
            case CANCEL -> cancelLabel;
        };
    }

    /**
     * Reports whether this task uses the embedded confirmation presentation.
     *
     * @return true for a nonzero presentation version.
     */
    public boolean embedded() {
        return version != 0;
    }

    /**
     * Returns the compatibility-preserving classic presentation.
     *
     * @return singleton version-zero presentation.
     */
    public static HumanTaskConfirmationPresentation none() {
        return NONE;
    }

    /**
     * Returns the canonical encoded bytes of the fixed boolean-true confirmation envelope.
     * @return fresh canonical response bytes
     */
    public static byte[] responseBytes() {
        return PayloadEnvelope.of(RESPONSE_SCHEMA, RESPONSE_SCHEMA_VERSION, PayloadValue.of(true))
                .toJson().getBytes(StandardCharsets.UTF_8);
    }

    private static String requireText(String value, String name, int maximum, boolean required) {
        value = value == null ? "" : value;
        if (required && value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (index + 1 == value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(name + " contains malformed Unicode");
                }
                index++;
                continue;
            }
            if (Character.isLowSurrogate(unit)) {
                throw new IllegalArgumentException(name + " contains malformed Unicode");
            }
            if (Character.isISOControl(unit)) {
                throw new IllegalArgumentException(name + " cannot contain control characters");
            }
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > maximum) {
            throw new IllegalArgumentException(name + " exceeds technical byte limit");
        }
        return value;
    }
}
