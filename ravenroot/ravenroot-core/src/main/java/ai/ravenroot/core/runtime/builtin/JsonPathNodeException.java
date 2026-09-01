package ai.ravenroot.core.runtime.builtin;

import ai.ravenroot.api.error.ErrorCode;
import ai.ravenroot.api.error.ErrorEnvelopeSource;

/** A classified, caller-safe refusal from the core {@code json-path} behavior. */
final class JsonPathNodeException extends IllegalArgumentException implements ErrorEnvelopeSource {
    private static final long serialVersionUID = 1L;

    enum Reason {
        INVALID_PATH("JSONPath expression is not valid RFC 9535"),
        INVALID_INPUT("JSONPath input is not valid bounded JSON"),
        RESOURCE_LIMIT("JSONPath processing exceeds the configured resource limits");

        private final String safeMessage;

        Reason(String safeMessage) {
            this.safeMessage = safeMessage;
        }
    }

    private final Reason reason;

    JsonPathNodeException(Reason reason) {
        super(reason.safeMessage);
        this.reason = reason;
    }

    Reason reason() {
        return reason;
    }

    @Override
    public ErrorCode errorCode() {
        return switch (reason) {
            case INVALID_PATH, INVALID_INPUT -> ErrorCode.INVALID_REQUEST;
            case RESOURCE_LIMIT -> ErrorCode.REQUEST_LIMIT_EXCEEDED;
        };
    }
}
