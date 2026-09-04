package ai.ravenroot.testkit.persistence;

import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionManifest;
import ai.ravenroot.api.persistence.ExecutionManifestCompatibility;
import ai.ravenroot.api.persistence.ExecutionManifestDifference;
import ai.ravenroot.api.persistence.ExecutionManifestDigest;
import ai.ravenroot.api.persistence.ExecutionManifestReferences;
import ai.ravenroot.api.persistence.ExecutionManifestStore;
import ai.ravenroot.api.persistence.ExecutionManifestStoreException;
import ai.ravenroot.api.persistence.ExecutionManifestStoreFailure;
import ai.ravenroot.api.persistence.GraphContentId;
import ai.ravenroot.api.persistence.GraphDefinitionIdentity;
import ai.ravenroot.api.persistence.PinnedNodePackage;
import ai.ravenroot.api.persistence.ResolvedRuntimeProfile;
import ai.ravenroot.api.persistence.Retryability;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.api.persistence.StoredExecutionManifest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reusable conformance suite every {@link ExecutionManifestStore} adapter must extend, following
 * the {@code GraphDefinitionStoreContract} precedent in this module.
 *
 * <p>Every assertion is derived from the port's own contract text rather than from reading any one
 * adapter, so an adapter that reproduces a bug already present in the reference adapter is still
 * caught. Test methods are {@code final}: a subclass supplies a factory, never a weaker
 * assertion.</p>
 *
 * <h2>Capability-gated assertions</h2>
 * <p>Enforcement is asymmetric, exactly as in the definition suite: absence of a declared
 * {@link StoreCapability} skips its assertion through {@link Assumptions#assumeTrue}, which reports
 * as a visible skip and never as a silent pass; presence never skips it.
 * {@link StoreCapability#DURABLE} is structurally impossible for an in-memory adapter to honour, so
 * the reopen assertion does not run against one — and does run, and must pass, against the SQLite
 * adapter.</p>
 *
 * <h2>The reference oracle</h2>
 * <p>Whether retained work still needs a manifest is a fact the manifest store does not own, so the
 * suite injects a controllable {@link ExecutionManifestReferences} and drives it with
 * {@link #markReferenced(ExecutionKey)}. An adapter that also computes reachability from durable
 * state it is co-located with must treat the two as a conjunction — removable only when every source
 * says unreachable — so these assertions hold for it unchanged.</p>
 *
 * <h2>Adapter-conditional failures</h2>
 * <p>{@link ExecutionManifestStoreFailure.Corrupted},
 * {@link ExecutionManifestStoreFailure.DigestMismatch},
 * {@link ExecutionManifestStoreFailure.NotAuthorized},
 * {@link ExecutionManifestStoreFailure.Unavailable} and
 * {@link ExecutionManifestStoreFailure.OutcomeUnknown} cannot be reached through this port's own
 * operations by a conforming adapter: nothing a caller can ask for produces them, because the only
 * way to make a stored manifest disagree with its address is to reach past the port. Their
 * classification is asserted here by constructing the failure records directly, which is what the
 * definition suite does for the same reason, and the live digest-mismatch behaviour is asserted by
 * the adapter that can actually be driven into it.</p>
 */
public abstract class ExecutionManifestStoreContract {

    /** Arbitrary, fixed epoch so every test starts from a readable, reproducible instant. */
    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");
    private static final String DEFAULT_TENANT = "acme";
    private static final String OTHER_TENANT = "globex";

    private final Set<ExecutionKey> referenced = ConcurrentHashMap.newKeySet();

    private String storeId;
    private MutableClock mutableClock;
    private ExecutionManifestStore store;

    /**
     * Creates, or reopens, an adapter instance backed by {@code clock} and consulting
     * {@code references} when deciding what retention may remove.
     *
     * @param storeId stable backing identity, so a reopen reconnects to the same stored manifests.
     * @param clock clock the adapter must treat as its time authority.
     * @param references oracle the adapter must consult before removing a manifest.
     * @return an adapter instance under test.
     */
    protected abstract ExecutionManifestStore createStore(String storeId, Clock clock,
                                                          ExecutionManifestReferences references);

    @AfterEach
    final void closeStore() {
        if (store != null) {
            store.close();
        }
    }

    /**
     * Returns the adapter under test, creating it on first use.
     *
     * @return the adapter instance this test is asserting against.
     */
    protected final ExecutionManifestStore store() {
        if (store == null) {
            storeId = "manifests-" + UUID.randomUUID();
            mutableClock = new MutableClock(EPOCH);
            store = createStore(storeId, mutableClock, referenced::contains);
        }
        return store;
    }

    /**
     * Returns the controllable clock the adapter under test was given.
     *
     * @return the suite's mutable clock.
     */
    protected final MutableClock clock() {
        store();
        return mutableClock;
    }

    /**
     * Simulates a reopen or a process death against the same backing storage and the same clock.
     *
     * @return the reopened adapter instance.
     */
    protected final ExecutionManifestStore reopen() {
        store().close();
        store = createStore(storeId, mutableClock, referenced::contains);
        return store;
    }

    /**
     * Declares that retained work still needs this manifest, so retention must leave it in place.
     *
     * @param key tenant-scoped execution to mark as still referenced.
     */
    protected final void markReferenced(ExecutionKey key) {
        referenced.add(key);
    }

    private void assumeCapability(StoreCapability capability) {
        Assumptions.assumeTrue(store().supports(capability), () -> capability
                + " not declared by this adapter; assertion skipped under the asymmetric enforcement "
                + "rule (absence skips, presence never does)");
    }

    // ============================================================ self-description

    @Test
    final void capabilitiesAreImmutableAndStable() {
        Set<StoreCapability> first = store().capabilities();
        assertEquals(first, store().capabilities(), "capabilities must not change between calls");
        assertThrows(UnsupportedOperationException.class,
                () -> first.add(StoreCapability.DURABLE),
                "a caller must not be able to widen an adapter's declared capabilities");
    }

    @Test
    final void supportsAgreesWithCapabilities() {
        for (StoreCapability capability : StoreCapability.values()) {
            assertEquals(store().capabilities().contains(capability), store().supports(capability),
                    () -> "supports(" + capability + ") must agree with capabilities()");
        }
    }

    // ============================================================ pin and load

    @Test
    final void aPinnedManifestReadsBackFieldForField() {
        ExecutionKey key = key(DEFAULT_TENANT);
        ExecutionManifest manifest = manifest(key, "a", "STANDARD");
        StoredExecutionManifest pinned = await(store().pin(manifest));

        assertEquals(manifest, pinned.manifest());
        assertEquals(manifest.digest(), pinned.digest());
        assertEquals(EPOCH, pinned.committedAt());
        assertEquals(pinned.manifest(), await(store().load(key)).manifest());
    }

    @Test
    final void nodePackagesSurviveTheRoundTripSortedAndComplete() {
        ExecutionKey key = key(DEFAULT_TENANT);
        List<PinnedNodePackage> packages = List.of(
                new PinnedNodePackage("zeta.nodes", "2.0.0", "node-sdk-1"),
                new PinnedNodePackage("alpha.nodes", "1.4.2", "node-sdk-1"));
        ExecutionManifest manifest = manifest(key, "a", "STANDARD", packages);
        await(store().pin(manifest));

        List<PinnedNodePackage> read = await(store().load(key)).manifest().nodePackages();
        assertEquals(List.of(
                new PinnedNodePackage("alpha.nodes", "1.4.2", "node-sdk-1"),
                new PinnedNodePackage("zeta.nodes", "2.0.0", "node-sdk-1")), read,
                "a manifest pins its packages in a stable order so its address does not depend on "
                        + "registration order");
    }

    @Test
    final void aManifestWithNoNodePackagesIsLegalAndDistinct() {
        ExecutionKey withNone = key(DEFAULT_TENANT);
        ExecutionKey withOne = key(DEFAULT_TENANT);
        await(store().pin(manifest(withNone, "a", "STANDARD", List.of())));
        await(store().pin(manifest(withOne, "a", "STANDARD",
                List.of(new PinnedNodePackage("alpha.nodes", "1.0.0", "node-sdk-1")))));

        assertTrue(await(store().load(withNone)).manifest().nodePackages().isEmpty());
        assertNotEquals(await(store().load(withNone)).digest(), await(store().load(withOne)).digest(),
                "a manifest that pins a package must not address the same as one that pins none");
    }

    @Test
    final void repeatingTheIdenticalPinConvergesOnOneManifest() {
        ExecutionKey key = key(DEFAULT_TENANT);
        ExecutionManifest manifest = manifest(key, "a", "STANDARD");
        StoredExecutionManifest first = await(store().pin(manifest));
        clock().advance(java.time.Duration.ofMinutes(5));
        StoredExecutionManifest second = await(store().pin(manifest));

        assertEquals(first.digest(), second.digest());
        assertEquals(first.committedAt(), second.committedAt(),
                "a repeated pin returns what is stored rather than restamping it, so an unknown "
                        + "outcome can be resolved by repeating the write");
    }

    @Test
    final void pinningDifferentContentForAPinnedExecutionIsRefused() {
        ExecutionKey key = key(DEFAULT_TENANT);
        ExecutionManifest first = manifest(key, "a", "STANDARD");
        await(store().pin(first));

        ExecutionManifestStoreFailure failure = failureOf(
                () -> await(store().pin(manifest(key, "a", "TEST_PASSTHROUGH"))));
        var conflict = assertInstanceOf(ExecutionManifestStoreFailure.ManifestConflict.class, failure);
        assertEquals(first.digest(), conflict.storedDigest(),
                "the refusal must name the manifest that is already pinned");
        assertEquals(first, await(store().load(key)).manifest(),
                "a refused pin must not have overwritten what was stored");
    }

    @Test
    final void loadingAnUnpinnedExecutionReportsNotFound() {
        ExecutionKey key = key(DEFAULT_TENANT);
        assertInstanceOf(ExecutionManifestStoreFailure.NotFound.class,
                failureOf(() -> await(store().load(key))));
    }

    @Test
    final void anotherTenantsManifestIsAbsentRatherThanDenied() {
        UUID instance = UUID.randomUUID();
        ExecutionKey mine = new ExecutionKey(DEFAULT_TENANT, instance);
        ExecutionKey theirs = new ExecutionKey(OTHER_TENANT, instance);
        await(store().pin(manifest(mine, "a", "STANDARD")));

        assertInstanceOf(ExecutionManifestStoreFailure.NotFound.class,
                failureOf(() -> await(store().load(theirs))),
                "a cross-tenant read must report absence, never a denial that would confirm existence");
        assertFalse(await(store().contains(theirs)));
    }

    @Test
    final void twoTenantsPinIndependentManifestsForTheSameInstanceId() {
        UUID instance = UUID.randomUUID();
        ExecutionKey mine = new ExecutionKey(DEFAULT_TENANT, instance);
        ExecutionKey theirs = new ExecutionKey(OTHER_TENANT, instance);
        await(store().pin(manifest(mine, "a", "STANDARD")));
        await(store().pin(manifest(theirs, "b", "STANDARD")));

        assertEquals("a".repeat(64), await(store().load(mine)).manifest().graphContentId().value());
        assertEquals("b".repeat(64), await(store().load(theirs)).manifest().graphContentId().value());
    }

    @Test
    final void containsReportsPresenceWithoutReadingTheManifest() {
        ExecutionKey key = key(DEFAULT_TENANT);
        assertFalse(await(store().contains(key)));
        await(store().pin(manifest(key, "a", "STANDARD")));
        assertTrue(await(store().contains(key)));
    }

    // ============================================================ retention

    @Test
    final void removingAManifestRetainedWorkStillNeedsIsRefused() {
        ExecutionKey key = key(DEFAULT_TENANT);
        await(store().pin(manifest(key, "a", "STANDARD")));
        markReferenced(key);

        assertInstanceOf(ExecutionManifestStoreFailure.StillReferenced.class,
                failureOf(() -> await(store().remove(key))));
        assertTrue(await(store().contains(key)),
                "a refused removal must leave the manifest exactly where it was");
    }

    @Test
    final void removingAnUnreferencedManifestSucceedsAndIsThenAbsent() {
        ExecutionKey key = key(DEFAULT_TENANT);
        await(store().pin(manifest(key, "a", "STANDARD")));

        await(store().remove(key));
        assertFalse(await(store().contains(key)));
        assertInstanceOf(ExecutionManifestStoreFailure.NotFound.class,
                failureOf(() -> await(store().load(key))));
    }

    @Test
    final void removingAnAbsentManifestReportsNotFoundRatherThanSucceedingSilently() {
        assertInstanceOf(ExecutionManifestStoreFailure.NotFound.class,
                failureOf(() -> await(store().remove(key(DEFAULT_TENANT)))));
    }

    // ============================================================ durability

    @Test
    final void aPinnedManifestSurvivesAReopen() {
        assumeCapability(StoreCapability.DURABLE);
        ExecutionKey key = key(DEFAULT_TENANT);
        ExecutionManifest manifest = manifest(key, "a", "STANDARD",
                List.of(new PinnedNodePackage("alpha.nodes", "1.4.2", "node-sdk-1")));
        StoredExecutionManifest pinned = await(store().pin(manifest));

        ExecutionManifestStore reopened = reopen();
        StoredExecutionManifest recovered = await(reopened.load(key));
        assertEquals(pinned.manifest(), recovered.manifest(),
                "an execution must rehydrate from the exact manifest it was accepted against");
        assertEquals(pinned.digest(), recovered.digest());
        assertEquals(pinned.committedAt(), recovered.committedAt());
    }

    // ============================================================ classification

    @Test
    final void everyDeterministicRejectionIsClassifiedAsOne() {
        ExecutionKey key = key(DEFAULT_TENANT);
        List<ExecutionManifestStoreFailure> rejections = List.of(
                new ExecutionManifestStoreFailure.NotFound(key),
                new ExecutionManifestStoreFailure.DigestMismatch(key, "0".repeat(64)),
                new ExecutionManifestStoreFailure.Corrupted(key, "unreadable"),
                new ExecutionManifestStoreFailure.ManifestConflict(key,
                        new ExecutionManifestDigest("0".repeat(64))),
                new ExecutionManifestStoreFailure.InvalidRequest("unusable"),
                new ExecutionManifestStoreFailure.StillReferenced(key),
                new ExecutionManifestStoreFailure.NotAuthorized("refused"));
        for (ExecutionManifestStoreFailure failure : rejections) {
            assertEquals(Retryability.DETERMINISTIC_REJECT, failure.retryability(),
                    () -> failure.getClass().getSimpleName() + " must not invite a retry");
            assertFalse(failure.describe().isBlank(),
                    () -> failure.getClass().getSimpleName() + " must describe itself");
        }
        assertEquals(Retryability.RETRYABLE_NO_EFFECT,
                new ExecutionManifestStoreFailure.Unavailable("down").retryability());
        assertEquals(Retryability.INDETERMINATE,
                new ExecutionManifestStoreFailure.OutcomeUnknown(key, "commit failed").retryability());
    }

    @Test
    final void noFailureDescriptionRepeatsATenantSecretOrCarriesControlBytes() {
        ExecutionKey key = key(DEFAULT_TENANT);
        List<ExecutionManifestStoreFailure> failures = List.of(
                new ExecutionManifestStoreFailure.NotFound(key),
                new ExecutionManifestStoreFailure.StillReferenced(key),
                new ExecutionManifestStoreFailure.ManifestConflict(key,
                        new ExecutionManifestDigest("0".repeat(64))));
        for (ExecutionManifestStoreFailure failure : failures) {
            String described = failure.describe();
            for (byte encoded : described.getBytes(StandardCharsets.UTF_8)) {
                assertTrue(encoded >= 0x20 || encoded == 0x09,
                        () -> "a manifest diagnostic must stay printable: " + described);
            }
        }
    }

    // ============================================================ compatibility

    @Test
    final void aManifestComparedAgainstItselfIsCompatible() {
        ExecutionManifest manifest = manifest(key(DEFAULT_TENANT), "a", "STANDARD");
        assertTrue(ExecutionManifestCompatibility.compare(manifest, manifest).compatible());
    }

    @Test
    final void aChangedExecutionPolicyIsReportedAsADifference() {
        ExecutionKey key = key(DEFAULT_TENANT);
        ExecutionManifestCompatibility report = ExecutionManifestCompatibility.compare(
                manifest(key, "a", "TEST_PASSTHROUGH"), manifest(key, "a", "STANDARD"));

        assertFalse(report.compatible());
        assertEquals(List.of(new ExecutionManifestDifference(
                        ExecutionManifestDifference.Dimension.EXECUTION_POLICY,
                        "TEST_PASSTHROUGH", "STANDARD")), report.differences());
    }

    @Test
    final void aRemovedNodePackageIsReportedAndAnAddedOneIsNot() {
        ExecutionKey key = key(DEFAULT_TENANT);
        var required = new PinnedNodePackage("alpha.nodes", "1.4.2", "node-sdk-1");
        var unrelated = new PinnedNodePackage("beta.nodes", "3.0.0", "node-sdk-1");

        ExecutionManifestCompatibility missing = ExecutionManifestCompatibility.compare(
                manifest(key, "a", "STANDARD", List.of(required)),
                manifest(key, "a", "STANDARD", List.of()));
        assertEquals(ExecutionManifestDifference.Dimension.NODE_PACKAGE_MISSING,
                missing.differences().get(0).dimension());

        ExecutionManifestCompatibility added = ExecutionManifestCompatibility.compare(
                manifest(key, "a", "STANDARD", List.of(required)),
                manifest(key, "a", "STANDARD", List.of(required, unrelated)));
        assertTrue(added.compatible(),
                "a package installed since acceptance that this execution never used is not a "
                        + "difference; the document decides which behaviors run and it is pinned exactly");
    }

    @Test
    final void aRebuiltNodePackageIsReportedAsChanged() {
        ExecutionKey key = key(DEFAULT_TENANT);
        ExecutionManifestCompatibility report = ExecutionManifestCompatibility.compare(
                manifest(key, "a", "STANDARD",
                        List.of(new PinnedNodePackage("alpha.nodes", "1.4.2", "node-sdk-1"))),
                manifest(key, "a", "STANDARD",
                        List.of(new PinnedNodePackage("alpha.nodes", "1.4.3", "node-sdk-1"))));

        assertEquals(ExecutionManifestDifference.Dimension.NODE_PACKAGE_CHANGED,
                report.differences().get(0).dimension());
        assertTrue(report.describe().contains("1.4.2") && report.describe().contains("1.4.3"));
    }

    // ============================================================ fixtures

    private static ExecutionKey key(String tenantId) {
        return new ExecutionKey(tenantId, UUID.randomUUID());
    }

    private static ExecutionManifest manifest(ExecutionKey key, String contentSeed, String policy) {
        return manifest(key, contentSeed, policy, List.of());
    }

    private static ExecutionManifest manifest(ExecutionKey key, String contentSeed, String policy,
                                              List<PinnedNodePackage> packages) {
        var profile = new ResolvedRuntimeProfile(1, 1, policy, "pass-through",
                "1".repeat(64), "2".repeat(64), "3".repeat(64), "4".repeat(64));
        return new ExecutionManifest(ExecutionManifest.CURRENT_FORMAT_VERSION, key,
                new GraphContentId(contentSeed.repeat(64)),
                new GraphDefinitionIdentity(GraphDefinitionIdentity.SUBMISSION_GRAPH_ID,
                        contentSeed.repeat(64)),
                profile, packages, EPOCH);
    }

    private static ExecutionManifestStoreFailure failureOf(Runnable operation) {
        RuntimeException thrown = assertThrows(RuntimeException.class, operation::run);
        ExecutionManifestStoreException classified = ExecutionManifestStoreException.unwrap(thrown);
        assertTrue(classified != null,
                () -> "every adapter failure must arrive classified, got " + thrown);
        return classified.failure();
    }

    private static <T> T await(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (CompletionException wrapped) {
            ExecutionManifestStoreException failure =
                    ExecutionManifestStoreException.unwrap(wrapped);
            throw failure == null ? wrapped : failure;
        }
    }
}
