package ai.ravenroot.extensions.mail.imap;

import ai.ravenroot.api.error.ErrorCode;
import ai.ravenroot.api.error.ErrorEnvelopeSource;

/** A sanitized infrastructure or input failure raised before an IMAP mutation has an unknown effect. */
public final class ImapMutationException extends RuntimeException implements ErrorEnvelopeSource {
    public enum Code {
        INVALID_INPUT,
        PROFILE_UNAVAILABLE,
        CREDENTIAL_UNAVAILABLE,
        SATURATED,
        TIMEOUT,
        TRANSPORT_FAILURE
    }

    private final Code code;

    public ImapMutationException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    @Override public ErrorCode errorCode() {
        return switch (code) {
            case INVALID_INPUT -> ErrorCode.INVALID_REQUEST;
            case SATURATED -> ErrorCode.REQUEST_LIMIT_EXCEEDED;
            case TIMEOUT, TRANSPORT_FAILURE -> ErrorCode.REQUEST_INTERRUPTED;
            case PROFILE_UNAVAILABLE, CREDENTIAL_UNAVAILABLE -> ErrorCode.INTERNAL_ERROR;
        };
    }
}
