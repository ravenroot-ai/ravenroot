package ai.ravenroot.extensions.jdbc;

final class JdbcFailure extends RuntimeException implements ai.ravenroot.api.execution.RetryClassified {
    enum Code {
        PROFILE_UNAVAILABLE, STATEMENT_UNAVAILABLE, INPUT_REJECTED, CREDENTIAL_UNAVAILABLE,
        DRIVER_REFUSED, DRIVER_AMBIGUOUS, SCHEMA_UNSUPPORTED, ADMISSION_REFUSED, DEADLINE_EXCEEDED, CANCELLED, EXECUTION_FAILED,
        RESULT_LIMIT_EXCEEDED, AMBIGUOUS_COMMIT
    }

    private final Code code;

    JdbcFailure(Code code) {
        super("JDBC_" + code.name());
        this.code = code;
    }

    Code code() { return code; }

    @Override public ai.ravenroot.api.persistence.Retryability retryability() {
        return code == Code.AMBIGUOUS_COMMIT
                ? ai.ravenroot.api.persistence.Retryability.INDETERMINATE
                : code == Code.EXECUTION_FAILED || code == Code.DEADLINE_EXCEEDED
                || code == Code.CANCELLED || code == Code.ADMISSION_REFUSED
                || code == Code.CREDENTIAL_UNAVAILABLE
                ? ai.ravenroot.api.persistence.Retryability.RETRYABLE_NO_EFFECT
                : ai.ravenroot.api.persistence.Retryability.DETERMINISTIC_REJECT;
    }
}
