package ai.ravenroot.api.persistence;

import ai.ravenroot.api.node.service.NodePackageEgressCapacityProfile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable operational values selected when one execution is accepted.
 *
 * <p>Every component is a bounded number, duration, Boolean or package identifier. Authorization
 * grants and secret material have no representation here. The fixed binary encoding is used only as
 * the durable SQL value; callers always receive this typed form.</p>
 */
public record ResolvedOperationalPolicy(GraphLimits graph, ResultLimits results,
                                        Optional<BuiltInHttpCapacity> builtInHttp,
                                        List<PackageCapacity> nodePackages) {
    private static final int ENCODING_VERSION = 1;
    private static final int MAX_POLICY_BYTES = 512 * 1024;
    private static final int MAX_BASE64_CHARACTERS = ((MAX_POLICY_BYTES + 2) / 3) * 4;

    public ResolvedOperationalPolicy {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(results, "results");
        Objects.requireNonNull(builtInHttp, "builtInHttp");
        Objects.requireNonNull(nodePackages, "nodePackages");
        if (nodePackages.size() > ExecutionManifest.MAX_NODE_PACKAGES) {
            throw new IllegalArgumentException("too many package capacity entries");
        }
        var sorted = new ArrayList<>(nodePackages);
        sorted.forEach(entry -> Objects.requireNonNull(entry, "package capacity"));
        sorted.sort(java.util.Comparator.comparing(PackageCapacity::packageId));
        var seen = new HashSet<String>();
        for (PackageCapacity entry : sorted) {
            if (!seen.add(entry.packageId())) {
                throw new IllegalArgumentException("duplicate package capacity");
            }
        }
        nodePackages = List.copyOf(sorted);
    }

    /** Compatibility constructor for policies whose graphs do not use core HTTP. */
    public ResolvedOperationalPolicy(GraphLimits graph, ResultLimits results,
                                     List<PackageCapacity> nodePackages) {
        this(graph, results, Optional.empty(), nodePackages);
    }

    /** Canonical durable value, with no free-form policy or secret field. */
    public String encode() {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.writeInt(ENCODING_VERSION);
                graph.write(out);
                out.writeBoolean(results.durable());
                out.writeInt(results.maximumPayloadBytes());
                out.writeBoolean(builtInHttp.isPresent());
                if (builtInHttp.isPresent()) builtInHttp.orElseThrow().write(out);
                out.writeInt(nodePackages.size());
                for (PackageCapacity entry : nodePackages) entry.write(out);
            }
            byte[] value = bytes.toByteArray();
            if (value.length > MAX_POLICY_BYTES) {
                throw new IllegalArgumentException("operational policy is too large");
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
        } catch (IOException impossible) {
            throw new IllegalStateException("could not encode resolved operational policy", impossible);
        }
    }

    /** Decodes and canonicalizes one stored value, rejecting unknown or trailing data. */
    public static ResolvedOperationalPolicy decode(String encoded) {
        try {
            Objects.requireNonNull(encoded, "encoded");
            if (encoded.length() > MAX_BASE64_CHARACTERS) {
                throw new IllegalArgumentException("policy is too large");
            }
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            if (bytes.length > MAX_POLICY_BYTES) throw new IllegalArgumentException("policy is too large");
            ResolvedOperationalPolicy policy;
            try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
                if (in.readInt() != ENCODING_VERSION) {
                    throw new IllegalArgumentException("unsupported operational policy encoding");
                }
                GraphLimits graph = GraphLimits.read(in);
                ResultLimits results = new ResultLimits(in.readBoolean(), in.readInt());
                Optional<BuiltInHttpCapacity> builtInHttp = in.readBoolean()
                        ? Optional.of(BuiltInHttpCapacity.read(in)) : Optional.empty();
                int count = in.readInt();
                if (count < 0 || count > ExecutionManifest.MAX_NODE_PACKAGES) {
                    throw new IllegalArgumentException("invalid package capacity count");
                }
                var packages = new ArrayList<PackageCapacity>(count);
                for (int i = 0; i < count; i++) packages.add(PackageCapacity.read(in));
                if (in.read() != -1) throw new IllegalArgumentException("trailing operational policy data");
                policy = new ResolvedOperationalPolicy(graph, results, builtInHttp, packages);
            }
            if (!policy.encode().equals(encoded)) {
                throw new IllegalArgumentException("operational policy is not canonical");
            }
            return policy;
        } catch (EOFException truncated) {
            throw new IllegalArgumentException("truncated operational policy", truncated);
        } catch (IOException | IllegalArgumentException malformed) {
            if (malformed instanceof IllegalArgumentException invalid) throw invalid;
            throw new IllegalArgumentException("invalid operational policy", malformed);
        }
    }

    /** The graph parser, payload and live traversal budgets used by runtime consumers. */
    public record GraphLimits(int graphMlMaxBytes, int graphMlMaxNodes, int graphMlMaxEdges,
                              int graphMlMaxProperties, int graphMlMaxDepth,
                              int graphMlMaxStringLength, int graphMlMaxKeys,
                              int graphMlMaxElements, int graphMlMaxAttributes,
                              int graphMlMaxNamespaceDeclarations, int payloadMaxEncodedBytes,
                              int payloadMaxDepth, int payloadMaxCollectionSize,
                              int payloadMaxValueCount, int payloadMaxTextLength,
                              int payloadMaxKeyLength, int maxFanOut, int maxResidentActors,
                              int maxLiveActorsPerTraversal, int maxInFlightHopsPerTraversal,
                              int maxQueuedAdmissionsPerNode, long maxTraversalSteps,
                              long maxAmplifiedDeliveries, long maxCumulativePayloadBytes,
                              int maxRecoveryDeliveriesPerAttempt) {
        public GraphLimits {
            if (java.util.stream.IntStream.of(graphMlMaxBytes, graphMlMaxNodes, graphMlMaxEdges,
                    graphMlMaxProperties, graphMlMaxDepth, graphMlMaxStringLength, graphMlMaxKeys,
                    graphMlMaxElements, graphMlMaxAttributes, graphMlMaxNamespaceDeclarations,
                    payloadMaxEncodedBytes, payloadMaxDepth, payloadMaxCollectionSize,
                    payloadMaxValueCount, payloadMaxTextLength, payloadMaxKeyLength, maxFanOut,
                    maxResidentActors, maxLiveActorsPerTraversal, maxInFlightHopsPerTraversal,
                    maxQueuedAdmissionsPerNode, maxRecoveryDeliveriesPerAttempt).anyMatch(v -> v < 1)
                    || maxTraversalSteps < 1 || maxAmplifiedDeliveries < 1
                    || maxCumulativePayloadBytes < 1) {
                throw new IllegalArgumentException("graph operational limits must be positive");
            }
        }

        private void write(DataOutputStream out) throws IOException {
            out.writeInt(graphMlMaxBytes); out.writeInt(graphMlMaxNodes); out.writeInt(graphMlMaxEdges);
            out.writeInt(graphMlMaxProperties); out.writeInt(graphMlMaxDepth);
            out.writeInt(graphMlMaxStringLength); out.writeInt(graphMlMaxKeys);
            out.writeInt(graphMlMaxElements); out.writeInt(graphMlMaxAttributes);
            out.writeInt(graphMlMaxNamespaceDeclarations); out.writeInt(payloadMaxEncodedBytes);
            out.writeInt(payloadMaxDepth); out.writeInt(payloadMaxCollectionSize);
            out.writeInt(payloadMaxValueCount); out.writeInt(payloadMaxTextLength);
            out.writeInt(payloadMaxKeyLength); out.writeInt(maxFanOut); out.writeInt(maxResidentActors);
            out.writeInt(maxLiveActorsPerTraversal); out.writeInt(maxInFlightHopsPerTraversal);
            out.writeInt(maxQueuedAdmissionsPerNode); out.writeLong(maxTraversalSteps);
            out.writeLong(maxAmplifiedDeliveries); out.writeLong(maxCumulativePayloadBytes);
            out.writeInt(maxRecoveryDeliveriesPerAttempt);
        }

        private static GraphLimits read(DataInputStream in) throws IOException {
            return new GraphLimits(in.readInt(), in.readInt(), in.readInt(), in.readInt(),
                    in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(),
                    in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(),
                    in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readLong(),
                    in.readLong(), in.readLong(), in.readInt());
        }
    }

    /** Result persistence decision resolved from the composed execution store. */
    public record ResultLimits(boolean durable, int maximumPayloadBytes) {
        public ResultLimits {
            if (maximumPayloadBytes < 1) {
                throw new IllegalArgumentException("maximumPayloadBytes must be positive");
            }
        }
    }

    /** Quantitative policy for the core {@code http-request} behavior, when the graph uses it. */
    public record BuiltInHttpCapacity(long maximumRequestBytes, long maximumResponseBytes,
                                      Duration maximumTimeout) {
        public BuiltInHttpCapacity {
            if (maximumRequestBytes < 1) {
                throw new IllegalArgumentException("maximumRequestBytes must be positive");
            }
            if (maximumResponseBytes < 1) {
                throw new IllegalArgumentException("maximumResponseBytes must be positive");
            }
            Objects.requireNonNull(maximumTimeout, "maximumTimeout");
            if (maximumTimeout.isZero() || maximumTimeout.isNegative()) {
                throw new IllegalArgumentException("maximumTimeout must be positive");
            }
            try {
                maximumTimeout.toNanos();
            } catch (ArithmeticException tooLarge) {
                throw new IllegalArgumentException("maximumTimeout is too large", tooLarge);
            }
        }

        private void write(DataOutputStream out) throws IOException {
            out.writeLong(maximumRequestBytes);
            out.writeLong(maximumResponseBytes);
            writeDuration(out, maximumTimeout);
        }

        private static BuiltInHttpCapacity read(DataInputStream in) throws IOException {
            return new BuiltInHttpCapacity(in.readLong(), in.readLong(), readDuration(in));
        }
    }

    /** Quantitative service policy for one package used by the accepted graph. */
    public record PackageCapacity(String packageId, NodePackageEgressCapacityProfile capacity) {
        public PackageCapacity {
            if (packageId == null || packageId.length() > 200
                    || !packageId.matches("[a-z0-9](?:[a-z0-9._-]*[a-z0-9])?")) {
                throw new IllegalArgumentException("invalid package id");
            }
            Objects.requireNonNull(capacity, "capacity");
        }

        private void write(DataOutputStream out) throws IOException {
            out.writeUTF(packageId);
            var value = capacity.limits();
            out.writeBoolean(value.isPresent());
            if (value.isEmpty()) return;
            var limits = value.orElseThrow();
            out.writeLong(limits.maximumRequestBytes());
            out.writeLong(limits.maximumResponseBytes());
            out.writeLong(limits.maximumWebSocketMessageBytes());
            out.writeInt(limits.maximumWebSocketFragments());
            out.writeInt(limits.maximumConcurrentOperations());
            out.writeInt(limits.maximumConcurrentPerTenant());
            out.writeInt(limits.maximumQueuedWebSocketSends());
            ResolvedOperationalPolicy.writeDuration(out, limits.maximumDeadline());
            ResolvedOperationalPolicy.writeDuration(out, limits.maximumWebSocketLifetime());
            ResolvedOperationalPolicy.writeDuration(out, limits.maximumWebSocketIdle());
        }

        private static PackageCapacity read(DataInputStream in) throws IOException {
            String packageId = in.readUTF();
            if (!in.readBoolean()) {
                return new PackageCapacity(packageId,
                        NodePackageEgressCapacityProfile.noManagedEgress());
            }
            return new PackageCapacity(packageId, NodePackageEgressCapacityProfile.bounded(
                    in.readLong(), in.readLong(), in.readLong(), in.readInt(), in.readInt(),
                    in.readInt(), in.readInt(), ResolvedOperationalPolicy.readDuration(in),
                    ResolvedOperationalPolicy.readDuration(in), ResolvedOperationalPolicy.readDuration(in)));
        }
    }

    private static void writeDuration(DataOutputStream out, Duration value) throws IOException {
        out.writeLong(value.getSeconds());
        out.writeInt(value.getNano());
    }

    private static Duration readDuration(DataInputStream in) throws IOException {
        return Duration.ofSeconds(in.readLong(), in.readInt());
    }
}
