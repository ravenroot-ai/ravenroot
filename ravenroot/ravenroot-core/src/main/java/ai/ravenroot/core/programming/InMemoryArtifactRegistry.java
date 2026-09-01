package ai.ravenroot.core.programming;

import ai.ravenroot.api.application.AuthorizedRavenrootApplication;
import ai.ravenroot.api.programming.ArtifactProvenanceVerifier;
import ai.ravenroot.api.programming.ArtifactRegistry;
import ai.ravenroot.api.programming.ArtifactState;
import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.programming.ArtifactReservation;
import ai.ravenroot.api.programming.ProgramAdmission;
import ai.ravenroot.api.programming.ProgramArtifactIdentity;
import ai.ravenroot.api.programming.ProgramBuildNodePlan;
import ai.ravenroot.api.programming.ProgramBuildNodeSnapshot;
import ai.ravenroot.api.programming.ProgramBuildPhase;
import ai.ravenroot.api.programming.ProgramBuildSnapshot;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Development registry with immutable revisions and enforced lifecycle transitions. */
public final class InMemoryArtifactRegistry implements ArtifactRegistry {
    private static final int LOCK_STRIPES = 64;
    private static final String OWNER_TENANT_METADATA = AuthorizedRavenrootApplication.OWNER_TENANT_METADATA;
    private static final Map<ArtifactState, EnumSet<ArtifactState>> TRANSITIONS = transitions();
    private final ConcurrentHashMap<String, GeneratedArtifact> artifacts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ArtifactReservation> reservations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ProgramBuildSnapshot> builds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> activeBuildRequests = new ConcurrentHashMap<>();
    /** Cancellations registered by in-flight admissions, keyed by artifact id. See {@link #revoke}. */
    private final ConcurrentHashMap<String, Collection<Runnable>> revocations = new ConcurrentHashMap<>();
    private final ArtifactProvenanceVerifier verifier;

    /**
     * Defaults to {@link ArtifactProvenanceVerifier#refusing()}: a registry constructed
     * without a verifier can hold artifacts and run their lifecycle, but cannot release any source for
     * execution. Absence of a verifier is not permission — see that interface's Javadoc.
     */
    public InMemoryArtifactRegistry() {
        this(ArtifactProvenanceVerifier.refusing());
    }

    public InMemoryArtifactRegistry(ArtifactProvenanceVerifier verifier) {
        if (verifier == null) {
            throw new IllegalArgumentException("An artifact provenance verifier is required; use "
                    + "ArtifactProvenanceVerifier.refusing() to state deliberately that none is configured");
        }
        this.verifier = verifier;
    }
    private final Object[] locks = java.util.stream.IntStream.range(0, LOCK_STRIPES)
            .mapToObj(ignored -> new Object()).toArray();

    @Override
    public GeneratedArtifact create(String language, String source, Map<String, String> metadata) {
        String normalizedSource = source == null ? "" : source;
        Instant now = Instant.now();
        String hash = ProgramArtifactIdentity.sha256(language, normalizedSource);
        var artifact = new GeneratedArtifact(UUID.randomUUID().toString(), language, hash, normalizedSource,
                ArtifactState.GENERATED, 1, now, now, metadata);
        artifacts.put(artifact.id(), artifact);
        return artifact;
    }

    @Override
    public Optional<GeneratedArtifact> find(String id) {
        return Optional.ofNullable(artifacts.get(id));
    }

    @Override
    public List<GeneratedArtifact> list() {
        return artifacts.values().stream().sorted(Comparator.comparing(GeneratedArtifact::createdAt)).toList();
    }

    @Override
    public Optional<GeneratedArtifact> findByTenantAndDigest(String tenantId, String sha256) {
        if (tenantId == null || tenantId.isBlank() || sha256 == null || sha256.isBlank()) {
            throw new IllegalArgumentException("tenant and source digest are required");
        }
        return artifacts.values().stream()
                .filter(artifact -> tenantId.equals(artifact.metadata().get(OWNER_TENANT_METADATA)))
                .filter(artifact -> constantTimeEquals(artifact.sha256(), sha256))
                .findFirst();
    }

    @Override
    public GeneratedArtifact recordEvidence(String id, long expectedRevision, Map<String, String> evidence) {
        if (id == null || id.isBlank() || expectedRevision < 1) {
            throw new IllegalArgumentException("artifact id and expected revision are required");
        }
        synchronized (lock(id)) {
            if (reservations.containsKey(id)) {
                throw new IllegalStateException("Artifact lifecycle operation is already in progress");
            }
            GeneratedArtifact current = artifacts.get(id);
            if (current == null) throw new IllegalArgumentException("Unknown artifact: " + id);
            if (current.revision() != expectedRevision) {
                throw new IllegalStateException("Artifact changed while qualification evidence was recorded");
            }
            if (current.state() == ArtifactState.RETIRED) {
                throw new IllegalStateException("Retired artifact qualification is immutable");
            }
            var metadata = new java.util.LinkedHashMap<>(current.metadata());
            if (evidence != null) metadata.putAll(evidence);
            var changed = new GeneratedArtifact(current.id(), current.language(), current.sha256(), current.source(),
                    current.state(), current.revision() + 1, current.createdAt(), Instant.now(), metadata);
            artifacts.put(id, changed);
            return changed;
        }
    }

