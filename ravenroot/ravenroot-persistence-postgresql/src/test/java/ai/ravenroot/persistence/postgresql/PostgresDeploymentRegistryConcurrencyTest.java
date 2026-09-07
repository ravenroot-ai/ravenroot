package ai.ravenroot.persistence.postgresql;

import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry;
import ai.ravenroot.api.deployment.registry.GenerationExpectation;
import ai.ravenroot.api.deployment.registry.GraphVersion;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.testkit.persistence.MutableClock;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lost updates this registry exists to make impossible, driven from several hosts at once.
 *
 * <p>Every assertion here passes trivially against a registry whose read-then-write sequences were
 * copied from the single-host adapter and run one caller at a time, which is exactly why
 * {@link ai.ravenroot.testkit.persistence.DeploymentRegistryContract} cannot see them: it is
 * single-threaded, so a fence derived from a stale read is always derived from a current one. These
 * tests put several registries on one aggregate at the same instant, where a decision derived from a
 * value that has since moved produces an answer nothing in the conformance suite would notice.</p>
 *
 * <p>A "host" here is a separate {@link DeploymentRegistry} over its own {@code DataSource} addressing
 * one schema. It is not a separate JVM, and it does not need to be: nothing in this adapter shares
 * state between instances in process memory, so the exclusion these tests exercise is entirely the
 * server's. They do share one {@link MutableClock}, which models hosts whose wall clocks agree —
 * the case in which a lost update is <em>hardest</em> to produce and therefore the honest one to
 * assert against.</p>
 *
 * <p>Each outcome is stated as a count rather than as "nothing threw". "Exactly one host acquired and
 * the fence advanced exactly once" fails deterministically when two hosts derive the same successor,
 * whereas "no exception was thrown" would pass while silently discarding one of them.</p>
 */
class PostgresDeploymentRegistryConcurrencyTest {

    private static final Instant START = Instant.parse("2026-08-01T00:00:00Z");
    private static final int HOSTS = 8;

    /**
     * One idempotency key mints one deployment, however many hosts present it at once.
     *
     * <p>This is the one read-then-write in this adapter with no row to lock, because the row does not
     * exist yet. The single-host adapter looks the key up, finds nothing and mints an identity, which
     * is atomic there only because SQLite admits one writer to the whole database. Here every host
     * finds nothing, every host mints, and without the partial unique index on the create ledger the
     * tenant ends up with {@code HOSTS} deployments for one key — each caller told it succeeded, and
     * nothing afterwards able to say which one the key names.</p>
     *
     * <p>The proof is that all hosts receive the <em>same</em> minted aggregate and that the tenant
     * holds exactly one deployment. A weaker assertion that merely counted successes would be
     * satisfied by an adapter that minted eight.</p>
     */
    @Test
    void oneCreateKeyMintsOneDeploymentHoweverManyHostsPresentItAtOnce() throws Exception {
        String storeId = "deployment-create-race-" + UUID.randomUUID();
        var clock = new MutableClock(START);
        List<DeploymentRegistry> hosts = openHosts(storeId, clock, HOSTS);
        try {
            var content = new GraphVersion.Content(1, "graph".getBytes(StandardCharsets.UTF_8), "alice",
                    clock.instant());
            var create = new DeploymentRegistry.CreateCommand("acme", "one-key", "a".repeat(64));

            List<DeploymentRegistry.Record> minted = inParallel(hosts,
                    host -> host.create(content, create).toCompletableFuture().join());

            Set<DeploymentId> identities = new HashSet<>();
            minted.forEach(record -> identities.add(record.deploymentId()));
            assertEquals(1, identities.size(),
                    "one idempotency key named " + identities.size() + " different deployments: "
                            + identities);
            assertEquals(Set.of(minted.getFirst()), new HashSet<>(minted),
                    "every host must be handed the identical recorded outcome, not merely an equal id");

            DeploymentRegistry.Page all = hosts.getFirst().list("acme", null, 100)
                    .toCompletableFuture().join();
            assertEquals(1, all.items().size(),
                    "the tenant holds " + all.items().size() + " deployments for one create key");
        } finally {
            hosts.forEach(DeploymentRegistry::close);
        }
    }

