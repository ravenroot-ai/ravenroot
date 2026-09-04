package ai.ravenroot.core.security.nodepackage;

/** Low-cardinality numeric accounting projection; its type cannot carry identifiers or content. */
@FunctionalInterface
public interface AgentBudgetTelemetry {
    void record(Dimension dimension, Outcome outcome, long amount);

    enum Dimension { TURNS, INPUT_TOKENS, OUTPUT_TOKENS, ELAPSED_MILLIS, COST_MICROS, TOOL_CALLS,
        TEAM_CUMULATIVE, TEAM_ACTIVE }
    enum Outcome { RESERVED, USED, RELEASED, INDETERMINATE }

    static AgentBudgetTelemetry discarding() { return (dimension, outcome, amount) -> { }; }
}
