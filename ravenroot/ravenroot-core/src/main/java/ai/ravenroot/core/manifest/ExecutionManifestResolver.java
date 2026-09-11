package ai.ravenroot.core.manifest;

import ai.ravenroot.api.application.ExecutionPolicy;
import ai.ravenroot.api.execution.EngineCapability;
import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionManifest;
import ai.ravenroot.api.persistence.ExecutionManifestCompatibility;
import ai.ravenroot.api.persistence.ExecutionManifestDifference;
import ai.ravenroot.api.persistence.GraphContentId;
import ai.ravenroot.api.persistence.GraphDefinitionIdentity;
import ai.ravenroot.api.persistence.PinnedNodePackage;
import ai.ravenroot.api.persistence.ResolvedOperationalPolicy;
import ai.ravenroot.api.persistence.ResolvedRuntimeProfile;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.api.programming.ProgramRuntime;
import ai.ravenroot.core.graph.GraphMlLimits;
import ai.ravenroot.core.graph.GraphVersionSnapshot;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.GraphExecutionLimits;
import ai.ravenroot.core.runtime.UnknownBehaviorPolicy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Resolves immutable manifest identities and operational values from one runtime composition. */
public final class ExecutionManifestResolver {
    private static final String ENGINE_POLICY_FINGERPRINT_VERSION =
            "execution-engine-policy-fingerprint-v1";

    private final String engineDigest;
    private final String storeDigest;
    private final String executionLimitsDigest;
    private final String programRuntimeDigest;
    private final String unknownBehaviorMode;
    private final Set<StoreCapability> storeCapabilities;
    private final GraphExecutionLimits graphExecutionLimits;
    private final int maximumExecutionResultPayloadBytes;
    private final Integer maximumPersistencePayloadBytes;
    private final BehaviorRegistry behaviors;

    private ExecutionManifestResolver(String engineDigest, String storeDigest,
                                      String executionLimitsDigest, String programRuntimeDigest,
                                      String unknownBehaviorMode,
                                      Set<StoreCapability> storeCapabilities,
                                      GraphExecutionLimits graphExecutionLimits,
                                      int maximumExecutionResultPayloadBytes,
                                      Integer maximumPersistencePayloadBytes,
                                      BehaviorRegistry behaviors) {
        this.engineDigest = engineDigest;
        this.storeDigest = storeDigest;
        this.executionLimitsDigest = executionLimitsDigest;
        this.programRuntimeDigest = programRuntimeDigest;
        this.unknownBehaviorMode = unknownBehaviorMode;
        this.storeCapabilities = Set.copyOf(storeCapabilities);
        this.graphExecutionLimits = graphExecutionLimits;
        this.maximumExecutionResultPayloadBytes = maximumExecutionResultPayloadBytes;
        this.maximumPersistencePayloadBytes = maximumPersistencePayloadBytes;
        this.behaviors = behaviors;
    }

    /**
     * Resolves a v2 authority. The result cap is the exact value enforced by the composed result
     * store, or any positive inert value when {@code EXECUTION_RESULTS} is absent.
     */
    public static ExecutionManifestResolver complete(ExecutionEngine engine,
                                                     Set<StoreCapability> storeCapabilities,
                                                     int maximumExecutionResultPayloadBytes,
                                                     BehaviorRegistry behaviors,
                                                     UnknownBehaviorPolicy unknownBehaviors,
                                                     GraphExecutionLimits limits,
                                                     ProgramRuntime programRuntime) {
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(storeCapabilities, "storeCapabilities");
        Objects.requireNonNull(behaviors, "behaviors");
        Objects.requireNonNull(unknownBehaviors, "unknownBehaviors");
        Objects.requireNonNull(limits, "limits");
        if (maximumExecutionResultPayloadBytes < 1) {
            throw new IllegalArgumentException("maximumExecutionResultPayloadBytes must be positive");
        }
        return new ExecutionManifestResolver(engineDigestOf(engine),
                storeDigestOf(storeCapabilities), limitsDigestOf(limits),
                programRuntimeDigestOf(programRuntime), normalizedMode(unknownBehaviors),
                storeCapabilities, limits, maximumExecutionResultPayloadBytes, null, behaviors);
    }