    /**
     * Exactly one host takes the lease, and the fence advances exactly once.
     *
     * <p>The fence is the whole content of ownership: two holders carrying the same token is the
     * split-brain the fencing mechanism exists to make impossible. Without the aggregate's row lock,
     * every host reads fence {@code 0} and writes fence {@code 1}, and each is told it owns the
     * deployment. The count of accepted calls would still be one on a naive implementation — the last
     * writer wins the row — so the assertion that actually distinguishes the two is that the refused
     * hosts were <em>told</em> they were refused, and that the surviving revision counts one write
     * rather than eight.</p>
     */
    @Test
    void exactlyOneHostTakesTheLeaseAndTheFenceAdvancesOnce() throws Exception {
        String storeId = "deployment-acquire-race-" + UUID.randomUUID();
        var clock = new MutableClock(START);
        List<DeploymentRegistry> hosts = openHosts(storeId, clock, HOSTS);
        try {
            DeploymentRegistry.Record made = create(hosts.getFirst(), clock, "acme", "create", "graph");

            var accepted = new AtomicInteger();
            var refused = new AtomicInteger();
            var winners = java.util.Collections.synchronizedList(
                    new ArrayList<DeploymentRegistry.Record>());
            inParallelIgnoringFailures(hosts, (host, index) -> {
                try {
                    winners.add(host.acquire("owner-" + index, Duration.ofSeconds(30),
                            command(made, "acquire-" + index, 'b')).toCompletableFuture().join());
                    accepted.incrementAndGet();
                } catch (CompletionException competing) {
                    assertInstanceOf(DeploymentRegistry.FailureReason.Conflict.class,
                            reasonOf(competing), "a host that lost the race must be told so");
                    refused.incrementAndGet();
                }
            });

            assertEquals(1, accepted.get(), "exactly one host may own the deployment");
            assertEquals(HOSTS - 1, refused.get(), "every other host must be refused, not silently lost");

            DeploymentRegistry.Record stored = hosts.getFirst().get("acme", made.deploymentId())
                    .toCompletableFuture().join().orElseThrow();
            assertEquals(winners.getFirst(), stored, "the stored aggregate is the winner's");
            assertNotNull(stored.lease());
            assertEquals(1, stored.lease().fence(),
                    "the fence must count one takeover, not one per host that tried");
            assertEquals(made.revision() + 1, stored.revision(),
                    "exactly one write survived, so the revision advanced by exactly one");
            assertEquals(made.generation(), stored.generation(),
                    "acquiring ownership is not a lifecycle decision");
        } finally {
            hosts.forEach(DeploymentRegistry::close);
        }
    }

    /**
     * One command key retried from several hosts at once returns the recorded outcome to all of them.
     *
     * <p>This is the assertion that the aggregate's row lock, and nothing else, satisfies. The
     * conditional {@code UPDATE} that guards every write already refuses a second host that derived
     * its decision from a revision that has moved — so the two mutation counts above would come out
     * right even without the lock. They would come out right for the <em>wrong</em> reason here: a
     * caller retrying a command it never learned the outcome of would be told {@code Conflict}, which
     * says "someone else wrote" when what actually happened is that its own earlier attempt landed.
     * Retrying is what an at-least-once caller does after a timeout, so answering it with a conflict
     * turns an ordinary recovery into an operator investigating a lifecycle race that never
     * happened.</p>
     *
     * <p>Reading the ledger under the lock is what makes the answer right: the host that waits reads
     * the row the winner wrote, recognises its own key, and replays the outcome instead of deciding
     * again.</p>
     */
    @Test
    void oneCommandKeyRetriedFromSeveralHostsAtOnceReplaysToAllOfThem() throws Exception {
        String storeId = "deployment-replay-race-" + UUID.randomUUID();
        var clock = new MutableClock(START);
        List<DeploymentRegistry> hosts = openHosts(storeId, clock, HOSTS);
        try {
            DeploymentRegistry.Record made = create(hosts.getFirst(), clock, "acme", "create", "graph");
            DeploymentRegistry.Record held = hosts.getFirst().acquire("owner-one",
                    Duration.ofSeconds(30), command(made, "acquire", 'b')).toCompletableFuture().join();
            DeploymentRegistry.Lease lease = held.lease();

            // The same words from every host: one key, one digest, one revision. Each of them believes
            // it is the first delivery, which is exactly the state a caller is in after a timeout.
            var observation = new DeploymentRegistry.Observation(
                    DeploymentRegistry.ObservedKind.READY, 1L, 0, clock.instant());
            DeploymentRegistry.Command retried = command(held, "observe", 'c');

            List<DeploymentRegistry.Record> answers = inParallel(hosts,
                    host -> host.observe(observation, lease, retried).toCompletableFuture().join());

            assertEquals(1, new HashSet<>(answers).size(),
                    "every host must be handed the one recorded outcome, not a conflict and not a "
                            + "second observation: " + new HashSet<>(answers));
            DeploymentRegistry.Record stored = hosts.getFirst().get("acme", made.deploymentId())
                    .toCompletableFuture().join().orElseThrow();
            assertEquals(answers.getFirst(), stored);
            assertEquals(held.revision() + 1, stored.revision(),
                    "one key is one write, however many hosts delivered it");
        } finally {
            hosts.forEach(DeploymentRegistry::close);
        }
    }

