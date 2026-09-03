package ai.ravenroot.api.publication;

/**
 * Provenance supplied with a candidate and checked before policy rules run.
 * Empty fields represent incomplete provenance and are rejected by the guard.
 *
 * @param sourceType producer family
 * @param sourceId producer-owned stable identifier
 * @param sourceVersion immutable producer revision
 * @param contentDigest SHA-256 binding over the candidate resources
 */
public record PublicationProvenance(String sourceType, String sourceId, String sourceVersion,
                                    String contentDigest) {
    /** Normalizes absent fields to empty while bounding all retained metadata. */
    public PublicationProvenance {
        sourceType = bounded(sourceType, 128);
        sourceId = bounded(sourceId, 256);
        sourceVersion = bounded(sourceVersion, 128);
        contentDigest = bounded(contentDigest, 71);
    }

    /**
     * Whether every required field has a canonical usable value.
     *
     * @return {@code true} when every required field is complete and canonical
     */
    public boolean complete() {
        return sourceType.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                && !sourceId.isBlank() && !sourceVersion.isBlank()
                && contentDigest.matches("sha256:[0-9a-f]{64}");
    }

    private static String bounded(String value, int maximum) {
        if (value == null) return "";
        return value.length() <= maximum ? value : "";
    }
}
