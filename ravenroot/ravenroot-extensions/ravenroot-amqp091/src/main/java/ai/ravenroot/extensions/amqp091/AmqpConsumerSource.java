package ai.ravenroot.extensions.amqp091;

import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.deployment.IngressReceipt;
import ai.ravenroot.api.deployment.IngressTarget;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.security.CredentialResolver;
import ai.ravenroot.api.security.egress.ReservedNetworkPolicy;
import ai.ravenroot.api.security.SecretValue;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;

/** One serial poll owner controls every channel operation and generation transition. */
final class AmqpConsumerSource implements InboundSource {
    enum State { STOPPED, STARTING, READY, BACKING_OFF, RECONNECTING, STOPPING, FAILED }
    private static final ConcurrentHashMap<String, Object> LEASES = new ConcurrentHashMap<>();

    private final NodeConfiguration configuration;
    private final CredentialResolver credentials;
    private final AmqpProfileResolver profiles;
    private final AmqpConsumerPolicyResolver policies;
    private final AmqpConsumerProtocol protocol;
    private final Executor executor;
    private final IntConsumer reconnectBackoffObserver;
    private final DoubleSupplier reconnectJitter;
    private final ReservedNetworkPolicy destinationPolicy;
    @SuppressWarnings("unused") private final Clock clock;
    private final Object lifecycle = new Object();
    private volatile State state = State.STOPPED;
    private volatile AmqpConsumerProtocol.Owner owner;
    private boolean stopRequested;
    private long generation;
    private CompletableFuture<Void> startFlight = CompletableFuture.completedFuture(null);
    private CompletableFuture<Void> stopFlight = CompletableFuture.completedFuture(null);

    AmqpConsumerSource(NodeConfiguration configuration, CredentialResolver credentials,
                       AmqpProfileResolver profiles, AmqpConsumerPolicyResolver policies,
                       AmqpConsumerProtocol protocol, Executor executor, Clock clock) {
        this(configuration, credentials, profiles, policies, protocol, executor, clock, ignored -> { },
                new java.security.SecureRandom()::nextDouble);
    }

    AmqpConsumerSource(NodeConfiguration configuration, CredentialResolver credentials,
                       AmqpProfileResolver profiles, AmqpConsumerPolicyResolver policies,
                       AmqpConsumerProtocol protocol, Executor executor, Clock clock,
                       IntConsumer reconnectBackoffObserver, DoubleSupplier reconnectJitter) {
        this(configuration, credentials, profiles, policies, protocol, executor, clock,
                reconnectBackoffObserver, reconnectJitter,
                ReservedNetworkPolicy.fromEnvironment(System.getenv()));
    }

    AmqpConsumerSource(NodeConfiguration configuration, CredentialResolver credentials,
                       AmqpProfileResolver profiles, AmqpConsumerPolicyResolver policies,
                       AmqpConsumerProtocol protocol, Executor executor, Clock clock,
                       IntConsumer reconnectBackoffObserver, DoubleSupplier reconnectJitter,
                       ReservedNetworkPolicy destinationPolicy) {
        this.configuration = Objects.requireNonNull(configuration); this.credentials = Objects.requireNonNull(credentials);
        this.profiles = Objects.requireNonNull(profiles); this.policies = Objects.requireNonNull(policies);
        this.protocol = Objects.requireNonNull(protocol); this.executor = Objects.requireNonNull(executor);
        this.clock = Objects.requireNonNull(clock);
        this.reconnectBackoffObserver = Objects.requireNonNull(reconnectBackoffObserver);
        this.reconnectJitter = Objects.requireNonNull(reconnectJitter);
        this.destinationPolicy = Objects.requireNonNull(destinationPolicy);
    }

