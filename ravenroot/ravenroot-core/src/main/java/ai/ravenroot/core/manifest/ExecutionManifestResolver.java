package ai.ravenroot.core.manifest;

import ai.ravenroot.api.application.ExecutionPolicy;
import ai.ravenroot.api.execution.EngineCapability;
import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.api.node.service.NodePackageEgressCapacityProfile;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionManifest;
import ai.ravenroot.api.persistence.ExecutionManifestCompatibility;
import ai.ravenroot.api.persistence.ExecutionManifestDifference;
import ai.ravenroot.api.persistence.GraphContentId;
import ai.ravenroot.api.persistence.GraphDefinitionIdentity;
import ai.ravenroot.api.persistence.PinnedNodePackage;
import ai.ravenroot.api.persistence.ResolvedRuntimeProfile;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.api.programming.ProgramRuntime;
import ai.ravenroot.core.graph.GraphMlLimits;
import ai.ravenroot.core.graph.GraphVersionSnapshot;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.GraphExecutionLimits;
import ai.ravenroot.core.runtime.UnknownBehaviorPolicy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Turns one immutable runtime composition into manifests and compatibility observations. */
public final class ExecutionManifestResolver {
    private static final String ENGINE_POLICY_FINGERPRINT_VERSION =
            "execution-engine-policy-fingerprint-v1";
    private static final String PACKAGE_CAPACITY_DOMAIN_V2 =
            "ravenroot.execution-manifest.node-package-capacity.v2";
    private static final String CAPACITY_PROFILE_UNAVAILABLE = "capacity-profile-unavailable";

    private final int formatVersion;
    private final String engineDigest;
    private final String storeDigest;
    private final String legacyExecutionLimitsDigest;
    private final String programRuntimeDigest;
    private final String unknownBehaviorMode;
    private final List<PinnedNodePackage> nodePackages;
    private final GraphExecutionLimits graphExecutionLimits;
    private final List<BehaviorRegistry.RegisteredNodePackageBinding> nodePackageBindings;
    private final Map<String, BehaviorRegistry.RegisteredNodePackageBinding> bindingsById;

    private ExecutionManifestResolver(int formatVersion, String engineDigest, String storeDigest,
                                      String legacyExecutionLimitsDigest, String programRuntimeDigest,
                                      String unknownBehaviorMode, List<PinnedNodePackage> nodePackages,
                                      GraphExecutionLimits graphExecutionLimits,
                                      List<BehaviorRegistry.RegisteredNodePackageBinding> bindings) {
        this.formatVersion = formatVersion;
        this.engineDigest = engineDigest;
        this.storeDigest = storeDigest;
        this.legacyExecutionLimitsDigest = legacyExecutionLimitsDigest;
        this.programRuntimeDigest = programRuntimeDigest;
        this.unknownBehaviorMode = unknownBehaviorMode;
        this.nodePackages = List.copyOf(nodePackages);
        this.graphExecutionLimits = graphExecutionLimits;
        this.nodePackageBindings = List.copyOf(bindings);
        var indexed = new LinkedHashMap<String, BehaviorRegistry.RegisteredNodePackageBinding>();
        for (BehaviorRegistry.RegisteredNodePackageBinding binding : bindings) {
            indexed.put(binding.identity().packageId(), binding);
        }
        this.bindingsById = Map.copyOf(indexed);
    }

