package ai.ravenroot.api.publication;

import java.util.Objects;

/**
 * One logical resource proposed for publication.
 *
 * @param logicalPath provider-neutral forward-slash path or logical name
 * @param artifactType operator-defined artifact family
 * @param mediaType declared media type
 * @param language declared content language or empty when not applicable
 * @param content immutable ordered content fragments
 */
public record PublicationResource(String logicalPath, String artifactType, String mediaType,
                                  String language, PublicationContent content) {
    /** Validates bounded metadata; content values are deliberately absent from failures. */
    public PublicationResource {
        logicalPath = bounded(logicalPath, 2_048, "logical path");
        artifactType = token(artifactType, "artifact type");
        mediaType = bounded(mediaType, 255, "media type");
        language = language == null ? "" : language;
        if (language.length() > 63) {
            throw new IllegalArgumentException("language must be at most 63 characters");
        }
        Objects.requireNonNull(content, "content");
    }

    /**
     * Returns a bounded summary without resource metadata or content.
     *
     * @return a redacted summary
     */
    @Override
    public String toString() {
        return "PublicationResource[contentType=" + content.getClass().getSimpleName()
                + ", protectedValues=redacted]";
    }

    private static String token(String value, String name) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:+/-]{0,127}")) {
            throw new IllegalArgumentException(name + " must be a bounded canonical identifier");
        }
        return value;
    }

    private static String bounded(String value, int maximum, String name) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(name + " must be non-blank and at most " + maximum + " characters");
        }
        return value;
    }
}
