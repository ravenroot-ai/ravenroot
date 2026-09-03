package ai.ravenroot.testkit.persistence;

import ai.ravenroot.api.persistence.CanonicalGraphMl;
import ai.ravenroot.api.persistence.GraphContentId;
import ai.ravenroot.api.persistence.GraphDefinitionIdentity;
import ai.ravenroot.api.persistence.GraphDefinitionKey;
import ai.ravenroot.api.persistence.GraphDefinitionReferences;
import ai.ravenroot.api.persistence.GraphDefinitionStore;
import ai.ravenroot.api.persistence.GraphDefinitionStoreException;
import ai.ravenroot.api.persistence.GraphDefinitionStoreFailure;
import ai.ravenroot.api.persistence.Retryability;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.api.persistence.StoredGraphDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reusable conformance suite every {@link GraphDefinitionStore} adapter must extend, following
 * the {@code ExecutionStoreContract} precedent in this module.
 *
 * <p>Every assertion is derived from the port's own contract text rather than from reading any one
 * adapter, so an adapter that reproduces a bug already present in the reference adapter is still
 * caught. Test methods are {@code final}: a subclass supplies a factory, never a weaker assertion.</p>
 *
 * <h2>Written so a future shared-store adapter subclasses it unchanged</h2>
 * <p>Two adapters run this suite today, one in-memory and one on SQLite. A multi-host shared store
 * does not exist in this repository yet, and the suite is deliberately written so that adding one
 * requires implementing {@link #createStore(String, Clock, GraphDefinitionReferences)} and nothing
 * else. Concretely: no assertion reaches for a file, a connection or a driver; every durability
 * assertion goes through {@link #reopen()} rather than through anything filesystem-shaped; and the
 * capability gate is the only mechanism by which an assertion may be skipped.</p>
 *
 * <h2>Capability-gated assertions</h2>
 * <p>Enforcement is asymmetric: absence of a declared {@link StoreCapability} skips its assertion
 * through {@link Assumptions#assumeTrue}, which reports as a visible skip and never as a silent
 * pass; presence never skips it. {@link StoreCapability#DURABLE} is structurally impossible for an
 * in-memory adapter to honour, so the reopen assertion does not run against one — and does run, and
 * must pass, against the SQLite adapter.</p>
 *
 * <h2>Reopen</h2>
 * <p>{@link #createStore(String, Clock, GraphDefinitionReferences)} is invoked with the same
 * {@code storeId}, the same {@link Clock} and the same reference oracle for the first open and for
 * every reopen. A non-durable adapter may ignore {@code storeId} and return a fresh empty instance,
 * because the assertions that would observe the difference are gated on
 * {@link StoreCapability#DURABLE}. A durable adapter must use {@code storeId} as its backing
 * identity, so a reopen reconnects to the same bytes exactly as a process restart would.</p>
 *
 * <h2>The reference oracle</h2>
 * <p>Retention is the one behaviour that cannot be exercised through this port alone: whether a
 * definition is still reachable is a fact about work the definition store does not own. The suite
 * therefore injects a controllable {@link GraphDefinitionReferences} and drives it with
 * {@link #markReferenced(GraphDefinitionKey)}. An adapter that also computes reachability from
 * durable state it is co-located with must treat the two as a conjunction — removable only when
 * every source says unreachable — so these assertions hold for it unchanged.</p>
 *
 * <h2>Adapter-conditional failures</h2>
 * <p>{@link GraphDefinitionStoreFailure.Corrupted}, {@link GraphDefinitionStoreFailure.NotAuthorized},
 * {@link GraphDefinitionStoreFailure.Unavailable} and
 * {@link GraphDefinitionStoreFailure.OutcomeUnknown} cannot be reached through this port's own
 * operations by a conforming in-memory adapter: nothing a caller can ask for produces them. Their
 * classification is asserted here by constructing the failure records directly, which is what the
 * execution-store suite does for the same reason, and their live behaviour is asserted by the
 * adapter that can actually be driven into them.</p>
 */
public abstract class GraphDefinitionStoreContract {

    /** Arbitrary, fixed epoch so every test starts from a readable, reproducible instant. */
    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");
    private static final String DEFAULT_TENANT = "acme";
    private static final String OTHER_TENANT = "globex";

    private static final String GRAPHML_A = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns"><graph id="a" edgedefault="directed"/></graphml>
            """;
    private static final String GRAPHML_B = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns"><graph id="b" edgedefault="directed"/></graphml>
            """;

    private final Set<GraphDefinitionKey> referenced = ConcurrentHashMap.newKeySet();

    private String storeId;
    private MutableClock mutableClock;
    private GraphDefinitionStore store;

    /**
     * Creates, or reopens, an adapter instance backed by {@code clock} and consulting
     * {@code references} when deciding what retention may remove.
     *
     * <p>{@code storeId} is the backing identity for adapters that persist across the call; see the
     * class documentation for the reopen contract.</p>
     *
     * @param storeId stable backing identity, so a reopen reconnects to the same stored definitions.
     * @param clock clock the adapter must treat as its time authority.
     * @param references oracle the adapter must consult before removing a definition.
     * @return an adapter instance under test.
     */
    protected abstract GraphDefinitionStore createStore(String storeId, Clock clock,
                                                        GraphDefinitionReferences references);

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
    protected final GraphDefinitionStore store() {
        if (store == null) {
            storeId = "definitions-" + UUID.randomUUID();
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
    protected final GraphDefinitionStore reopen() {
        store().close();
        store = createStore(storeId, mutableClock, referenced::contains);
        return store;
    }

    /**
     * Declares that retained work reaches this definition, so retention must leave it in place.
     *
     * @param key tenant-scoped address to mark as still referenced.
     */
    protected final void markReferenced(GraphDefinitionKey key) {
        referenced.add(key);
    }

    private void assumeCapability(StoreCapability capability) {
        Assumptions.assumeTrue(store().supports(capability), () -> capability
                + " not declared by this adapter; assertion skipped under the asymmetric enforcement "
                + "rule (absence skips, presence never does)");
    }

    // ============================================================ self-description

    @Test
    final void theAdapterPublishesADefinitionBoundLargeEnoughForAValidatedDocument() {
        assertTrue(store().maxDefinitionBytes() >= 10 * 1024 * 1024,
                "a store whose bound sat below the ceiling GraphML ingest already enforces would "
                        + "accept a document at the edge and then fail to persist it, turning a "
                        + "validated submission into an acceptance that cannot complete");
    }

    // ============================================================ storing and reading back

    @Test
    final void aStoredDefinitionIsReturnedByteForByte() {
        byte[] document = GRAPHML_A.getBytes(StandardCharsets.UTF_8);
        StoredGraphDefinition stored = await(store().put(DEFAULT_TENANT, identity("orders", "1"),
                CanonicalGraphMl.of(document)));

        assertEquals(GraphContentId.of(document), stored.key().contentId());
        assertArrayEquals(document, stored.canonical().bytes());

        StoredGraphDefinition read = await(store().load(stored.key()));
        assertArrayEquals(document, read.canonical().bytes(),
                "a recovering runtime needs the document the execution was accepted against, not a "
                        + "document that merely means the same thing");
        assertEquals(CanonicalGraphMl.CURRENT_FORMAT_VERSION, read.canonical().formatVersion());
        assertEquals(identity("orders", "1"), read.identity());
    }

    @Test
    final void aDefinitionSurvivesAReopenAndIsStillReturnedByteForByte() {
        assumeCapability(StoreCapability.DURABLE);
        byte[] document = GRAPHML_A.getBytes(StandardCharsets.UTF_8);
        GraphDefinitionKey key = await(store().put(DEFAULT_TENANT, identity("orders", "1"),
                CanonicalGraphMl.of(document))).key();

        reopen();

        StoredGraphDefinition read = await(store().load(key));
        assertArrayEquals(document, read.canonical().bytes(), "the durable store is the authority "
                + "after a restart; a caller must never be asked to submit the document again");
        assertEquals(key.contentId(), read.canonical().contentId(),
                "and the bytes returned must verify against the address they were filed under");
    }

    @Test
    final void aDefinitionIsResolvableThroughItsImmutableLogicalVersion() {
        byte[] document = GRAPHML_A.getBytes(StandardCharsets.UTF_8);
        StoredGraphDefinition stored = await(store().put(DEFAULT_TENANT, identity("orders", "7"),
                CanonicalGraphMl.of(document)));

        StoredGraphDefinition resolved = await(store().resolve(DEFAULT_TENANT, identity("orders", "7")));
        assertEquals(stored.key(), resolved.key());
        assertArrayEquals(document, resolved.canonical().bytes());
    }

    @Test
    final void containsReportsPresenceWithoutTransferringTheDocument() {
        GraphDefinitionKey key = await(store().put(DEFAULT_TENANT, identity("orders", "1"),
                CanonicalGraphMl.of(GRAPHML_A.getBytes(StandardCharsets.UTF_8)))).key();

        assertTrue(await(store().contains(key)));
        assertFalse(await(store().contains(new GraphDefinitionKey(DEFAULT_TENANT,
                GraphContentId.of(GRAPHML_B.getBytes(StandardCharsets.UTF_8))))));
    }

    // ============================================================ deduplication

    @Test
    final void repeatingTheIdenticalPutConvergesOnOneStoredDefinition() {
        CanonicalGraphMl canonical = CanonicalGraphMl.of(GRAPHML_A.getBytes(StandardCharsets.UTF_8));
        StoredGraphDefinition first = await(store().put(DEFAULT_TENANT, identity("orders", "1"), canonical));
        StoredGraphDefinition second = await(store().put(DEFAULT_TENANT, identity("orders", "1"), canonical));

        assertEquals(first.key(), second.key());
        assertEquals(first.storedAt(), second.storedAt(),
                "a repeated write must not restamp the definition: the stored instant records when "
                        + "the content first became durable, and moving it would let retention "
                        + "reasoning drift with every retry");
        assertArrayEquals(canonical.bytes(), await(store().load(first.key())).canonical().bytes());
    }

    @Test
    final void identicalContentUnderTwoLogicalVersionsSharesOneStoredDefinition() {
        CanonicalGraphMl canonical = CanonicalGraphMl.of(GRAPHML_A.getBytes(StandardCharsets.UTF_8));
        StoredGraphDefinition asOrders = await(store().put(DEFAULT_TENANT, identity("orders", "1"), canonical));
        StoredGraphDefinition asBilling = await(store().put(DEFAULT_TENANT, identity("billing", "1"), canonical));

        assertEquals(asOrders.key(), asBilling.key(), "copying a graph and publishing it under a new "
                + "name is legal and must not store the document twice");
        assertEquals(identity("orders", "1"), await(store().resolve(DEFAULT_TENANT, identity("orders", "1"))).identity());
        assertEquals(identity("billing", "1"), await(store().resolve(DEFAULT_TENANT, identity("billing", "1"))).identity());
    }

    @Test
    final void distinctContentNeverAliases() {
        StoredGraphDefinition a = await(store().put(DEFAULT_TENANT, identity("orders", "1"),
                CanonicalGraphMl.of(GRAPHML_A.getBytes(StandardCharsets.UTF_8))));
        StoredGraphDefinition b = await(store().put(DEFAULT_TENANT, identity("orders", "2"),
                CanonicalGraphMl.of(GRAPHML_B.getBytes(StandardCharsets.UTF_8))));

        assertNotEquals(a.key(), b.key());
        assertArrayEquals(GRAPHML_A.getBytes(StandardCharsets.UTF_8), await(store().load(a.key())).canonical().bytes());
        assertArrayEquals(GRAPHML_B.getBytes(StandardCharsets.UTF_8), await(store().load(b.key())).canonical().bytes());
    }

    @Test
    final void twoTenantsSubmittingIdenticalContentHoldTwoIndependentDefinitions() {
        CanonicalGraphMl canonical = CanonicalGraphMl.of(GRAPHML_A.getBytes(StandardCharsets.UTF_8));
        GraphDefinitionKey mine = await(store().put(DEFAULT_TENANT, identity("orders", "1"), canonical)).key();
        GraphDefinitionKey theirs = await(store().put(OTHER_TENANT, identity("orders", "1"), canonical)).key();

        assertEquals(mine.contentId(), theirs.contentId(), "the address is a function of the bytes");
        assertNotEquals(mine, theirs, "but the definitions are distinct, because sharing one stored "
                + "copy would give one tenant's retention decision authority over another tenant's "
                + "recoverability");

        await(store().purgeUnreferencedDefinitions(DEFAULT_TENANT));
        assertTrue(await(store().contains(theirs)),
                "and purging one tenant must not remove the other tenant's definition");
    }

    @Test
    final void rebindingAnImmutableVersionToDifferentContentIsRefused() {
        await(store().put(DEFAULT_TENANT, identity("orders", "1"),
                CanonicalGraphMl.of(GRAPHML_A.getBytes(StandardCharsets.UTF_8))));

        var failure = failureOf(() -> await(store().put(DEFAULT_TENANT, identity("orders", "1"),
                CanonicalGraphMl.of(GRAPHML_B.getBytes(StandardCharsets.UTF_8)))));

        var conflict = assertInstanceOf(GraphDefinitionStoreFailure.IdentityConflict.class, failure,
                "an execution pinned to a version must not silently change what it replays");
        assertEquals(GraphContentId.of(GRAPHML_A.getBytes(StandardCharsets.UTF_8)), conflict.boundContentId());
        assertEquals(GraphContentId.of(GRAPHML_B.getBytes(StandardCharsets.UTF_8)), conflict.requestedContentId());
        assertArrayEquals(GRAPHML_A.getBytes(StandardCharsets.UTF_8),
                await(store().resolve(DEFAULT_TENANT, identity("orders", "1"))).canonical().bytes(),
                "and the refused write must have changed nothing");
    }

    // ============================================================ failing closed

    @Test
    final void aMissingDefinitionFailsWithNotFound() {
        GraphDefinitionKey absent = new GraphDefinitionKey(DEFAULT_TENANT,
                GraphContentId.of(GRAPHML_A.getBytes(StandardCharsets.UTF_8)));

        var failure = failureOf(() -> await(store().load(absent)));
        assertInstanceOf(GraphDefinitionStoreFailure.NotFound.class, failure);
        assertEquals(Retryability.DETERMINISTIC_REJECT, failure.retryability());
    }

    @Test
    final void anUnboundLogicalVersionFailsWithNotFound() {
        var failure = failureOf(() -> await(store().resolve(DEFAULT_TENANT, identity("orders", "9"))));
        assertInstanceOf(GraphDefinitionStoreFailure.NotFound.class, failure);
    }

    @Test
    final void anotherTenantsDefinitionIsNotFoundRatherThanDenied() {
        CanonicalGraphMl canonical = CanonicalGraphMl.of(GRAPHML_A.getBytes(StandardCharsets.UTF_8));
        await(store().put(OTHER_TENANT, identity("orders", "1"), canonical));

        GraphDefinitionKey asMe = new GraphDefinitionKey(DEFAULT_TENANT, canonical.contentId());
        var failure = failureOf(() -> await(store().load(asMe)));
        assertInstanceOf(GraphDefinitionStoreFailure.NotFound.class, failure,
                "a denial would confirm that another tenant holds a document whose bytes this "
                        + "caller already possesses, which is a sharper oracle than a plain absence");
        assertFalse(await(store().contains(asMe)));
        assertInstanceOf(GraphDefinitionStoreFailure.NotFound.class,
                failureOf(() -> await(store().resolve(DEFAULT_TENANT, identity("orders", "1")))));
    }

    @Test
    final void anOversizedDefinitionIsRefusedAndNothingIsWritten() {
        int limit = store().maxDefinitionBytes();
        byte[] oversized = new byte[limit + 1];
        // Distinct bytes, so the address below is not the all-zero document's address by accident.
        oversized[0] = '<';
        oversized[limit] = '>';
        CanonicalGraphMl canonical = CanonicalGraphMl.of(oversized);

        var failure = failureOf(() -> await(store().put(DEFAULT_TENANT, identity("orders", "1"), canonical)));
        var tooLarge = assertInstanceOf(GraphDefinitionStoreFailure.DefinitionTooLarge.class, failure);
        assertEquals(oversized.length, tooLarge.actualBytes());
        assertEquals(limit, tooLarge.limitBytes());
        assertEquals(Retryability.DETERMINISTIC_REJECT, failure.retryability());
        assertFalse(await(store().contains(new GraphDefinitionKey(DEFAULT_TENANT, canonical.contentId()))),
                "the bound is checked before anything is written, so a refused put leaves no row");
    }

    @Test
    final void absentAndBlankArgumentsAreClassifiedRatherThanLeakingAnAdapterException() {
        CanonicalGraphMl canonical = CanonicalGraphMl.of(GRAPHML_A.getBytes(StandardCharsets.UTF_8));
        assertInstanceOf(GraphDefinitionStoreFailure.InvalidRequest.class,
                failureOf(() -> await(store().put("  ", identity("orders", "1"), canonical))));
        assertInstanceOf(GraphDefinitionStoreFailure.InvalidRequest.class,
                failureOf(() -> await(store().put(DEFAULT_TENANT, null, canonical))));
        assertInstanceOf(GraphDefinitionStoreFailure.InvalidRequest.class,
                failureOf(() -> await(store().put(DEFAULT_TENANT, identity("orders", "1"), null))));
        assertInstanceOf(GraphDefinitionStoreFailure.InvalidRequest.class,
                failureOf(() -> await(store().load(null))));
        assertInstanceOf(GraphDefinitionStoreFailure.InvalidRequest.class,
                failureOf(() -> await(store().purgeUnreferencedDefinitions(""))));
        assertInstanceOf(GraphDefinitionStoreFailure.InvalidRequest.class,
                failureOf(() -> await(store().remove(null))));
        assertInstanceOf(GraphDefinitionStoreFailure.InvalidRequest.class,
                failureOf(() -> await(store().resolve(DEFAULT_TENANT, null))));
    }

    // ============================================================ retention

    @Test
    final void retentionRemovesADefinitionNoRetainedWorkReaches() {
        GraphDefinitionKey key = await(store().put(DEFAULT_TENANT, identity("orders", "1"),
                CanonicalGraphMl.of(GRAPHML_A.getBytes(StandardCharsets.UTF_8)))).key();

        assertEquals(1L, await(store().purgeUnreferencedDefinitions(DEFAULT_TENANT)));
        assertFalse(await(store().contains(key)));
        assertInstanceOf(GraphDefinitionStoreFailure.NotFound.class,
                failureOf(() -> await(store().resolve(DEFAULT_TENANT, identity("orders", "1")))),
                "removing a definition must remove the bindings that named it, or a resolve would "
                        + "point at nothing and report a corrupt store instead of an absent one");
    }

    @Test
    final void retentionCannotRemoveADefinitionRetainedWorkStillReaches() {
        GraphDefinitionKey key = await(store().put(DEFAULT_TENANT, identity("orders", "1"),
                CanonicalGraphMl.of(GRAPHML_A.getBytes(StandardCharsets.UTF_8)))).key();
        markReferenced(key);

        assertEquals(0L, await(store().purgeUnreferencedDefinitions(DEFAULT_TENANT)));
        assertTrue(await(store().contains(key)));
        assertArrayEquals(GRAPHML_A.getBytes(StandardCharsets.UTF_8),
                await(store().load(key)).canonical().bytes(),
                "and the definition must still be readable, not merely present");
    }

    @Test
    final void retentionRemovesOnlyTheUnreferencedDefinitions() {
        GraphDefinitionKey referencedKey = await(store().put(DEFAULT_TENANT, identity("orders", "1"),
                CanonicalGraphMl.of(GRAPHML_A.getBytes(StandardCharsets.UTF_8)))).key();
        GraphDefinitionKey unreferencedKey = await(store().put(DEFAULT_TENANT, identity("orders", "2"),
                CanonicalGraphMl.of(GRAPHML_B.getBytes(StandardCharsets.UTF_8)))).key();
        markReferenced(referencedKey);

        assertEquals(1L, await(store().purgeUnreferencedDefinitions(DEFAULT_TENANT)));
        assertTrue(await(store().contains(referencedKey)));
        assertFalse(await(store().contains(unreferencedKey)));
    }

    @Test
    final void retentionSurvivesAReopen() {
        assumeCapability(StoreCapability.DURABLE);
        GraphDefinitionKey kept = await(store().put(DEFAULT_TENANT, identity("orders", "1"),
                CanonicalGraphMl.of(GRAPHML_A.getBytes(StandardCharsets.UTF_8)))).key();
        GraphDefinitionKey removed = await(store().put(DEFAULT_TENANT, identity("orders", "2"),
                CanonicalGraphMl.of(GRAPHML_B.getBytes(StandardCharsets.UTF_8)))).key();
        markReferenced(kept);
        assertEquals(1L, await(store().purgeUnreferencedDefinitions(DEFAULT_TENANT)));

        reopen();

        assertTrue(await(store().contains(kept)));
        assertFalse(await(store().contains(removed)));
    }

    @Test
    final void aTargetedRemovalIsRefusedWhileRetainedWorkStillReachesTheDefinition() {
        GraphDefinitionKey key = await(store().put(DEFAULT_TENANT, identity("orders", "1"),
                CanonicalGraphMl.of(GRAPHML_A.getBytes(StandardCharsets.UTF_8)))).key();
        markReferenced(key);

        var failure = failureOf(() -> await(store().remove(key)));
        assertInstanceOf(GraphDefinitionStoreFailure.StillReferenced.class, failure,
                "a caller that asked to delete and was answered with silence could not tell a "
                        + "refusal from a success");
        assertEquals(Retryability.DETERMINISTIC_REJECT, failure.retryability());
        assertArrayEquals(GRAPHML_A.getBytes(StandardCharsets.UTF_8),
                await(store().load(key)).canonical().bytes());
    }

    @Test
    final void aTargetedRemovalOfAnUnreferencedDefinitionAlsoRemovesItsBindings() {
        GraphDefinitionKey key = await(store().put(DEFAULT_TENANT, identity("orders", "1"),
                CanonicalGraphMl.of(GRAPHML_A.getBytes(StandardCharsets.UTF_8)))).key();

        await(store().remove(key));

        assertFalse(await(store().contains(key)));
        assertInstanceOf(GraphDefinitionStoreFailure.NotFound.class,
                failureOf(() -> await(store().resolve(DEFAULT_TENANT, identity("orders", "1")))));
        assertInstanceOf(GraphDefinitionStoreFailure.NotFound.class,
                failureOf(() -> await(store().remove(key))),
                "removing what is already gone is an absence, not a success");
    }

    @Test
    final void aTargetedRemovalCannotReachAnotherTenantsDefinition() {
        CanonicalGraphMl canonical = CanonicalGraphMl.of(GRAPHML_A.getBytes(StandardCharsets.UTF_8));
        GraphDefinitionKey theirs = await(store().put(OTHER_TENANT, identity("orders", "1"), canonical)).key();

        assertInstanceOf(GraphDefinitionStoreFailure.NotFound.class,
                failureOf(() -> await(store().remove(new GraphDefinitionKey(DEFAULT_TENANT, canonical.contentId())))));
        assertTrue(await(store().contains(theirs)));
    }

    // ============================================================ classification of every member

    @Test
    final void everyFailureMemberCarriesARetryabilityAndAContentSafeDiagnosis() {
        GraphDefinitionKey key = new GraphDefinitionKey(DEFAULT_TENANT,
                GraphContentId.of(GRAPHML_A.getBytes(StandardCharsets.UTF_8)));
        GraphDefinitionIdentity version = identity("orders", "1");
        GraphContentId other = GraphContentId.of(GRAPHML_B.getBytes(StandardCharsets.UTF_8));

        assertClassified(new GraphDefinitionStoreFailure.NotFound(key), Retryability.DETERMINISTIC_REJECT);
        assertClassified(new GraphDefinitionStoreFailure.DigestMismatch(key, other.value()),
                Retryability.DETERMINISTIC_REJECT);
        assertClassified(new GraphDefinitionStoreFailure.Corrupted(key, "truncated row"),
                Retryability.DETERMINISTIC_REJECT);
        assertClassified(new GraphDefinitionStoreFailure.DefinitionTooLarge(11, 10),
                Retryability.DETERMINISTIC_REJECT);
        assertClassified(new GraphDefinitionStoreFailure.InvalidRequest("tenantId cannot be blank"),
                Retryability.DETERMINISTIC_REJECT);
        assertClassified(new GraphDefinitionStoreFailure.IdentityConflict(DEFAULT_TENANT, version,
                key.contentId(), other), Retryability.DETERMINISTIC_REJECT);
        assertClassified(new GraphDefinitionStoreFailure.StillReferenced(key),
                Retryability.DETERMINISTIC_REJECT);
        assertClassified(new GraphDefinitionStoreFailure.NotAuthorized("no grant"),
                Retryability.DETERMINISTIC_REJECT);
        assertClassified(new GraphDefinitionStoreFailure.Unavailable("connection refused"),
                Retryability.RETRYABLE_NO_EFFECT);
        assertClassified(new GraphDefinitionStoreFailure.OutcomeUnknown(key, "commit failed"),
                Retryability.INDETERMINATE);
    }

    private static void assertClassified(GraphDefinitionStoreFailure failure, Retryability expected) {
        assertEquals(expected, failure.retryability(), () -> failure + " is misclassified");
        String description = failure.describe();
        assertNotNull(description);
        assertFalse(description.isBlank(), () -> failure + " must describe itself");
        assertFalse(description.contains("<graphml"), () -> failure
                + " leaked document content into a diagnostic that reaches logs");
        assertEquals(description, new GraphDefinitionStoreException(failure).getMessage(),
                "the carrier must not restate the diagnosis in its own words");
        assertEquals(expected, new GraphDefinitionStoreException(failure).retryability());
    }

    // ============================================================ helpers

    private static GraphDefinitionIdentity identity(String graphId, String versionId) {
        return new GraphDefinitionIdentity(graphId, versionId);
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    /** Also asserts, on every call, that adapters never leak a non-store exception. */
    private static GraphDefinitionStoreFailure failureOf(Runnable operation) {
        CompletionException thrown = assertThrows(CompletionException.class, operation::run);
        GraphDefinitionStoreException failure = GraphDefinitionStoreException.unwrap(thrown);
        assertNotNull(failure, "adapters must not leak non-store exceptions: " + thrown);
        return failure.failure();
    }
}