    @Override public CompletionStage<Void> start(InboundSourceContext context) {
        Objects.requireNonNull(context);
        synchronized (lifecycle) {
            if (owner != null || !stopFlight.isDone()) {
                if (state == State.STARTING) return startFlight;
                if (state == State.READY || state == State.BACKING_OFF || state == State.RECONNECTING)
                    return CompletableFuture.completedFuture(null);
                return stopFlight.thenCompose(ignored -> start(context));
            }
            if (state == State.STARTING) return startFlight;
            if (state == State.READY || state == State.BACKING_OFF || state == State.RECONNECTING)
                return CompletableFuture.completedFuture(null);
            stopRequested = false; state = State.STARTING;
            startFlight = new CompletableFuture<>(); stopFlight = new CompletableFuture<>();
            executor.execute(() -> run(context, startFlight, stopFlight));
            return startFlight;
        }
    }

    @Override public CompletionStage<Void> stop() {
        AmqpConsumerProtocol.Owner current;
        synchronized (lifecycle) {
            if (state == State.STOPPED) return CompletableFuture.completedFuture(null);
            if (state == State.STOPPING) return stopFlight;
            if (owner == null && stopFlight.isDone()) {
                stopRequested = true; state = State.STOPPED;
                return CompletableFuture.completedFuture(null);
            }
            stopRequested = true; state = State.STOPPING; generation++; current = owner;
            lifecycle.notifyAll();
        }
        if (current != null) try { current.wakeup(); } catch (RuntimeException ignored) { }
        return stopFlight;
    }

    @Override public CompletionStage<Void> rollback() { return stop(); }
    @Override public CompletionStage<Void> shutdown() { return stop(); }
    State state() { return state; }
    long generation() { synchronized (lifecycle) { return generation; } }

    private void run(InboundSourceContext context, CompletableFuture<Void> ready,
                     CompletableFuture<Void> stopped) {
        AmqpProfile profile = null;
        AmqpConsumerPolicy policy = null;
        Settings settings = null;
        SecretValue secret = null;
        char[] password = null;
        Object lease = null;
        String leaseKey = null;
        Map<String, Integer> attempts = new HashMap<>();
        FailureStreak reconnectFailures = new FailureStreak();
        try {
            String profileName = configuration.requiredProperty("brokerProfile");
            profile = resolveProfile(context.identity().tenantId(), profileName);
            policy = resolvePolicy(context.identity().tenantId(), profileName);
            settings = Settings.resolve(configuration, policy);
            probeDurability(context, profile.timeoutMs());
            leaseKey = policy.tenant() + "\0" + policy.profile() + "\0" + policy.queue();
            lease = new Object();
            if (LEASES.putIfAbsent(leaseKey, lease) != null) throw new SourceFailure("amqp-consumer-already-active");
            Optional<SecretValue> resolved = credentials.resolve(profile.credentialRef());
            if (resolved == null || resolved.isEmpty()) throw new SourceFailure("credential-unavailable");
            secret = resolved.get(); password = secret.copy();
            while (!stopRequested) {
                long sessionGeneration = nextGeneration();
                try {
                    AmqpConsumerProtocol.Owner opened = protocol.open(profile, policy, password, settings.prefetch);
                    if (opened == null) throw new SourceFailure("amqp-consumer-unavailable");
                    owner = opened;
                    claimReady(context, ready);
                    consume(context, policy, settings, attempts, reconnectFailures, sessionGeneration);
                } catch (AmqpConsumerProtocol.Failure failure) {
                    if (stopRequested) break;
                    if (failure.permanent() || !ready.isDone()) throw new SourceFailure(failure.getMessage());
                    degrade(context, "amqp-consumer-reconnecting", State.RECONNECTING);
                    closeOwner(settings.drainTimeoutMs);
                    awaitReconnectBackoff(jitteredReconnectBackoff(settings, reconnectFailures.failed()));
                } finally {
                    closeOwner(settings.drainTimeoutMs);
                }
            }
        } catch (SourceFailure failure) {
            fail(context, ready, failure.safeReason);
        } catch (RuntimeException failure) {
            fail(context, ready, "amqp-consumer-failed");
        } finally {
            synchronized (lifecycle) { generation++; owner = null; }
            if (password != null) java.util.Arrays.fill(password, '\0');
            if (secret != null) secret.close();
            if (leaseKey != null && lease != null) LEASES.remove(leaseKey, lease);
            synchronized (lifecycle) {
                if (!ready.isDone()) ready.completeExceptionally(
                        new IllegalStateException("AMQP source stopped before readiness"));
                state = stopRequested ? State.STOPPED : State.FAILED;
                stopped.complete(null); lifecycle.notifyAll();
            }
        }
    }

