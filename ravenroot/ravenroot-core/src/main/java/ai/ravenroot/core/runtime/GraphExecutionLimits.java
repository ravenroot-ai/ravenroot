package ai.ravenroot.core.runtime;

import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.persistence.GraphDefinitionStore;
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
    public static final String MAX_GRAPHML_DEPTH_VARIABLE = "RAVENROOT_GRAPHML_MAX_DEPTH";
    public static final String MAX_GRAPHML_STRING_LENGTH_VARIABLE = "RAVENROOT_GRAPHML_MAX_STRING_LENGTH";
    public static final String MAX_GRAPHML_KEYS_VARIABLE = "RAVENROOT_GRAPHML_MAX_KEYS";
    public static final String MAX_GRAPHML_ELEMENTS_VARIABLE = "RAVENROOT_GRAPHML_MAX_ELEMENTS";
    public static final String MAX_GRAPHML_ATTRIBUTES_VARIABLE = "RAVENROOT_GRAPHML_MAX_ATTRIBUTES";
    public static final String MAX_GRAPHML_NAMESPACE_DECLARATIONS_VARIABLE =
            "RAVENROOT_GRAPHML_MAX_NAMESPACE_DECLARATIONS";
    public static final String MAX_NODES_VARIABLE = "RAVENROOT_GRAPH_MAX_NODES";
    public static final String MAX_EDGES_VARIABLE = "RAVENROOT_GRAPH_MAX_EDGES";
    public static final String MAX_PROPERTIES_VARIABLE = "RAVENROOT_GRAPH_MAX_PROPERTIES";
    public static final String MAX_PAYLOAD_BYTES_VARIABLE = "RAVENROOT_GRAPH_MAX_PAYLOAD_BYTES";
    public static final String MAX_PAYLOAD_DEPTH_VARIABLE = "RAVENROOT_GRAPH_MAX_PAYLOAD_DEPTH";
    public static final String MAX_PAYLOAD_COLLECTION_SIZE_VARIABLE =
            "RAVENROOT_GRAPH_MAX_PAYLOAD_COLLECTION_SIZE";
    public static final String MAX_PAYLOAD_VALUE_COUNT_VARIABLE = "RAVENROOT_GRAPH_MAX_PAYLOAD_VALUE_COUNT";
    public static final String MAX_PAYLOAD_TEXT_LENGTH_VARIABLE = "RAVENROOT_GRAPH_MAX_PAYLOAD_TEXT_LENGTH";
    public static final String MAX_PAYLOAD_KEY_LENGTH_VARIABLE = "RAVENROOT_GRAPH_MAX_PAYLOAD_KEY_LENGTH";
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
                integer(environment, MAX_GRAPHML_BYTES_VARIABLE, graphMl.maxBytes(),
                        GraphDefinitionStore.HARD_MAX_DEFINITION_BYTES),
                integer(environment, MAX_NODES_VARIABLE, graphMl.maxNodes(), GraphMlLimits.HARD_MAX_NODES),
                integer(environment, MAX_EDGES_VARIABLE, graphMl.maxEdges(), GraphMlLimits.HARD_MAX_EDGES),
                integer(environment, MAX_PROPERTIES_VARIABLE, graphMl.maxProperties(),
                        GraphMlLimits.HARD_MAX_PROPERTIES),
                integer(environment, MAX_GRAPHML_DEPTH_VARIABLE, graphMl.maxDepth(), GraphMlLimits.HARD_MAX_DEPTH),
                integer(environment, MAX_GRAPHML_STRING_LENGTH_VARIABLE, graphMl.maxStringLength(),
                        GraphMlLimits.HARD_MAX_STRING_LENGTH),
                integer(environment, MAX_GRAPHML_KEYS_VARIABLE, graphMl.maxKeys(), GraphMlLimits.HARD_MAX_KEYS),
                integer(environment, MAX_GRAPHML_ELEMENTS_VARIABLE, graphMl.maxElements(),
                        GraphMlLimits.HARD_MAX_ELEMENTS),
                integer(environment, MAX_GRAPHML_ATTRIBUTES_VARIABLE, graphMl.maxAttributes(),
                        GraphMlLimits.HARD_MAX_ATTRIBUTES),
                integer(environment, MAX_GRAPHML_NAMESPACE_DECLARATIONS_VARIABLE,
                        graphMl.maxNamespaceDeclarations(), GraphMlLimits.HARD_MAX_NAMESPACE_DECLARATIONS));
        PayloadLimits payload = defaults.payload;
        payload = new PayloadLimits(
                integer(environment, MAX_PAYLOAD_BYTES_VARIABLE, payload.maxEncodedBytes(),
                        PayloadLimits.HARD_MAX_ENCODED_BYTES),
                integer(environment, MAX_PAYLOAD_DEPTH_VARIABLE, payload.maxDepth(), PayloadLimits.HARD_MAX_DEPTH),
                integer(environment, MAX_PAYLOAD_COLLECTION_SIZE_VARIABLE, payload.maxCollectionSize(),
                        PayloadLimits.HARD_MAX_COLLECTION_SIZE),
                integer(environment, MAX_PAYLOAD_VALUE_COUNT_VARIABLE, payload.maxValueCount(),
                        PayloadLimits.HARD_MAX_VALUE_COUNT),
                integer(environment, MAX_PAYLOAD_TEXT_LENGTH_VARIABLE, payload.maxTextLength(),
                        PayloadLimits.HARD_MAX_TEXT_LENGTH),
                integer(environment, MAX_PAYLOAD_KEY_LENGTH_VARIABLE, payload.maxKeyLength(),
                        PayloadLimits.HARD_MAX_KEY_LENGTH));
        return new GraphExecutionLimits(graphMl, payload,
                integer(environment, MAX_FAN_OUT_VARIABLE, defaults.maxFanOut, HARD_MAX_FAN_OUT),
                integer(environment, MAX_RESIDENT_ACTORS_VARIABLE, defaults.maxResidentActors,
                        HARD_MAX_RESIDENT_ACTORS),
                integer(environment, MAX_LIVE_ACTORS_VARIABLE, defaults.maxLiveActorsPerTraversal,
                        HARD_MAX_LIVE_ACTORS),
                integer(environment, MAX_IN_FLIGHT_HOPS_VARIABLE, defaults.maxInFlightHopsPerTraversal,
                        HARD_MAX_IN_FLIGHT_HOPS),
                integer(environment, MAX_QUEUED_ADMISSIONS_VARIABLE, defaults.maxQueuedAdmissionsPerNode,
                        HARD_MAX_QUEUED_ADMISSIONS),
                longInteger(environment, MAX_TRAVERSAL_STEPS_VARIABLE, defaults.maxTraversalSteps,
                        HARD_MAX_TRAVERSAL_STEPS),
                longInteger(environment, MAX_AMPLIFIED_DELIVERIES_VARIABLE, defaults.maxAmplifiedDeliveries,
                        HARD_MAX_AMPLIFIED_DELIVERIES),
                longInteger(environment, MAX_CUMULATIVE_PAYLOAD_BYTES_VARIABLE,
                        defaults.maxCumulativePayloadBytes, HARD_MAX_CUMULATIVE_PAYLOAD_BYTES),
                integer(environment, MAX_RECOVERY_DELIVERIES_VARIABLE,
                        defaults.maxRecoveryDeliveriesPerAttempt, HARD_MAX_RECOVERY_DELIVERIES));
    }

    private static int integer(Map<String, String> environment, String name, int fallback, int ceiling) {
        long value = longInteger(environment, name, fallback, ceiling);
        return (int) value;
    }

    private static long longInteger(Map<String, String> environment, String name, long fallback, long ceiling) {
        String raw = environment.get(name);
        if (raw == null || raw.isBlank()) return fallback;
        long value;
        try {
            value = Long.parseLong(raw.strip());
        } catch (NumberFormatException invalid) {
            throw invalid(name, ceiling);
        }
        if (value < 1 || value > ceiling) throw invalid(name, ceiling);
        return value;
    }

    private static IllegalArgumentException invalid(String name, long ceiling) {
        return new IllegalArgumentException(name + " must be a whole number from 1 through " + ceiling);
    }

    private static void positiveWithin(String name, long value, long ceiling) {
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        if (value > ceiling) throw new IllegalArgumentException(name + " exceeds the supported safety ceiling");
    }
}
