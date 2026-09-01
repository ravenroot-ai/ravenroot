package ai.ravenroot.core.deployment.registry;

import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.registry.DeploymentIdSource;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry;
import ai.ravenroot.api.deployment.registry.GraphVersion;
import ai.ravenroot.api.persistence.RevisionExpectation;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/** Deterministic ephemeral reference adapter; never a durable or shared cluster authority. */
public final class InMemoryDeploymentRegistry implements DeploymentRegistry {
    private enum Action { CREATE, APPEND, COMMAND, OBSERVE, FAIL, TOMBSTONE, ACQUIRE, RENEW, RELEASE }
    private static final String CURSOR_VERSION = "rr1";

    private final Clock clock;
    private final DeploymentIdSource ids;
    private final Limits limits = new Limits(100, Duration.ofMinutes(5));
    private final Map<Key, Entry> entries = new HashMap<>();
    private final Map<CreateLedgerKey, Recorded> createLedger = new HashMap<>();

    public InMemoryDeploymentRegistry(Clock clock) {
        this(clock, tenant -> DeploymentId.of(UUID.randomUUID().toString()));
    }

    public InMemoryDeploymentRegistry(Clock clock, DeploymentIdSource ids) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override public Limits limits() { return limits; }

    @Override
    public synchronized CompletionStage<Record> create(GraphVersion.Content content, CreateCommand command) {
        return complete(() -> {
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(command, "command");
            CreateLedgerKey ledgerKey = new CreateLedgerKey(command.tenantId(), command.key());
            Recorded prior = createLedger.get(ledgerKey);
            if (prior != null) return replay(prior, command.digest());
            if (content.createdAt().isAfter(now())) throw invalid("createdAt is in the future");

            DeploymentId id = Objects.requireNonNull(ids.mint(command.tenantId()), "minted deploymentId");
            Key key = new Key(command.tenantId(), id);
            if (entries.containsKey(key)) throw failure(new FailureReason.Conflict());
            GraphVersion first = GraphVersion.bind(command.tenantId(), id, 1, content);
            Entry entry = new Entry(command.tenantId(), first, now());
            entries.put(key, entry);
            Record result = entry.record();
            createLedger.put(ledgerKey, new Recorded(command.digest(), result));
            return result;
        });
    }

    @Override
    public synchronized CompletionStage<Record> append(long version, GraphVersion.Content content, Command command) {
        return mutate(Action.APPEND, command, false, entry -> {
            if (version != entry.latest.version() + 1) throw failure(new FailureReason.Conflict());
            if (content.createdAt().isBefore(entry.createdAt) || content.createdAt().isAfter(now()))
                throw invalid("incoherent version timestamp");
            GraphVersion bound = GraphVersion.bind(entry.tenant, entry.latest.deploymentId(), version, content);
            entry.latest = bound;
            entry.versions.put(version, bound);
            return entry.advance(now());
        });
    }

    @Override
    public synchronized CompletionStage<Record> command(Desired desired, Command command) {
        return mutate(Action.COMMAND, command, false, entry -> {
            Objects.requireNonNull(desired, "desired");
            if (desired.kind() == DesiredKind.RUNNING && !entry.versions.containsKey(desired.desiredVersion()))
                throw invalid("unknown desired version");
            entry.generation++;
            entry.desired = new Desired(desired.kind(), desired.desiredVersion(), desired.updateStrategy(), entry.generation);
            entry.failure = null; // a new generation is the explicit recovery boundary
            return entry.advance(now());
        });
    }

    @Override
    public synchronized CompletionStage<Record> observe(Observation observation, Lease lease, Command command) {
        return ownerMutate(Action.OBSERVE, lease, command, entry -> {
            evidenceTime(entry, observation.observedAt());
            if (observation.observedGeneration() > entry.generation) throw invalid("observation generation is ahead");
            if (observation.activeVersion() != null && !entry.versions.containsKey(observation.activeVersion()))
                throw invalid("unknown active version");
            entry.observed = observation;
            return entry.advance(now());
        });
    }

    @Override
    public synchronized CompletionStage<Record> fail(Failure reported, Lease lease, Command command) {
        return ownerMutate(Action.FAIL, lease, command, entry -> {
            evidenceTime(entry, reported.at());
            entry.failure = reported;
            return entry.advance(now());
        });
    }

    @Override
    public synchronized CompletionStage<Record> tombstone(Tombstone tombstone, Command command) {
        return complete(() -> {
            Entry entry = require(command.tenantId(), command.deploymentId());
            LedgerKey key = new LedgerKey(Action.TOMBSTONE, command.key());
            Recorded prior = entry.ledger.get(key);
            if (prior != null) return replay(prior, command.digest());
            if (entry.tombstone != null) throw failure(new FailureReason.Conflict());
            expect(entry, command.expectedRevision());
            evidenceTime(entry, tombstone.at());
            entry.tombstone = tombstone;
            entry.lease = null;
            Record result = entry.advance(now());
            entry.ledger.put(key, new Recorded(command.digest(), result));
            return result;
        });
    }