    private void consume(InboundSourceContext context, AmqpConsumerPolicy policy, Settings settings,
                         Map<String, Integer> attempts, FailureStreak reconnectFailures, long sessionGeneration)
            throws AmqpConsumerProtocol.Failure {
        while (!stopRequested && current(sessionGeneration)) {
            AmqpConsumerProtocol.Event event = owner.poll(Duration.ofMillis(Math.min(1_000, settings.retryBackoffMs)));
            if (event instanceof AmqpConsumerProtocol.Disconnected disconnected)
                throw new AmqpConsumerProtocol.Failure(false, disconnected.safeReason());
            if (event instanceof AmqpConsumerProtocol.Rejected rejected) {
                context.reportDegraded(rejected.safeReason());
                nackIfCurrent(sessionGeneration, rejected.deliveryTag(), false);
                continue;
            }
            if (!(event instanceof AmqpConsumerProtocol.Delivery delivery)) continue;
            process(context, policy, settings, attempts, reconnectFailures, sessionGeneration, delivery);
        }
    }

    private void process(InboundSourceContext context, AmqpConsumerPolicy policy, Settings settings,
                         Map<String, Integer> attempts, FailureStreak reconnectFailures, long sessionGeneration,
                         AmqpConsumerProtocol.Delivery delivery) throws AmqpConsumerProtocol.Failure {
        AmqpDeliveryEvent.Projected projected;
        try { projected = AmqpDeliveryEvent.project(delivery, policy, 1); }
        catch (AmqpDeliveryEvent.Invalid invalid) {
            context.reportDegraded(invalid.safeReason());
            nackIfCurrent(sessionGeneration, delivery.deliveryTag(), false);
            return;
        }
        int attempt = attempts.merge(projected.idempotentKey(), 1, Integer::sum);
        projected = AmqpDeliveryEvent.project(delivery, policy, attempt);
        IngressReceipt receipt = context.ingress().offerDurably(context.identity(), IngressTarget.start(),
                projected.payload(), context.nodeId(), projected.idempotentKey());
        if (!current(sessionGeneration)) return;
        if (receipt.acknowledgeable()) {
            if (ackIfCurrent(sessionGeneration, delivery.deliveryTag())) {
                attempts.remove(projected.idempotentKey()); reconnectFailures.reset(); healthy(context);
            }
            return;
        }
        if (receipt instanceof IngressReceipt.VolatileCustody)
            throw new SourceFailure("durable-ingress-lost");
        if (attempt >= settings.poisonAttempts) {
            context.reportDegraded("amqp-delivery-dead-lettered");
            if (nackIfCurrent(sessionGeneration, delivery.deliveryTag(), false))
                attempts.remove(projected.idempotentKey());
            return;
        }
        degrade(context, receipt instanceof IngressReceipt.Ambiguous
                ? "ambiguous-ingress" : "ingress-refused", State.BACKING_OFF);
        awaitBackoff(backoff(settings, attempt));
        if (!current(sessionGeneration)) return;
        if (receipt instanceof IngressReceipt.Refused) nackIfCurrent(sessionGeneration, delivery.deliveryTag(), true);
        else process(context, policy, settings, attempts, reconnectFailures, sessionGeneration, delivery);
    }

    private void claimReady(InboundSourceContext context, CompletableFuture<Void> ready) {
        synchronized (lifecycle) {
            if (stopRequested || state == State.STOPPING) throw new SourceFailure("startup-cancelled");
            state = State.READY; context.reportHealthy(); ready.complete(null);
        }
    }