    /**
     * Builds the frozen legacy-v1 resolver.
     *
     * <p>This factory deliberately continues to emit v1 after the complete runtime moved to v2.
     * Its component domains and byte order are a compatibility contract for existing callers and
     * retained rows.</p>
     */
    public static ExecutionManifestResolver from(ExecutionEngine engine,
                                                 Set<StoreCapability> storeCapabilities,
                                                 BehaviorRegistry behaviors,
                                                 UnknownBehaviorPolicy unknownBehaviors,
                                                 GraphExecutionLimits limits,
                                                 ProgramRuntime programRuntime) {
        requireInputs(engine, storeCapabilities, behaviors, unknownBehaviors, limits);
        return new ExecutionManifestResolver(
                ExecutionManifest.FORMAT_VERSION_1,
                engineDigestOf(engine, ResolvedRuntimeProfile.ENGINE_DOMAIN),
                storeDigestOfV1(storeCapabilities),
                limitsDigestOfV1(limits),
                programRuntimeDigestOf(programRuntime, ResolvedRuntimeProfile.PROGRAM_RUNTIME_DOMAIN),
                normalizedMode(unknownBehaviors),
                List.copyOf(behaviors.nodePackageIdentities()),
                null, List.of());
    }

    /**
     * Builds a v2 resolver from the exact result projection and package-service composition.
     *
     * @param durableExecutionResults whether the composed result bridge is durable.
     * @param maxExecutionResultPayloadBytes effective cap already used by result admission.
     */
    public static ExecutionManifestResolver complete(ExecutionEngine engine,
                                                     Set<StoreCapability> storeCapabilities,
                                                     boolean durableExecutionResults,
                                                     int maxExecutionResultPayloadBytes,
                                                     BehaviorRegistry behaviors,
                                                     UnknownBehaviorPolicy unknownBehaviors,
                                                     GraphExecutionLimits limits,
                                                     ProgramRuntime programRuntime) {
        requireInputs(engine, storeCapabilities, behaviors, unknownBehaviors, limits);
        if (maxExecutionResultPayloadBytes < 1) {
            throw new IllegalArgumentException("maxExecutionResultPayloadBytes must be positive");
        }
        List<BehaviorRegistry.RegisteredNodePackageBinding> bindings =
                List.copyOf(behaviors.nodePackageBindings());
        List<PinnedNodePackage> identities = bindings.stream()
                .map(BehaviorRegistry.RegisteredNodePackageBinding::identity).toList();
        return new ExecutionManifestResolver(
                ExecutionManifest.FORMAT_VERSION_2,
                engineDigestOf(engine, ResolvedRuntimeProfile.ENGINE_DOMAIN_V2),
                storeDigestOfV2(storeCapabilities, durableExecutionResults,
                        maxExecutionResultPayloadBytes),
                null,
                programRuntimeDigestOf(programRuntime,
                        ResolvedRuntimeProfile.PROGRAM_RUNTIME_DOMAIN_V2),
                normalizedMode(unknownBehaviors), identities, limits, bindings);
    }

    /** The node packages this immutable resolver captured, sorted and free of duplicates. */
    public List<PinnedNodePackage> nodePackages() {
        return nodePackages;
    }

    /** Refuses v2 admission while a selected package has no quantitative description. */
    public void requireAdmissionReady() {
        if (formatVersion != ExecutionManifest.FORMAT_VERSION_2) {
            return;
        }
        for (BehaviorRegistry.RegisteredNodePackageBinding binding : nodePackageBindings) {
            if (binding.capacityProfile().isEmpty()) {
                throw new ExecutionManifestResolutionException(
                        ExecutionManifestResolutionException.Reason.CAPACITY_PROFILE_UNAVAILABLE);
            }
        }
    }

    /** Builds one manifest under the submission policy after checking complete-v2 readiness. */
    public ExecutionManifest manifestFor(ExecutionKey key, GraphContentId graphContentId,
                                         GraphDefinitionIdentity graphIdentity, ExecutionPolicy policy,
                                         Instant pinnedAt) {
        Objects.requireNonNull(policy, "policy");
        String limitsDigest;
        if (formatVersion == ExecutionManifest.FORMAT_VERSION_1) {
            limitsDigest = legacyExecutionLimitsDigest;
        } else {
            requireAdmissionReady();
            limitsDigest = limitsDigestOfV2(graphExecutionLimits, nodePackageBindings);
        }
        var runtime = new ResolvedRuntimeProfile(
                GraphVersionSnapshot.CURRENT_SCHEMA_VERSION,
                ai.ravenroot.api.persistence.CanonicalGraphMl.CURRENT_FORMAT_VERSION,
                policy.name(), unknownBehaviorMode, engineDigest, storeDigest,
                limitsDigest, programRuntimeDigest);
        return new ExecutionManifest(formatVersion, key, graphContentId, graphIdentity,
                runtime, nodePackages, pinnedAt);
    }

