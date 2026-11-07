package ai.ravenroot.server.personal;

/** Closed, secret-free actionable states; provider responses are never exception messages. */
public final class PersonalConnectionException extends SecurityException {
    private final String code;
    public PersonalConnectionException(String code) { super(code); this.code = code; }
    public String code() { return code; }
}