    /**
     * Concurrent lifecycle commands advance the generation exactly once.
     *
     * <p>The generation is the half-open barrier consumers reason about: work admitted at {@code G}
     * completes while work admitted afterwards enters at {@code G + 1}. Two hosts both reading
     * generation {@code 0} and both writing {@code 1} would produce two different desired states
     * stamped with one generation, and a consumer could not tell which decision it is executing. Each
     * host here pins both the revision and the generation it decided against, which is what a
     * lifecycle mutation is required to do.</p>
     */
    @Test
    void concurrentLifecycleCommandsAdvanceTheGenerationExactlyOnce() throws Exception {
        String storeId = "deployment-command-race-" + UUID.randomUUID();
        var clock = new MutableClock(START);
        List<DeploymentRegistry> hosts = openHosts(storeId, clock, HOSTS);
        try {
            DeploymentRegistry.Record made = create(hosts.getFirst(), clock, "acme", "create", "graph");
            var running = new DeploymentRegistry.Desired(DeploymentRegistry.DesiredKind.RUNNING, 1L,
                    DeploymentRegistry.UpdateStrategy.STOP_FIRST, 0);

            var accepted = new AtomicInteger();
            var refused = new AtomicInteger();
            inParallelIgnoringFailures(hosts, (host, index) -> {
                var decided = new DeploymentRegistry.Command("acme", made.deploymentId(),
                        // A hex digit per host: the port requires a 64-character lowercase hex
                        // digest, so eight distinct decisions are spelled with 0-7 rather than a-h.
                        "start-" + index, Integer.toHexString(index).repeat(64),
                        RevisionExpectation.exactly(made.revision()),
                        GenerationExpectation.exactly(made.generation()));
                try {
                    host.command(running, decided).toCompletableFuture().join();
                    accepted.incrementAndGet();
                } catch (CompletionException competing) {
                    assertInstanceOf(DeploymentRegistry.FailureReason.Conflict.class,
                            reasonOf(competing), "a decision taken against a generation that has moved "
                                    + "must be refused, never applied over the newer one");
                    refused.incrementAndGet();
                }
            });

            assertEquals(1, accepted.get());
            assertEquals(HOSTS - 1, refused.get());

            DeploymentRegistry.Record stored = hosts.getFirst().get("acme", made.deploymentId())
                    .toCompletableFuture().join().orElseThrow();
            assertEquals(made.generation() + 1, stored.generation(),
                    "the generation must count one lifecycle decision, not one per host that tried");
            assertEquals(made.revision() + 1, stored.revision());
            assertEquals(stored.generation(), stored.desired().generation(),
                    "the surviving desired state is stamped with the generation it belongs to");
        } finally {
            hosts.forEach(DeploymentRegistry::close);
        }
    }

