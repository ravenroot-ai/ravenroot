package ai.ravenroot.core.runtime;

import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.core.graph.GraphMlLimits;

import java.util.Map;
import java.util.Objects;

/** Operator-owned limits for graph admission and one live traversal. */
public record GraphExecutionLimits(
        GraphMlLimits graphMl,
        PayloadLimits payload,
        int maxFanOut,
        int maxResidentActors,
        int maxLiveActorsPerTraversal,
        int maxInFlightHopsPerTraversal,
        int maxQueuedAdmissionsPerNode,
        long maxTraversalSteps,
        long maxAmplifiedDeliveries,
        long maxCumulativePayloadBytes,
        int maxRecoveryDeliveriesPerAttempt) {

    public static final String MAX_FAN_OUT_VARIABLE = "RAVENROOT_GRAPH_MAX_FAN_OUT";
    public static final String MAX_GRAPHML_BYTES_VARIABLE = "RAVENROOT_GRAPHML_MAX_BYTES";
    public static final String MAX_NODES_VARIABLE = "RAVENROOT_GRAPH_MAX_NODES";
    public static final String MAX_EDGES_VARIABLE = "RAVENROOT_GRAPH_MAX_EDGES";
    public static final String MAX_PROPERTIES_VARIABLE = "RAVENROOT_GRAPH_MAX_PROPERTIES";
    public static final String MAX_PAYLOAD_BYTES_VARIABLE = "RAVENROOT_GRAPH_MAX_PAYLOAD_BYTES";
    public static final String MAX_RESIDENT_ACTORS_VARIABLE = "RAVENROOT_GRAPH_MAX_RESIDENT_ACTORS";
    public static final String MAX_LIVE_ACTORS_VARIABLE = "RAVENROOT_GRAPH_MAX_LIVE_ACTORS_PER_TRAVERSAL";
    public static final String MAX_IN_FLIGHT_HOPS_VARIABLE = "RAVENROOT_GRAPH_MAX_IN_FLIGHT_HOPS";
    public static final String MAX_QUEUED_ADMISSIONS_VARIABLE = "RAVENROOT_GRAPH_MAX_QUEUED_ADMISSIONS_PER_NODE";
    public static final String MAX_TRAVERSAL_STEPS_VARIABLE = "RAVENROOT_GRAPH_MAX_TRAVERSAL_STEPS";
    public static final String MAX_AMPLIFIED_DELIVERIES_VARIABLE = "RAVENROOT_GRAPH_MAX_AMPLIFIED_DELIVERIES";
    public static final String MAX_CUMULATIVE_PAYLOAD_BYTES_VARIABLE = "RAVENROOT_GRAPH_MAX_CUMULATIVE_PAYLOAD_BYTES";
    public static final String MAX_RECOVERY_DELIVERIES_VARIABLE = "RAVENROOT_GRAPH_MAX_RECOVERY_DELIVERIES_PER_ATTEMPT";

    public static final int HARD_MAX_FAN_OUT = 256;
    public static final int HARD_MAX_RESIDENT_ACTORS = 4_096;
    public static final int HARD_MAX_LIVE_ACTORS = 1_024;
    public static final int HARD_MAX_IN_FLIGHT_HOPS = 4_096;
    public static final int HARD_MAX_QUEUED_ADMISSIONS = 4_096;
    public static final long HARD_MAX_TRAVERSAL_STEPS = 1_000_000L;
    public static final long HARD_MAX_AMPLIFIED_DELIVERIES = 1_000_000L;
    public static final long HARD_MAX_CUMULATIVE_PAYLOAD_BYTES = 256L * 1024 * 1024;
    public static final int HARD_MAX_RECOVERY_DELIVERIES = 64;

    public static final GraphExecutionLimits DEFAULTS = new GraphExecutionLimits(
            GraphMlLimits.DEFAULTS,
            PayloadLimits.DEFAULTS,
            64,
            256,
            256,
            1_024,
            1_024,
            100_000,
            100_000,
            64L * 1024 * 1024,
            8);

    public GraphExecutionLimits {
        Objects.requireNonNull(graphMl, "graphMl");
        Objects.requireNonNull(payload, "payload");
        positiveWithin("maxFanOut", maxFanOut, HARD_MAX_FAN_OUT);
        positiveWithin("maxResidentActors", maxResidentActors, HARD_MAX_RESIDENT_ACTORS);
        positiveWithin("maxLiveActorsPerTraversal", maxLiveActorsPerTraversal, HARD_MAX_LIVE_ACTORS);
        positiveWithin("maxInFlightHopsPerTraversal", maxInFlightHopsPerTraversal, HARD_MAX_IN_FLIGHT_HOPS);
        positiveWithin("maxQueuedAdmissionsPerNode", maxQueuedAdmissionsPerNode, HARD_MAX_QUEUED_ADMISSIONS);
        positiveWithin("maxTraversalSteps", maxTraversalSteps, HARD_MAX_TRAVERSAL_STEPS);
        positiveWithin("maxAmplifiedDeliveries", maxAmplifiedDeliveries, HARD_MAX_AMPLIFIED_DELIVERIES);
        positiveWithin("maxCumulativePayloadBytes", maxCumulativePayloadBytes,
                HARD_MAX_CUMULATIVE_PAYLOAD_BYTES);
        positiveWithin("maxRecoveryDeliveriesPerAttempt", maxRecoveryDeliveriesPerAttempt,
                HARD_MAX_RECOVERY_DELIVERIES);
    }

    /** Parses only the supplied map; core never reads ambient process configuration. */
    public static GraphExecutionLimits fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        GraphExecutionLimits defaults = DEFAULTS;
        GraphMlLimits graphMl = defaults.graphMl;
        graphMl = new GraphMlLimits(
                integer(environment, MAX_GRAPHML_BYTES_VARIABLE, graphMl.maxBytes()),
                integer(environment, MAX_NODES_VARIABLE, graphMl.maxNodes()),
                integer(environment, MAX_EDGES_VARIABLE, graphMl.maxEdges()),
                integer(environment, MAX_PROPERTIES_VARIABLE, graphMl.maxProperties()),
                graphMl.maxDepth(), graphMl.maxStringLength(), graphMl.maxKeys(), graphMl.maxElements(),
                graphMl.maxAttributes(), graphMl.maxNamespaceDeclarations());
        PayloadLimits payload = defaults.payload;
        payload = new PayloadLimits(
                integer(environment, MAX_PAYLOAD_BYTES_VARIABLE, payload.maxEncodedBytes()),
                payload.maxDepth(), payload.maxCollectionSize(), payload.maxValueCount(), payload.maxTextLength(),
                payload.maxKeyLength());
        return new GraphExecutionLimits(graphMl, payload,
                integer(environment, MAX_FAN_OUT_VARIABLE, defaults.maxFanOut),
                integer(environment, MAX_RESIDENT_ACTORS_VARIABLE, defaults.maxResidentActors),
                integer(environment, MAX_LIVE_ACTORS_VARIABLE, defaults.maxLiveActorsPerTraversal),
                integer(environment, MAX_IN_FLIGHT_HOPS_VARIABLE, defaults.maxInFlightHopsPerTraversal),
                integer(environment, MAX_QUEUED_ADMISSIONS_VARIABLE, defaults.maxQueuedAdmissionsPerNode),
                longInteger(environment, MAX_TRAVERSAL_STEPS_VARIABLE, defaults.maxTraversalSteps),
                longInteger(environment, MAX_AMPLIFIED_DELIVERIES_VARIABLE, defaults.maxAmplifiedDeliveries),
                longInteger(environment, MAX_CUMULATIVE_PAYLOAD_BYTES_VARIABLE,
                        defaults.maxCumulativePayloadBytes),
                integer(environment, MAX_RECOVERY_DELIVERIES_VARIABLE,
                        defaults.maxRecoveryDeliveriesPerAttempt));
    }

    private static int integer(Map<String, String> environment, String name, int fallback) {
        long value = longInteger(environment, name, fallback);
        if (value > Integer.MAX_VALUE) throw invalid(name, environment.get(name), null);
        return (int) value;
    }

    private static long longInteger(Map<String, String> environment, String name, long fallback) {
        String raw = environment.get(name);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException invalid) {
            throw invalid(name, raw, invalid);
        }
    }

    private static IllegalArgumentException invalid(String name, String raw, Throwable cause) {
        return new IllegalArgumentException(name + " must be a whole number", cause);
    }

    private static void positiveWithin(String name, long value, long ceiling) {
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        if (value > ceiling) throw new IllegalArgumentException(name + " exceeds the supported safety ceiling");
    }
}