    @Override
    public synchronized ProgramBuildSnapshot startOrFindBuild(
            String tenantId, String requestDigest, boolean dualControl,
            Map<String, String> trustedMetadata, List<ProgramBuildNodePlan> nodes) {
        if (tenantId == null || tenantId.isBlank() || requestDigest == null || requestDigest.isBlank()) {
            throw new IllegalArgumentException("tenant and request digest are required");
        }
        String requestKey = tenantId + '\0' + requestDigest;
        String existingId = activeBuildRequests.get(requestKey);
        ProgramBuildSnapshot existing = existingId == null ? null : builds.get(existingId);
        if (existing != null && !existing.terminal()) return existing;
        activeBuildRequests.remove(requestKey, existingId);

        if (nodes == null || nodes.isEmpty() || nodes.size() > 256
                || nodes.stream().map(ProgramBuildNodePlan::nodeId).distinct().count() != nodes.size()) {
            throw new IllegalArgumentException("one to 256 uniquely identified nodes are required");
        }
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        var snapshots = nodes.stream().map(plan -> new ProgramBuildNodeSnapshot(
                id, tenantId, plan, "", ProgramBuildPhase.REGISTER, 1, now, now,
                false, false, false, "", "")).toList();
        var created = new ProgramBuildSnapshot(id, tenantId, requestDigest, dualControl, 1, now, now,
                false, trustedMetadata, snapshots);
        builds.put(id, created);
        activeBuildRequests.put(requestKey, id);
        return created;
    }

    @Override
    public synchronized Optional<ProgramBuildSnapshot> findBuild(String tenantId, String buildId) {
        ProgramBuildSnapshot snapshot = builds.get(buildId);
        return snapshot != null && snapshot.tenantId().equals(tenantId) ? Optional.of(snapshot) : Optional.empty();
    }

    @Override
    public synchronized List<ProgramBuildSnapshot> listIncompleteBuilds() {
        return builds.values().stream().filter(build -> !build.terminal())
                .sorted(Comparator.comparing(ProgramBuildSnapshot::createdAt)).toList();
    }