    /**
     * After a takeover only the fenced owner can advance the deployment; the superseded host is
     * refused on every route it has.
     *
     * <p>This is the acceptance criterion the conformance suite structurally cannot prove, because it
     * drives one caller at a time and the caller it drives always holds the current token. Here the
     * two hosts are genuinely separate and the old one is still running, still holding a lease value
     * it believes in, and still able to reach the database. Every route it has — renew, observe, fail,
     * release — has to answer {@code Fenced}, and the new owner has to be able to write.</p>
     */
    @Test
    void afterATakeoverOnlyTheFencedOwnerCanAdvanceAndTheSupersededHostIsRefused() {
        String storeId = "deployment-fencing-" + UUID.randomUUID();
        var clock = new MutableClock(START);
        List<DeploymentRegistry> hosts = openHosts(storeId, clock, 2);
        DeploymentRegistry first = hosts.get(0);
        DeploymentRegistry second = hosts.get(1);
        try {
            DeploymentRegistry.Record made = create(first, clock, "acme", "create", "graph");
            DeploymentRegistry.Record held = first.acquire("owner-one", Duration.ofSeconds(10),
                    command(made, "acquire", 'b')).toCompletableFuture().join();
            DeploymentRegistry.Lease superseded = held.lease();

            var observation = new DeploymentRegistry.Observation(
                    DeploymentRegistry.ObservedKind.READY, 1L, 0, clock.instant());
            DeploymentRegistry.Record observed = first.observe(observation, superseded,
                    command(held, "observe", 'c')).toCompletableFuture().join();

            // The first host stops renewing -- a crash, a partition, a long pause -- and the lease
            // lapses on the clock both hosts read.
            clock.advance(Duration.ofSeconds(11));
            DeploymentRegistry.Record takenOver = second.acquire("owner-two", Duration.ofSeconds(30),
                    command(observed, "takeover", 'd')).toCompletableFuture().join();
            DeploymentRegistry.Lease current = takenOver.lease();
            assertTrue(current.fence() > superseded.fence(), "a takeover advances the fence");

            // Every route the superseded host still has. It is not asleep: it holds a lease value, it
            // can reach the database, and it would happily keep working if the registry let it.
            DeploymentRegistry.Record before = second.get("acme", made.deploymentId())
                    .toCompletableFuture().join().orElseThrow();
            var stale = new DeploymentRegistry.Observation(DeploymentRegistry.ObservedKind.DEGRADED,
                    1L, 0, clock.instant());
            assertInstanceOf(DeploymentRegistry.FailureReason.Fenced.class,
                    reasonOf(() -> first.renew(superseded, Duration.ofSeconds(10),
                            command(before, "renew-old", 'e')).toCompletableFuture().join()));
            assertInstanceOf(DeploymentRegistry.FailureReason.Fenced.class,
                    reasonOf(() -> first.observe(stale, superseded, command(before, "observe-old", 'f'))
                            .toCompletableFuture().join()));
            assertInstanceOf(DeploymentRegistry.FailureReason.Fenced.class,
                    reasonOf(() -> first.fail(new DeploymentRegistry.Failure("STALE", "still working",
                            clock.instant()), superseded, command(before, "fail-old", 'a'))
                            .toCompletableFuture().join()));
            assertInstanceOf(DeploymentRegistry.FailureReason.Fenced.class,
                    reasonOf(() -> first.release(superseded, command(before, "release-old", 'b'))
                            .toCompletableFuture().join()));
            assertEquals(before, second.get("acme", made.deploymentId()).toCompletableFuture().join()
                    .orElseThrow(), "not one refused call may leave a trace on the aggregate");

            // And the current owner can still write, from the other host, with the current token.
            DeploymentRegistry.Record advanced = second.observe(new DeploymentRegistry.Observation(
                            DeploymentRegistry.ObservedKind.READY, 1L, 0, clock.instant()), current,
                    command(before, "observe-new", 'c')).toCompletableFuture().join();
            assertEquals(DeploymentRegistry.ObservedKind.READY, advanced.observed().state());
            assertEquals(current.fence(), advanced.lease().fence());

            // Releasing from the current owner leaves the deployment unleased without resetting the
            // fence, so a resurrected first host still cannot come back with its old token.
            DeploymentRegistry.Record released = second.release(current,
                    command(advanced, "release-new", 'd')).toCompletableFuture().join();
            assertNull(released.lease());
            assertInstanceOf(DeploymentRegistry.FailureReason.Fenced.class,
                    reasonOf(() -> first.renew(superseded, Duration.ofSeconds(10),
                            command(released, "renew-after-release", 'e')).toCompletableFuture().join()),
                    "a released lease must not let the counter fall back to a value a stale holder "
                            + "can still present");
        } finally {
            hosts.forEach(DeploymentRegistry::close);
        }
    }

