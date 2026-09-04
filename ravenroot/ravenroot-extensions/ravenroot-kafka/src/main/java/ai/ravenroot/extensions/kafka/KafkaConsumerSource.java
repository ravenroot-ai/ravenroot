package ai.ravenroot.extensions.kafka;

import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.deployment.IngressReceipt;
import ai.ravenroot.api.deployment.IngressTarget;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.security.CredentialResolver;
import ai.ravenroot.api.security.egress.ReservedNetworkPolicy;
import ai.ravenroot.api.security.SecretValue;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.WakeupException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/** Poll-thread-owned lifecycle, retry, rebalance and contiguous-offset state for kafka.consume. */
final class KafkaConsumerSource implements InboundSource {
    enum State { STOPPED, STARTING, READY, REBALANCING, BACKING_OFF, STOPPING, FAILED }

    private final NodeConfiguration configuration;
    private final CredentialResolver credentials;
    private final KafkaConsumerProfileResolver profiles;
    private final KafkaConsumerProtocol protocol;
    private final Executor executor;
    private final Clock clock;
    private final ReservedNetworkPolicy destinationPolicy;
    private final Object lifecycle = new Object();

    private volatile State state = State.STOPPED;
    private volatile KafkaConsumerProtocol.Owner owner;
    private volatile boolean stopRequested;
    private CompletableFuture<Void> startFlight;
    private CompletableFuture<Void> stopFlight = CompletableFuture.completedFuture(null);
    private long generation;

    KafkaConsumerSource(NodeConfiguration configuration, CredentialResolver credentials,
                        KafkaConsumerProfileResolver profiles, KafkaConsumerProtocol protocol,
                        Executor executor, Clock clock) {
        this(configuration, credentials, profiles, protocol, executor, clock,
                ReservedNetworkPolicy.fromEnvironment(System.getenv()));
    }

    KafkaConsumerSource(NodeConfiguration configuration, CredentialResolver credentials,
                        KafkaConsumerProfileResolver profiles, KafkaConsumerProtocol protocol,
                        Executor executor, Clock clock, ReservedNetworkPolicy destinationPolicy) {
        this.configuration = Objects.requireNonNull(configuration);
        this.credentials = Objects.requireNonNull(credentials);
        this.profiles = Objects.requireNonNull(profiles);
        this.protocol = Objects.requireNonNull(protocol);
        this.executor = Objects.requireNonNull(executor);
        this.clock = Objects.requireNonNull(clock);
        this.destinationPolicy = Objects.requireNonNull(destinationPolicy);
    }

    @Override public CompletionStage<Void> start(InboundSourceContext context) {
        Objects.requireNonNull(context);
        synchronized (lifecycle) {
            // A failed/stopping run is not restartable until its poll owner has left close() and the
            // terminal future has completed. This closes the poison-failure owner overlap window.
            if (owner != null || !stopFlight.isDone()) {
                if (state == State.STARTING) return startFlight;
                if (state == State.READY || state == State.REBALANCING || state == State.BACKING_OFF) {
                    return CompletableFuture.completedFuture(null);
                }
                return stopFlight.thenCompose(ignored -> start(context));
            }
            if (state == State.READY || state == State.REBALANCING || state == State.BACKING_OFF) {
                return CompletableFuture.completedFuture(null);
            }
            if (state == State.STARTING) return startFlight;
            stopRequested = false;
            state = State.STARTING;
            startFlight = new CompletableFuture<>();
            stopFlight = new CompletableFuture<>();
            executor.execute(() -> run(context, startFlight, stopFlight));
            return startFlight;
        }
    }

    @Override public CompletionStage<Void> stop() {
        KafkaConsumerProtocol.Owner current;
        synchronized (lifecycle) {
            if (state == State.STOPPED) return CompletableFuture.completedFuture(null);
            if (state == State.STOPPING) return stopFlight;
            if (owner == null && stopFlight.isDone()) {
                stopRequested = true;
                state = State.STOPPED;
                return CompletableFuture.completedFuture(null);
            }
            stopRequested = true;
            state = State.STOPPING;
            current = owner;
        }
        if (current != null) try { current.wakeup(); } catch (RuntimeException ignored) { }
        return stopFlight;
    }

