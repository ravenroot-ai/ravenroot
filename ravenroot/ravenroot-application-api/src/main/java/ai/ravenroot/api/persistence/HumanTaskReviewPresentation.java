package ai.ravenroot.api.persistence;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Immutable, content-bound material deliberately selected for a Human Task responder to review.
 *
 * <p>Version zero represents tasks created without review content. Version one is deliberately
 * closed to inert {@code text/plain}: it carries no markup mode, URL, callback, form definition, or
 * executable client content. The digest binds the bytes displayed by an exact-task projection to
 * the bytes admitted when the task was registered without requiring logs or events to copy them.</p>
 *
 * @param version review-presentation wire version, zero when absent
 * @param contentType exact closed media type, blank only for version zero
 * @param text exact review text, including authored line endings
 * @param contentDigest SHA-256 binding of the UTF-8 review text, blank only for version zero
 * @param maxUtf8Bytes graph-authored inclusive byte limit pinned with the presentation
 */
public record HumanTaskReviewPresentation(int version, String contentType, String text,
                                          String contentDigest, int maxUtf8Bytes) {
    /** First and currently only review-presentation wire version. */
    public static final int VERSION_1 = 1;
    /** The only content type accepted by version one. */
    public static final String TEXT_PLAIN = "text/plain";
    /** Structural ceiling; deployments normally configure a lower admission maximum. */
    public static final int HARD_MAX_TEXT_UTF8_BYTES = 1024 * 1024;

    private static final HumanTaskReviewPresentation NONE =
            new HumanTaskReviewPresentation(0, "", "", "", 1);

    /** Validates the closed presentation and its byte-level integrity binding. */
    public HumanTaskReviewPresentation {
        if (version < 0 || version > VERSION_1) {
            throw new IllegalArgumentException("unsupported human-task review presentation version");
        }
        contentType = Objects.requireNonNull(contentType, "contentType");
        text = Objects.requireNonNull(text, "text");
        contentDigest = Objects.requireNonNull(contentDigest, "contentDigest");
        if (maxUtf8Bytes < 1 || maxUtf8Bytes > HARD_MAX_TEXT_UTF8_BYTES) {
            throw new IllegalArgumentException("review text byte limit is outside the technical bounds");
        }
        if (version == 0) {
            if (!contentType.isEmpty() || !text.isEmpty() || !contentDigest.isEmpty()
                    || maxUtf8Bytes != 1) {
                throw new IllegalArgumentException("absent review presentation cannot carry content");
            }
        } else {
            if (!TEXT_PLAIN.equals(contentType)) {
                throw new IllegalArgumentException("review presentation version one requires text/plain");
            }
            requireSafePlainText(text);
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > maxUtf8Bytes) {
                throw new IllegalArgumentException("review text exceeds its pinned byte limit");
            }
            String expected = ToolApprovalRegistration.digest(bytes);
            if (!expected.equals(contentDigest)) {
                throw new IllegalArgumentException("review content digest does not match review text");
            }
        }
    }

    /**
     * Creates an admitted version-one plain-text value with its canonical content binding.
     * @param text exact responder-visible review text.
     * @param maxUtf8Bytes inclusive pinned byte limit.
     * @return admitted immutable review presentation.
     */
    public static HumanTaskReviewPresentation plainText(String text, int maxUtf8Bytes) {
        Objects.requireNonNull(text, "text");
        return new HumanTaskReviewPresentation(VERSION_1, TEXT_PLAIN, text,
                ToolApprovalRegistration.digest(text.getBytes(StandardCharsets.UTF_8)), maxUtf8Bytes);
    }

    /**
     * Returns the compatibility value used by classic tasks and pre-feature persisted rows.
     * @return absent review presentation.
     */
    public static HumanTaskReviewPresentation none() {
        return NONE;
    }

    /**
     * Reports whether this value carries review material.
     * @return {@code true} only for an admitted version-one presentation.
     */
    public boolean present() {
        return version != 0;
    }

    private static void requireSafePlainText(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (index + 1 == value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException("review text contains malformed Unicode");
                }
                index++;
                continue;
            }
            if (Character.isLowSurrogate(unit)) {
                throw new IllegalArgumentException("review text contains malformed Unicode");
            }
            if (Character.isISOControl(unit) && unit != '\n' && unit != '\r' && unit != '\t') {
                throw new IllegalArgumentException("review text contains an unsafe control character");
            }
            if (isBidirectionalControl(unit)) {
                throw new IllegalArgumentException("review text contains a bidirectional formatting control");
            }
        }
    }

    private static boolean isBidirectionalControl(char unit) {
        return unit == '\u061c' || unit == '\u200e' || unit == '\u200f'
                || unit >= '\u202a' && unit <= '\u202e'
                || unit >= '\u2066' && unit <= '\u2069';
    }
}