    // ---------------------------------------------------------------- fixtures

    private static List<DeploymentRegistry> openHosts(String storeId, MutableClock clock, int count) {
        List<DeploymentRegistry> hosts = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            hosts.add(new PostgresDeploymentRegistry(PostgresTestDatabase.dataSourceFor(storeId), clock,
                    tenant -> DeploymentId.of(UUID.randomUUID().toString())));
        }
        return hosts;
    }

    private static DeploymentRegistry.Record create(DeploymentRegistry registry, MutableClock clock,
                                                    String tenant, String key, String bytes) {
        var content = new GraphVersion.Content(1, bytes.getBytes(StandardCharsets.UTF_8), "alice",
                clock.instant());
        return registry.create(content, new DeploymentRegistry.CreateCommand(tenant, key,
                "a".repeat(64))).toCompletableFuture().join();
    }

    private static DeploymentRegistry.Command command(DeploymentRegistry.Record record, String key,
                                                      char digest) {
        return new DeploymentRegistry.Command(record.tenantId(), record.deploymentId(), key,
                String.valueOf(digest).repeat(64), RevisionExpectation.exactly(record.revision()));
    }

    /**
     * Releases every host at one instant, so the calls genuinely overlap.
     *
     * <p>A dedicated thread per host rather than the common pool, and a latch rather than submission
     * order. The common pool's parallelism is the machine's, so on a small runner the later hosts would
     * not start until the earlier ones had finished and the test would quietly assert the sequential
     * behaviour the conformance suite already covers -- passing, on a machine where it proves
     * nothing.</p>
     */
    private static <T> List<T> inParallel(List<DeploymentRegistry> hosts,
                                          java.util.function.Function<DeploymentRegistry, T> call)
            throws Exception {
        List<T> results = java.util.Collections.synchronizedList(new ArrayList<T>());
        race(hosts, (host, index) -> results.add(call.apply(host)));
        return List.copyOf(results);
    }

    private static void inParallelIgnoringFailures(List<DeploymentRegistry> hosts, Contender contender)
            throws Exception {
        race(hosts, contender);
    }

    private static void race(List<DeploymentRegistry> hosts, Contender contender) throws Exception {
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(hosts.size());
        try {
            List<CompletableFuture<Void>> running = new ArrayList<>();
            for (int index = 0; index < hosts.size(); index++) {
                DeploymentRegistry host = hosts.get(index);
                int position = index;
                running.add(CompletableFuture.runAsync(() -> {
                    awaitStart(start);
                    contender.run(host, position);
                }, pool));
            }
            start.countDown();
            CompletableFuture.allOf(running.toArray(CompletableFuture[]::new)).get(2, TimeUnit.MINUTES);
        } finally {
            pool.shutdownNow();
        }
    }

    private static void awaitStart(CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted before the contended call started", interrupted);
        }
    }

    private static DeploymentRegistry.FailureReason reasonOf(Runnable call) {
        return reasonOf(assertThrows(CompletionException.class, call::run));
    }

    private static DeploymentRegistry.FailureReason reasonOf(CompletionException thrown) {
        return assertInstanceOf(DeploymentRegistry.RegistryException.class, thrown.getCause()).reason();
    }

    /** One host's attempt at a contended mutation, told which host it is. */
    @FunctionalInterface
    private interface Contender {
        void run(DeploymentRegistry host, int index);
    }
}