    /**
     * Compares a stored manifest against this snapshot, selecting stored package ids before v2
     * capacity availability is tested.
     */
    public ExecutionManifestCompatibility compare(ExecutionManifest pinned, ExecutionPolicy policy) {
        Objects.requireNonNull(pinned, "pinned");
        Objects.requireNonNull(policy, "policy");
        if (pinned.formatVersion() != formatVersion) {
            return new ExecutionManifestCompatibility(List.of(new ExecutionManifestDifference(
                    ExecutionManifestDifference.Dimension.MANIFEST_FORMAT_VERSION,
                    Integer.toString(pinned.formatVersion()), Integer.toString(formatVersion))), false);
        }
        if (formatVersion == ExecutionManifest.FORMAT_VERSION_1) {
            ExecutionManifest current = manifestFor(pinned.key(), pinned.graphContentId(),
                    pinned.graphIdentity(), policy, pinned.pinnedAt());
            return ExecutionManifestCompatibility.compare(pinned, current);
        }
        return compareV2(pinned, policy);
    }

    private ExecutionManifestCompatibility compareV2(ExecutionManifest pinned, ExecutionPolicy policy) {
        var found = new DifferenceCollector();
        ResolvedRuntimeProfile was = pinned.runtime();
        found.add(ExecutionManifestDifference.Dimension.GRAPH_SCHEMA_VERSION,
                was.graphSchemaVersion(), GraphVersionSnapshot.CURRENT_SCHEMA_VERSION);
        found.add(ExecutionManifestDifference.Dimension.DEFINITION_FORMAT_VERSION,
                was.definitionFormatVersion(),
                ai.ravenroot.api.persistence.CanonicalGraphMl.CURRENT_FORMAT_VERSION);
        found.add(ExecutionManifestDifference.Dimension.EXECUTION_POLICY,
                was.executionPolicy(), policy.name());
        found.add(ExecutionManifestDifference.Dimension.UNKNOWN_BEHAVIOR_MODE,
                was.unknownBehaviorMode(), unknownBehaviorMode);
        found.add(ExecutionManifestDifference.Dimension.ENGINE, was.engineDigest(), engineDigest);
        found.add(ExecutionManifestDifference.Dimension.EXECUTION_STORE,
                was.storeDigest(), storeDigest);

        var selected = new ArrayList<BehaviorRegistry.RegisteredNodePackageBinding>();
        boolean missing = false;
        boolean unavailable = false;
        for (PinnedNodePackage required : pinned.nodePackages()) {
            BehaviorRegistry.RegisteredNodePackageBinding binding =
                    bindingsById.get(required.packageId());
            if (binding == null) {
                missing = true;
            } else {
                selected.add(binding);
                unavailable |= binding.capacityProfile().isEmpty();
            }
        }
        if (unavailable) {
            found.addExact(new ExecutionManifestDifference(
                    ExecutionManifestDifference.Dimension.EXECUTION_LIMITS,
                    was.executionLimitsDigest(), CAPACITY_PROFILE_UNAVAILABLE));
        } else if (!missing) {
            found.add(ExecutionManifestDifference.Dimension.EXECUTION_LIMITS,
                    was.executionLimitsDigest(), limitsDigestOfV2(graphExecutionLimits, selected));
        }
        found.add(ExecutionManifestDifference.Dimension.PROGRAM_RUNTIME,
                was.programRuntimeDigest(), programRuntimeDigest);

        for (PinnedNodePackage required : pinned.nodePackages()) {
            BehaviorRegistry.RegisteredNodePackageBinding binding =
                    bindingsById.get(required.packageId());
            if (binding == null) {
                found.addExact(new ExecutionManifestDifference(
                        ExecutionManifestDifference.Dimension.NODE_PACKAGE_MISSING,
                        identityOf(required), required.packageId() + "@absent"));
            } else if (!binding.identity().equals(required)) {
                found.addExact(new ExecutionManifestDifference(
                        ExecutionManifestDifference.Dimension.NODE_PACKAGE_CHANGED,
                        identityOf(required), identityOf(binding.identity())));
            }
        }
        return found.result();
    }