    @Override
    public synchronized CompletionStage<Optional<Record>> get(String tenant, DeploymentId id) {
        return complete(() -> Optional.ofNullable(entries.get(new Key(tenant, id))).map(Entry::record));
    }

    @Override
    public synchronized CompletionStage<Optional<GraphVersion>> version(String tenant, DeploymentId id, long version) {
        return complete(() -> {
            Entry entry = entries.get(new Key(tenant, id));
            return entry == null ? Optional.empty() : Optional.ofNullable(entry.versions.get(version));
        });
    }

    @Override
    public synchronized CompletionStage<Page> list(String tenant, String cursor, int limit) {
        return complete(() -> {
            if (tenant == null || tenant.isBlank() || limit < 1 || limit > limits.maximumPageSize()) throw invalid("limit or tenant");
            String after = decodeCursor(tenant, cursor);
            List<Entry> sorted = entries.entrySet().stream()
                    .filter(item -> item.getKey().tenant.equals(tenant))
                    .sorted(Comparator.comparing(item -> item.getKey().id.value()))
                    .map(Map.Entry::getValue)
                    .filter(entry -> after == null || entry.latest.deploymentId().value().compareTo(after) > 0)
                    .toList();
            int end = Math.min(limit, sorted.size());
            List<Record> items = new ArrayList<>(end);
            for (int i = 0; i < end; i++) items.add(sorted.get(i).record());
            String next = end < sorted.size() ? encodeCursor(tenant, sorted.get(end - 1).latest.deploymentId().value()) : null;
            return new Page(items, next);
        });
    }

    @Override
    public synchronized CompletionStage<Record> acquire(String owner, Duration ttl, Command command) {
        return complete(() -> {
            Entry entry = requireMutable(command);
            ttl(ttl);
            LedgerKey key = new LedgerKey(Action.ACQUIRE, command.key());
            Recorded prior = entry.ledger.get(key);
            if (prior != null) {
                if (!prior.digest.equals(command.digest())) throw failure(new FailureReason.Conflict());
                Lease recordedLease = prior.record.lease();
                if (recordedLease == null || !isCurrentLive(entry, recordedLease)) throw failure(new FailureReason.LeaseLost());
                return prior.record;
            }
            expect(entry, command.expectedRevision());
            Instant current = now();
            if (entry.lease != null && entry.lease.expiresAt().isAfter(current)) throw failure(new FailureReason.Conflict());
            entry.fence++;
            entry.lease = new Lease(entry.tenant, entry.latest.deploymentId(), owner, entry.fence, current, current.plus(ttl));
            Record result = entry.advance(current);
            entry.ledger.put(key, new Recorded(command.digest(), result));
            return result;
        });
    }

    @Override
    public synchronized CompletionStage<Record> renew(Lease lease, Duration ttl, Command command) {
        return leaseMutate(Action.RENEW, lease, command, entry -> {
            ttl(ttl);
            entry.lease = new Lease(entry.tenant, entry.latest.deploymentId(), lease.owner(), lease.fence(),
                    lease.acquiredAt(), now().plus(ttl));
            return entry.advance(now());
        });
    }

    @Override
    public synchronized CompletionStage<Record> release(Lease lease, Command command) {
        return complete(() -> {
            Entry entry = requireMutable(command);
            LedgerKey key = new LedgerKey(Action.RELEASE, command.key());
            Recorded prior = entry.ledger.get(key);
            if (prior != null) {
                if (!prior.digest.equals(command.digest())) throw failure(new FailureReason.Conflict());
                if (entry.fence != lease.fence()) throw failure(new FailureReason.Fenced());
                if (entry.lease != null) ownerGuard(entry, lease);
                return prior.record;
            }
            ownerGuard(entry, lease);
            expect(entry, command.expectedRevision());
            entry.lease = null;
            Record result = entry.advance(now());
            entry.ledger.put(key, new Recorded(command.digest(), result));
            return result;
        });
    }

    private CompletionStage<Record> leaseMutate(Action action, Lease lease, Command command, Function<Entry, Record> operation) {
        return complete(() -> {
            Entry entry = requireMutable(command);
            ownerGuard(entry, lease); // owner authority is checked before replay
            LedgerKey key = new LedgerKey(action, command.key());
            Recorded prior = entry.ledger.get(key);
            if (prior != null) return replay(prior, command.digest());
            expect(entry, command.expectedRevision());
            Record result = operation.apply(entry);
            entry.ledger.put(key, new Recorded(command.digest(), result));
            return result;
        });
    }

    private CompletionStage<Record> ownerMutate(Action action, Lease lease, Command command, Function<Entry, Record> operation) {
        return leaseMutate(action, lease, command, operation);
    }

