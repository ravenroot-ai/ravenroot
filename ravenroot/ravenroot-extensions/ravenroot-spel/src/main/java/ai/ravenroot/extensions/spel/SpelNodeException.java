package ai.ravenroot.extensions.spel;

final class SpelNodeException extends IllegalArgumentException {
    enum Code {
        EXPRESSION_MISSING,
        EXPRESSION_TOO_LONG,
        EXPRESSION_INVALID,
        AST_UNSUPPORTED,
        AST_LIMIT_EXCEEDED,
        FORBIDDEN_PROPERTY,
        INPUT_REJECTED,
        RESULT_REJECTED,
        DECISION_NOT_BOOLEAN,
        CAPACITY_UNAVAILABLE,
        DEADLINE_EXCEEDED,
        EVALUATION_FAILED
    }

    private final Code code;

    SpelNodeException(Code code) {
        super("SPEL_" + code.name());
        this.code = code;
    }

    Code code() {
        return code;
    }
}