    @Override public CompletionStage<Void> rollback() { return stop(); }
    @Override public CompletionStage<Void> shutdown() { return stop(); }

    State state() { return state; }
    long generation() { return generation; }

    private void run(InboundSourceContext context, CompletableFuture<Void> ready,
                     CompletableFuture<Void> stopped) {
        KafkaConsumerProtocol.Owner client = null;
        Settings settings = null;
        SecretValue secret = null;
        char[] password = null;
        RuntimeState runtime = null;
        try {
            settings = Settings.resolve(configuration, context, profiles, destinationPolicy);
            probeDurableIngress(context, settings.profile.startupTimeoutMs());
            Optional<SecretValue> resolved = credentials.resolve(settings.profile.credentialRef());
            if (resolved == null || resolved.isEmpty()) throw new SourceFailure("credential-unavailable");
            secret = resolved.get(); password = secret.copy();
            client = protocol.open(settings.profile, password);
            owner = client;
            runtime = new RuntimeState(settings, context, client);
            RuntimeState active = runtime;
            client.subscribe(settings.subscription, new KafkaConsumerProtocol.RebalanceListener() {
                @Override public void revoked(Set<KafkaConsumerProtocol.Partition> partitions) {
                    active.revoke(partitions, false);
                }
                @Override public void assigned(Set<KafkaConsumerProtocol.Partition> partitions) {
                    active.assign(partitions);
                }
                @Override public void lost(Set<KafkaConsumerProtocol.Partition> partitions) {
                    active.revoke(partitions, true);
                }
            });
            Instant startupDeadline = clock.instant().plusMillis(settings.profile.startupTimeoutMs());
            while (!stopRequested && client.assignment().isEmpty()) {
                active.accept(client.poll(Duration.ofMillis(settings.pollTimeoutMs)));
                active.work();
                if (!clock.instant().isBefore(startupDeadline)) throw new SourceFailure("assignment-timeout");
            }
            if (stopRequested) throw new SourceFailure("startup-cancelled");
            // Re-read immediately before completing the public start flight. The callback only
            // records assignment; it never publishes readiness or health on its own.
            if (client.assignment().isEmpty()) throw new SourceFailure("assignment-lost-before-ready");
            claimReadiness(context, client, ready);
            while (!stopRequested) {
                active.accept(client.poll(Duration.ofMillis(settings.pollTimeoutMs)));
                active.work();
                if (state == State.REBALANCING && !client.assignment().isEmpty()) {
                    state = State.READY;
                    context.reportHealthy();
                }
            }
            active.drainAndCommit(settings.drainTimeoutMs);
        } catch (WakeupException wakeup) {
            if (!stopRequested) fail(context, ready, "consumer-wakeup");
            if (runtime != null) runtime.drainAndCommit(settings == null ? 0 : settings.drainTimeoutMs);
        } catch (SourceFailure failure) {
            fail(context, ready, failure.safeReason);
        } catch (AuthenticationException | AuthorizationException failure) {
            fail(context, ready, "broker-authorization-failed");
        } catch (RuntimeException failure) {
            fail(context, ready, "consumer-failed");
        } finally {
            owner = null;
            if (password != null) java.util.Arrays.fill(password, '\0');
            if (secret != null) secret.close();
            if (client != null) try {
                int closeMs = settings == null ? 0 : settings.drainTimeoutMs;
                client.close(Duration.ofMillis(closeMs));
            } catch (RuntimeException ignored) { }
            synchronized (lifecycle) {
                if (!ready.isDone()) ready.completeExceptionally(new IllegalStateException("Kafka source stopped before readiness"));
                state = stopRequested ? State.STOPPED : State.FAILED;
                stopped.complete(null);
            }
        }
    }

    private void claimReadiness(InboundSourceContext context, KafkaConsumerProtocol.Owner client,
                                CompletableFuture<Void> ready) {
        synchronized (lifecycle) {
            // stop() owns the same monitor. Re-check the complete readiness predicate while claiming
            // READY so STOPPING can never be overwritten by health publication or start success.
            if (stopRequested || (state != State.STARTING && state != State.BACKING_OFF)) {
                throw new SourceFailure("startup-cancelled");
            }
            if (client.assignment().isEmpty()) throw new SourceFailure("assignment-lost-before-ready");
            state = State.READY;
            context.reportHealthy();
            ready.complete(null);
        }
    }