    /** Resolves a v3 authority including the exact generic capacity of the managed execution store. */
    public static ExecutionManifestResolver completeManaged(ExecutionEngine engine,
                                                            Set<StoreCapability> storeCapabilities,
                                                            int maximumExecutionResultPayloadBytes,
                                                            int maximumPersistencePayloadBytes,
                                                            BehaviorRegistry behaviors,
                                                            UnknownBehaviorPolicy unknownBehaviors,
                                                            GraphExecutionLimits limits,
                                                            ProgramRuntime programRuntime) {
        if (maximumPersistencePayloadBytes < 1) {
            throw new IllegalArgumentException("maximumPersistencePayloadBytes must be positive");
        }
        ExecutionManifestResolver legacy = complete(engine, storeCapabilities,
                maximumExecutionResultPayloadBytes, behaviors, unknownBehaviors, limits, programRuntime);
        return new ExecutionManifestResolver(legacy.engineDigest, legacy.storeDigest,
                legacy.executionLimitsDigest, legacy.programRuntimeDigest, legacy.unknownBehaviorMode,
                legacy.storeCapabilities, legacy.graphExecutionLimits,
                legacy.maximumExecutionResultPayloadBytes, maximumPersistencePayloadBytes, behaviors);
    }

    /**
     * Source-compatible factory for embedded compositions without a result store. It emits v2 and
     * therefore records that durable results are unavailable rather than omitting the decision.
     */
    public static ExecutionManifestResolver from(ExecutionEngine engine,
                                                 Set<StoreCapability> storeCapabilities,
                                                 BehaviorRegistry behaviors,
                                                 UnknownBehaviorPolicy unknownBehaviors,
                                                 GraphExecutionLimits limits,
                                                 ProgramRuntime programRuntime) {
        return complete(engine, storeCapabilities, limits.payload().maxEncodedBytes(), behaviors,
                unknownBehaviors, limits, programRuntime);
    }

    /** All installed package identities, retained for existing diagnostics and tests. */
    public List<PinnedNodePackage> nodePackages() {
        return behaviors.nodePackageIdentities();
    }

    /** Resolves values for exactly the behavior names referenced by one accepted graph. */
    public ResolvedOperationalPolicy operationalPolicyFor(Collection<String> behaviorNames) {
        Objects.requireNonNull(behaviorNames, "behaviorNames");
        List<BehaviorRegistry.RegisteredNodePackageBinding> bindings =
                behaviors.nodePackageBindingsFor(behaviorNames);
        var capacities = new ArrayList<ResolvedOperationalPolicy.PackageCapacity>(bindings.size());
        for (BehaviorRegistry.RegisteredNodePackageBinding binding : bindings) {
            capacities.add(new ResolvedOperationalPolicy.PackageCapacity(
                    binding.identity().packageId(), binding.capacity().orElseThrow(() ->
                    new ExecutionManifestResolutionException(
                            ExecutionManifestResolutionException.Reason.CAPACITY_PROFILE_UNAVAILABLE))));
        }
        return new ResolvedOperationalPolicy(graphPolicyOf(graphExecutionLimits),
                new ResolvedOperationalPolicy.ResultLimits(
                        storeCapabilities.contains(StoreCapability.EXECUTION_RESULTS),
                        maximumExecutionResultPayloadBytes),
                behaviors.builtInHttpCapacityFor(behaviorNames), capacities,
                java.util.Optional.ofNullable(maximumPersistencePayloadBytes)
                        .map(ResolvedOperationalPolicy.PersistenceLimits::new), List.of());
    }

