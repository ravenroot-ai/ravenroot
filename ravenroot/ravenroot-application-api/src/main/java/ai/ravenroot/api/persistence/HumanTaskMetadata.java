package ai.ravenroot.api.persistence;

import java.nio.charset.StandardCharsets;

/**
 * Bounded, graph-authored copy shown to a human without exposing execution payloads.
 *
 * @param title required task title.
 * @param description optional task description, represented by an empty string when absent.
 */
public record HumanTaskMetadata(String title, String description) {
    /** Technical encoded title ceiling; the operator policy normally supplies a smaller maximum. */
    public static final int MAX_TITLE_UTF8_BYTES = HumanTaskPolicy.HARD_MAX_TITLE_UTF8_BYTES;
    /** Technical description ceiling; the operator policy normally supplies a smaller maximum. */
    public static final int MAX_DESCRIPTION_UTF8_BYTES = HumanTaskPolicy.HARD_MAX_DESCRIPTION_UTF8_BYTES;

    /** Validates the bounded static display copy. */
    public HumanTaskMetadata {
        title = requireBounded(title, "title", MAX_TITLE_UTF8_BYTES, false);
        description = requireBounded(description, "description", MAX_DESCRIPTION_UTF8_BYTES, true);
    }

    private static String requireBounded(String value, String name, int limit, boolean blankAllowed) {
        if (value == null || (!blankAllowed && value.isBlank())) {
            throw new IllegalArgumentException(name + (blankAllowed ? " cannot be null" : " cannot be blank"));
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException(name + " cannot contain control characters");
            }
        }
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > limit) {
            throw new IllegalArgumentException(name + " is " + bytes + " UTF-8 bytes, above the "
                    + limit + "-byte bound");
        }
        return value;
    }
}
