package ai.ravenroot.extensions.telegram;

public final class TelegramSendException extends RuntimeException {
    public enum Code { INVALID_INPUT, CONFIGURATION, CREDENTIAL_UNAVAILABLE, CAPACITY_UNAVAILABLE }
    private final Code code;
    TelegramSendException(Code code, String message) { super(message, null, false, false); this.code = code; }
    public Code code() { return code; }
}