    private static void probeDurableIngress(InboundSourceContext context, int timeoutMs) {
        try {
            context.ingress().sourceCheckpoint(context.identity(), context.nodeId()).toCompletableFuture()
                    .get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception unavailable) {
            throw new SourceFailure("durable-ingress-required");
        }
    }

    private void fail(InboundSourceContext context, CompletableFuture<Void> ready, String safeReason) {
        synchronized (lifecycle) {
            if (stopRequested || state == State.STOPPING) return;
            state = State.FAILED;
            context.reportDegraded(safeReason);
            ready.completeExceptionally(new IllegalStateException(safeReason));
        }
    }

    private final class RuntimeState {
        private final Settings settings;
        private final InboundSourceContext context;
        private final KafkaConsumerProtocol.Owner client;
        private final Map<KafkaConsumerProtocol.Partition, PartitionState> partitions = new HashMap<>();
        private Set<KafkaConsumerProtocol.Partition> paused = Set.of();

        RuntimeState(Settings settings, InboundSourceContext context, KafkaConsumerProtocol.Owner client) {
            this.settings = settings; this.context = context; this.client = client;
        }

        void assign(Set<KafkaConsumerProtocol.Partition> assigned) {
            generation++;
            partitions.values().forEach(partition -> partition.rebase(generation));
            assigned.stream().sorted().forEach(partition -> partitions.computeIfAbsent(partition,
                    ignored -> new PartitionState(generation)).rebase(generation));
        }