    private static void requireInputs(ExecutionEngine engine, Set<StoreCapability> storeCapabilities,
                                      BehaviorRegistry behaviors, UnknownBehaviorPolicy unknownBehaviors,
                                      GraphExecutionLimits limits) {
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(storeCapabilities, "storeCapabilities");
        Objects.requireNonNull(behaviors, "behaviors");
        Objects.requireNonNull(unknownBehaviors, "unknownBehaviors");
        Objects.requireNonNull(limits, "limits");
    }

    private static String engineDigestOf(ExecutionEngine engine, String domain) {
        String policyFingerprint = engine.compatibilityFingerprint();
        if (policyFingerprint == null
                || (!policyFingerprint.isEmpty() && !policyFingerprint.matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException(
                    "Execution engine compatibility fingerprint must be empty or lowercase SHA-256 hexadecimal");
        }
        var parts = new ArrayList<String>();
        parts.add(engine.id());
        var names = new TreeSet<String>();
        for (EngineCapability capability : engine.capabilities()) {
            names.add(capability.name());
        }
        parts.add(Integer.toString(names.size()));
        parts.addAll(names);
        if (!policyFingerprint.isEmpty()) {
            parts.add(ENGINE_POLICY_FINGERPRINT_VERSION);
            parts.add(policyFingerprint);
        }
        return ResolvedRuntimeProfile.digestOf(domain, parts);
    }

    private static String storeDigestOfV1(Set<StoreCapability> capabilities) {
        var names = new TreeSet<String>();
        for (StoreCapability capability : capabilities) {
            names.add(capability.name());
        }
        var parts = new ArrayList<String>();
        parts.add(Integer.toString(names.size()));
        parts.addAll(names);
        return ResolvedRuntimeProfile.digestOf(ResolvedRuntimeProfile.STORE_DOMAIN, parts);
    }

    private static String storeDigestOfV2(Set<StoreCapability> capabilities,
                                          boolean durableExecutionResults,
                                          int maxExecutionResultPayloadBytes) {
        var names = new TreeSet<String>();
        for (StoreCapability capability : capabilities) {
            names.add(capability.name());
        }
        var parts = new ArrayList<String>();
        add(parts, "store-capability-count", names.size());
        for (String name : names) {
            add(parts, "store-capability", name);
        }
        add(parts, "durable-execution-results", durableExecutionResults);
        add(parts, "max-execution-result-payload-bytes", maxExecutionResultPayloadBytes);
        return ResolvedRuntimeProfile.digestOf(ResolvedRuntimeProfile.STORE_DOMAIN_V2, parts);
    }

    /** Frozen v1 limit encoding. Do not add labels or fields here. */
    private static String limitsDigestOfV1(GraphExecutionLimits limits) {
        GraphMlLimits graphMl = limits.graphMl();
        PayloadLimits payload = limits.payload();
        List<String> parts = List.of(
                Integer.toString(graphMl.maxBytes()), Integer.toString(graphMl.maxNodes()),
                Integer.toString(graphMl.maxEdges()), Integer.toString(graphMl.maxProperties()),
                Integer.toString(graphMl.maxDepth()), Integer.toString(graphMl.maxStringLength()),
                Integer.toString(graphMl.maxKeys()), Integer.toString(graphMl.maxElements()),
                Integer.toString(graphMl.maxAttributes()),
                Integer.toString(graphMl.maxNamespaceDeclarations()),
                Integer.toString(payload.maxEncodedBytes()), Integer.toString(payload.maxDepth()),
                Integer.toString(payload.maxCollectionSize()), Integer.toString(payload.maxValueCount()),
                Integer.toString(payload.maxTextLength()), Integer.toString(payload.maxKeyLength()),
                Integer.toString(limits.maxFanOut()), Integer.toString(limits.maxResidentActors()),
                Integer.toString(limits.maxLiveActorsPerTraversal()),
                Integer.toString(limits.maxInFlightHopsPerTraversal()),
                Integer.toString(limits.maxQueuedAdmissionsPerNode()),
                Long.toString(limits.maxTraversalSteps()), Long.toString(limits.maxAmplifiedDeliveries()),
                Long.toString(limits.maxCumulativePayloadBytes()),
                Integer.toString(limits.maxRecoveryDeliveriesPerAttempt()));
        return ResolvedRuntimeProfile.digestOf(ResolvedRuntimeProfile.LIMITS_DOMAIN, parts);
    }

    private static String limitsDigestOfV2(
            GraphExecutionLimits limits,
            List<BehaviorRegistry.RegisteredNodePackageBinding> bindings) {
        GraphMlLimits graphMl = limits.graphMl();
        PayloadLimits payload = limits.payload();
        var parts = new ArrayList<String>();
        add(parts, "graphml-max-bytes", graphMl.maxBytes());
        add(parts, "graphml-max-nodes", graphMl.maxNodes());
        add(parts, "graphml-max-edges", graphMl.maxEdges());
        add(parts, "graphml-max-properties", graphMl.maxProperties());
        add(parts, "graphml-max-depth", graphMl.maxDepth());
        add(parts, "graphml-max-string-length", graphMl.maxStringLength());
        add(parts, "graphml-max-keys", graphMl.maxKeys());
        add(parts, "graphml-max-elements", graphMl.maxElements());
        add(parts, "graphml-max-attributes", graphMl.maxAttributes());
        add(parts, "graphml-max-namespace-declarations", graphMl.maxNamespaceDeclarations());
        add(parts, "payload-max-encoded-bytes", payload.maxEncodedBytes());
        add(parts, "payload-max-depth", payload.maxDepth());
        add(parts, "payload-max-collection-size", payload.maxCollectionSize());
        add(parts, "payload-max-value-count", payload.maxValueCount());
        add(parts, "payload-max-text-length", payload.maxTextLength());
        add(parts, "payload-max-key-length", payload.maxKeyLength());
        add(parts, "max-fan-out", limits.maxFanOut());
        add(parts, "max-resident-actors", limits.maxResidentActors());
        add(parts, "max-live-actors-per-traversal", limits.maxLiveActorsPerTraversal());
        add(parts, "max-in-flight-hops-per-traversal", limits.maxInFlightHopsPerTraversal());
        add(parts, "max-queued-admissions-per-node", limits.maxQueuedAdmissionsPerNode());
        add(parts, "max-traversal-steps", limits.maxTraversalSteps());
        add(parts, "max-amplified-deliveries", limits.maxAmplifiedDeliveries());
        add(parts, "max-cumulative-payload-bytes", limits.maxCumulativePayloadBytes());
        add(parts, "max-recovery-deliveries-per-attempt", limits.maxRecoveryDeliveriesPerAttempt());
        add(parts, "node-package-count", bindings.size());
        for (BehaviorRegistry.RegisteredNodePackageBinding binding : bindings) {
            NodePackageEgressCapacityProfile profile = binding.capacityProfile().orElseThrow(() ->
                    new ExecutionManifestResolutionException(
                            ExecutionManifestResolutionException.Reason.CAPACITY_PROFILE_UNAVAILABLE));
            add(parts, "node-package-id", binding.identity().packageId());
            add(parts, "node-package-capacity-digest", capacityDigestOf(profile));
        }
        return ResolvedRuntimeProfile.digestOf(ResolvedRuntimeProfile.LIMITS_DOMAIN_V2, parts);
    }

    private static String capacityDigestOf(NodePackageEgressCapacityProfile profile) {
        var parts = new ArrayList<String>();
        var limits = profile.limits();
        if (limits.isEmpty()) {
            add(parts, "profile-kind", "deny-only");
        } else {
            NodePackageEgressCapacityProfile.Limits bounded = limits.orElseThrow();
            add(parts, "profile-kind", "bounded");
            add(parts, "maximum-request-bytes", bounded.maximumRequestBytes());
            add(parts, "maximum-response-bytes", bounded.maximumResponseBytes());
            add(parts, "maximum-websocket-message-bytes", bounded.maximumWebSocketMessageBytes());
            add(parts, "maximum-websocket-fragments", bounded.maximumWebSocketFragments());
            add(parts, "maximum-concurrent-operations", bounded.maximumConcurrentOperations());
            add(parts, "maximum-concurrent-per-tenant", bounded.maximumConcurrentPerTenant());
            add(parts, "maximum-queued-websocket-sends", bounded.maximumQueuedWebSocketSends());
            addDuration(parts, "maximum-deadline", bounded.maximumDeadline());
            addDuration(parts, "maximum-websocket-lifetime", bounded.maximumWebSocketLifetime());
            addDuration(parts, "maximum-websocket-idle", bounded.maximumWebSocketIdle());
            add(parts, "maximum-http-decompression-ratio", bounded.maximumHttpDecompressionRatio());
        }
        return ResolvedRuntimeProfile.digestOf(PACKAGE_CAPACITY_DOMAIN_V2, parts);
    }

    private static void addDuration(List<String> parts, String name, Duration value) {
        add(parts, name + "-seconds", value.getSeconds());
        add(parts, name + "-nanos", value.getNano());
    }

    private static void add(List<String> parts, String name, Object value) {
        parts.add(name);
        parts.add(value.toString());
    }

    private static String programRuntimeDigestOf(ProgramRuntime programRuntime, String domain) {
        List<String> parts = programRuntime == null
                ? List.of("", "")
                : List.of(programRuntime.id(), programRuntime.compatibilityFingerprint());
        return ResolvedRuntimeProfile.digestOf(domain, parts);
    }

    private static String normalizedMode(UnknownBehaviorPolicy unknownBehaviors) {
        String mode = unknownBehaviors.mode();
        if (UnknownBehaviorPolicy.REFUSE_VALUE.equals(mode)
                || UnknownBehaviorPolicy.PASS_THROUGH_VALUE.equals(mode)) {
            return mode;
        }
        return UnknownBehaviorPolicy.UNKNOWN_VALUE;
    }

    private static String identityOf(PinnedNodePackage pinned) {
        return pinned.packageId() + "@" + pinned.identityDigest();
    }

    private static final class DifferenceCollector {
        private final List<ExecutionManifestDifference> differences = new ArrayList<>();
        private boolean truncated;

        private void add(ExecutionManifestDifference.Dimension dimension, Object pinned, Object observed) {
            if (!pinned.equals(observed)) {
                addExact(new ExecutionManifestDifference(
                        dimension, pinned.toString(), observed.toString()));
            }
        }

        private void addExact(ExecutionManifestDifference difference) {
            if (differences.size() < ExecutionManifestCompatibility.MAX_DIFFERENCES) {
                differences.add(difference);
            } else {
                truncated = true;
            }
        }

        private ExecutionManifestCompatibility result() {
            return new ExecutionManifestCompatibility(differences, truncated);
        }
    }
}
