package ai.ravenroot.extensions.amqp091;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.security.CredentialResolver;
import ai.ravenroot.api.security.egress.ReservedNetworkPolicy;
import ai.ravenroot.api.security.SecretValue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/** Bounded AMQP 0-9-1 publisher with explicit authority and delivery-state semantics. */
public final class AmqpPublishNodeBehavior implements NodeBehavior {
    public static final String BEHAVIOR = "amqp.publish";
    private static final String VERSION = "amqp.publish.v1";
    private static final Set<String> CONFIGURATION_FIELDS = Set.of("brokerProfile", "exchange", "routingKey",
            "mandatory", "contentType", "contentEncoding", "persistent", "priority", "expirationMs",
            "messageId", "correlationId", "replyTo", "type", "appId", "headers", "confirmTimeoutMs",
            "maxConcurrency", "retries");
    private static final Set<String> PAYLOAD_FIELDS = Set.of("version", "exchange", "routingKey", "bodyText",
            "bodyJson", "bodyBase64", "contentType", "contentEncoding", "persistent", "priority",
            "expirationMs", "messageId", "correlationId", "replyTo", "type", "appId", "headers");
    private static final PayloadLimits JSON_LIMITS =
            new PayloadLimits(1_048_576, 16, 128, 512, 1_048_576, 256);

    private final CredentialResolver credentials;
    private final AmqpProfileResolver profiles;
    private final AmqpProtocol protocol;
    private final AmqpRuntimeControls controls;
    private final LongSupplier ticker;
    private final Sleeper sleeper;
    private final ReservedNetworkPolicy destinationPolicy;

    public AmqpPublishNodeBehavior() {
        this(new EnvironmentAmqpCredentialResolver(), new EnvironmentAmqpProfileResolver());
    }

    public AmqpPublishNodeBehavior(CredentialResolver credentials, AmqpProfileResolver profiles) {
        this(credentials, profiles, new RabbitMqAmqpProtocol(), AmqpRuntimeControls.PRODUCTION,
                System::nanoTime, Thread::sleep);
    }

    AmqpPublishNodeBehavior(CredentialResolver credentials, AmqpProfileResolver profiles, AmqpProtocol protocol,
                            AmqpRuntimeControls controls, LongSupplier ticker, Sleeper sleeper) {
        this(credentials, profiles, protocol, controls, ticker, sleeper,
                ReservedNetworkPolicy.fromEnvironment(System.getenv()));
    }
    AmqpPublishNodeBehavior(CredentialResolver credentials, AmqpProfileResolver profiles, AmqpProtocol protocol,
                            AmqpRuntimeControls controls, LongSupplier ticker, Sleeper sleeper,
                            ReservedNetworkPolicy destinationPolicy) {
        this.credentials = Objects.requireNonNull(credentials);
        this.profiles = Objects.requireNonNull(profiles);
        this.protocol = Objects.requireNonNull(protocol);
        this.controls = Objects.requireNonNull(controls);
        this.ticker = Objects.requireNonNull(ticker);
        this.sleeper = Objects.requireNonNull(sleeper);
        this.destinationPolicy = Objects.requireNonNull(destinationPolicy);
    }

