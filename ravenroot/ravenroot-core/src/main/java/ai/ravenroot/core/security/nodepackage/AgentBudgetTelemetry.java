package ai.ravenroot.core.security.nodepackage;

/** Low-cardinality numeric accounting projection; its type cannot carry identifiers or content. */
@FunctionalInterface
public interface AgentBudgetTelemetry {
    /**
     * Records one durable aggregate delta.
     * @param dimension fixed accounting dimension
     * @param outcome fixed lifecycle outcome
     * @param amount non-negative aggregate amount
     */
    void record(Dimension dimension, Outcome outcome, long amount);

    /** Fixed, identifier-free accounting dimensions. */
    enum Dimension {
        /** Model-turn proposals. */ TURNS,
        /** Model input tokens. */ INPUT_TOKENS,
        /** Model output tokens. */ OUTPUT_TOKENS,
        /** Elapsed milliseconds. */ ELAPSED_MILLIS,
        /** Monetary cost in root currency micros. */ COST_MICROS,
        /** Tool-call proposals. */ TOOL_CALLS,
        /** Cumulative unique child grants. */ TEAM_CUMULATIVE,
        /** Concurrent active child grants. */ TEAM_ACTIVE
    }

    /** Fixed, identifier-free reservation outcomes. */
    enum Outcome {
        /** Resources held before dispatch. */ RESERVED,
        /** Resources durably charged. */ USED,
        /** Resources or an active team slot released. */ RELEASED,
        /** Conservatively charged ambiguous effect outcome. */ INDETERMINATE,
        /** Provider usage exceeded its pre-authorized reservation. */ BREACHED
    }

    /**
     * Returns a telemetry sink that emits nothing.
     * @return sink that discards every aggregate
     */
    static AgentBudgetTelemetry discarding() { return (dimension, outcome, amount) -> { }; }

    /** Mutable composition-only relay; callers can emit before observability is installed safely. */
    final class Relay implements AgentBudgetTelemetry {
        private volatile AgentBudgetTelemetry delegate = discarding();

        @Override public void record(Dimension dimension, Outcome outcome, long amount) {
            delegate.record(dimension, outcome, amount);
        }

        /** Installs the process observability sink. */
        public void install(AgentBudgetTelemetry telemetry) {
            delegate = java.util.Objects.requireNonNull(telemetry, "telemetry");
        }

        /** Returns to the identifier-free discard behavior. */
        public void clear() {
            delegate = discarding();
        }
    }
}