    private CompletionStage<Record> mutate(Action action, Command command, boolean allowTombstone,
                                            Function<Entry, Record> operation) {
        return complete(() -> {
            Entry entry = require(command.tenantId(), command.deploymentId());
            if (!allowTombstone && entry.tombstone != null) throw failure(new FailureReason.Conflict());
            LedgerKey key = new LedgerKey(action, command.key());
            Recorded prior = entry.ledger.get(key);
            if (prior != null) return replay(prior, command.digest());
            expect(entry, command.expectedRevision());
            Record result = operation.apply(entry);
            entry.ledger.put(key, new Recorded(command.digest(), result));
            return result;
        });
    }

    private Entry requireMutable(Command command) {
        Entry entry = require(command.tenantId(), command.deploymentId());
        if (entry.tombstone != null) throw failure(new FailureReason.Conflict());
        return entry;
    }

    private Entry require(String tenant, DeploymentId id) {
        Entry entry = entries.get(new Key(tenant, id));
        if (entry == null) throw failure(new FailureReason.NotFound());
        return entry;
    }

    private void expect(Entry entry, RevisionExpectation.Exactly expectation) {
        if (expectation.revision() != entry.revision) throw failure(new FailureReason.Conflict());
    }

    private void ownerGuard(Entry entry, Lease lease) {
        if (!entry.tenant.equals(lease.tenantId()) || !entry.latest.deploymentId().equals(lease.deploymentId()))
            throw failure(new FailureReason.NotFound());
        if (entry.fence != lease.fence()) throw failure(new FailureReason.Fenced());
        if (entry.lease == null || !entry.lease.owner().equals(lease.owner()) || !entry.lease.expiresAt().isAfter(now()))
            throw failure(new FailureReason.LeaseLost());
    }

    private boolean isCurrentLive(Entry entry, Lease lease) {
        return entry.fence == lease.fence() && entry.lease != null && entry.lease.owner().equals(lease.owner())
                && entry.lease.expiresAt().isAfter(now());
    }

    private void evidenceTime(Entry entry, Instant at) {
        if (at.isBefore(entry.createdAt) || at.isAfter(now())) throw invalid("incoherent evidence timestamp");
    }

    private void ttl(Duration value) {
        if (value == null || value.isNegative() || value.isZero() || value.compareTo(limits.maximumLeaseTtl()) > 0)
            throw invalid("ttl");
    }

    private String encodeCursor(String tenant, String lastId) {
        String raw = CURSOR_VERSION + "\u0000" + tenant + "\u0000" + lastId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeCursor(String tenant, String cursor) {
        if (cursor == null) return null;
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\u0000", -1);
            if (parts.length != 3 || !CURSOR_VERSION.equals(parts[0]) || !tenant.equals(parts[1]) || parts[2].isBlank())
                throw invalid("invalid cursor");
            return parts[2];
        } catch (IllegalArgumentException malformed) {
            throw invalid("invalid cursor");
        }
    }

    private Record replay(Recorded prior, String digest) {
        if (!prior.digest.equals(digest)) throw failure(new FailureReason.Conflict());
        return prior.record;
    }

    private Instant now() { return clock.instant(); }
    private static RegistryException invalid(String message) { return failure(new FailureReason.InvalidRequest(message)); }
    private static RegistryException failure(FailureReason reason) { return new RegistryException(reason); }
    private static <T> CompletionStage<T> complete(java.util.concurrent.Callable<T> operation) {
        try { return CompletableFuture.completedFuture(operation.call()); }
        catch (Exception failure) { return CompletableFuture.failedFuture(failure); }
    }

    private record Key(String tenant, DeploymentId id) {}
    private record CreateLedgerKey(String tenant, String key) {}
    private record LedgerKey(Action action, String key) {}
    private record Recorded(String digest, Record record) {}

    private static final class Entry {
        final String tenant;
        final Map<Long, GraphVersion> versions = new HashMap<>();
        final Map<LedgerKey, Recorded> ledger = new HashMap<>();
        final Instant createdAt;
        GraphVersion latest;
        long generation;
        long revision = 1;
        long fence;
        Instant updatedAt;
        Desired desired;
        Observation observed;
        Lease lease;
        Tombstone tombstone;
        Failure failure;

        Entry(String tenant, GraphVersion first, Instant now) {
            this.tenant = tenant;
            this.latest = first;
            this.versions.put(1L, first);
            this.createdAt = now;
            this.updatedAt = now;
            this.desired = new Desired(DesiredKind.STOPPED, null, null, 0);
            this.observed = new Observation(ObservedKind.COLD, null, 0, now);
        }

        Record advance(Instant now) { revision++; updatedAt = now; return record(); }
        Record record() {
            return new Record(tenant, latest.deploymentId(), latest.version(), generation, revision, desired,
                    observed, lease, failure, tombstone, createdAt, updatedAt);
        }
    }
}
