package ai.ravenroot.persistence.postgresql;

import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionManifest;
import ai.ravenroot.api.persistence.ExecutionManifestReferences;
import ai.ravenroot.api.persistence.ExecutionManifestStoreException;
import ai.ravenroot.api.persistence.ExecutionManifestStoreFailure;
import ai.ravenroot.api.persistence.GraphContentId;
import ai.ravenroot.api.persistence.GraphDefinitionIdentity;
import ai.ravenroot.api.persistence.PinnedNodePackage;
import ai.ravenroot.api.persistence.ResolvedRuntimeProfile;
import ai.ravenroot.testkit.persistence.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A manifest read under churn reports absence or the manifest, and never damage.
 *
 * <p>{@code load} is a fold: it reads the manifest row and then the node packages that belong to it,
 * and recomputes the digest across both. Under {@code READ COMMITTED} each statement takes its own
 * snapshot whether or not a transaction is open, so a {@code remove} committing between them yields a
 * manifest paired with an empty package set — a digest matching neither — and the adapter reports
 * {@link ExecutionManifestStoreFailure.DigestMismatch}.</p>
 *
 * <p>That misreport is worse than a wrong answer. {@code DigestMismatch} names the store as damaged,
 * and an operator who sees it during ordinary contention learns to disregard the one signal that says
 * the manifest and its packages have stopped agreeing. The invariant here is therefore categorical
 * rather than statistical: under this churn every read is either the manifest or {@code NotFound}, and
 * a single damage report is a failure.</p>
 */
class PostgresExecutionManifestStoreConcurrencyTest {

    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");
    private static final ExecutionManifestReferences NONE = key -> false;

    @Test
    void aManifestReadWhileItIsBeingRemovedIsNeverReportedAsDamaged() throws Exception {
        String storeId = "manifest-churn-" + UUID.randomUUID();
        var key = new ExecutionKey("acme", UUID.randomUUID());

        try (var store = new PostgresExecutionManifestStore(
                PostgresTestDatabase.dataSourceFor(storeId), new MutableClock(EPOCH), NONE)) {

            var stop = new AtomicBoolean();
            var churn = CompletableFuture.runAsync(() -> {
                while (!stop.get()) {
                    // Packages are what make load a fold; without them the second statement returns
                    // nothing either way and the window this test exists for does not open.
                    store.pin(manifestWithPackages(key)).toCompletableFuture().join();
                    store.remove(key).toCompletableFuture().join();
                }
            });

            var damaged = new ArrayList<String>();
            int reads = 0;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                reads++;
                try {
                    store.load(key).toCompletableFuture().join();
                } catch (CompletionException wrapped) {
                    ExecutionManifestStoreException classified =
                            ExecutionManifestStoreException.unwrap(wrapped);
                    if (classified == null) {
                        throw wrapped;
                    }
                    ExecutionManifestStoreFailure failure = classified.failure();
                    if (!(failure instanceof ExecutionManifestStoreFailure.NotFound)) {
                        damaged.add(failure.toString());
                    }
                } catch (ExecutionManifestStoreException classified) {
                    if (!(classified.failure() instanceof ExecutionManifestStoreFailure.NotFound)) {
                        damaged.add(classified.failure().toString());
                    }
                }
            }
            stop.set(true);
            churn.get(1, TimeUnit.MINUTES);

            assertTrue(reads >= 50, "only " + reads + " reads completed in the window, which is too "
                    + "few for their agreement to mean anything");
            assertTrue(damaged.isEmpty(), damaged.size() + " of " + reads
                    + " reads reported the store as damaged while it was merely busy: "
                    + damaged.subList(0, Math.min(5, damaged.size())));
        }
    }

    private static ExecutionManifest manifestWithPackages(ExecutionKey key) {
        var profile = new ResolvedRuntimeProfile(1, 1, "STANDARD", "pass-through",
                "1".repeat(64), "2".repeat(64), "3".repeat(64), "4".repeat(64));
        List<PinnedNodePackage> packages = List.of(
                new PinnedNodePackage("package-a", "a".repeat(64)),
                new PinnedNodePackage("package-b", "b".repeat(64)));
        return new ExecutionManifest(ExecutionManifest.FORMAT_VERSION_1, key,
                new GraphContentId("c".repeat(64)),
                new GraphDefinitionIdentity(GraphDefinitionIdentity.SUBMISSION_GRAPH_ID, "c".repeat(64)),
                profile, packages, EPOCH);
    }
}
