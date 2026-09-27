package ai.ravenroot.api.deployment;

/** Extension-owned source-start reason whose public use is gated by catalog registration. */
@FunctionalInterface
public interface SourceStartFailureCode {
    /**
     * Returns the extension-owned lower-kebab wire value.
     * @return bounded lower-kebab code
     */
    String code();

    /**
     * Validates the common source-start code grammar.
     * @param code extension-owned code candidate
     * @return the validated code
     */
    static String requireValid(String code) {
        if (code == null || !code.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("source-start failure code must be lower-kebab-case and bounded");
        }
        return code;
    }
}