    /** Resolves package and per-node external-I/O capacities from one immutable accepted graph. */
    public ResolvedOperationalPolicy operationalPolicyForNodes(Collection<GraphNode> nodes) {
        Objects.requireNonNull(nodes, "nodes");
        var behaviorNames = nodes.stream().filter(node -> node.behavior() != null)
                .map(GraphNode::behavior).collect(java.util.stream.Collectors.toSet());
        ResolvedOperationalPolicy base = operationalPolicyFor(behaviorNames);
        return new ResolvedOperationalPolicy(base.graph(), base.results(), base.builtInHttp(),
                base.nodePackages(), base.persistence(), behaviors.nodeExternalIoCapacitiesFor(nodes));
    }

    /** Compatibility overload: includes every installed package. */
    public ExecutionManifest manifestFor(ExecutionKey key, GraphContentId graphContentId,
                                         GraphDefinitionIdentity graphIdentity, ExecutionPolicy policy,
                                         Instant pinnedAt) {
        return manifestFor(key, graphContentId, graphIdentity, policy, pinnedAt,
                behaviors.catalogSources().keySet());
    }

    /** Builds a v4 manifest from the exact policy snapshot already used to construct runtime. */
    public ExecutionManifest manifestForResolved(ExecutionKey key, GraphContentId graphContentId,
                                                 GraphDefinitionIdentity graphIdentity,
                                                 ExecutionPolicy policy, Instant pinnedAt,
                                                 ResolvedOperationalPolicy operational) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(operational, "operational");
        var runtime = runtime(policy, executionLimitsDigestOf(operational.graph()));
        List<PinnedNodePackage> packages = operational.nodePackages().stream()
                .map(entry -> behaviors.nodePackageBinding(entry.packageId()).orElseThrow().identity())
                .toList();
        return new ExecutionManifest(ExecutionManifest.FORMAT_VERSION_4, key, graphContentId,
                graphIdentity, runtime, packages, pinnedAt, operational);
    }

    /** Builds a v2 manifest for one accepted graph and its actually referenced packages. */
    public ExecutionManifest manifestFor(ExecutionKey key, GraphContentId graphContentId,
                                         GraphDefinitionIdentity graphIdentity, ExecutionPolicy policy,
                                         Instant pinnedAt, Collection<String> behaviorNames) {
        Objects.requireNonNull(policy, "policy");
        ResolvedOperationalPolicy operational = legacyOperationalPolicyFor(behaviorNames);
        var runtime = runtime(policy, executionLimitsDigestOf(operational.graph()));
        List<PinnedNodePackage> packages = operational.nodePackages().stream()
                .map(entry -> behaviors.nodePackageBinding(entry.packageId()).orElseThrow().identity())
                .toList();
        int formatVersion = maximumPersistencePayloadBytes == null
                ? ExecutionManifest.FORMAT_VERSION_2 : ExecutionManifest.FORMAT_VERSION_3;
        return new ExecutionManifest(formatVersion, key, graphContentId,
                graphIdentity, runtime, packages, pinnedAt, operational);
    }

    private ResolvedOperationalPolicy legacyOperationalPolicyFor(Collection<String> behaviorNames) {
        ResolvedOperationalPolicy current = operationalPolicyFor(behaviorNames);
        var packages = current.nodePackages().stream().map(entry -> {
            var limits = entry.capacity().limits();
            if (limits.isEmpty()) return entry;
            var value = limits.orElseThrow();
            return new ResolvedOperationalPolicy.PackageCapacity(entry.packageId(),
                    ai.ravenroot.api.node.service.NodePackageEgressCapacityProfile.bounded(
                            value.maximumRequestBytes(), value.maximumResponseBytes(),
                            value.maximumWebSocketMessageBytes(), value.maximumWebSocketFragments(),
                            value.maximumConcurrentOperations(), value.maximumConcurrentPerTenant(),
                            value.maximumQueuedWebSocketSends(), 1_000, value.maximumDeadline(),
                            value.maximumWebSocketLifetime(), value.maximumWebSocketIdle()));
        }).toList();
        return new ResolvedOperationalPolicy(current.graph(), current.results(), current.builtInHttp(),
                packages, current.persistence());
    }

    /** Builds the frozen v1 description used only to compare existing v1 rows. */
    private ExecutionManifest legacyManifestFor(ExecutionManifest pinned, ExecutionPolicy policy) {
        return new ExecutionManifest(ExecutionManifest.FORMAT_VERSION_1, pinned.key(),
                pinned.graphContentId(), pinned.graphIdentity(), runtime(policy, executionLimitsDigest),
                behaviors.nodePackageIdentities(), pinned.pinnedAt());
    }

    /** Version-aware compatibility; v2 operational values are restored rather than compared to now. */
    public ExecutionManifestCompatibility compare(ExecutionManifest pinned, ExecutionPolicy policy) {
        Objects.requireNonNull(pinned, "pinned");
        Objects.requireNonNull(policy, "policy");
        if (pinned.formatVersion() == ExecutionManifest.FORMAT_VERSION_1) {
            return ExecutionManifestCompatibility.compare(pinned, legacyManifestFor(pinned, policy));
        }
        var found = new ArrayList<ExecutionManifestDifference>();
        ResolvedRuntimeProfile was = pinned.runtime();
        add(found, ExecutionManifestDifference.Dimension.GRAPH_SCHEMA_VERSION,
                was.graphSchemaVersion(), GraphVersionSnapshot.CURRENT_SCHEMA_VERSION);
        add(found, ExecutionManifestDifference.Dimension.DEFINITION_FORMAT_VERSION,
                was.definitionFormatVersion(),
                ai.ravenroot.api.persistence.CanonicalGraphMl.CURRENT_FORMAT_VERSION);
        add(found, ExecutionManifestDifference.Dimension.EXECUTION_POLICY,
                was.executionPolicy(), policy.name());
        add(found, ExecutionManifestDifference.Dimension.UNKNOWN_BEHAVIOR_MODE,
                was.unknownBehaviorMode(), unknownBehaviorMode);
        add(found, ExecutionManifestDifference.Dimension.ENGINE, was.engineDigest(), engineDigest);
        add(found, ExecutionManifestDifference.Dimension.EXECUTION_STORE,
                was.storeDigest(), storeDigest);
        add(found, ExecutionManifestDifference.Dimension.PROGRAM_RUNTIME,
                was.programRuntimeDigest(), programRuntimeDigest);
        add(found, ExecutionManifestDifference.Dimension.EXECUTION_LIMITS,
                was.executionLimitsDigest(), executionLimitsDigestOf(pinned.operationalPolicy().graph()));

        Map<String, PinnedNodePackage> installed = new LinkedHashMap<>();
        for (PinnedNodePackage candidate : behaviors.nodePackageIdentities()) {
            installed.put(candidate.packageId(), candidate);
        }
        for (PinnedNodePackage required : pinned.nodePackages()) {
            PinnedNodePackage present = installed.get(required.packageId());
            if (present == null) {
                found.add(new ExecutionManifestDifference(
                        ExecutionManifestDifference.Dimension.NODE_PACKAGE_MISSING,
                        identityOf(required), required.packageId() + "@absent"));
            } else if (!present.equals(required)) {
                found.add(new ExecutionManifestDifference(
                        ExecutionManifestDifference.Dimension.NODE_PACKAGE_CHANGED,
                        identityOf(required), identityOf(present)));
            } else if (behaviors.nodePackageBinding(required.packageId()).orElseThrow()
                    .capacity().isEmpty()) {
                found.add(new ExecutionManifestDifference(
                        ExecutionManifestDifference.Dimension.EXECUTION_LIMITS,
                        was.executionLimitsDigest(), "capacity-profile-unavailable"));
            }
        }
        boolean truncated = found.size() > ExecutionManifestCompatibility.MAX_DIFFERENCES;
        return new ExecutionManifestCompatibility(truncated
                ? found.subList(0, ExecutionManifestCompatibility.MAX_DIFFERENCES) : found, truncated);
    }

    /**
     * Returns a policy for execution. A v1 row is recoverable only when its old digest proves the
     * exact current graph tuple and the two newly required dimensions were inapplicable.
     */
    public ResolvedOperationalPolicy resolvePolicy(ExecutionManifest pinned, ExecutionPolicy policy) {
        return resolvePolicy(pinned, policy, behaviors.catalogSources().keySet());
    }

    /** Resolves policy using the immutable graph's exact behavior set for legacy safety proof. */
    public ResolvedOperationalPolicy resolvePolicy(ExecutionManifest pinned, ExecutionPolicy policy,
                                                   Collection<String> behaviorNames) {
        ExecutionManifestCompatibility compatibility = compare(pinned, policy);
        if (!compatibility.compatible()) {
            throw new ExecutionManifestIncompatibleException(pinned.key(), compatibility);
        }
        if (pinned.formatVersion() == ExecutionManifest.FORMAT_VERSION_2
                || pinned.formatVersion() == ExecutionManifest.FORMAT_VERSION_3
                || pinned.formatVersion() == ExecutionManifest.FORMAT_VERSION_4) {
            return pinned.operationalPolicy();
        }
        if (storeCapabilities.contains(StoreCapability.EXECUTION_RESULTS)
                || !behaviors.provesNoManagedEgress(behaviorNames)) {
            throw new ExecutionManifestResolutionException(
                    ExecutionManifestResolutionException.Reason.LEGACY_OPERATIONAL_POLICY_UNAVAILABLE);
        }
        return new ResolvedOperationalPolicy(graphPolicyOf(graphExecutionLimits),
                new ResolvedOperationalPolicy.ResultLimits(false,
                        maximumExecutionResultPayloadBytes), List.of());
    }

    /** Restores policy with proof that older layouts are unaffected by node-bound I/O capacity. */
    public ResolvedOperationalPolicy resolvePolicyForNodes(ExecutionManifest pinned, ExecutionPolicy policy,
                                                           Collection<GraphNode> nodes) {
        Objects.requireNonNull(nodes, "nodes");
        if (pinned.formatVersion() < ExecutionManifest.FORMAT_VERSION_4
                && nodes.stream().anyMatch(behaviors::requiresExternalIoCapacity)) {
            throw new ExecutionManifestResolutionException(
                    ExecutionManifestResolutionException.Reason.LEGACY_OPERATIONAL_POLICY_UNAVAILABLE);
        }
        return resolvePolicy(pinned, policy, nodes.stream().filter(node -> node.behavior() != null)
                .map(GraphNode::behavior).collect(java.util.stream.Collectors.toSet()));
    }

    /**
     * Restores the graph limits needed to parse the pinned document before v1 egress safety can be
     * decided from its behavior set. Legacy limits are returned only after their original digest
     * compares byte-for-byte; no missing v1 I/O or result value is invented here.
     */
    public ResolvedOperationalPolicy graphPolicyForParsing(ExecutionManifest pinned,
                                                           ExecutionPolicy policy) {
        ExecutionManifestCompatibility compatibility = compare(pinned, policy);
        if (!compatibility.compatible()) {
            throw new ExecutionManifestIncompatibleException(pinned.key(), compatibility);
        }
        if (pinned.formatVersion() == ExecutionManifest.FORMAT_VERSION_2
                || pinned.formatVersion() == ExecutionManifest.FORMAT_VERSION_3
                || pinned.formatVersion() == ExecutionManifest.FORMAT_VERSION_4) {
            return pinned.operationalPolicy();
        }
        return new ResolvedOperationalPolicy(graphPolicyOf(graphExecutionLimits),
                new ResolvedOperationalPolicy.ResultLimits(false,
                        maximumExecutionResultPayloadBytes), List.of());
    }

    /** Converts pinned API values into the core limits consumed by graph recovery. */
    public static GraphExecutionLimits graphExecutionLimits(ResolvedOperationalPolicy policy) {
        ResolvedOperationalPolicy.GraphLimits value = Objects.requireNonNull(policy, "policy").graph();
        return new GraphExecutionLimits(new GraphMlLimits(value.graphMlMaxBytes(),
                value.graphMlMaxNodes(), value.graphMlMaxEdges(), value.graphMlMaxProperties(),
                value.graphMlMaxDepth(), value.graphMlMaxStringLength(), value.graphMlMaxKeys(),
                value.graphMlMaxElements(), value.graphMlMaxAttributes(),
                value.graphMlMaxNamespaceDeclarations()),
                new PayloadLimits(value.payloadMaxEncodedBytes(), value.payloadMaxDepth(),
                        value.payloadMaxCollectionSize(), value.payloadMaxValueCount(),
                        value.payloadMaxTextLength(), value.payloadMaxKeyLength()),
                value.maxFanOut(), value.maxResidentActors(), value.maxLiveActorsPerTraversal(),
                value.maxInFlightHopsPerTraversal(), value.maxQueuedAdmissionsPerNode(),
                value.maxTraversalSteps(), value.maxAmplifiedDeliveries(),
                value.maxCumulativePayloadBytes(), value.maxRecoveryDeliveriesPerAttempt());
    }

    private ResolvedRuntimeProfile runtime(ExecutionPolicy policy, String limitsDigest) {
        return new ResolvedRuntimeProfile(GraphVersionSnapshot.CURRENT_SCHEMA_VERSION,
                ai.ravenroot.api.persistence.CanonicalGraphMl.CURRENT_FORMAT_VERSION,
                policy.name(), unknownBehaviorMode, engineDigest, storeDigest, limitsDigest,
                programRuntimeDigest);
    }

    private static ResolvedOperationalPolicy.GraphLimits graphPolicyOf(GraphExecutionLimits limits) {
        GraphMlLimits graphMl = limits.graphMl();
        PayloadLimits payload = limits.payload();
        return new ResolvedOperationalPolicy.GraphLimits(graphMl.maxBytes(), graphMl.maxNodes(),
                graphMl.maxEdges(), graphMl.maxProperties(), graphMl.maxDepth(),
                graphMl.maxStringLength(), graphMl.maxKeys(), graphMl.maxElements(),
                graphMl.maxAttributes(), graphMl.maxNamespaceDeclarations(),
                payload.maxEncodedBytes(), payload.maxDepth(), payload.maxCollectionSize(),
                payload.maxValueCount(), payload.maxTextLength(), payload.maxKeyLength(),
                limits.maxFanOut(), limits.maxResidentActors(), limits.maxLiveActorsPerTraversal(),
                limits.maxInFlightHopsPerTraversal(), limits.maxQueuedAdmissionsPerNode(),
                limits.maxTraversalSteps(), limits.maxAmplifiedDeliveries(),
                limits.maxCumulativePayloadBytes(), limits.maxRecoveryDeliveriesPerAttempt());
    }

    private static String executionLimitsDigestOf(ResolvedOperationalPolicy.GraphLimits value) {
        return ResolvedRuntimeProfile.digestOf(ResolvedRuntimeProfile.LIMITS_DOMAIN, List.of(
                Integer.toString(value.graphMlMaxBytes()), Integer.toString(value.graphMlMaxNodes()),
                Integer.toString(value.graphMlMaxEdges()), Integer.toString(value.graphMlMaxProperties()),
                Integer.toString(value.graphMlMaxDepth()), Integer.toString(value.graphMlMaxStringLength()),
                Integer.toString(value.graphMlMaxKeys()), Integer.toString(value.graphMlMaxElements()),
                Integer.toString(value.graphMlMaxAttributes()),
                Integer.toString(value.graphMlMaxNamespaceDeclarations()),
                Integer.toString(value.payloadMaxEncodedBytes()),
                Integer.toString(value.payloadMaxDepth()),
                Integer.toString(value.payloadMaxCollectionSize()),
                Integer.toString(value.payloadMaxValueCount()),
                Integer.toString(value.payloadMaxTextLength()),
                Integer.toString(value.payloadMaxKeyLength()), Integer.toString(value.maxFanOut()),
                Integer.toString(value.maxResidentActors()),
                Integer.toString(value.maxLiveActorsPerTraversal()),
                Integer.toString(value.maxInFlightHopsPerTraversal()),
                Integer.toString(value.maxQueuedAdmissionsPerNode()),
                Long.toString(value.maxTraversalSteps()),
                Long.toString(value.maxAmplifiedDeliveries()),
                Long.toString(value.maxCumulativePayloadBytes()),
                Integer.toString(value.maxRecoveryDeliveriesPerAttempt())));
    }

    private static String limitsDigestOf(GraphExecutionLimits limits) {
        return executionLimitsDigestOf(graphPolicyOf(limits));
    }

    private static String engineDigestOf(ExecutionEngine engine) {
        String policyFingerprint = engine.compatibilityFingerprint();
        if (policyFingerprint == null
                || (!policyFingerprint.isEmpty() && !policyFingerprint.matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException(
                    "Execution engine compatibility fingerprint must be empty or lowercase SHA-256 hexadecimal");
        }
        var parts = new ArrayList<String>();
        parts.add(engine.id());
        var names = new TreeSet<String>();
        for (EngineCapability capability : engine.capabilities()) names.add(capability.name());
        parts.add(Integer.toString(names.size()));
        parts.addAll(names);
        if (!policyFingerprint.isEmpty()) {
            parts.add(ENGINE_POLICY_FINGERPRINT_VERSION);
            parts.add(policyFingerprint);
        }
        return ResolvedRuntimeProfile.digestOf(ResolvedRuntimeProfile.ENGINE_DOMAIN, parts);
    }

    private static String storeDigestOf(Set<StoreCapability> capabilities) {
        var names = new TreeSet<String>();
        for (StoreCapability capability : capabilities) names.add(capability.name());
        var parts = new ArrayList<String>();
        parts.add(Integer.toString(names.size()));
        parts.addAll(names);
        return ResolvedRuntimeProfile.digestOf(ResolvedRuntimeProfile.STORE_DOMAIN, parts);
    }

    private static String programRuntimeDigestOf(ProgramRuntime programRuntime) {
        List<String> parts = programRuntime == null ? List.of("", "")
                : List.of(programRuntime.id(), programRuntime.compatibilityFingerprint());
        return ResolvedRuntimeProfile.digestOf(ResolvedRuntimeProfile.PROGRAM_RUNTIME_DOMAIN, parts);
    }

    private static String normalizedMode(UnknownBehaviorPolicy unknownBehaviors) {
        String mode = unknownBehaviors.mode();
        if (UnknownBehaviorPolicy.REFUSE_VALUE.equals(mode)
                || UnknownBehaviorPolicy.PASS_THROUGH_VALUE.equals(mode)) return mode;
        return UnknownBehaviorPolicy.UNKNOWN_VALUE;
    }

    private static String identityOf(PinnedNodePackage pinned) {
        return pinned.packageId() + "@" + pinned.identityDigest();
    }

    private static void add(List<ExecutionManifestDifference> found,
                            ExecutionManifestDifference.Dimension dimension, Object was, Object now) {
        if (!was.equals(now)) {
            found.add(new ExecutionManifestDifference(dimension, was.toString(), now.toString()));
        }
    }
}
