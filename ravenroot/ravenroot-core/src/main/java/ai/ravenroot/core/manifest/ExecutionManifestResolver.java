package ai.ravenroot.core.manifest;

import ai.ravenroot.api.application.ExecutionPolicy;
import ai.ravenroot.api.execution.EngineCapability;
import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionManifest;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Turns what a runtime has actually composed into the profile an execution is pinned against.
 *
 * <h2>Resolved once, at composition, and then never re-read</h2>
 * <p>Every input to this resolver is fixed for the life of the process: the engine and its
 * capabilities, the execution store's capabilities, the operator-supplied execution limits, the
 * unknown-behavior stance, the program runtime and the registered node packages are all decided
 * before the first submission and cannot change under a running process. Resolving them once is
 * therefore not a cache to be invalidated; it is a statement of that fact. Re-reading them per
 * submission would suggest they could differ between two submissions of the same process, which
 * would be a more misleading design and a slower one.</p>
 *
 * <p>The one input that does vary per submission is {@link ExecutionPolicy}, which is chosen by the
 * caller, so it is a parameter of {@link #manifestFor} rather than of construction.</p>
 *
 * <h2>Why adapter-supplied values are digested here rather than recorded</h2>
 * <p>{@link ResolvedRuntimeProfile} states the argument: identities this contract does not own are
 * digested so that a change is still detected exactly while the manifest still cannot carry anything
 * but hexadecimal. This class is where that digesting happens, so the encoding lives in one place
 * and the two sides of a comparison cannot drift apart.</p>
 */
public final class ExecutionManifestResolver {

    private final String engineDigest;
    private final String storeDigest;
    private final String executionLimitsDigest;
    private final String programRuntimeDigest;
    private final String unknownBehaviorMode;
    private final List<PinnedNodePackage> nodePackages;

    private ExecutionManifestResolver(String engineDigest, String storeDigest,
                                      String executionLimitsDigest, String programRuntimeDigest,
                                      String unknownBehaviorMode, List<PinnedNodePackage> nodePackages) {
        this.engineDigest = engineDigest;
        this.storeDigest = storeDigest;
        this.executionLimitsDigest = executionLimitsDigest;
        this.programRuntimeDigest = programRuntimeDigest;
        this.unknownBehaviorMode = unknownBehaviorMode;
        this.nodePackages = List.copyOf(nodePackages);
    }

    /**
     * Resolves the dependency profile from a composed runtime.
     *
     * <p>{@code storeCapabilities} is passed rather than an {@code ExecutionStore}, because the only
     * thing this resolver needs from that port is its self-description, and a resolver that held the
     * store would look as though it might read from it.</p>
     *
     * @param engine execution engine this runtime dispatches through.
     * @param storeCapabilities capability set the composed execution store advertises.
     * @param behaviors trusted behavior catalog whose node packages this execution may reach.
     * @param unknownBehaviors admission stance for a behavior no catalog entry claims.
     * @param limits operator-owned graph admission and traversal limits in force.
     * @param programRuntime composed program runtime, or {@code null} when none is composed.
     * @return a resolver that stamps every manifest with this runtime's resolved dependencies.
     */
    public static ExecutionManifestResolver from(ExecutionEngine engine,
                                                 Set<StoreCapability> storeCapabilities,
                                                 BehaviorRegistry behaviors,
                                                 UnknownBehaviorPolicy unknownBehaviors,
                                                 GraphExecutionLimits limits,
                                                 ProgramRuntime programRuntime) {
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(storeCapabilities, "storeCapabilities");
        Objects.requireNonNull(behaviors, "behaviors");
        Objects.requireNonNull(unknownBehaviors, "unknownBehaviors");
        Objects.requireNonNull(limits, "limits");
        return new ExecutionManifestResolver(
                engineDigestOf(engine),
                storeDigestOf(storeCapabilities),
                limitsDigestOf(limits),
                programRuntimeDigestOf(programRuntime),
                normalizedMode(unknownBehaviors),
                packagesOf(behaviors));
    }

    /**
     * The node packages this runtime resolved, sorted and free of duplicates.
     *
     * @return the packages every manifest this resolver builds will pin.
     */
    public List<PinnedNodePackage> nodePackages() {
        return nodePackages;
    }

    /**
     * Builds the manifest for one execution under one submission policy.
     *
     * @param key tenant-scoped process instance the manifest is pinned for.
     * @param graphContentId address of the canonical document the execution was accepted against.
     * @param graphIdentity logical graph and version identity that document is bound under.
     * @param policy submission policy this execution runs under.
     * @param pinnedAt instant to record as the moment these dependencies were resolved.
     * @return the manifest to pin, or to compare a pinned one against.
     */
    public ExecutionManifest manifestFor(ExecutionKey key, GraphContentId graphContentId,
                                         GraphDefinitionIdentity graphIdentity, ExecutionPolicy policy,
                                         Instant pinnedAt) {
        Objects.requireNonNull(policy, "policy");
        var runtime = new ResolvedRuntimeProfile(
                GraphVersionSnapshot.CURRENT_SCHEMA_VERSION,
                ai.ravenroot.api.persistence.CanonicalGraphMl.CURRENT_FORMAT_VERSION,
                policy.name(), unknownBehaviorMode, engineDigest, storeDigest,
                executionLimitsDigest, programRuntimeDigest);
        return new ExecutionManifest(ExecutionManifest.CURRENT_FORMAT_VERSION, key, graphContentId,
                graphIdentity, runtime, nodePackages, pinnedAt);
    }

    private static String engineDigestOf(ExecutionEngine engine) {
        var parts = new ArrayList<String>();
        parts.add(engine.id());
        var names = new TreeSet<String>();
        for (EngineCapability capability : engine.capabilities()) {
            names.add(capability.name());
        }
        parts.add(Integer.toString(names.size()));
        parts.addAll(names);
        return ResolvedRuntimeProfile.digestOf(ResolvedRuntimeProfile.ENGINE_DOMAIN, parts);
    }

    private static String storeDigestOf(Set<StoreCapability> capabilities) {
        var names = new TreeSet<String>();
        for (StoreCapability capability : capabilities) {
            names.add(capability.name());
        }
        var parts = new ArrayList<String>();
        parts.add(Integer.toString(names.size()));
        parts.addAll(names);
        return ResolvedRuntimeProfile.digestOf(ResolvedRuntimeProfile.STORE_DOMAIN, parts);
    }

    /**
     * Encodes every limit explicitly rather than through {@code toString}.
     *
     * <p>A generated {@code toString} is not a contract: adding a component, or reordering two, would
     * silently change every stored digest and make every retained execution look incompatible. Naming
     * the components here means a future limit is a deliberate decision about the manifest format
     * rather than an accident of a record definition.</p>
     */
    private static String limitsDigestOf(GraphExecutionLimits limits) {
        GraphMlLimits graphMl = limits.graphMl();
        PayloadLimits payload = limits.payload();
        List<String> parts = List.of(
                Integer.toString(graphMl.maxBytes()),
                Integer.toString(graphMl.maxNodes()),
                Integer.toString(graphMl.maxEdges()),
                Integer.toString(graphMl.maxProperties()),
                Integer.toString(graphMl.maxDepth()),
                Integer.toString(graphMl.maxStringLength()),
                Integer.toString(graphMl.maxKeys()),
                Integer.toString(graphMl.maxElements()),
                Integer.toString(graphMl.maxAttributes()),
                Integer.toString(graphMl.maxNamespaceDeclarations()),
                Integer.toString(payload.maxEncodedBytes()),
                Integer.toString(payload.maxDepth()),
                Integer.toString(payload.maxCollectionSize()),
                Integer.toString(payload.maxValueCount()),
                Integer.toString(payload.maxTextLength()),
                Integer.toString(payload.maxKeyLength()),
                Integer.toString(limits.maxFanOut()),
                Integer.toString(limits.maxResidentActors()),
                Integer.toString(limits.maxLiveActorsPerTraversal()),
                Integer.toString(limits.maxInFlightHopsPerTraversal()),
                Integer.toString(limits.maxQueuedAdmissionsPerNode()),
                Long.toString(limits.maxTraversalSteps()),
                Long.toString(limits.maxAmplifiedDeliveries()),
                Long.toString(limits.maxCumulativePayloadBytes()),
                Integer.toString(limits.maxRecoveryDeliveriesPerAttempt()));
        return ResolvedRuntimeProfile.digestOf(ResolvedRuntimeProfile.LIMITS_DOMAIN, parts);
    }

    private static String programRuntimeDigestOf(ProgramRuntime programRuntime) {
        List<String> parts = programRuntime == null
                ? List.of("", "")
                : List.of(programRuntime.id(), programRuntime.compatibilityFingerprint());
        return ResolvedRuntimeProfile.digestOf(ResolvedRuntimeProfile.PROGRAM_RUNTIME_DOMAIN, parts);
    }

    /**
     * Normalizes the unknown-behavior stance to the three values this repository defines.
     *
     * <p>{@code mode()} is a probe rather than a declaration, and an integrator may override it. A
     * value outside the known vocabulary is recorded as {@code unknown} rather than verbatim, so a
     * manifest cannot become a channel for arbitrary implementation text. The cost is stated: two
     * different unrecognised stances both record as {@code unknown} and therefore compare equal.
     * That is acceptable here and only here, because this repository already documents {@code mode()}
     * as observability that gates no admission decision.</p>
     */
    private static String normalizedMode(UnknownBehaviorPolicy unknownBehaviors) {
        String mode = unknownBehaviors.mode();
        if (UnknownBehaviorPolicy.REFUSE_VALUE.equals(mode)
                || UnknownBehaviorPolicy.PASS_THROUGH_VALUE.equals(mode)) {
            return mode;
        }
        return UnknownBehaviorPolicy.UNKNOWN_VALUE;
    }

    private static List<PinnedNodePackage> packagesOf(BehaviorRegistry behaviors) {
        return List.copyOf(behaviors.nodePackageIdentities());
    }
}
