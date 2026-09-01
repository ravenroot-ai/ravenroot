package ai.ravenroot.extensions.mail;

/** Safe, typed failure for invalid mail input or an undeliverable SMTP operation. */
public final class MailSendException extends RuntimeException {
    public enum Code { INVALID_INPUT, CONFIGURATION, CREDENTIAL_UNAVAILABLE, CAPACITY_UNAVAILABLE, AUTHENTICATION_FAILURE, TRANSPORT_FAILURE }
    private final Code code;
    MailSendException(Code code, String message) { super(message); this.code = code; }
    /** Deliberately does not retain a protocol cause: SMTP replies can contain hostile or secret text. */
    MailSendException(Code code, String message, Throwable cause) { super(message); this.code = code; }
    static MailSendException transportFailure(String classification) {
        MailSendException failure = new MailSendException(Code.TRANSPORT_FAILURE, "SMTP connection failed");
        failure.initCause(new SafeConnectionCause(classification));
        return failure;
    }
    private static final class SafeConnectionCause extends RuntimeException {
        SafeConnectionCause(String classification) { super(classification, null, false, false); }
    }
    public Code code() { return code; }
}