        void revoke(Set<KafkaConsumerProtocol.Partition> revoked, boolean lost) {
            state = State.REBALANCING;
            generation++;
            partitions.values().forEach(partition -> partition.rebase(generation));
            if (!lost) drain(revoked, settings.drainTimeoutMs);
            if (!lost) commit(revoked);
            revoked.forEach(partitions::remove);
            paused = paused.stream().filter(partition -> !revoked.contains(partition))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        void accept(List<KafkaConsumerProtocol.Record> records) {
            for (KafkaConsumerProtocol.Record record : records) {
                PartitionState partition = partitions.get(record.partition());
                if (partition == null || partition.generation != generation || partition.halted) continue;
                if (partition.lastObservedOffset >= 0 && record.offset() < partition.lastObservedOffset) {
                    throw new SourceFailure("partition-order-violation");
                }
                partition.lastObservedOffset = Math.max(partition.lastObservedOffset, record.offset());
                partition.records.putIfAbsent(record.offset(), new Pending(record, generation));
            }
            updatePause();
        }

        void work() {
            Instant now = clock.instant();
            var ordered = partitions.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
            for (var entry : ordered) {
                PartitionState partition = entry.getValue();
                if (partition.generation != generation || partition.halted) continue;
                Pending pending = partition.firstUnsafe();
                if (pending == null || pending.nextAttempt.isAfter(now)) continue;
                process(partition, pending);
            }
            commit(partitions.keySet());
            updatePause();
        }

        private void process(PartitionState partition, Pending pending) {
            if (pending.generation != generation || partition.generation != generation) return;
            pending.attempts++;
            String reason = null;
            Map<String, Object> event = null;
            try {
                event = KafkaRecordEvent.from(pending.record, settings.profile, pending.attempts,
                        KafkaRecordEvent.correlation(pending.record));
            } catch (KafkaRecordEvent.InvalidRecord invalid) { reason = invalid.safeReason(); }
            if (reason == null) {
                IngressReceipt receipt = context.ingress().offerDurably(context.identity(), IngressTarget.start(), event,
                        context.nodeId(), KafkaRecordEvent.idempotentKey(settings.profile, pending.record));
                if (receipt instanceof IngressReceipt.DurablyCommitted || receipt instanceof IngressReceipt.Duplicate) {
                    pending.safe = true;
                    partition.advanceFrontier();
                    recoverFromBackoff();
                    return;
                }
                if (receipt instanceof IngressReceipt.VolatileCustody) {
                    throw new SourceFailure("durable-ingress-lost");
                }
                if (receipt instanceof IngressReceipt.Ambiguous) reason = "ambiguous-ingress";
                else reason = "ingress-refused";
            }
            if (pending.attempts >= settings.poisonAttempts) {
                if (settings.poisonPolicy.equals("dead-letter")
                        && client.deadLetter(pending.record, reason, Duration.ofMillis(settings.maxRetryBackoffMs))) {
                    pending.safe = true;
                    partition.advanceFrontier();
                    recoverFromBackoff();
                    return;
                }
                partition.halted = true;
                throw new SourceFailure("poison-record-halted");
            }
            long multiplier = 1L << Math.min(30, pending.attempts - 1);
            long delay = Math.min(settings.maxRetryBackoffMs,
                    Math.multiplyExact((long) settings.retryBackoffMs, multiplier));
            pending.nextAttempt = clock.instant().plusMillis(delay);
            state = State.BACKING_OFF;
            context.reportDegraded(reason);
        }

        private void recoverFromBackoff() {
            if (state == State.BACKING_OFF) {
                state = State.READY;
                context.reportHealthy();
            }
        }

        void drainAndCommit(int timeoutMs) {
            drain(Set.copyOf(partitions.keySet()), timeoutMs);
            commit(partitions.keySet());
        }

        private void drain(Collection<KafkaConsumerProtocol.Partition> target, int timeoutMs) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            do {
                work();
                if (target.stream().noneMatch(partition -> {
                    PartitionState state = partitions.get(partition);
                    return state != null && state.firstUnsafe() != null && !state.halted;
                })) return;
                if (timeoutMs == 0) return;
                try { Thread.sleep(Math.min(10, Math.max(1, settings.pollTimeoutMs))); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            } while (System.nanoTime() < deadline);
        }

        private void commit(Collection<KafkaConsumerProtocol.Partition> target) {
            Map<KafkaConsumerProtocol.Partition, Long> offsets = new TreeMap<>();
            for (KafkaConsumerProtocol.Partition key : target) {
                PartitionState partition = partitions.get(key);
                if (partition != null && partition.commitThrough >= 0
                        && partition.commitThrough + 1 > partition.lastCommittedNext) {
                    offsets.put(key, partition.commitThrough + 1);
                }
            }
            if (offsets.isEmpty()) return;
            client.commit(offsets);
            offsets.forEach((key, next) -> partitions.get(key).lastCommittedNext = next);
        }

        private void updatePause() {
            Set<KafkaConsumerProtocol.Partition> shouldPause = new HashSet<>();
            int total = partitions.values().stream().mapToInt(value -> value.records.size()).sum();
            if (total >= settings.maxInFlight) shouldPause.addAll(partitions.keySet());
            partitions.forEach((partition, value) -> {
                Pending first = value.firstUnsafe();
                if (value.halted || first != null && first.attempts > 0 && !first.safe) shouldPause.add(partition);
            });
            Set<KafkaConsumerProtocol.Partition> toPause = new HashSet<>(shouldPause); toPause.removeAll(paused);
            Set<KafkaConsumerProtocol.Partition> toResume = new HashSet<>(paused); toResume.removeAll(shouldPause);
            if (!toPause.isEmpty()) client.pause(toPause);
            if (!toResume.isEmpty()) client.resume(toResume);
            paused = Set.copyOf(shouldPause);
        }
    }

    private static final class PartitionState {
        private long generation;
        private final TreeMap<Long, Pending> records = new TreeMap<>();
        private long commitThrough = -1;
        private long lastCommittedNext = -1;
        private long lastObservedOffset = -1;
        private boolean halted;
        private PartitionState(long generation) { this.generation = generation; }
        private void rebase(long generation) {
            this.generation = generation;
            records.values().forEach(pending -> pending.generation = generation);
        }
        private Pending firstUnsafe() {
            return records.values().stream().filter(pending -> !pending.safe).findFirst().orElse(null);
        }
        private void advanceFrontier() {
            while (!records.isEmpty()) {
                Map.Entry<Long, Pending> next = records.firstEntry();
                if (!next.getValue().safe) break;
                commitThrough = next.getKey();
                records.pollFirstEntry();
            }
        }
    }

