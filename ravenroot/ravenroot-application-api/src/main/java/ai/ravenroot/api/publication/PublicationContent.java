package ai.ravenroot.api.publication;

import java.util.List;

/**
 * Immutable publication content represented as ordered text or base64 fragments.
 * Fragment boundaries remain visible so a policy evaluator can detect values split across them.
 */
public sealed interface PublicationContent permits PublicationContent.Text, PublicationContent.Base64Binary {

    /**
     * UTF-16 Java text whose publication byte size is measured as UTF-8.
     *
     * @param fragments ordered non-null fragments
     */
    record Text(List<String> fragments) implements PublicationContent {
        /** Takes an immutable snapshot and rejects null fragments. */
        public Text {
            fragments = List.copyOf(fragments == null ? List.of() : fragments);
            if (fragments.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("text fragments cannot contain null");
            }
        }

        /**
         * Creates one-fragment text content.
         *
         * @param text text content, or {@code null} for an empty fragment
         */
        public Text(String text) {
            this(List.of(text == null ? "" : text));
        }
    }

    /**
     * Binary content encoded as independently decodable base64 fragments.
     *
     * @param fragments ordered canonical or padded base64 fragments
     */
    record Base64Binary(List<String> fragments) implements PublicationContent {
        /** Takes an immutable snapshot and rejects null fragments. */
        public Base64Binary {
            fragments = List.copyOf(fragments == null ? List.of() : fragments);
            if (fragments.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("binary fragments cannot contain null");
            }
        }

        /**
         * Creates one-fragment binary content.
         *
         * @param base64 base64 content, or {@code null} for an empty fragment
         */
        public Base64Binary(String base64) {
            this(List.of(base64 == null ? "" : base64));
        }
    }
}
