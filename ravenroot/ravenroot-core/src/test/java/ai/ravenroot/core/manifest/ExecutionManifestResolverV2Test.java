package ai.ravenroot.core.manifest;

import ai.ravenroot.api.application.ExecutionPolicy;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.api.node.service.NodePackageEgressCapacityProfile;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionManifest;
import ai.ravenroot.api.persistence.ExecutionManifestDifference;
import ai.ravenroot.api.persistence.GraphContentId;
import ai.ravenroot.api.persistence.GraphDefinitionIdentity;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.GraphExecutionLimits;
import ai.ravenroot.core.runtime.NodePackageServiceRegistry;
import ai.ravenroot.core.runtime.NodePackages;
import ai.ravenroot.core.runtime.UnknownBehaviorPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionManifestResolverV2Test {
    private static final Instant PINNED_AT = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void legacyFactoryStillEmitsV1WhileCompleteFactoryEmitsV2() {
        BehaviorRegistry registry = new BehaviorRegistry();
        assertEquals(ExecutionManifest.LEGACY_FORMAT_VERSION,
                legacy(registry).manifestFor(key(), content(), identity(), ExecutionPolicy.STANDARD,
                        PINNED_AT).formatVersion());
        assertEquals(ExecutionManifest.CURRENT_FORMAT_VERSION,
                complete(registry, false, 101).manifestFor(key(), content(), identity(),
                        ExecutionPolicy.STANDARD, PINNED_AT).formatVersion());
    }

    @Test
    void resultPresenceAndEffectiveProjectionCapAreBothPinned() {
        BehaviorRegistry registry = new BehaviorRegistry();
        ExecutionManifest local = manifest(complete(registry, false, 101));
        ExecutionManifest durableSameCap = manifest(complete(registry, true, 101));
        ExecutionManifest durableChangedCap = manifest(complete(registry, true, 102));

        assertNotEquals(local.runtime().storeDigest(), durableSameCap.runtime().storeDigest());
        assertNotEquals(durableSameCap.runtime().storeDigest(),
                durableChangedCap.runtime().storeDigest());
        assertEquals(ExecutionManifestDifference.Dimension.EXECUTION_STORE,
                complete(registry, true, 101).compare(local, ExecutionPolicy.STANDARD)
                        .differences().getFirst().dimension());
    }

    @Test
    void everyPublishedEgressScalarChangesTheV2LimitsDigest() {
        String baseline = manifest(complete(registry("test.capacity", profile(0)), true, 101))
                .runtime().executionLimitsDigest();
        for (int changedField = 1; changedField <= 11; changedField++) {
            String changed = manifest(complete(
                    registry("test.capacity", profile(changedField)), true, 101))
                    .runtime().executionLimitsDigest();
            assertNotEquals(baseline, changed, "capacity field " + changedField + " was not pinned");
        }
    }

    @Test
    void verificationSelectsPinnedIdsBeforeCheckingUnknownProfiles() {
        ExecutionManifest pinned = manifest(complete(
                registry("test.required", profile(0)), true, 101));

        BehaviorRegistry current = registry("test.required", profile(0));
        register(current, "test.unrelated", Optional.empty());
        ExecutionManifestResolver resolver = complete(current, true, 101);

        assertTrue(resolver.compare(pinned, ExecutionPolicy.STANDARD).compatible());
        ExecutionManifestResolutionException refusal = assertThrows(
                ExecutionManifestResolutionException.class, resolver::requireAdmissionReady);
        assertEquals(ExecutionManifestResolutionException.Reason.CAPACITY_PROFILE_UNAVAILABLE,
                refusal.reason());
    }

    @Test
    void aRequiredUnknownProfileReturnsABoundedTokenRatherThanAFakeDigest() {
        ExecutionManifest pinned = manifest(complete(
                registry("test.required", profile(0)), true, 101));
        var report = complete(registry("test.required", Optional.empty()), true, 101)
                .compare(pinned, ExecutionPolicy.STANDARD);

        assertFalse(report.compatible());
        assertEquals(1, report.differences().size());
        assertEquals(ExecutionManifestDifference.Dimension.EXECUTION_LIMITS,
                report.differences().getFirst().dimension());
        assertEquals("capacity-profile-unavailable", report.differences().getFirst().observed());
        assertFalse(report.differences().getFirst().observed().matches("[0-9a-f]{64}"));
    }

    @Test
    void missingAndChangedPinnedPackagesRetainTheirExistingDimensions() {
        ExecutionManifest pinned = manifest(complete(
                registry("test.required", profile(0)), true, 101));
        assertEquals(ExecutionManifestDifference.Dimension.NODE_PACKAGE_MISSING,
                complete(new BehaviorRegistry(), true, 101)
                        .compare(pinned, ExecutionPolicy.STANDARD).differences().getFirst().dimension());

        BehaviorRegistry changed = new BehaviorRegistry();
        register(changed, "test.required", "2.0", Optional.of(profile(0)));
        assertTrue(complete(changed, true, 101).compare(pinned, ExecutionPolicy.STANDARD)
                .dimensions().contains(ExecutionManifestDifference.Dimension.NODE_PACKAGE_CHANGED));
    }

    @Test
    void resolverSnapshotDoesNotAcquirePackagesRegisteredLater() {
        BehaviorRegistry registry = registry("test.required", profile(0));
        ExecutionManifestResolver resolver = complete(registry, true, 101);
        register(registry, "test.later", Optional.empty());

        assertEquals(List.of("test.required"), resolver.nodePackages().stream()
                .map(packageIdentity -> packageIdentity.packageId()).toList());
        assertEquals(List.of("test.required"), manifest(resolver).nodePackages().stream()
                .map(packageIdentity -> packageIdentity.packageId()).toList());
    }

    @Test
    void completeResolverRefusesLegacyManifestAtTheFormatBoundary() {
        ExecutionManifest legacy = manifest(legacy(new BehaviorRegistry()));
        var report = complete(new BehaviorRegistry(), false, 101)
                .compare(legacy, ExecutionPolicy.STANDARD);
        assertEquals(List.of(ExecutionManifestDifference.Dimension.MANIFEST_FORMAT_VERSION),
                report.dimensions());
    }

    private static ExecutionManifestResolver legacy(BehaviorRegistry registry) {
        return ExecutionManifestResolver.from(engine(), Set.of(), registry,
                UnknownBehaviorPolicy.passThrough(), GraphExecutionLimits.DEFAULTS,
                new DisabledProgramRuntime());
    }

    private static ExecutionManifestResolver complete(BehaviorRegistry registry,
                                                      boolean durableResults, int effectiveCap) {
        return ExecutionManifestResolver.complete(engine(),
                durableResults ? Set.of(StoreCapability.EXECUTION_RESULTS) : Set.of(),
                durableResults, effectiveCap, registry, UnknownBehaviorPolicy.passThrough(),
                GraphExecutionLimits.DEFAULTS, new DisabledProgramRuntime());
    }

    private static ExecutionEngine engine() {
        return (ExecutionEngine) Proxy.newProxyInstance(
                ExecutionEngine.class.getClassLoader(), new Class<?>[] {ExecutionEngine.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "id" -> "test-engine";
                    case "capabilities" -> Set.of();
                    case "compatibilityFingerprint" -> "";
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static ExecutionManifest manifest(ExecutionManifestResolver resolver) {
        return resolver.manifestFor(key(), content(), identity(), ExecutionPolicy.STANDARD, PINNED_AT);
    }

    private static ExecutionKey key() {
        return new ExecutionKey("acme", UUID.fromString("00000000-0000-0000-0000-000000000001"));
    }

    private static GraphContentId content() {
        return new GraphContentId("a".repeat(64));
    }

    private static GraphDefinitionIdentity identity() {
        return GraphDefinitionIdentity.forSubmission(content());
    }

    private static BehaviorRegistry registry(String packageId,
                                             NodePackageEgressCapacityProfile profile) {
        return registry(packageId, Optional.of(profile));
    }

    private static BehaviorRegistry registry(String packageId,
                                             Optional<NodePackageEgressCapacityProfile> profile) {
        BehaviorRegistry registry = new BehaviorRegistry();
        register(registry, packageId, profile);
        return registry;
    }

    private static void register(BehaviorRegistry registry, String packageId,
                                 Optional<NodePackageEgressCapacityProfile> profile) {
        register(registry, packageId, "1.0", profile);
    }

    private static void register(BehaviorRegistry registry, String packageId, String version,
                                 Optional<NodePackageEgressCapacityProfile> profile) {
        NodePackageServices unavailable = NodePackageServices.unavailable();
        NodePackageServices services = new NodePackageServices() {
            @Override public Set<ai.ravenroot.api.node.service.NodePackageCapability> capabilities() {
                return Set.of();
            }
            @Override public ai.ravenroot.api.node.service.NodeCredentialService credentials() {
                return unavailable.credentials();
            }
            @Override public ai.ravenroot.api.node.service.OutboundHttpService outboundHttp() {
                return unavailable.outboundHttp();
            }
            @Override public ai.ravenroot.api.node.service.OutboundWebSocketService outboundWebSocket() {
                return unavailable.outboundWebSocket();
            }
            @Override public Optional<NodePackageEgressCapacityProfile> egressCapacityProfile() {
                return profile;
            }
        };
        NodePackage nodePackage = new NodePackage() {
            @Override public String id() { return packageId; }
            @Override public String version() { return version; }
            @Override public String sdkContract() { return NodeSdk.CONTRACT; }
            @Override public List<NodeBehavior> behaviors() {
                return List.of(behavior(packageId.replace('.', '-')));
            }
        };
        NodePackages.register(registry, nodePackage,
                NodePackageServiceRegistry.builder().grant(packageId, services).build());
    }

    private static NodeBehavior behavior(String name) {
        return new NodeBehavior() {
            @Override public NodeTypeDescriptor descriptor() {
                return new NodeTypeDescriptor(name, name, "Test", "", "actor", false,
                        List.of(), Set.of());
            }
            @Override public NodeAction create(NodeConfiguration configuration) {
                return message -> CompletableFuture.completedFuture(
                        NodeResult.continueWith(message.payload()));
            }
        };
    }

    private static NodePackageEgressCapacityProfile profile(int changed) {
        return NodePackageEgressCapacityProfile.bounded(
                changed == 1 ? 102 : 101,
                changed == 2 ? 202 : 201,
                changed == 3 ? 302 : 301,
                changed == 4 ? 12 : 11,
                changed == 5 ? 22 : 21,
                changed == 6 ? 12 : 11,
                changed == 7 ? 32 : 31,
                Duration.ofSeconds(changed == 8 ? 42 : 41),
                Duration.ofSeconds(changed == 9 ? 52 : 51),
                Duration.ofSeconds(changed == 10 ? 12 : 11),
                changed == 11 ? 62 : 61);
    }
}