    @Override
    public synchronized ProgramBuildNodeSnapshot recordBuildNode(
            String tenantId, String buildId, String nodeId, long expectedRevision,
            String artifactId, ProgramBuildPhase phase, boolean terminal, boolean ready,
            boolean reused, String diagnostic, String smokeOutputJson) {
        ProgramBuildSnapshot build = findBuild(tenantId, buildId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown program build"));
        ProgramBuildNodeSnapshot current = build.nodes().stream().filter(node -> node.plan().nodeId().equals(nodeId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown program build node"));
        if (current.revision() != expectedRevision) {
            throw new IllegalStateException("Program build node changed during transactional compare-and-set");
        }
        Instant now = Instant.now();
        var changed = new ProgramBuildNodeSnapshot(buildId, tenantId, current.plan(), artifactId, phase,
                current.revision() + 1, current.createdAt(), now, terminal, ready, reused,
                bounded(diagnostic, 4096), bounded(smokeOutputJson, 256 * 1024));
        var changedNodes = build.nodes().stream()
                .map(node -> node.plan().nodeId().equals(nodeId) ? changed : node).toList();
        boolean buildTerminal = changedNodes.stream().allMatch(ProgramBuildNodeSnapshot::terminal);
        var updated = new ProgramBuildSnapshot(build.id(), build.tenantId(), build.requestDigest(),
                build.dualControl(), build.revision() + 1, build.createdAt(), now, buildTerminal,
                build.trustedMetadata(), changedNodes);
        builds.put(buildId, updated);
        if (buildTerminal) activeBuildRequests.remove(tenantId + '\0' + build.requestDigest(), buildId);
        return changed;
    }

    private static String bounded(String value, int limit) {
        if (value == null) return "";
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    @Override
    public GeneratedArtifact transition(String id, ArtifactState expected, ArtifactState target) {
        return transition(id, expected, target, Map.of());
    }

    @Override
    public GeneratedArtifact transition(String id, ArtifactState expected, ArtifactState target,
                                        Map<String, String> evidence) {
        if (id == null || expected == null || target == null) {
            throw new IllegalArgumentException("Artifact id, expected state and target state are required");
        }
        GeneratedArtifact changed;
        synchronized (lock(id)) {
            Map<String, String> recordedEvidence = evidence == null ? Map.of() : Map.copyOf(evidence);
            if (reservations.containsKey(id)) {
                throw new IllegalStateException("Artifact lifecycle operation is already in progress");
            }
            GeneratedArtifact current = required(id, expected);
            changed = changed(current, target, recordedEvidence);
            artifacts.put(id, changed);
        }
        // Outside the stripe lock, and only once the retirement is durable in the map: an admission
        // redeeming concurrently now observes RETIRED and is refused, while one that already redeemed
        // is cancelled here. Announcing before the state changed would leave a window in which a
        // cancelled execution could be re-admitted.
        if (changed.state() == ArtifactState.RETIRED) {
            revoke(id);
        }
        return changed;
    }

    /**
     * SEC-12 / SEC-25. See {@link ArtifactRegistry#admitForExecution} and
     * {@link ProgramAdmission} for why execution is admitted through a redeemable handle instead of a
     * snapshot. The three checks live in {@code redeem()}, deliberately: performing any of them here
     * would put them back on the wrong side of the interval.
     */
    @Override
    public ProgramAdmission admitForExecution(String tenantId, String artifactId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("Tenant is required to admit an artifact for execution");
        }
        if (artifactId == null || artifactId.isBlank()) {
            throw new IllegalArgumentException("Artifact id is required");
        }
        GeneratedArtifact observed = artifacts.get(artifactId);
        if (observed == null) {
            throw new IllegalArgumentException("Unknown program artifact: " + artifactId);
        }
        return new RegistryAdmission(tenantId, artifactId, observed);
    }

    /**
     * Runs the revocation cancellations registered by in-flight admissions for one artifact.
     *
     * <p>Called when an artifact reaches {@link ArtifactState#RETIRED}. An execution admitted just
     * before the retirement is legitimately admitted and its source may already be inside a worker,
     * so redemption cannot stop it; cancelling it is the only remaining remedy. Cancellations are run
     * outside the stripe lock: they reach into a sandbox adapter, and holding a registry lock across
     * that would invert the lock order the lifecycle operations use.
     */
    private void revoke(String artifactId) {
        Collection<Runnable> cancellations = revocations.remove(artifactId);
        if (cancellations == null) {
            return;
        }
        for (Runnable cancellation : cancellations) {
            try {
                cancellation.run();
            } catch (RuntimeException ignored) {
                // One adapter refusing to cancel must not prevent the others from being told.
            }
        }
    }

    private final class RegistryAdmission implements ProgramAdmission {
        private final String tenantId;
        private final String artifactId;
        private final GeneratedArtifact observed;
        private final long admittedRevision;
        private final java.util.concurrent.atomic.AtomicBoolean closed =
                new java.util.concurrent.atomic.AtomicBoolean();
        private volatile Runnable cancellation;

        private RegistryAdmission(String tenantId, String artifactId, GeneratedArtifact observed) {
            this.tenantId = tenantId;
            this.artifactId = artifactId;
            this.observed = observed;
            this.admittedRevision = observed.revision();
        }

        @Override
        public String artifactId() {
            return artifactId;
        }

        @Override
        public GeneratedArtifact unverifiedSnapshot() {
            return observed;
        }

        @Override
        public GeneratedArtifact redeem() {
            if (closed.get()) {
                throw new SecurityException("Program artifact admission is already released: " + artifactId);
            }
            // Under the same stripe lock every lifecycle transition takes, so a transition cannot be
            // half-applied underneath these three checks.
            synchronized (lock(artifactId)) {
                GeneratedArtifact current = artifacts.get(artifactId);
                if (current == null) {
                    throw new SecurityException("Unknown program artifact: " + artifactId);
                }
                String owner = current.metadata().get(OWNER_TENANT_METADATA);
                if (owner == null || owner.isBlank() || !owner.equals(tenantId)) {
                    // Fails closed on an absent owner: an artifact with no recorded
                    // owner belongs to no tenant, and "unowned" must not read as "everyone's".
                    throw new SecurityException("Program artifact is not owned by tenant " + tenantId
                            + ": " + artifactId);
                }
                if (current.state() != ArtifactState.ACTIVE) {
                    throw new SecurityException("Program artifact is not ACTIVE: " + artifactId + " ("
                            + current.state() + ")");
                }
                if (current.revision() != admittedRevision) {
                    throw new SecurityException("Program artifact changed between admission and execution: "
                            + artifactId + " (admitted revision " + admittedRevision + ", now "
                            + current.revision() + ")");
                }
                // SEC-12, signature half. Inside redemption and last, so it decides on the same
                // authoritative record the three gates above just approved and the caller is about to
                // receive. Verifying beside redemption instead of within it would re-create the very
                // interval ADR 0020 removes; verifying BEFORE the gates would spend a key check on a
                // record that is about to be rejected anyway.
                verifier.verify(current);
                return current;
            }
        }

        @Override
        public void onRevoked(Runnable toCancel) {
            if (toCancel == null || closed.get()) {
                return;
            }
            this.cancellation = toCancel;
            revocations.computeIfAbsent(artifactId, ignored -> new java.util.concurrent.CopyOnWriteArrayList<>())
                    .add(toCancel);
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            Runnable registered = cancellation;
            if (registered == null) {
                return;
            }
            Collection<Runnable> live = revocations.get(artifactId);
            if (live != null) {
                live.remove(registered);
            }
        }
    }

    @Override
    public ArtifactReservation reserve(String id, ArtifactState expected, ArtifactState target) {
        if (!TRANSITIONS.getOrDefault(expected, EnumSet.noneOf(ArtifactState.class)).contains(target)) {
            throw new IllegalStateException("Illegal artifact transition " + expected + " -> " + target);
        }
        synchronized (lock(id)) {
            if (reservations.containsKey(id)) {
                throw new IllegalStateException("Artifact lifecycle operation is already in progress");
            }
            var reservation = new ArtifactReservation(UUID.randomUUID(), required(id, expected), target);
            reservations.put(id, reservation);
            return reservation;
        }
    }

    @Override
    public GeneratedArtifact complete(ArtifactReservation reservation, Map<String, String> evidence) {
        String id = reservation.artifact().id();
        synchronized (lock(id)) {
            ArtifactReservation owned = reservations.get(id);
            if (!reservation.equals(owned)) {
                throw new IllegalStateException("Artifact lifecycle reservation is not current");
            }
            GeneratedArtifact current = required(id, reservation.artifact().state());
            if (current.revision() != reservation.artifact().revision()) {
                throw new IllegalStateException("Artifact changed while lifecycle operation was reserved");
            }
            GeneratedArtifact changed = changed(current, reservation.target(),
                    evidence == null ? Map.of() : Map.copyOf(evidence));
            artifacts.put(current.id(), changed);
            reservations.remove(current.id(), reservation);
            return changed;
        }
    }

    @Override
    public void cancel(ArtifactReservation reservation) {
        String id = reservation.artifact().id();
        synchronized (lock(id)) {
            reservations.remove(id, reservation);
        }
    }

    private GeneratedArtifact required(String id, ArtifactState expected) {
        GeneratedArtifact current = artifacts.get(id);
        if (current == null) throw new IllegalArgumentException("Unknown artifact: " + id);
        if (current.state() != expected) {
            throw new IllegalStateException("Artifact " + id + " is " + current.state() + ", not " + expected);
        }
        return current;
    }

    private Object lock(String id) {
        return locks[(id.hashCode() & Integer.MAX_VALUE) % locks.length];
    }

    private static GeneratedArtifact changed(GeneratedArtifact current, ArtifactState target,
                                             Map<String, String> evidence) {
        if (!TRANSITIONS.getOrDefault(current.state(), EnumSet.noneOf(ArtifactState.class)).contains(target)) {
            throw new IllegalStateException("Illegal artifact transition " + current.state() + " -> " + target);
        }
        var metadata = new java.util.LinkedHashMap<>(current.metadata());
        evidence.forEach((key, value) -> metadata.put("evidence." + target.name().toLowerCase()
                + "." + key, value));
        return new GeneratedArtifact(current.id(), current.language(), current.sha256(), current.source(),
                target, current.revision() + 1, current.createdAt(), Instant.now(), metadata);
    }

    private static Map<ArtifactState, EnumSet<ArtifactState>> transitions() {
        var result = new EnumMap<ArtifactState, EnumSet<ArtifactState>>(ArtifactState.class);
        result.put(ArtifactState.GENERATED, EnumSet.of(ArtifactState.VALIDATED, ArtifactState.RETIRED));
        result.put(ArtifactState.VALIDATED, EnumSet.of(ArtifactState.TESTED, ArtifactState.RETIRED));
        result.put(ArtifactState.TESTED, EnumSet.of(ArtifactState.APPROVED, ArtifactState.RETIRED));
        result.put(ArtifactState.APPROVED, EnumSet.of(ArtifactState.ACTIVE, ArtifactState.RETIRED));
        result.put(ArtifactState.ACTIVE, EnumSet.of(ArtifactState.RETIRED));
        result.put(ArtifactState.RETIRED, EnumSet.noneOf(ArtifactState.class));
        return Map.copyOf(result);
    }

    private static boolean constantTimeEquals(String left, String right) {
        try {
            return java.security.MessageDigest.isEqual(java.util.HexFormat.of().parseHex(left),
                    java.util.HexFormat.of().parseHex(right));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

}
