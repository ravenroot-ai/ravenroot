package ai.ravenroot.api.persistence;

import ai.ravenroot.api.node.service.NodePackageEgressCapacityProfile;
import ai.ravenroot.api.node.service.NodeExternalIoCapacity;

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
 *
 * @param graph graph parsing, payload, traversal and recovery limits
 * @param results execution-result durability and payload limits
 * @param builtInHttp capacity for the core HTTP behavior, when used
 * @param nodePackages managed external-I/O capacity for each resolved node package
 * @param persistence generic persistence capacity, when the store supplies it
 * @param nodeExternalIo capacity resolved for each exact node binding
 */
public record ResolvedOperationalPolicy(GraphLimits graph, ResultLimits results,
                                        Optional<BuiltInHttpCapacity> builtInHttp,
                                        List<PackageCapacity> nodePackages,
                                        Optional<PersistenceLimits> persistence,
                                        List<NodeIoCapacity> nodeExternalIo) {
    private static final int ENCODING_VERSION_1 = 1;
    private static final int ENCODING_VERSION_2 = 2;
    private static final int ENCODING_VERSION_3 = 3;
    private static final int MAX_POLICY_BYTES = 512 * 1024;
    private static final int MAX_BASE64_CHARACTERS = ((MAX_POLICY_BYTES + 2) / 3) * 4;

    /** Canonicalizes and validates one complete operational policy snapshot. */
    public ResolvedOperationalPolicy {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(results, "results");
        Objects.requireNonNull(builtInHttp, "builtInHttp");
        Objects.requireNonNull(nodePackages, "nodePackages");
        Objects.requireNonNull(persistence, "persistence");
        Objects.requireNonNull(nodeExternalIo, "nodeExternalIo");
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
        var sortedIo = new ArrayList<>(nodeExternalIo);
        if (sortedIo.size() > ExecutionManifest.MAX_NODE_EXTERNAL_IO_CAPACITIES) {
            throw new IllegalArgumentException("too many node external-I/O capacity entries");
        }
        sortedIo.forEach(entry -> Objects.requireNonNull(entry, "node external-I/O capacity"));
        sortedIo.sort(java.util.Comparator.comparing(NodeIoCapacity::bindingDigest));
        var seenIo = new HashSet<String>();
        for (NodeIoCapacity entry : sortedIo) {
            if (!seenIo.add(entry.bindingDigest())) {
                throw new IllegalArgumentException("duplicate node external-I/O capacity");
            }
        }
        nodeExternalIo = List.copyOf(sortedIo);
    }

    /**
     * Compatibility constructor for policies before node-bound external-I/O snapshots.
     *
     * @param graph graph and traversal limits
     * @param results result persistence limits
     * @param builtInHttp core HTTP capacity, when used
     * @param nodePackages resolved node-package capacities
     * @param persistence generic persistence capacity, when available
     */
    public ResolvedOperationalPolicy(GraphLimits graph, ResultLimits results,
                                     Optional<BuiltInHttpCapacity> builtInHttp,
                                     List<PackageCapacity> nodePackages,
                                     Optional<PersistenceLimits> persistence) {
        this(graph, results, builtInHttp, nodePackages, persistence, List.of());
    }

    /**
     * Compatibility constructor for policies whose graphs do not use core HTTP.
     *
     * @param graph graph and traversal limits
     * @param results result persistence limits
     * @param nodePackages resolved node-package capacities
     */
    public ResolvedOperationalPolicy(GraphLimits graph, ResultLimits results,
                                     List<PackageCapacity> nodePackages) {
        this(graph, results, Optional.empty(), nodePackages, Optional.empty(), List.of());
    }

    /**
     * Compatibility constructor for the v2 layout, which had no generic persistence capacity.
     *
     * @param graph graph and traversal limits
     * @param results result persistence limits
     * @param builtInHttp core HTTP capacity, when used
     * @param nodePackages resolved node-package capacities
     */
    public ResolvedOperationalPolicy(GraphLimits graph, ResultLimits results,
                                     Optional<BuiltInHttpCapacity> builtInHttp,
                                     List<PackageCapacity> nodePackages) {
        this(graph, results, builtInHttp, nodePackages, Optional.empty(), List.of());
    }

    /**
     * Canonical durable value, with no free-form policy or secret field.
     *
     * @return canonical URL-safe Base64 representation of the legacy policy layout
     */
    public String encode() {
        if (persistence.isPresent() || !nodeExternalIo.isEmpty()) {
            throw new IllegalStateException("new operational capacity requires manifest format 4");
        }
        if (!hasLegacyDecompressionAuthority()) {
            throw new IllegalStateException("legacy operational policy requires decompression ratio 1000");
        }
        return encodeVersion(ENCODING_VERSION_1);
    }

    /**
     * Encodes the policy layout required by the containing manifest format.
     *
     * @param manifestFormatVersion execution-manifest format that will contain the value
     * @return canonical URL-safe Base64 representation for that manifest format
     */
    public String encodeForManifest(int manifestFormatVersion) {
        if (manifestFormatVersion == ExecutionManifest.FORMAT_VERSION_2 && persistence.isEmpty()
                && nodeExternalIo.isEmpty()
                && hasLegacyDecompressionAuthority()) {
            return encodeVersion(ENCODING_VERSION_1);
        }
        if (manifestFormatVersion == ExecutionManifest.FORMAT_VERSION_3 && persistence.isPresent()
                && hasLegacyDecompressionAuthority()) {
            if (!nodeExternalIo.isEmpty()) {
                throw new IllegalArgumentException("manifest format 3 cannot carry new external-I/O capacity");
            }
            return encodeVersion(ENCODING_VERSION_2);
        }
        if (manifestFormatVersion == ExecutionManifest.FORMAT_VERSION_4 && hasCompleteDecompressionAuthority()) {
            return encodeVersion(ENCODING_VERSION_3);
        }
        throw new IllegalArgumentException("operational policy does not match manifest format");
    }

    private String encodeVersion(int encodingVersion) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.writeInt(encodingVersion);
                graph.write(out);
                out.writeBoolean(results.durable());
                out.writeInt(results.maximumPayloadBytes());
                if (encodingVersion == ENCODING_VERSION_2) {
                    out.writeInt(persistence.orElseThrow().maximumPayloadBytes());
                } else if (encodingVersion == ENCODING_VERSION_3) {
                    out.writeBoolean(persistence.isPresent());
                    if (persistence.isPresent()) out.writeInt(persistence.orElseThrow().maximumPayloadBytes());
                }
                out.writeBoolean(builtInHttp.isPresent());
                if (builtInHttp.isPresent()) builtInHttp.orElseThrow().write(out);
                out.writeInt(nodePackages.size());
                for (PackageCapacity entry : nodePackages) entry.write(out, encodingVersion);
                if (encodingVersion == ENCODING_VERSION_3) {
                    out.writeInt(nodeExternalIo.size());
                    for (NodeIoCapacity entry : nodeExternalIo) entry.write(out);
                }
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

    /**
     * Decodes and canonicalizes one stored value, rejecting unknown or trailing data.
     *
     * @param encoded canonical legacy policy value
     * @return decoded immutable operational policy
     */
    public static ResolvedOperationalPolicy decode(String encoded) {
        return decodeVersion(encoded, ENCODING_VERSION_1);
    }

    /**
     * Decodes the exact policy layout selected by its containing manifest.
     *
     * @param encoded canonical policy value
     * @param manifestFormatVersion containing execution-manifest format
     * @return decoded immutable operational policy
     */
    public static ResolvedOperationalPolicy decodeForManifest(String encoded, int manifestFormatVersion) {
        return decodeVersion(encoded, switch (manifestFormatVersion) {
            case ExecutionManifest.FORMAT_VERSION_2 -> ENCODING_VERSION_1;
            case ExecutionManifest.FORMAT_VERSION_3 -> ENCODING_VERSION_2;
            case ExecutionManifest.FORMAT_VERSION_4 -> ENCODING_VERSION_3;
            default -> throw new IllegalArgumentException("manifest format has no operational policy");
        });
    }

    private static ResolvedOperationalPolicy decodeVersion(String encoded, int expectedEncodingVersion) {
        try {
            Objects.requireNonNull(encoded, "encoded");
            if (encoded.length() > MAX_BASE64_CHARACTERS) {
                throw new IllegalArgumentException("policy is too large");
            }
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            if (bytes.length > MAX_POLICY_BYTES) throw new IllegalArgumentException("policy is too large");
            ResolvedOperationalPolicy policy;
            try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
                if (in.readInt() != expectedEncodingVersion) {
                    throw new IllegalArgumentException("unsupported operational policy encoding");
                }
                GraphLimits graph = GraphLimits.read(in);
                ResultLimits results = new ResultLimits(in.readBoolean(), in.readInt());
                Optional<PersistenceLimits> persistence = switch (expectedEncodingVersion) {
                    case ENCODING_VERSION_2 -> Optional.of(new PersistenceLimits(in.readInt()));
                    case ENCODING_VERSION_3 -> in.readBoolean()
                            ? Optional.of(new PersistenceLimits(in.readInt())) : Optional.empty();
                    default -> Optional.empty();
                };
                Optional<BuiltInHttpCapacity> builtInHttp = in.readBoolean()
                        ? Optional.of(BuiltInHttpCapacity.read(in)) : Optional.empty();
                int count = in.readInt();
                if (count < 0 || count > ExecutionManifest.MAX_NODE_PACKAGES) {
                    throw new IllegalArgumentException("invalid package capacity count");
                }
                var packages = new ArrayList<PackageCapacity>(count);
                for (int i = 0; i < count; i++) packages.add(PackageCapacity.read(in, expectedEncodingVersion));
                var externalIo = new ArrayList<NodeIoCapacity>();
                if (expectedEncodingVersion == ENCODING_VERSION_3) {
                    int ioCount = in.readInt();
                    if (ioCount < 0 || ioCount > ExecutionManifest.MAX_NODE_EXTERNAL_IO_CAPACITIES) {
                        throw new IllegalArgumentException("invalid node external-I/O capacity count");
                    }
                    for (int i = 0; i < ioCount; i++) externalIo.add(NodeIoCapacity.read(in));
                }
                if (in.read() != -1) throw new IllegalArgumentException("trailing operational policy data");
                policy = new ResolvedOperationalPolicy(graph, results, builtInHttp, packages,
                        persistence, externalIo);
            }
            if (!policy.encodeVersion(expectedEncodingVersion).equals(encoded)) {
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

    private boolean hasCompleteDecompressionAuthority() {
        return nodePackages.stream().flatMap(entry -> entry.capacity().limits().stream())
                .allMatch(limits -> limits.maximumDecompressionRatio().isPresent());
    }

    private boolean hasLegacyDecompressionAuthority() {
        return nodePackages.stream().flatMap(entry -> entry.capacity().limits().stream())
                .allMatch(limits -> limits.maximumDecompressionRatio().orElse(-1) == 1_000);
    }

    /**
     * The graph parser, payload and live traversal budgets used by runtime consumers.
     *
     * @param graphMlMaxBytes maximum GraphML document bytes
     * @param graphMlMaxNodes maximum GraphML nodes
     * @param graphMlMaxEdges maximum GraphML edges
     * @param graphMlMaxProperties maximum GraphML properties
     * @param graphMlMaxDepth maximum GraphML element depth
     * @param graphMlMaxStringLength maximum GraphML string length
     * @param graphMlMaxKeys maximum GraphML key declarations
     * @param graphMlMaxElements maximum XML elements in GraphML
     * @param graphMlMaxAttributes maximum XML attributes in GraphML
     * @param graphMlMaxNamespaceDeclarations maximum XML namespace declarations in GraphML
     * @param payloadMaxEncodedBytes maximum encoded payload bytes
     * @param payloadMaxDepth maximum payload nesting depth
     * @param payloadMaxCollectionSize maximum elements in one payload collection
     * @param payloadMaxValueCount maximum values in one payload
     * @param payloadMaxTextLength maximum text length in one payload value
     * @param payloadMaxKeyLength maximum payload map-key length
     * @param maxFanOut maximum fan-out from one node
     * @param maxResidentActors maximum resident actor instances
     * @param maxLiveActorsPerTraversal maximum live actors for one traversal
     * @param maxInFlightHopsPerTraversal maximum in-flight hops for one traversal
     * @param maxQueuedAdmissionsPerNode maximum queued admissions for one node
     * @param maxTraversalSteps maximum steps for one traversal
     * @param maxAmplifiedDeliveries maximum amplified deliveries for one traversal
     * @param maxCumulativePayloadBytes maximum cumulative payload bytes for one traversal
     * @param maxRecoveryDeliveriesPerAttempt maximum recovery deliveries for one attempt
     */
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
        /** Validates that every graph, payload and traversal budget is positive. */
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

    /**
     * Result persistence decision resolved from the composed execution store.
     *
     * @param durable whether execution results are stored durably
     * @param maximumPayloadBytes maximum encoded result payload bytes
     */
    public record ResultLimits(boolean durable, int maximumPayloadBytes) {
        /** Validates one result-persistence decision. */
        public ResultLimits {
            if (maximumPayloadBytes < 1) {
                throw new IllegalArgumentException("maximumPayloadBytes must be positive");
            }
        }
    }

    /**
     * Generic opaque-payload capacity of the execution store used by this execution.
     *
     * @param maximumPayloadBytes maximum opaque payload bytes accepted by the store
     */
    public record PersistenceLimits(int maximumPayloadBytes) {
        /** Validates one generic persistence capacity. */
        public PersistenceLimits {
            if (maximumPayloadBytes < 1) {
                throw new IllegalArgumentException("maximumPayloadBytes must be positive");
            }
        }
    }

    /**
     * Quantitative policy for the core {@code http-request} behavior, when the graph uses it.
     *
     * @param maximumRequestBytes maximum request-body bytes
     * @param maximumResponseBytes maximum response-body bytes
     * @param maximumTimeout maximum duration of one HTTP request
     */
    public record BuiltInHttpCapacity(long maximumRequestBytes, long maximumResponseBytes,
                                      Duration maximumTimeout) {
        /** Validates one finite core HTTP capacity. */
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

    /**
     * Quantitative service policy for one package used by the accepted graph.
     *
     * @param packageId stable node-package identifier
     * @param capacity resolved managed external-I/O capacity for the package
     */
    public record PackageCapacity(String packageId, NodePackageEgressCapacityProfile capacity) {
        /** Validates one identified package-capacity entry. */
        public PackageCapacity {
            if (packageId == null || packageId.length() > 200
                    || !packageId.matches("[a-z0-9](?:[a-z0-9._-]*[a-z0-9])?")) {
                throw new IllegalArgumentException("invalid package id");
            }
            Objects.requireNonNull(capacity, "capacity");
        }

        private void write(DataOutputStream out, int encodingVersion) throws IOException {
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
            if (encodingVersion == ENCODING_VERSION_3) {
                out.writeInt(limits.maximumDecompressionRatio().orElseThrow());
            }
            ResolvedOperationalPolicy.writeDuration(out, limits.maximumDeadline());
            ResolvedOperationalPolicy.writeDuration(out, limits.maximumWebSocketLifetime());
            ResolvedOperationalPolicy.writeDuration(out, limits.maximumWebSocketIdle());
        }

        private static PackageCapacity read(DataInputStream in, int encodingVersion) throws IOException {
            String packageId = in.readUTF();
            if (!in.readBoolean()) {
                return new PackageCapacity(packageId,
                        NodePackageEgressCapacityProfile.noManagedEgress());
            }
            long request = in.readLong();
            long response = in.readLong();
            long websocket = in.readLong();
            int fragments = in.readInt();
            int concurrent = in.readInt();
            int tenant = in.readInt();
            int queued = in.readInt();
            int ratio = encodingVersion == ENCODING_VERSION_3 ? in.readInt() : 0;
            Duration deadline = ResolvedOperationalPolicy.readDuration(in);
            Duration lifetime = ResolvedOperationalPolicy.readDuration(in);
            Duration idle = ResolvedOperationalPolicy.readDuration(in);
            return new PackageCapacity(packageId, encodingVersion == ENCODING_VERSION_3
                    ? NodePackageEgressCapacityProfile.bounded(request, response, websocket, fragments,
                    concurrent, tenant, queued, ratio, deadline, lifetime, idle)
                    : NodePackageEgressCapacityProfile.bounded(request, response, websocket, fragments,
                    concurrent, tenant, queued, 1_000, deadline, lifetime, idle));
        }
    }

    /**
     * Capacity snapshot for one exact graph node, package and behavior binding.
     *
     * @param bindingDigest digest of the graph node, package and behavior binding
     * @param capacity quantitative external-I/O capacity pinned for that binding
     */
    public record NodeIoCapacity(String bindingDigest, NodeExternalIoCapacity capacity) {
        /** Validates one identified node-bound capacity entry. */
        public NodeIoCapacity {
            bindingDigest = ManifestTokens.requireSha256Hex(bindingDigest, "node I/O binding digest");
            Objects.requireNonNull(capacity, "capacity");
        }

        private void write(DataOutputStream out) throws IOException {
            out.writeUTF(bindingDigest);
            out.writeInt(capacity.maximumMessageBytes());
            out.writeInt(capacity.maximumFragments());
            writeDuration(out, capacity.maximumTimeout());
            out.writeInt(capacity.maximumConcurrency());
        }

        private static NodeIoCapacity read(DataInputStream in) throws IOException {
            return new NodeIoCapacity(in.readUTF(), new NodeExternalIoCapacity(in.readInt(), in.readInt(),
                    readDuration(in), in.readInt()));
        }
    }

    private static void writeDuration(DataOutputStream out, Duration value) throws IOException {
        out.writeLong(value.getSeconds());
        out.writeInt(value.getNano());
    }

    private static Duration readDuration(DataInputStream in) throws IOException {
        long seconds = in.readLong();
        int nanos = in.readInt();
        try {
            return Duration.ofSeconds(seconds, nanos);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("operational policy duration is out of range", overflow);
        }
    }
}
