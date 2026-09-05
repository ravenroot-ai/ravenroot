package ai.ravenroot.core.runtime;

/** A payload-free, machine-classifiable refusal of graph work that exceeds an execution limit. */
public final class GraphExecutionLimitException extends IllegalArgumentException {
    private final Reason reason;
    private final long observed;
    private final long limit;

    public GraphExecutionLimitException(Reason reason, long observed, long limit) {
        super(java.util.Objects.requireNonNull(reason, "reason").message(observed, limit));
        this.reason = reason;
        this.observed = observed;
        this.limit = limit;
    }

    public Reason reason() {
        return reason;
    }

    public long observed() {
        return observed;
    }

    public long limit() {
        return limit;
    }

    /** Closed public vocabulary. No graph identifier, property value, or payload can enter it. */
    public enum Reason {
        NODES,
        EDGES,
        PROPERTIES,
        FAN_OUT,
        RESIDENT_ACTORS,
        LIVE_ACTORS,
        IN_FLIGHT_HOPS,
        ADMISSION_QUEUE,
        TRAVERSAL_STEPS,
        AMPLIFIED_DELIVERIES,
        PAYLOAD_BYTES,
        RECOVERY_DELIVERIES;

        public String publicCode() {
            return "GRAPH_LIMIT_" + name() + "_EXCEEDED";
        }

        private String message(long observed, long limit) {
            return "Graph execution limit " + name() + " exceeded: observed " + observed + ", limit " + limit;
        }
    }
}