    private void healthy(InboundSourceContext context) {
        synchronized (lifecycle) {
            if (!stopRequested && state != State.READY) { state = State.READY; context.reportHealthy(); }
        }
    }

    private void degrade(InboundSourceContext context, String reason, State target) {
        synchronized (lifecycle) {
            if (stopRequested) return;
            state = target; context.reportDegraded(reason);
        }
    }

    private void fail(InboundSourceContext context, CompletableFuture<Void> ready, String reason) {
        synchronized (lifecycle) {
            if (stopRequested || state == State.STOPPING) return;
            state = State.FAILED; context.reportDegraded(reason);
            ready.completeExceptionally(new IllegalStateException(reason));
        }
    }

    private void closeOwner(int timeoutMs) {
        AmqpConsumerProtocol.Owner current = owner;
        owner = null;
        if (current != null) try { current.close(Duration.ofMillis(timeoutMs)); }
        catch (RuntimeException ignored) { }
    }

    private long nextGeneration() { synchronized (lifecycle) { return ++generation; } }
    private boolean current(long expected) { synchronized (lifecycle) { return !stopRequested && generation == expected; } }

    private boolean ackIfCurrent(long expected, long deliveryTag) throws AmqpConsumerProtocol.Failure {
        synchronized (lifecycle) {
            if (stopRequested || generation != expected || owner == null) return false;
            owner.ack(deliveryTag);
            return true;
        }
    }

    private boolean nackIfCurrent(long expected, long deliveryTag, boolean requeue)
            throws AmqpConsumerProtocol.Failure {
        synchronized (lifecycle) {
            if (stopRequested || generation != expected || owner == null) return false;
            owner.nack(deliveryTag, requeue);
            return true;
        }
    }

