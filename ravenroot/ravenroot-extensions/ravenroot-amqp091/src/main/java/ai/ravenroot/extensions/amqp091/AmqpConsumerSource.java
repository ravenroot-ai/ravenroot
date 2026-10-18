package ai.ravenroot.extensions.amqp091;

import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.SourceStartException;
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
import java.util.concurrent.Executor;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;

/** One serial poll owner controls every channel operation and generation transition. */
final class AmqpConsumerSource implements InboundSource {
    enum State { STOPPED, STARTING, READY, BACKING_OFF, RECONNECTING, STOPPING, FAILED }
    private static final AmqpConsumerLeaseRegistry RESOURCE_LEASES = new AmqpConsumerLeaseRegistry();
    private static final AmqpSubscriptionLeaseRegistry SUBSCRIPTION_LEASES = new AmqpSubscriptionLeaseRegistry();

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
    /** Serializes owner generation and channel calls without pinning a Java 21 consumer carrier. */
    private final ReentrantLock lifecycle = new ReentrantLock();
    private final Condition lifecycleChanged = lifecycle.newCondition();
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
        lifecycle.lock();
        try {
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
        } finally {
            lifecycle.unlock();
        }
    }

    @Override public CompletionStage<Void> stop() {
        AmqpConsumerProtocol.Owner current;
        lifecycle.lock();
        try {
            if (state == State.STOPPED) return CompletableFuture.completedFuture(null);
            if (state == State.STOPPING) return stopFlight;
            if (owner == null && stopFlight.isDone()) {
                stopRequested = true; state = State.STOPPED;
                return CompletableFuture.completedFuture(null);
            }
            stopRequested = true; state = State.STOPPING; generation++; current = owner;
            lifecycleChanged.signalAll();
        } finally {
            lifecycle.unlock();
        }
        if (current != null) try { current.wakeup(); } catch (RuntimeException ignored) { }
        return stopFlight;
    }

    @Override public CompletionStage<Void> rollback() { return stop(); }
    @Override public CompletionStage<Void> shutdown() { return stop(); }
    State state() { return state; }
    long generation() {
        lifecycle.lock();
        try {
            return generation;
        } finally {
            lifecycle.unlock();
        }
    }

    private void run(InboundSourceContext context, CompletableFuture<Void> ready,
                     CompletableFuture<Void> stopped) {
        AmqpProfile profile = null;
        AmqpConsumerPolicy policy = null;
        Settings settings = null;
        SecretValue secret = null;
        char[] password = null;
        AmqpConsumerLeaseRegistry.Lease resourceLease = null;
        AmqpSubscriptionLeaseRegistry.Lease subscriptionLease = null;
        Map<String, Integer> attempts = new HashMap<>();
        FailureStreak reconnectFailures = new FailureStreak();
        try {
            String profileName = configuration.requiredProperty("brokerProfile");
            profile = resolveProfile(context.identity().tenantId(), profileName);
            policy = resolvePolicy(context.identity().tenantId(), profileName);
            settings = Settings.resolve(configuration, policy);
            probeDurability(context, profile.timeoutMs());
            if (settings.destination.mode() == AmqpConsumerProtocol.Destination.Mode.CONSUMER) {
                String leaseKey = policy.tenant() + "\0" + policy.profile() + "\0" + policy.queue();
                resourceLease = RESOURCE_LEASES.tryAcquire(leaseKey, settings.resourceMode);
                if (resourceLease == null)
                    throw sourceFailure(AmqpSourceStartFailure.AMQP_CONSUMER_ALREADY_ACTIVE);
            } else {
                subscriptionLease = SUBSCRIPTION_LEASES.tryAcquire(policy.tenant(), policy.profile(),
                        policy.maxEphemeralSubscriptions());
                if (subscriptionLease == null)
                    throw sourceFailure(AmqpSourceStartFailure.AMQP_SUBSCRIPTION_CAPACITY_EXHAUSTED);
            }
            Optional<SecretValue> resolved = credentials.resolve(profile.credentialRef());
            if (resolved == null || resolved.isEmpty()) throw sourceFailure(AmqpSourceStartFailure.CREDENTIAL_UNAVAILABLE);
            secret = resolved.get(); password = secret.copy();
            while (!stopRequested) {
                long sessionGeneration = nextGeneration();
                try {
                    AmqpConsumerProtocol.Owner opened = protocol.open(profile, policy, password,
                            settings.prefetch, settings.destination);
                    if (opened == null) throw sourceFailure(AmqpSourceStartFailure.AMQP_CONSUMER_UNAVAILABLE);
                    owner = opened;
                    claimReady(context, ready);
                    consume(context, policy, settings, attempts, reconnectFailures, sessionGeneration);
                } catch (AmqpConsumerProtocol.Failure failure) {
                    if (stopRequested) break;
                    if (failure.permanent() || !ready.isDone())
                        throw sourceFailure(AmqpSourceStartFailure.AMQP_CONSUMER_FAILED, failure);
                    degrade(context, "amqp-consumer-reconnecting", State.RECONNECTING);
                    closeOwner(settings.drainTimeoutMs);
                    awaitReconnectBackoff(jitteredReconnectBackoff(settings, reconnectFailures.failed()));
                } finally {
                    closeOwner(settings.drainTimeoutMs);
                }
            }
        } catch (SourceStartException failure) {
            fail(context, ready, failure);
        } catch (RuntimeException failure) {
            fail(context, ready, failure);
        } finally {
            lifecycle.lock();
            try {
                generation++;
                owner = null;
            } finally {
                lifecycle.unlock();
            }
            if (password != null) java.util.Arrays.fill(password, '\0');
            if (secret != null) secret.close();
            if (resourceLease != null) resourceLease.close();
            if (subscriptionLease != null) subscriptionLease.close();
            lifecycle.lock();
            try {
                if (!ready.isDone()) ready.completeExceptionally(
                        new IllegalStateException("AMQP source stopped before readiness"));
                state = stopRequested ? State.STOPPED : State.FAILED;
                stopped.complete(null); lifecycleChanged.signalAll();
            } finally {
                lifecycle.unlock();
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
        try { projected = AmqpDeliveryEvent.project(delivery, policy, settings.destination, 1); }
        catch (AmqpDeliveryEvent.Invalid invalid) {
            context.reportDegraded(invalid.safeReason());
            nackIfCurrent(sessionGeneration, delivery.deliveryTag(), false);
            return;
        }
        int attempt = attempts.merge(projected.idempotentKey(), 1, Integer::sum);
        projected = AmqpDeliveryEvent.project(delivery, policy, settings.destination, attempt);
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
            throw sourceFailure(AmqpSourceStartFailure.DURABLE_INGRESS_LOST);
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
        lifecycle.lock();
        try {
            if (stopRequested || state == State.STOPPING) throw sourceFailure(AmqpSourceStartFailure.STARTUP_CANCELLED);
            state = State.READY; context.reportHealthy(); ready.complete(null);
        } finally {
            lifecycle.unlock();
        }
    }

    private void healthy(InboundSourceContext context) {
        lifecycle.lock();
        try {
            if (!stopRequested && state != State.READY) { state = State.READY; context.reportHealthy(); }
        } finally {
            lifecycle.unlock();
        }
    }

    private void degrade(InboundSourceContext context, String reason, State target) {
        lifecycle.lock();
        try {
            if (stopRequested) return;
            state = target; context.reportDegraded(reason);
        } finally {
            lifecycle.unlock();
        }
    }

    private void fail(InboundSourceContext context, CompletableFuture<Void> ready, RuntimeException failure) {
        lifecycle.lock();
        try {
            if (stopRequested || state == State.STOPPING) return;
            state = State.FAILED;
            if (failure instanceof SourceStartException classified) context.reportDegraded(classified.code());
            else if (ready.isDone()) context.reportDegraded("amqp-consumer-failed");
            ready.completeExceptionally(failure);
        } finally {
            lifecycle.unlock();
        }
    }

    private void closeOwner(int timeoutMs) {
        AmqpConsumerProtocol.Owner current = owner;
        owner = null;
        if (current != null) try { current.close(Duration.ofMillis(timeoutMs)); }
        catch (RuntimeException ignored) { }
    }

    private long nextGeneration() {
        lifecycle.lock();
        try {
            return ++generation;
        } finally {
            lifecycle.unlock();
        }
    }

    private boolean current(long expected) {
        lifecycle.lock();
        try {
            return !stopRequested && generation == expected;
        } finally {
            lifecycle.unlock();
        }
    }

    private boolean ackIfCurrent(long expected, long deliveryTag) throws AmqpConsumerProtocol.Failure {
        lifecycle.lock();
        try {
            if (stopRequested || generation != expected || owner == null) return false;
            owner.ack(deliveryTag);
            return true;
        } finally {
            lifecycle.unlock();
        }
    }

    private boolean nackIfCurrent(long expected, long deliveryTag, boolean requeue)
            throws AmqpConsumerProtocol.Failure {
        lifecycle.lock();
        try {
            if (stopRequested || generation != expected || owner == null) return false;
            owner.nack(deliveryTag, requeue);
            return true;
        } finally {
            lifecycle.unlock();
        }
    }

    private void awaitBackoff(int millis) {
        lifecycle.lock();
        try {
            if (stopRequested) return;
            try { lifecycleChanged.await(millis, java.util.concurrent.TimeUnit.MILLISECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); stopRequested = true; }
        } finally {
            lifecycle.unlock();
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
                    .orElseThrow(() -> sourceFailure(AmqpSourceStartFailure.AMQP_PROFILE_UNAVAILABLE));
            if (!profile.tenant().equals(tenant) || !profile.name().equals(name))
                throw sourceFailure(AmqpSourceStartFailure.AMQP_PROFILE_UNAVAILABLE);
            destinationPolicy.requireAllowedDestination(
                    "amqp091", tenant + "/" + name, profile.host(), profile.port());
            return profile;
        }
        catch (SourceStartException failure) { throw failure; }
        catch (SecurityException refused) {
            throw sourceFailure("OUTBOUND_TRANSPORT_ENCRYPTION_REQUIRED".equals(refused.getMessage())
                    ? AmqpSourceStartFailure.OUTBOUND_TRANSPORT_ENCRYPTION_REQUIRED
                    : AmqpSourceStartFailure.OUTBOUND_DESTINATION_POLICY_REFUSED, refused);
        }
        catch (RuntimeException failure) {
            throw sourceFailure(AmqpSourceStartFailure.AMQP_PROFILE_UNAVAILABLE, failure);
        }
    }

    private AmqpConsumerPolicy resolvePolicy(String tenant, String name) {
        try {
            AmqpConsumerPolicy policy = policies.resolve(tenant, name)
                    .orElseThrow(() -> sourceFailure(AmqpSourceStartFailure.AMQP_CONSUMER_POLICY_UNAVAILABLE));
            if (!policy.tenant().equals(tenant) || !policy.profile().equals(name))
                throw sourceFailure(AmqpSourceStartFailure.AMQP_CONSUMER_POLICY_UNAVAILABLE);
            return policy;
        }
        catch (SourceStartException failure) { throw failure; }
        catch (RuntimeException failure) {
            throw sourceFailure(AmqpSourceStartFailure.AMQP_CONSUMER_POLICY_UNAVAILABLE, failure);
        }
    }

    private static void probeDurability(InboundSourceContext context, int timeoutMs) {
        try { context.ingress().sourceCheckpoint(context.identity(), context.nodeId()).toCompletableFuture()
                .get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS); }
        catch (Exception failure) {
            throw sourceFailure(AmqpSourceStartFailure.DURABLE_INGRESS_REQUIRED, failure);
        }
    }

    private record Settings(int prefetch, int retryBackoffMs, int maxRetryBackoffMs,
                            int poisonAttempts, int drainTimeoutMs,
                            AmqpConsumerLeaseRegistry.Mode resourceMode,
                            AmqpConsumerProtocol.Destination destination) {
        static Settings resolve(NodeConfiguration c, AmqpConsumerPolicy policy) {
            if (!AmqpConsumeNodeBehavior.knownConfiguration().containsAll(c.properties().keySet()))
                throw sourceFailure(AmqpSourceStartFailure.UNKNOWN_GRAPH_PROPERTY);
            String mode = c.property("mode", "Consumer");
            AmqpConsumerProtocol.Destination destination;
            if ("Consumer".equals(mode)) {
                String queue = c.property("queue", "");
                if (policy.queue().isEmpty())
                    throw sourceFailure(AmqpSourceStartFailure.QUEUE_NOT_AUTHORIZED);
                if (!queue.isEmpty() && !queue.equals(policy.queue()))
                    throw sourceFailure(AmqpSourceStartFailure.QUEUE_NOT_AUTHORIZED);
                if (c.properties().containsKey("exchange") || c.properties().containsKey("bindingPattern"))
                    throw sourceFailure(AmqpSourceStartFailure.INVALID_DESTINATION_MODE);
                destination = AmqpConsumerProtocol.Destination.consumer(policy.queue());
            } else if ("Subscriber".equals(mode)) {
                if (c.properties().containsKey("queue") || c.properties().containsKey("resourceMode"))
                    throw sourceFailure(AmqpSourceStartFailure.INVALID_DESTINATION_MODE);
                String exchange = c.requiredProperty("exchange");
                String bindingPattern = c.requiredProperty("bindingPattern");
                if (!policy.allowsSubscription(exchange, bindingPattern))
                    throw sourceFailure(AmqpSourceStartFailure.SUBSCRIPTION_NOT_AUTHORIZED);
                destination = AmqpConsumerProtocol.Destination.subscriber(exchange, bindingPattern);
            } else {
                throw sourceFailure(AmqpSourceStartFailure.INVALID_DESTINATION_MODE);
            }
            int prefetch = tighten(c.property("prefetch", ""), policy.prefetch(), 1,
                    AmqpSourceStartFailure.INVALID_PREFETCH);
            prefetch = Math.min(prefetch,
                    tighten(c.property("maxInFlight", ""), policy.prefetch(), 1,
                            AmqpSourceStartFailure.INVALID_MAX_IN_FLIGHT));
            int retry = tighten(c.property("retryBackoffMs", ""), policy.retryBackoffMs(),
                    AmqpConsumerPolicy.MIN_RETRY_BACKOFF_MS,
                    AmqpSourceStartFailure.INVALID_RETRY_BACKOFF);
            int maximum = tighten(c.property("maxRetryBackoffMs", ""), policy.maxRetryBackoffMs(),
                    AmqpConsumerPolicy.MIN_MAX_RETRY_BACKOFF_MS,
                    AmqpSourceStartFailure.INVALID_MAX_RETRY_BACKOFF);
            if (maximum < retry) throw sourceFailure(AmqpSourceStartFailure.INVALID_MAX_RETRY_BACKOFF);
            int poison = tighten(c.property("poisonAttempts", ""), policy.poisonAttempts(), 1,
                    AmqpSourceStartFailure.INVALID_POISON_ATTEMPTS);
            int drain = tighten(c.property("drainTimeoutMs", ""), policy.drainTimeoutMs(), 0,
                    AmqpSourceStartFailure.INVALID_DRAIN_TIMEOUT);
            String poisonPolicy = c.property("poisonPolicy", "profile");
            if (!poisonPolicy.equals("profile") && !poisonPolicy.equals("dead-letter"))
                throw sourceFailure(AmqpSourceStartFailure.INVALID_POISON_POLICY);
            String effectivePoison = poisonPolicy.equals("profile") ? policy.poisonPolicy() : poisonPolicy;
            if (!effectivePoison.equals(policy.poisonPolicy())) throw sourceFailure(AmqpSourceStartFailure.POISON_POLICY_FORBIDDEN);
            // Hidden properties are deliberately not read unless their condition holds.
            if (poisonPolicy.equals("dead-letter") && !c.requiredProperty("deadLetterMode").equals("broker-dlx"))
                throw sourceFailure(AmqpSourceStartFailure.INVALID_DEAD_LETTER_MODE);
            if (!c.property("checkpointPolicy", "require-durable").equals("require-durable"))
                throw sourceFailure(AmqpSourceStartFailure.INVALID_CHECKPOINT_POLICY);
            AmqpConsumerLeaseRegistry.Mode resourceMode = switch (c.property("resourceMode", "shared")) {
                case "shared" -> AmqpConsumerLeaseRegistry.Mode.SHARED;
                case "exclusive" -> AmqpConsumerLeaseRegistry.Mode.EXCLUSIVE;
                default -> throw sourceFailure(AmqpSourceStartFailure.INVALID_RESOURCE_MODE);
            };
            return new Settings(prefetch, retry, maximum, poison, drain, resourceMode, destination);
        }

        private static int tighten(String raw, int ceiling, int minimum, AmqpSourceStartFailure reason) {
            if (raw == null || raw.isBlank()) return ceiling;
            try {
                int value = Integer.parseInt(raw);
                if (value > ceiling || value < minimum) throw new NumberFormatException();
                return value;
            } catch (RuntimeException invalid) { throw sourceFailure(reason); }
        }
    }

    /** Validates graph-authored syntax and absolute bounds before consulting operator authority. */
    static void validateTemplateAuthoredSettings(NodeConfiguration configuration) {
        if (!AmqpConsumeNodeBehavior.knownConfiguration().containsAll(configuration.properties().keySet())) {
            throw sourceFailure(AmqpSourceStartFailure.UNKNOWN_GRAPH_PROPERTY);
        }
        String queue = configuration.property("queue", "");
        if (!queue.isEmpty() && (queue.length() > 255 || !AmqpWireLimits.isShortstr(queue)
                || queue.codePoints().anyMatch(Character::isISOControl))) {
            throw sourceFailure(AmqpSourceStartFailure.QUEUE_NOT_AUTHORIZED);
        }
        String mode = configuration.property("mode", "Consumer");
        if (!mode.equals("Consumer") && !mode.equals("Subscriber"))
            throw sourceFailure(AmqpSourceStartFailure.INVALID_DESTINATION_MODE);
        for (String name : java.util.List.of("exchange", "bindingPattern")) {
            String value = configuration.property(name, "");
            if (!value.isEmpty() && (value.length() > 255 || !AmqpWireLimits.isShortstr(value)
                    || value.codePoints().anyMatch(Character::isISOControl))) {
                throw sourceFailure(AmqpSourceStartFailure.SUBSCRIPTION_NOT_AUTHORIZED);
            }
        }
        if (mode.equals("Subscriber") && (configuration.property("exchange", "").isEmpty()
                || configuration.property("bindingPattern", "").isEmpty()))
            throw sourceFailure(AmqpSourceStartFailure.INVALID_DESTINATION_MODE);
        authoredInteger(configuration, "prefetch", 1, 1_024,
                AmqpSourceStartFailure.INVALID_PREFETCH);
        authoredInteger(configuration, "maxInFlight", 1, 1_024,
                AmqpSourceStartFailure.INVALID_MAX_IN_FLIGHT);
        int retry = authoredInteger(configuration, "retryBackoffMs",
                AmqpConsumerPolicy.MIN_RETRY_BACKOFF_MS, 60_000,
                AmqpSourceStartFailure.INVALID_RETRY_BACKOFF);
        int maximum = authoredInteger(configuration, "maxRetryBackoffMs",
                AmqpConsumerPolicy.MIN_MAX_RETRY_BACKOFF_MS, 60_000,
                AmqpSourceStartFailure.INVALID_MAX_RETRY_BACKOFF);
        if (retry >= 0 && maximum >= 0 && maximum < retry) {
            throw sourceFailure(AmqpSourceStartFailure.INVALID_MAX_RETRY_BACKOFF);
        }
        authoredInteger(configuration, "drainTimeoutMs", 0, 30_000,
                AmqpSourceStartFailure.INVALID_DRAIN_TIMEOUT);
        authoredInteger(configuration, "poisonAttempts", 1, 100,
                AmqpSourceStartFailure.INVALID_POISON_ATTEMPTS);
        String poisonPolicy = configuration.property("poisonPolicy", "profile");
        if (!poisonPolicy.equals("profile") && !poisonPolicy.equals("dead-letter")) {
            throw sourceFailure(AmqpSourceStartFailure.INVALID_POISON_POLICY);
        }
        if (poisonPolicy.equals("dead-letter")
                && !configuration.property("deadLetterMode", "").equals("broker-dlx")) {
            throw sourceFailure(AmqpSourceStartFailure.INVALID_DEAD_LETTER_MODE);
        }
        if (!configuration.property("checkpointPolicy", "require-durable").equals("require-durable")) {
            throw sourceFailure(AmqpSourceStartFailure.INVALID_CHECKPOINT_POLICY);
        }
    }

    private static int authoredInteger(NodeConfiguration configuration, String name, int minimum, int maximum,
                                       AmqpSourceStartFailure reason) {
        String raw = configuration.property(name, "");
        if (raw.isEmpty()) return -1;
        try {
            int value = Integer.parseInt(raw);
            if (value < minimum || value > maximum) throw new NumberFormatException();
            return value;
        } catch (RuntimeException invalid) {
            throw sourceFailure(reason);
        }
    }

    /** Reuses source-start authority checks without opening a connection or resolving a credential. */
    static void validateTemplateSettings(NodeConfiguration configuration, AmqpConsumerPolicy policy) {
        Settings.resolve(configuration, policy);
    }

    private static final class FailureStreak {
        private int attempts;
        int failed() { if (attempts < Integer.MAX_VALUE) attempts++; return attempts; }
        void reset() { attempts = 0; }
    }

    private static SourceStartException sourceFailure(AmqpSourceStartFailure code) {
        return new SourceStartException(code);
    }

    private static SourceStartException sourceFailure(AmqpSourceStartFailure code, Throwable cause) {
        return new SourceStartException(code, cause);
    }
}
