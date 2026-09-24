package ai.ravenroot.api.deployment;

/** Extension-owned source-start reason whose public use is gated by catalog registration. */
@FunctionalInterface
public interface SourceStartFailureCode {
    String code();

    static String requireValid(String code) {
        if (code == null || !code.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("source-start failure code must be lower-kebab-case and bounded");
        }
        return code;
    }
}