    @Override
    public NodeTypeDescriptor descriptor() {
        List<NodePropertyDescriptor> properties = new ArrayList<>();
        properties.add(NodePropertyDescriptor.required("brokerProfile", "Broker profile", NodePropertyType.STRING,
                "Opaque tenant-scoped operator profile; endpoints and credentials never come from GraphML."));
        properties.add(optional("exchange", "Exchange", NodePropertyType.STRING, "Authorized profile default override."));
        properties.add(optional("routingKey", "Routing key", NodePropertyType.STRING, "Authorized profile default override."));
        properties.add(NodePropertyDescriptor.optional("mandatory", "Mandatory", NodePropertyType.BOOLEAN,
                "Must remain true so unroutable publications are observed.", "true"));
        properties.add(optional("contentType", "Content type", NodePropertyType.STRING, "Default AMQP content type."));
        properties.add(optional("contentEncoding", "Content encoding", NodePropertyType.STRING, "Default AMQP content encoding."));
        properties.add(NodePropertyDescriptor.optional("persistent", "Persistent", NodePropertyType.BOOLEAN,
                "May be true only when authorized by the operator profile.", "false"));
        properties.add(optional("priority", "Priority", NodePropertyType.INTEGER, "May only tighten the profile ceiling (0-9)."));
        properties.add(optional("expirationMs", "Expiration (ms)", NodePropertyType.INTEGER, "May only tighten the profile ceiling."));
        properties.add(optional("messageId", "Message id", NodePropertyType.STRING, "Default safe message identifier."));
        properties.add(optional("correlationId", "Correlation id", NodePropertyType.STRING, "Default safe correlation identifier."));
        properties.add(optional("replyTo", "Reply-to", NodePropertyType.STRING, "Must be authorized by the operator profile."));
        properties.add(optional("type", "Type", NodePropertyType.STRING, "Default AMQP message type."));
        properties.add(optional("appId", "Application id", NodePropertyType.STRING, "Default AMQP application id."));
        properties.add(optional("headers", "Approved headers", NodePropertyType.TEXT,
                "Comma-separated key=value defaults; keys must be approved by the profile."));
        properties.add(optional("confirmTimeoutMs", "Confirm timeout", NodePropertyType.INTEGER,
                "One total deadline; may only tighten the operator profile."));
        properties.add(optional("maxConcurrency", "Concurrency", NodePropertyType.INTEGER,
                "May only tighten the operator profile (1-16)."));
        properties.add(optional("retries", "Pre-publish retries", NodePropertyType.INTEGER,
                "Only proven connection-establishment failures are retried (0-3)."));
        // PERS-04 (ADR 0022). Declared, because unlike a mail or Telegram send this effect CAN
        // be made safe to repeat -- but only by the receiving side, which is why it is an author
        // assertion and not a property of this node.
        //
        // AMQP 0-9-1 has no server-side deduplication: a publish whose confirm never arrived
        // (CONFIRM_TIMEOUT, and the AMBIGUOUS result this node already reports) may or may not be in
        // the queue, and republishing it enqueues a second copy. What makes the second copy harmless
        // is a consumer that discards a message-id it has already handled -- and 'messageId' is one
        // of this node's properties, so the author is exactly the party who knows whether the
        // receiver does that. The same fact carries the overlap case: a re-dispatch replays the same
        // attempt, so it carries the same message id as the publish that may still be in flight.
        //
        // No default, so an author who has not thought about the consumer still parks.
        properties.add(ai.ravenroot.api.catalog.RecoveryRepeatabilityProperty.declaration(
                "Whether republishing this message after a crash of unknown outcome is safe. The "
                        + "broker does not deduplicate; say repeatable only where the consumer "
                        + "discards a message id it has already handled."));
        return new NodeTypeDescriptor(BEHAVIOR, "Publish AMQP message", "AMQP 0-9-1",
                "Publishes one bounded message with mandatory returns and publisher confirms.",
                "actor", false, List.copyOf(properties), Set.of("network", "credential-reference", "side-effect"));
    }

    private static NodePropertyDescriptor optional(String name, String displayName, NodePropertyType type,
                                                   String description) {
        return NodePropertyDescriptor.optional(name, displayName, type, description, "");
    }