    private void awaitBackoff(int millis) {
        synchronized (lifecycle) {
            if (stopRequested) return;
            try { lifecycle.wait(millis); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); stopRequested = true; }
        }
    }

    private void awaitReconnectBackoff(int millis) {
        try { reconnectBackoffObserver.accept(millis); } catch (RuntimeException ignored) { }
        awaitBackoff(millis);
    }

    private int jitteredReconnectBackoff(Settings settings, int attempt) {
        int cap = backoff(settings, attempt == Integer.MAX_VALUE ? attempt : attempt + 1);
        int minimum = Math.max(settings.retryBackoffMs, (cap + 1) / 2);
        if (minimum >= cap) return cap;
        double sample;
        try { sample = reconnectJitter.getAsDouble(); }
        catch (RuntimeException unavailable) { sample = 0.5d; }
        if (!Double.isFinite(sample) || sample < 0.0d || sample >= 1.0d) sample = 0.5d;
        long width = (long) cap - minimum + 1;
        long offset = Math.min(width - 1, (long) (sample * width));
        return minimum + (int) offset;
    }

    private static int backoff(Settings settings, int attempt) {
        long multiplier = 1L << Math.min(30, Math.max(0, attempt - 1));
        return (int) Math.min(settings.maxRetryBackoffMs, settings.retryBackoffMs * multiplier);
    }

    private AmqpProfile resolveProfile(String tenant, String name) {
        try {
            AmqpProfile profile = profiles.resolve(tenant, name)
                    .orElseThrow(() -> new SourceFailure("amqp-profile-unavailable"));
            if (!profile.tenant().equals(tenant) || !profile.name().equals(name))
                throw new SourceFailure("amqp-profile-unavailable");
            destinationPolicy.requireAllowedLiteral(profile.host());
            return profile;
        }
        catch (SourceFailure failure) { throw failure; }
        catch (RuntimeException failure) { throw new SourceFailure("amqp-profile-unavailable"); }
    }

    private AmqpConsumerPolicy resolvePolicy(String tenant, String name) {
        try {
            AmqpConsumerPolicy policy = policies.resolve(tenant, name)
                    .orElseThrow(() -> new SourceFailure("amqp-consumer-policy-unavailable"));
            if (!policy.tenant().equals(tenant) || !policy.profile().equals(name))
                throw new SourceFailure("amqp-consumer-policy-unavailable");
            return policy;
        }
        catch (SourceFailure failure) { throw failure; }
        catch (RuntimeException failure) { throw new SourceFailure("amqp-consumer-policy-unavailable"); }
    }

    private static void probeDurability(InboundSourceContext context, int timeoutMs) {
        try { context.ingress().sourceCheckpoint(context.identity(), context.nodeId()).toCompletableFuture()
                .get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS); }
        catch (Exception failure) { throw new SourceFailure("durable-ingress-required"); }
    }

    private record Settings(int prefetch, int retryBackoffMs, int maxRetryBackoffMs,
                            int poisonAttempts, int drainTimeoutMs) {
        static Settings resolve(NodeConfiguration c, AmqpConsumerPolicy policy) {
            if (!AmqpConsumeNodeBehavior.knownConfiguration().containsAll(c.properties().keySet()))
                throw new SourceFailure("unknown-graph-property");
            String queue = c.property("queue", "");
            if (!queue.isEmpty() && !queue.equals(policy.queue())) throw new SourceFailure("queue-not-authorized");
            int prefetch = tighten(c.property("prefetch", ""), policy.prefetch(), 1, "invalid-prefetch");
            prefetch = Math.min(prefetch,
                    tighten(c.property("maxInFlight", ""), policy.prefetch(), 1, "invalid-max-in-flight"));
            int retry = tighten(c.property("retryBackoffMs", ""), policy.retryBackoffMs(),
                    AmqpConsumerPolicy.MIN_RETRY_BACKOFF_MS,
                    "invalid-retry-backoff");
            int maximum = tighten(c.property("maxRetryBackoffMs", ""), policy.maxRetryBackoffMs(),
                    AmqpConsumerPolicy.MIN_MAX_RETRY_BACKOFF_MS,
                    "invalid-max-retry-backoff");
            if (maximum < retry) throw new SourceFailure("invalid-max-retry-backoff");
            int poison = tighten(c.property("poisonAttempts", ""), policy.poisonAttempts(), 1,
                    "invalid-poison-attempts");
            int drain = tighten(c.property("drainTimeoutMs", ""), policy.drainTimeoutMs(), 0,
                    "invalid-drain-timeout");
            String poisonPolicy = c.property("poisonPolicy", "profile");
            if (!poisonPolicy.equals("profile") && !poisonPolicy.equals("dead-letter"))
                throw new SourceFailure("invalid-poison-policy");
            String effectivePoison = poisonPolicy.equals("profile") ? policy.poisonPolicy() : poisonPolicy;
            if (!effectivePoison.equals(policy.poisonPolicy())) throw new SourceFailure("poison-policy-forbidden");
            // Hidden properties are deliberately not read unless their condition holds.
            if (poisonPolicy.equals("dead-letter") && !c.requiredProperty("deadLetterMode").equals("broker-dlx"))
                throw new SourceFailure("invalid-dead-letter-mode");
            if (!c.property("checkpointPolicy", "require-durable").equals("require-durable"))
                throw new SourceFailure("invalid-checkpoint-policy");
            return new Settings(prefetch, retry, maximum, poison, drain);
        }

        private static int tighten(String raw, int ceiling, int minimum, String reason) {
            if (raw == null || raw.isBlank()) return ceiling;
            try {
                int value = Integer.parseInt(raw);
                if (value > ceiling || value < minimum) throw new NumberFormatException();
                return value;
            } catch (RuntimeException invalid) { throw new SourceFailure(reason); }
        }
    }

    private static final class FailureStreak {
        private int attempts;
        int failed() { if (attempts < Integer.MAX_VALUE) attempts++; return attempts; }
        void reset() { attempts = 0; }
    }

    private static final class SourceFailure extends RuntimeException {
        private final String safeReason;
        SourceFailure(String safeReason) { super(safeReason); this.safeReason = safeReason; }
    }
}