    private static final class Pending {
        private final KafkaConsumerProtocol.Record record;
        private long generation;
        private int attempts;
        private boolean safe;
        private Instant nextAttempt = Instant.EPOCH;
        private Pending(KafkaConsumerProtocol.Record record, long generation) {
            this.record = record; this.generation = generation;
        }
    }

    private record Settings(KafkaConsumerProfile profile, KafkaConsumerProtocol.Subscription subscription,
                            int maxInFlight, int pollTimeoutMs, int drainTimeoutMs, int retryBackoffMs,
                            int maxRetryBackoffMs, int poisonAttempts, String poisonPolicy) {
        static Settings resolve(NodeConfiguration c, InboundSourceContext context,
                                KafkaConsumerProfileResolver profiles,
                                ReservedNetworkPolicy destinationPolicy) {
            for (String property : c.properties().keySet()) {
                if (!KafkaConsumeNodeBehavior.knownConfiguration().contains(property)) {
                    throw new SourceFailure("unknown-graph-property");
                }
            }
            String name = c.property("clusterProfile").orElseThrow(() -> new SourceFailure("cluster-profile-required"));
            KafkaConsumerProfile profile;
            try { profile = profiles.resolve(context.identity().tenantId(), name).orElse(null); }
            catch (RuntimeException invalid) { profile = null; }
            if (profile == null || !profile.tenant().equals(context.identity().tenantId()) || !profile.name().equals(name)) {
                throw new SourceFailure("cluster-profile-unavailable");
            }
            try { EnvironmentKafkaProfileResolver.requireDestinations(
                    String.join(",", profile.bootstrapServers()), destinationPolicy); }
            catch (SecurityException refused) { throw new SourceFailure("cluster-profile-unavailable"); }
            String group = c.property("group", profile.groupLogicalName());
            if (!group.equals(profile.groupLogicalName())) throw new SourceFailure("group-forbidden");
            String mode = c.property("subscriptionMode", "profile");
            KafkaConsumerProtocol.Subscription subscription = switch (mode) {
                case "profile" -> profile.patternSubscription()
                        ? new KafkaConsumerProtocol.Subscription(Set.of(), profile.topicPattern())
                        : new KafkaConsumerProtocol.Subscription(profile.topics(), null);
                case "topics" -> {
                    Set<String> topics = csv(c.requiredProperty("topics"));
                    boolean forbidden = topics.isEmpty();
                    for (String topic : topics) forbidden |= !profile.topics().contains(topic);
                    if (forbidden) {
                        throw new SourceFailure("topics-forbidden");
                    }
                    yield new KafkaConsumerProtocol.Subscription(topics, null);
                }
                case "pattern" -> {
                    String pattern = c.requiredProperty("topicPattern");
                    if (!profile.patternSubscription() || !profile.topicPattern().equals(pattern)) {
                        throw new SourceFailure("topic-pattern-forbidden");
                    }
                    yield new KafkaConsumerProtocol.Subscription(Set.of(), pattern);
                }
                default -> throw new SourceFailure("subscription-mode-invalid");
            };
            String membership = c.property("staticMember", "profile");
            if (!Set.of("profile", "dynamic").contains(membership)) throw new SourceFailure("membership-invalid");
            if ("dynamic".equals(membership) && profile.staticMemberId() != null) {
                profile = dynamic(profile);
            }
            String poison = c.property("poisonPolicy", "profile");
            if ("profile".equals(poison)) poison = profile.poisonPolicy();
            if (!poison.equals(profile.poisonPolicy())) throw new SourceFailure("poison-policy-forbidden");
            if ("dead-letter".equals(poison) && !c.property("deadLetterTopic", profile.deadLetterTopic())
                    .equals(profile.deadLetterTopic())) throw new SourceFailure("dead-letter-topic-forbidden");
            if (!"require-durable".equals(c.property("checkpointPolicy", "require-durable"))) {
                throw new SourceFailure("checkpoint-policy-forbidden");
            }
            int maxInFlight = tighten(c, "maxInFlight", profile.maxInFlight(), 1);
            int pollTimeout = tighten(c, "pollTimeoutMs", profile.pollTimeoutMs(), 10);
            int drainTimeout = tighten(c, "drainTimeoutMs", profile.drainTimeoutMs(), 0);
            int retryBackoff = tighten(c, "retryBackoffMs", profile.retryBackoffMs(), 1);
            int maxRetryBackoff = tighten(c, "maxRetryBackoffMs", profile.maxRetryBackoffMs(), 1);
            int poisonAttempts = tighten(c, "poisonAttempts", profile.poisonAttempts(), 1);
            if (maxRetryBackoff < retryBackoff) throw new SourceFailure("invalid-tightening");
            profile = tightened(profile, maxInFlight, pollTimeout, drainTimeout, retryBackoff,
                    maxRetryBackoff, poisonAttempts);
            return new Settings(profile, subscription, maxInFlight, pollTimeout, drainTimeout,
                    retryBackoff, maxRetryBackoff, poisonAttempts, poison);
        }
        private static int tighten(NodeConfiguration c, String name, int ceiling, int minimum) {
            String raw = c.property(name, ""); if (raw.isEmpty()) return ceiling;
            try { int value = Integer.parseInt(raw); if (value < minimum || value > ceiling) throw new NumberFormatException(); return value; }
            catch (NumberFormatException invalid) { throw new SourceFailure("invalid-tightening"); }
        }
        private static Set<String> csv(String value) {
            if (value == null || value.isBlank()) return Set.of();
            Set<String> values = new HashSet<>();
            for (String item : value.split(",", -1)) {
                String normalized = item.strip();
                if (normalized.isEmpty() || !values.add(normalized)) throw new SourceFailure("topics-invalid");
            }
            return Set.copyOf(values);
        }
        private static KafkaConsumerProfile dynamic(KafkaConsumerProfile p) {
            return new KafkaConsumerProfile(p.tenant(), p.name(), p.bootstrapServers(), p.clientDnsLookup(), p.tls(),
                    p.saslMechanism(), p.username(), p.credentialRef(), p.clientId(), p.groupLogicalName(), p.groupId(),
                    null, p.topics(), p.topicPattern(), p.headers(), p.assignmentStrategy(), p.autoOffsetReset(),
                    p.isolationLevel(), p.startupTimeoutMs(), p.pollTimeoutMs(), p.maxPollIntervalMs(), p.sessionTimeoutMs(),
                    p.heartbeatIntervalMs(), p.maxInFlight(), p.maxFetchBytes(), p.maxPartitionFetchBytes(),
                    p.maxRecordBytes(), p.maxKeyBytes(), p.maxValueBytes(), p.maxHeaderBytes(), p.drainTimeoutMs(),
                    p.retryBackoffMs(), p.maxRetryBackoffMs(), p.poisonAttempts(), p.poisonPolicy(), p.deadLetterTopic());
        }
        private static KafkaConsumerProfile tightened(KafkaConsumerProfile p, int maxInFlight, int pollTimeout,
                int drainTimeout, int retryBackoff, int maxRetryBackoff, int poisonAttempts) {
            return new KafkaConsumerProfile(p.tenant(), p.name(), p.bootstrapServers(), p.clientDnsLookup(), p.tls(),
                    p.saslMechanism(), p.username(), p.credentialRef(), p.clientId(), p.groupLogicalName(), p.groupId(),
                    p.staticMemberId(), p.topics(), p.topicPattern(), p.headers(), p.assignmentStrategy(),
                    p.autoOffsetReset(), p.isolationLevel(), p.startupTimeoutMs(), pollTimeout, p.maxPollIntervalMs(),
                    p.sessionTimeoutMs(), p.heartbeatIntervalMs(), maxInFlight, p.maxFetchBytes(),
                    p.maxPartitionFetchBytes(), p.maxRecordBytes(), p.maxKeyBytes(), p.maxValueBytes(),
                    p.maxHeaderBytes(), drainTimeout, retryBackoff, maxRetryBackoff, poisonAttempts,
                    p.poisonPolicy(), p.deadLetterTopic());
        }
    }

    private static final class SourceFailure extends RuntimeException {
        private final String safeReason;
        private SourceFailure(String safeReason) { super(safeReason); this.safeReason = safeReason; }
    }
}
