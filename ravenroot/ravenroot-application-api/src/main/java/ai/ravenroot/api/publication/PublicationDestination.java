package ai.ravenroot.api.publication;

/**
 * Provider-neutral destination requested by a publication candidate.
 *
 * @param type bounded destination family such as {@code repository}, {@code message}, or {@code object-store}
 * @param address provider-owned opaque destination address; never suitable for diagnostics
 */
public record PublicationDestination(String type, String address) {
    /** Bounds destination metadata without interpreting provider syntax. */
    public PublicationDestination {
        if (type == null || !type.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("destination type must be a bounded canonical identifier");
        }
        if (address == null || address.isBlank() || address.length() > 2_048) {
            throw new IllegalArgumentException("destination address must be non-blank and at most 2048 characters");
        }
    }

    /**
     * Returns a fixed summary without destination values.
     *
     * @return a redacted summary
     */
    @Override
    public String toString() {
        return "PublicationDestination[protectedValues=redacted]";
    }
}
