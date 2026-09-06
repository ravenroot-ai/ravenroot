package ai.ravenroot.api.persistence;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Static, graph-authored embedded confirmation contract pinned with a durable Human Task.
 *
 * <p>The value is display data and action policy only. It deliberately carries no HTML, URL,
 * callback, credential, execution payload, or third-party protocol. Version zero denotes the
 * classic Human Task experience and preserves rows created before embedded confirmations existed.</p>
 */
public record HumanTaskConfirmationPresentation(
        int version,
        String prompt,
        HumanTaskCommentRequirement commentRequirement,
        Set<HumanTaskConfirmationAction> actions,
        String resolveLabel,
        String denyLabel,
        String cancelLabel) {

    /** First and currently only embedded presentation wire version. */
    public static final int VERSION_1 = 1;
    /** Technical ceiling; the server policy normally configures a smaller bound. */
    public static final int HARD_MAX_PROMPT_UTF8_BYTES = 64 * 1024;
    /** Technical ceiling; the server policy normally configures a smaller bound. */
    public static final int HARD_MAX_ACTION_LABEL_UTF8_BYTES = 256;

    private static final HumanTaskConfirmationPresentation NONE =
            new HumanTaskConfirmationPresentation(0, "", HumanTaskCommentRequirement.DISALLOWED,
                    Set.of(), "", "", "");

    /** Validates the structural, versioned presentation shape. */
    public HumanTaskConfirmationPresentation {
        if (version < 0 || version > VERSION_1) {
            throw new IllegalArgumentException("unsupported human-task confirmation presentation version");
        }
        prompt = requireText(prompt, "prompt", HARD_MAX_PROMPT_UTF8_BYTES, version != 0);
        commentRequirement = Objects.requireNonNull(commentRequirement, "commentRequirement");
        actions = actions == null || actions.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(actions));
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

    /** Returns the exact label pinned for one supported action. */
    public String label(HumanTaskConfirmationAction action) {
        return switch (Objects.requireNonNull(action, "action")) {
            case RESOLVE -> resolveLabel;
            case DENY -> denyLabel;
            case CANCEL -> cancelLabel;
        };
    }

    /** Reports whether this task uses the embedded confirmation presentation. */
    public boolean embedded() {
        return version != 0;
    }

    /** Returns the compatibility-preserving classic presentation. */
    public static HumanTaskConfirmationPresentation none() {
        return NONE;
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
