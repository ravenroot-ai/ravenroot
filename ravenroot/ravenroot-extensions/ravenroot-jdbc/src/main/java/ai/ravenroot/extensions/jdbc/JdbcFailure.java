package ai.ravenroot.extensions.jdbc;

final class JdbcFailure extends RuntimeException {
    enum Code {
        PROFILE_UNAVAILABLE, STATEMENT_UNAVAILABLE, INPUT_REJECTED, CREDENTIAL_UNAVAILABLE,
        DRIVER_REFUSED, SCHEMA_UNSUPPORTED, ADMISSION_REFUSED, DEADLINE_EXCEEDED, CANCELLED, EXECUTION_FAILED,
        RESULT_LIMIT_EXCEEDED, AMBIGUOUS_COMMIT
    }

    private final Code code;

    JdbcFailure(Code code) {
        super("JDBC_" + code.name());
        this.code = code;
    }

    Code code() { return code; }
}