    @Override
    public NodeAction create(NodeConfiguration configuration) {
        Objects.requireNonNull(configuration);
        ConcurrentHashMap<String, AmqpRuntimeControls.Gate> actionGates = new ConcurrentHashMap<>();
        return message -> {
            final Settings settings;
            final Payload payload;
            AmqpRuntimeControls.Admission acquired = null;
            try {
                settings = Settings.from(configuration, profiles, destinationPolicy, message.tenantId());
                payload = Payload.from(message.payload(), settings);
                acquired = controls.acquire(message.tenantId(), settings.profile, settings.maxConcurrency, actionGates);
                if (!acquired.acquired())
                    return CompletableFuture.completedFuture(result("TEMPORARY_FAILURE", "LOCAL_CAPACITY", payload, 0, null));
                if (!controls.rates.allow(message.tenantId(), settings.profile.name(), settings.profile.maxPerSecond())) {
                    acquired.release();
                    return CompletableFuture.completedFuture(result("RATE_LIMITED", "LOCAL_RATE_LIMIT", payload, 0, null));
                }
            } catch (Refusal refusal) {
                if (acquired != null) acquired.release();
                return CompletableFuture.completedFuture(result(refusal.status, refusal.reason, refusal.payload, 0, null));
            } catch (RuntimeException sanitized) {
                if (acquired != null) acquired.release();
                return CompletableFuture.completedFuture(result("REJECTED", "INVALID_REQUEST", null, 0, null));
            }
            final AmqpRuntimeControls.Admission admission = acquired;
            try {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        return execute(settings, payload);
                    } finally {
                        admission.release();
                    }
                }, controls.executor);
            } catch (RuntimeException rejected) {
                admission.release();
                return CompletableFuture.completedFuture(result("TEMPORARY_FAILURE", "LOCAL_CAPACITY", payload, 0, null));
            }
        };
    }

    private NodeResult execute(Settings settings, Payload payload) {
        long deadline = deadline(ticker.getAsLong(), settings.timeoutMs);
        SecretValue secret = null;
        char[] password = null;
        int attempts = 0;
        try {
            Optional<SecretValue> resolved;
            try {
                resolved = credentials.resolve(settings.profile.credentialRef());
            } catch (RuntimeException unavailable) {
                return result("PERMANENT_FAILURE", "CREDENTIAL_UNAVAILABLE", payload, attempts, null);
            }
            if (resolved == null || resolved.isEmpty())
                return result("PERMANENT_FAILURE", "CREDENTIAL_UNAVAILABLE", payload, attempts, null);
            secret = resolved.get();
            try {
                password = secret.copy();
            } catch (RuntimeException unavailable) {
                return result("PERMANENT_FAILURE", "CREDENTIAL_UNAVAILABLE", payload, attempts, null);
            }
            if (password.length == 0)
                return result("PERMANENT_FAILURE", "CREDENTIAL_UNAVAILABLE", payload, attempts, null);

            while (attempts <= settings.retries) {
                int remaining = remainingMillis(deadline, ticker.getAsLong());
                if (remaining == 0)
                    return result("TEMPORARY_FAILURE", "CONNECT_TIMEOUT", payload, attempts, null);
                attempts++;
                final AmqpProtocol.Session session;
                try {
                    session = connectWithinDeadline(settings.profile, password, deadline);
                } catch (ConnectDeadlineExceeded timeout) {
                    return result("TEMPORARY_FAILURE", "CONNECT_TIMEOUT", payload, attempts, null);
                } catch (AmqpProtocol.ConnectFailure failure) {
                    if (failure.kind() == AmqpProtocol.ConnectFailureKind.PERMANENT)
                        return result("PERMANENT_FAILURE", "CONNECTION_REJECTED", payload, attempts, null);
                    if (attempts > settings.retries)
                        return result("TEMPORARY_FAILURE", "CONNECT_FAILED", payload, attempts, null);
                    if (!backoff(attempts, deadline))
                        return result("TEMPORARY_FAILURE", "CONNECT_TIMEOUT", payload, attempts, null);
                    continue;
                } catch (RuntimeException sanitized) {
                    return result("TEMPORARY_FAILURE", "CONNECT_FAILED", payload, attempts, null);
                }

                PublishTracker tracker = new PublishTracker();
                AtomicBoolean abortStarted = new AtomicBoolean();
                try {
                    int publishBudget = remainingMillis(deadline, ticker.getAsLong());
                    if (publishBudget == 0)
                        return result("TEMPORARY_FAILURE", "CONNECT_TIMEOUT", payload, attempts, null);
                    publishWithinDeadline(session, payload.publication(), tracker, deadline, publishBudget,
                            abortStarted);
                    Delivery delivery = tracker.current();
                    if (delivery == null)
                        delivery = tracker.await(remainingNanos(deadline, ticker.getAsLong()));
                    return result(delivery.status, delivery.reason, payload, attempts, delivery.returnMetadata);
                } finally {
                    cleanupSession(session, deadline, abortStarted);
                }
            }
            return result("TEMPORARY_FAILURE", "CONNECT_FAILED", payload, attempts, null);
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (secret != null) {
                try {
                    secret.close();
                } catch (RuntimeException ignored) { }
            }
        }
    }

    private AmqpProtocol.Session connectWithinDeadline(AmqpProfile profile, char[] password, long deadline)
            throws AmqpProtocol.ConnectFailure, ConnectDeadlineExceeded {
        int budget = remainingMillis(deadline, ticker.getAsLong());
        if (budget == 0) throw new ConnectDeadlineExceeded();
        char[] attemptPassword = password.clone();
        final AmqpProtocol.ConnectAttempt attempt;
        try {
            attempt = protocol.beginConnect(profile, attemptPassword, budget);
        } finally {
            Arrays.fill(attemptPassword, '\0');
        }
        FutureTask<Void> task = new FutureTask<>(() -> {
            attempt.establish();
            return null;
        });
        Thread worker = Thread.ofVirtual().name("ravenroot-amqp091-connect").start(task);
        try {
            long remaining = remainingNanos(deadline, ticker.getAsLong());
            if (remaining == 0) throw new TimeoutException();
            task.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            cancelConnectAttempt(attempt, worker, deadline);
            throw new ConnectDeadlineExceeded();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            cancelConnectAttempt(attempt, worker, deadline);
            throw new ConnectDeadlineExceeded();
        } catch (ExecutionException failed) {
            boolean contained = cancelConnectAttempt(attempt, worker, deadline);
            if (!contained) throw new ConnectDeadlineExceeded();
            Throwable cause = failed.getCause();
            if (cause instanceof AmqpProtocol.ConnectFailure classified) throw classified;
            throw new AmqpProtocol.ConnectFailure(AmqpProtocol.ConnectFailureKind.TEMPORARY);
        }
        if (remainingNanos(deadline, ticker.getAsLong()) == 0) {
            cancelConnectAttempt(attempt, worker, deadline);
            throw new ConnectDeadlineExceeded();
        }
        try {
            return attempt.claim();
        } catch (AmqpProtocol.ConnectFailure failure) {
            if (!cancelConnectAttempt(attempt, worker, deadline)) throw new ConnectDeadlineExceeded();
            throw failure;
        }
    }

    private boolean cancelConnectAttempt(AmqpProtocol.ConnectAttempt attempt, Thread connectWorker, long deadline) {
        connectWorker.interrupt();
        try {
            attempt.cancel();
        } catch (RuntimeException ignored) {
            return false;
        }
        return remainingNanos(deadline, ticker.getAsLong()) > 0;
    }

    private void publishWithinDeadline(AmqpProtocol.Session session, AmqpProtocol.Publication publication,
                                       PublishTracker tracker, long deadline, int publishBudget,
                                       AtomicBoolean abortStarted) {
        FutureTask<Void> task = new FutureTask<>(() -> {
            // Once this invocation begins no client-side failure can prove that no bytes were transmitted.
            session.publish(publication, tracker, publishBudget);
            return null;
        });
        Thread worker = Thread.ofVirtual().name("ravenroot-amqp091-publish").start(task);
        try {
            long remaining = remainingNanos(deadline, ticker.getAsLong());
            if (remaining == 0) throw new TimeoutException();
            task.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            tracker.closed();
            abortSession(session, abortStarted, deadline);
            worker.interrupt();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            tracker.closed();
            abortSession(session, abortStarted, deadline);
            worker.interrupt();
        } catch (ExecutionException postPublish) {
            tracker.closed();
        }
    }

    private void cleanupSession(AmqpProtocol.Session session, long deadline, AtomicBoolean abortStarted) {
        if (abortStarted.get()) return;
        int closeBudget = remainingMillis(deadline, ticker.getAsLong());
        if (closeBudget == 0) {
            abortSession(session, abortStarted, deadline);
            return;
        }
        FutureTask<Void> close = new FutureTask<>(() -> {
            session.close(closeBudget);
            return null;
        });
        Thread worker = Thread.ofVirtual().name("ravenroot-amqp091-close").start(close);
        try {
            long remaining = remainingNanos(deadline, ticker.getAsLong());
            if (remaining == 0) throw new TimeoutException();
            close.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException | ExecutionException closeFailure) {
            worker.interrupt();
            abortSession(session, abortStarted, deadline);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            worker.interrupt();
            abortSession(session, abortStarted, deadline);
        }
    }

    private void abortSession(AmqpProtocol.Session session, AtomicBoolean abortStarted, long deadline) {
        if (!abortStarted.compareAndSet(false, true)) return;
        int abortBudget = remainingMillis(deadline, ticker.getAsLong());
        FutureTask<Void> abort = new FutureTask<>(() -> {
            session.abort(abortBudget);
            return null;
        });
        Thread worker = Thread.ofVirtual().name("ravenroot-amqp091-abort").start(abort);
        long remaining = remainingNanos(deadline, ticker.getAsLong());
        if (remaining == 0) return;
        try {
            abort.get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            worker.interrupt();
        } catch (TimeoutException | ExecutionException ignored) {
            worker.interrupt();
        }
    }

    private boolean backoff(int attempt, long deadline) {
        int remaining = remainingMillis(deadline, ticker.getAsLong());
        if (remaining == 0) return false;
        long delay = Math.min(200L, 25L << Math.min(attempt - 1, 3));
        if (delay >= remaining) return false;
        try {
            sleeper.sleep(delay);
            return remainingMillis(deadline, ticker.getAsLong()) > 0;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static long deadline(long now, int timeoutMs) {
        long duration = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        try {
            return Math.addExact(now, duration);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static int remainingMillis(long deadline, long now) {
        long remaining = remainingNanos(deadline, now);
        if (remaining <= 0) return 0;
        long milliseconds = TimeUnit.NANOSECONDS.toMillis(remaining);
        if (TimeUnit.MILLISECONDS.toNanos(milliseconds) < remaining) milliseconds++;
        return (int) Math.min(Integer.MAX_VALUE, milliseconds);
    }

    private static long remainingNanos(long deadline, long now) {
        final long remaining;
        try {
            remaining = Math.subtractExact(deadline, now);
        } catch (ArithmeticException overflow) {
            return 0;
        }
        return Math.max(0, remaining);
    }

    private static NodeResult result(String status, String reason, Payload payload, int attemptCount,
                                     AmqpProtocol.ReturnMetadata returned) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("version", VERSION);
        output.put("status", status);
        output.put("attemptCount", attemptCount);
        output.put("reason", reason);
        if (payload != null) {
            output.put("exchange", safe(payload.exchange, 255));
            output.put("routingKey", safe(payload.routingKey, 255));
            if (payload.messageId != null) output.put("messageId", safe(payload.messageId, 128));
            if (payload.correlationId != null) output.put("correlationId", safe(payload.correlationId, 128));
        }
        if (returned != null) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("replyCode", returned.replyCode());
            metadata.put("replyText", safe(returned.replyText(), 128));
            metadata.put("exchange", safe(returned.exchange(), 255));
            metadata.put("routingKey", safe(returned.routingKey(), 255));
            output.put("return", metadata);
        }
        return NodeResult.continueWith(output);
    }

    private static String safe(String value, int maximum) {
        if (value == null) return "";
        StringBuilder output = new StringBuilder(Math.min(value.length(), maximum));
        value.codePoints().limit(maximum).forEach(codePoint -> output.appendCodePoint(
                Character.isISOControl(codePoint) ? '?' : codePoint));
        return output.toString();
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    static final class PublishTracker implements AmqpProtocol.Observer {
        private final AtomicReference<Delivery> delivery = new AtomicReference<>();
        private final AtomicReference<AmqpProtocol.ReturnMetadata> returned = new AtomicReference<>();
        private final CompletableFuture<Delivery> completed = new CompletableFuture<>();

        @Override
        public void confirmed() {
            AmqpProtocol.ReturnMetadata metadata = returned.get();
            complete(metadata == null ? new Delivery("CONFIRMED", "CONFIRMED", null)
                    : new Delivery("RETURNED", "UNROUTABLE", metadata));
        }

        @Override
        public void nacked() {
            complete(new Delivery("NACKED", "BROKER_NACK", null));
        }

        @Override
        public void returned(AmqpProtocol.ReturnMetadata metadata) {
            if (metadata != null) returned.compareAndSet(null, metadata);
        }

        @Override
        public void closed() {
            complete(uncertain("DELIVERY_STATE_UNKNOWN"));
        }

        Delivery await(long timeoutNanos) {
            if (timeoutNanos <= 0) {
                complete(uncertain("CONFIRM_TIMEOUT"));
                return delivery.get();
            }
            try {
                return completed.get(timeoutNanos, TimeUnit.NANOSECONDS);
            } catch (TimeoutException timeout) {
                complete(uncertain("CONFIRM_TIMEOUT"));
                return delivery.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                complete(uncertain("DELIVERY_STATE_UNKNOWN"));
                return delivery.get();
            } catch (java.util.concurrent.ExecutionException impossible) {
                complete(uncertain("DELIVERY_STATE_UNKNOWN"));
                return delivery.get();
            }
        }

        Delivery current() {
            return delivery.get();
        }

        private void complete(Delivery outcome) {
            if (delivery.compareAndSet(null, outcome)) completed.complete(outcome);
        }

        private Delivery uncertain(String reason) {
            AmqpProtocol.ReturnMetadata metadata = returned.get();
            return metadata == null ? new Delivery("AMBIGUOUS", reason, null)
                    : new Delivery("RETURNED", "UNROUTABLE", metadata);
        }
    }

    record Delivery(String status, String reason, AmqpProtocol.ReturnMetadata returnMetadata) { }

    private static final class ConnectDeadlineExceeded extends Exception { }

    private static final class Refusal extends RuntimeException {
        private final String status;
        private final String reason;
        private final Payload payload;

        private Refusal(String status, String reason, Payload payload) {
            super(reason);
            this.status = status;
            this.reason = reason;
            this.payload = payload;
        }

        static Refusal rejected(String reason) {
            return new Refusal("REJECTED", reason, null);
        }

        static Refusal permanent(String reason) {
            return new Refusal("PERMANENT_FAILURE", reason, null);
        }
    }

    private record Settings(AmqpProfile profile, String exchange, String routingKey, String contentType,
                            String contentEncoding, boolean persistent, Integer priority, int priorityCap,
                            Long expirationMs, long expirationCap,
                            String messageId, String correlationId, String replyTo, String type, String appId,
                            Map<String, Object> headers, int timeoutMs, int maxConcurrency, int retries) {

        static Settings from(NodeConfiguration configuration, AmqpProfileResolver resolver,
                             ReservedNetworkPolicy destinationPolicy, String tenant) {
            for (String name : configuration.properties().keySet())
                if (!CONFIGURATION_FIELDS.contains(name)) throw Refusal.rejected("UNKNOWN_GRAPH_PROPERTY");
            String profileName = configuration.property("brokerProfile")
                    .orElseThrow(() -> Refusal.rejected("BROKER_PROFILE_REQUIRED"));
            final AmqpProfile profile;
            try {
                Optional<AmqpProfile> resolved = resolver.resolve(tenant, profileName);
                profile = resolved == null ? null : resolved.orElse(null);
            } catch (RuntimeException unavailable) {
                throw Refusal.permanent("BROKER_PROFILE_UNAVAILABLE");
            }
            if (profile == null) throw Refusal.permanent("BROKER_PROFILE_UNAVAILABLE");
            if (!tenant.equals(profile.tenant()) || !profileName.equals(profile.name()))
                throw Refusal.rejected("BROKER_PROFILE_FORBIDDEN");
            try { destinationPolicy.requireAllowedLiteral(profile.host()); }
            catch (SecurityException refused) { throw Refusal.permanent("BROKER_PROFILE_UNAVAILABLE"); }
            if (!strictBoolean(configuration, "mandatory", true))
                throw Refusal.rejected("MANDATORY_REQUIRED");
            String exchange = configured(configuration, "exchange", profile.defaultExchange(), 255, true);
            String routing = configured(configuration, "routingKey", profile.defaultRoutingKey(), 255, false);
            if (!profile.allowsExchange(exchange) || !profile.allowsRoutingKey(routing))
                throw Refusal.rejected("PUBLICATION_FORBIDDEN");
            boolean persistent = strictBoolean(configuration, "persistent", false);
            if (persistent && !profile.allowPersistent()) throw Refusal.rejected("PERSISTENCE_FORBIDDEN");
            Integer priority = optionalInt(configuration, "priority", profile.maxPriority());
            Long expiration = optionalLong(configuration, "expirationMs", profile.maxExpirationMs());
            String replyTo = optional(configuration, "replyTo", 255);
            if (!profile.allowsReplyTo(replyTo)) throw Refusal.rejected("REPLY_TO_FORBIDDEN");
            Map<String, Object> headers = parseHeaders(configuration.property("headers", ""), profile);
            return new Settings(profile, exchange, routing, optional(configuration, "contentType", 128),
                    optional(configuration, "contentEncoding", 128), persistent, priority,
                    priority == null ? profile.maxPriority() : priority, expiration,
                    expiration == null ? profile.maxExpirationMs() : expiration,
                    optional(configuration, "messageId", 128), optional(configuration, "correlationId", 128),
                    replyTo, optional(configuration, "type", 128), optional(configuration, "appId", 128), headers,
                    tighten(configuration, "confirmTimeoutMs", profile.timeoutMs(), 100),
                    tighten(configuration, "maxConcurrency", profile.maxConcurrency(), 1),
                    tighten(configuration, "retries", profile.retries(), 0));
        }

        private static int tighten(NodeConfiguration configuration, String name, int ceiling, int minimum) {
            String raw = configuration.property(name, "");
            if (raw.isEmpty()) return ceiling;
            try {
                int value = Integer.parseInt(raw);
                if (value < minimum || value > ceiling) throw new NumberFormatException();
                return value;
            } catch (NumberFormatException invalid) {
                throw Refusal.rejected("INVALID_TIGHTENING");
            }
        }

        private static String configured(NodeConfiguration configuration, String name, String fallback,
                                         int maximum, boolean emptyAllowed) {
            if (!configuration.properties().containsKey(name)) return fallback;
            Object value = configuration.properties().get(name);
            if (!(value instanceof String text) || (!emptyAllowed && text.isBlank()) || text.length() > maximum
                    || text.contains("\0") || !AmqpWireLimits.isShortstr(text))
                throw Refusal.rejected("INVALID_GRAPH_PROPERTY");
            return text;
        }

        private static String optional(NodeConfiguration configuration, String name, int maximum) {
            if (!configuration.properties().containsKey(name)) return null;
            Object value = configuration.properties().get(name);
            if (!(value instanceof String text) || text.isBlank() || text.length() > maximum || text.contains("\0"))
                throw Refusal.rejected("INVALID_GRAPH_PROPERTY");
            if (!AmqpWireLimits.isShortstr(text)) throw Refusal.rejected("INVALID_GRAPH_PROPERTY");
            return text;
        }

        private static boolean strictBoolean(NodeConfiguration configuration, String name, boolean fallback) {
            if (!configuration.properties().containsKey(name)) return fallback;
            Object value = configuration.properties().get(name);
            if (value instanceof Boolean bool) return bool;
            if ("true".equals(value)) return true;
            if ("false".equals(value)) return false;
            throw Refusal.rejected("INVALID_GRAPH_PROPERTY");
        }

        private static Integer optionalInt(NodeConfiguration configuration, String name, int maximum) {
            if (!configuration.properties().containsKey(name)) return null;
            long value = integer(configuration.properties().get(name), "INVALID_GRAPH_PROPERTY");
            if (value < 0 || value > maximum) throw Refusal.rejected("INVALID_GRAPH_PROPERTY");
            return (int) value;
        }

        private static Long optionalLong(NodeConfiguration configuration, String name, long maximum) {
            if (!configuration.properties().containsKey(name)) return null;
            long value = integer(configuration.properties().get(name), "INVALID_GRAPH_PROPERTY");
            if (value < 0 || value > maximum) throw Refusal.rejected("INVALID_GRAPH_PROPERTY");
            return value;
        }

        private static Map<String, Object> parseHeaders(String raw, AmqpProfile profile) {
            if (raw.isEmpty()) return Map.of();
            Map<String, Object> headers = new LinkedHashMap<>();
            for (String field : raw.split(",", -1)) {
                int separator = field.indexOf('=');
                if (separator < 1) throw Refusal.rejected("INVALID_HEADER");
                String key = field.substring(0, separator);
                String value = field.substring(separator + 1);
                if (!profile.allowsHeader(key) || !AmqpWireLimits.isShortstr(key)
                        || value.length() > 256 || value.contains("\0")
                        || headers.putIfAbsent(key, value) != null) throw Refusal.rejected("INVALID_HEADER");
            }
            return Map.copyOf(headers);
        }
    }

    private record Payload(String exchange, String routingKey, byte[] body, String contentType,
                           String contentEncoding, boolean persistent, Integer priority, String expiration,
                           String messageId, String correlationId, String replyTo, String type, String appId,
                           Map<String, Object> headers) {

        static Payload from(Object raw, Settings settings) {
            if (!(raw instanceof Map<?, ?> map)) throw Refusal.rejected("INVALID_PAYLOAD");
            for (Object key : map.keySet())
                if (!(key instanceof String name) || !PAYLOAD_FIELDS.contains(name))
                    throw Refusal.rejected("UNKNOWN_PAYLOAD_FIELD");
            if (!VERSION.equals(map.get("version"))) throw Refusal.rejected("UNSUPPORTED_PAYLOAD_VERSION");
            int bodies = (map.containsKey("bodyText") ? 1 : 0) + (map.containsKey("bodyJson") ? 1 : 0)
                    + (map.containsKey("bodyBase64") ? 1 : 0);
            if (bodies != 1) throw Refusal.rejected("BODY_VARIANT_REQUIRED");
            String exchange = overlayString(map, "exchange", settings.exchange, 255, true);
            String routing = overlayString(map, "routingKey", settings.routingKey, 255, false);
            if (!settings.profile.allowsExchange(exchange) || !settings.profile.allowsRoutingKey(routing))
                throw Refusal.rejected("PUBLICATION_FORBIDDEN");

            byte[] body;
            String defaultContentType;
            if (map.containsKey("bodyText")) {
                body = requiredString(map.get("bodyText"), settings.profile.maxBodyBytes(), true)
                        .getBytes(StandardCharsets.UTF_8);
                defaultContentType = "text/plain";
            } else if (map.containsKey("bodyJson")) {
                try {
                    body = PayloadJson.write(PayloadValue.fromJava(map.get("bodyJson"), JSON_LIMITS))
                            .getBytes(StandardCharsets.UTF_8);
                } catch (RuntimeException invalid) {
                    throw Refusal.rejected("INVALID_JSON_BODY");
                }
                defaultContentType = "application/json";
            } else {
                String encoded = requiredString(map.get("bodyBase64"), settings.profile.maxBodyBytes() * 2 + 4, true);
                try {
                    body = Base64.getDecoder().decode(encoded);
                } catch (IllegalArgumentException invalid) {
                    throw Refusal.rejected("INVALID_BASE64_BODY");
                }
                defaultContentType = "application/octet-stream";
            }
            if (body.length > settings.profile.maxBodyBytes()) throw Refusal.rejected("BODY_TOO_LARGE");

            boolean persistent = overlayBoolean(map, "persistent", settings.persistent);
            if (persistent && !settings.profile.allowPersistent()) throw Refusal.rejected("PERSISTENCE_FORBIDDEN");
            Integer priority = overlayInteger(map, "priority", settings.priority, settings.priorityCap);
            Long expirationMs = overlayExpiration(map, settings.expirationMs, settings.expirationCap);
            String replyTo = overlayOptional(map, "replyTo", settings.replyTo, 255);
            if (!settings.profile.allowsReplyTo(replyTo)) throw Refusal.rejected("REPLY_TO_FORBIDDEN");
            Map<String, Object> headers = new LinkedHashMap<>(settings.headers);
            if (map.containsKey("headers")) {
                if (!(map.get("headers") instanceof Map<?, ?> values) || values.size() > 32)
                    throw Refusal.rejected("INVALID_HEADER");
                for (Map.Entry<?, ?> entry : values.entrySet()) {
                    if (!(entry.getKey() instanceof String key) || !settings.profile.allowsHeader(key)
                            || !AmqpWireLimits.isShortstr(key)
                            || !(entry.getValue() instanceof String value) || value.length() > 256
                            || value.contains("\0")) throw Refusal.rejected("INVALID_HEADER");
                    headers.put(key, value);
                }
            }
            String contentType = overlayOptional(map, "contentType", settings.contentType, 128);
            if (contentType == null) contentType = defaultContentType;
            return new Payload(exchange, routing, body, contentType,
                    overlayOptional(map, "contentEncoding", settings.contentEncoding, 128), persistent, priority,
                    expirationMs == null ? null : Long.toString(expirationMs),
                    overlayOptional(map, "messageId", settings.messageId, 128),
                    overlayOptional(map, "correlationId", settings.correlationId, 128), replyTo,
                    overlayOptional(map, "type", settings.type, 128),
                    overlayOptional(map, "appId", settings.appId, 128), Map.copyOf(headers));
        }

        AmqpProtocol.Publication publication() {
            return new AmqpProtocol.Publication(exchange, routingKey, true, body, contentType, contentEncoding,
                    persistent, priority, expiration, messageId, correlationId, replyTo, type, appId, headers);
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        private static String overlayString(Map<?, ?> map, String name, String fallback, int maximum,
                                            boolean emptyAllowed) {
            if (!map.containsKey(name)) return fallback;
            String value = requiredShortstr(map.get(name), maximum, emptyAllowed);
            return value;
        }

        private static String overlayOptional(Map<?, ?> map, String name, String fallback, int maximum) {
            if (!map.containsKey(name)) return fallback;
            Object value = map.get(name);
            if (value == null) return null;
            return requiredShortstr(value, maximum, false);
        }

        private static boolean overlayBoolean(Map<?, ?> map, String name, boolean fallback) {
            if (!map.containsKey(name)) return fallback;
            if (!(map.get(name) instanceof Boolean value)) throw Refusal.rejected("INVALID_PAYLOAD");
            return value;
        }

        private static Integer overlayInteger(Map<?, ?> map, String name, Integer fallback, int maximum) {
            if (!map.containsKey(name)) return fallback;
            Object raw = map.get(name);
            if (raw == null) return null;
            long value = integer(raw, "INVALID_PAYLOAD");
            if (value < 0 || value > maximum) throw Refusal.rejected("INVALID_PAYLOAD");
            return (int) value;
        }

        private static Long overlayExpiration(Map<?, ?> map, Long fallback, long maximum) {
            if (!map.containsKey("expirationMs")) return fallback;
            Object raw = map.get("expirationMs");
            if (raw == null) {
                if (fallback != null) throw Refusal.rejected("INVALID_PAYLOAD");
                return null;
            }
            long value = integer(raw, "INVALID_PAYLOAD");
            if (value < 0 || value > maximum) throw Refusal.rejected("INVALID_PAYLOAD");
            return value;
        }

        private static String requiredString(Object raw, int maximum, boolean emptyAllowed) {
            if (!(raw instanceof String value) || !emptyAllowed && value.isBlank() || value.length() > maximum
                    || value.contains("\0")) throw Refusal.rejected("INVALID_PAYLOAD");
            return value;
        }

        private static String requiredShortstr(Object raw, int maximum, boolean emptyAllowed) {
            String value = requiredString(raw, maximum, emptyAllowed);
            if (!AmqpWireLimits.isShortstr(value)) throw Refusal.rejected("INVALID_PAYLOAD");
            return value;
        }
    }

    private static long integer(Object raw, String reason) {
        if (raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long)
            return ((Number) raw).longValue();
        if (raw instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException invalid) {
                throw Refusal.rejected(reason);
            }
        }
        throw Refusal.rejected(reason);
    }
}
