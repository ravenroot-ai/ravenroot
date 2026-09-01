package ai.ravenroot.extensions.amqp091;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.api.security.SecretValue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class AmqpTestSupport {
    static final String TENANT = "tenant-a";
    static final String PROFILE = "orders";
    static final String SECRET = "correct-horse-battery-staple";

    private AmqpTestSupport() { }

    static AmqpProfile profile() {
        return profile(TENANT, PROFILE, 4, 100, 1_000, 2);
    }

    static AmqpProfile profile(String tenant, String name, int concurrency, int rate, int timeout, int retries) {
        return new AmqpProfile(tenant, name, "broker.example.test", 5671, true, "/tenant-a", "publisher",
                "amqp-orders", "orders", Set.of("audit"), "created", Set.of("updated"),
                Set.of("trace", "source"), Set.of("responses"), true, 5, 60_000,
                concurrency, rate, timeout, 4_096, retries);
    }

    static NodeConfiguration configuration() {
        return configuration(Map.of());
    }

    static NodeConfiguration configuration(Map<String, Object> overrides) {
        java.util.LinkedHashMap<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put("brokerProfile", PROFILE);
        properties.putAll(overrides);
        return new NodeConfiguration("publish", AmqpPublishNodeBehavior.BEHAVIOR, properties);
    }

    static Map<String, Object> payload() {
        return Map.of("version", "amqp.publish.v1", "bodyText", "hello", "messageId", "m-1",
                "correlationId", "c-1");
    }

    static NodeMessage message(Object payload) {
        return message(TENANT, payload);
    }

    static NodeMessage message(String tenant, Object payload) {
        UUID id = UUID.randomUUID();
        return new NodeMessage(new SecurityContext("request", tenant, "subject", PrincipalType.WORKLOAD,
                "issuer"), id, id, id, id, Set.of(), "publish", payload, Map.of());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> output(NodeAction action, Object payload) {
        return (Map<String, Object>) action.handle(message(payload)).toCompletableFuture().join().payload();
    }

    static AmqpPublishNodeBehavior behavior(FakeProtocol protocol) {
        return behavior(protocol, reference -> Optional.of(new SecretValue(SECRET.toCharArray())),
                new AmqpRuntimeControls(System::nanoTime, Runnable::run, 32, 16, 128),
                System::nanoTime, millis -> { });
    }

    static AmqpPublishNodeBehavior behavior(FakeProtocol protocol,
                                            ai.ravenroot.api.security.CredentialResolver credentials,
                                            AmqpRuntimeControls controls, java.util.function.LongSupplier ticker,
                                            AmqpPublishNodeBehavior.Sleeper sleeper) {
        return new AmqpPublishNodeBehavior(credentials,
                (tenant, name) -> Optional.of(profile(tenant, name, 4, 100, 1_000, 2)),
                protocol, controls, ticker, sleeper);
    }

    enum Event {
        CONFIRM, NACK, RETURN, CLOSE, SILENT, THROW, RETURN_THEN_CONFIRM, CONFIRM_THEN_RETURN,
        RETURN_THEN_CLOSE, RETURN_THEN_NACK
    }

    static final class FakeProtocol implements AmqpProtocol {
        final ArrayDeque<Object> steps = new ArrayDeque<>();
        final AtomicInteger connects = new AtomicInteger();
        final AtomicInteger publishes = new AtomicInteger();
        final List<String> observedPasswords = new ArrayList<>();
        final List<char[]> passwordReferences = new ArrayList<>();
        final List<Publication> publications = new ArrayList<>();
        final List<Integer> connectTimeouts = new ArrayList<>();
        final List<Integer> publishTimeouts = new ArrayList<>();
        final List<Integer> closeTimeouts = new ArrayList<>();
        final AtomicInteger aborts = new AtomicInteger();

        FakeProtocol(Event... events) {
            steps.addAll(Arrays.asList(events));
        }

        FakeProtocol temporaryFailures(int count, Event terminal) {
            for (int i = 0; i < count; i++) steps.add(ConnectFailureKind.TEMPORARY);
            steps.add(terminal);
            return this;
        }

        FakeProtocol permanentFailure() {
            steps.add(ConnectFailureKind.PERMANENT);
            return this;
        }

        @Override
        public synchronized ConnectAttempt beginConnect(AmqpProfile profile, char[] password, int timeoutMs) {
            connects.incrementAndGet();
            connectTimeouts.add(timeoutMs);
            observedPasswords.add(new String(password));
            passwordReferences.add(password);
            Object step = steps.isEmpty() ? Event.CONFIRM : steps.removeFirst();
            return new ConnectAttempt() {
                @Override
                public void establish() throws ConnectFailure {
                    if (step instanceof ConnectFailureKind kind) throw new ConnectFailure(kind);
                }

                @Override
                public Session claim() throws ConnectFailure {
                    if (step instanceof ConnectFailureKind kind) throw new ConnectFailure(kind);
                    Event event = (Event) step;
                    return new Session() {
                        @Override
                        public void publish(Publication publication, Observer observer, int timeoutMs)
                                throws Exception {
                            publishes.incrementAndGet();
                            publications.add(publication);
                            publishTimeouts.add(timeoutMs);
                            switch (event) {
                                case CONFIRM -> observer.confirmed();
                                case NACK -> observer.nacked();
                                case RETURN -> observer.returned(returned());
                                case CLOSE -> observer.closed();
                                case SILENT -> { }
                                case THROW -> throw new java.io.IOException("secret=" + SECRET);
                                case RETURN_THEN_CONFIRM -> { observer.returned(returned()); observer.confirmed(); }
                                case CONFIRM_THEN_RETURN -> { observer.confirmed(); observer.returned(returned()); }
                                case RETURN_THEN_CLOSE -> { observer.returned(returned()); observer.closed(); }
                                case RETURN_THEN_NACK -> { observer.returned(returned()); observer.nacked(); }
                            }
                        }

                        @Override
                        public void close(int timeoutMs) {
                            closeTimeouts.add(timeoutMs);
                        }

                        @Override
                        public void abort(int timeoutMs) {
                            aborts.incrementAndGet();
                        }
                    };
                }

                @Override
                public void cancel() { }
            };
        }

        private static ReturnMetadata returned() {
            return new ReturnMetadata(312, "NO_ROUTE\nsecret", "orders\r", "created\0tail");
        }
    }

    static final class MutableTicker implements java.util.function.LongSupplier {
        private final AtomicLong nanos = new AtomicLong();
        @Override public long getAsLong() { return nanos.get(); }
        void advanceMillis(long millis) { nanos.addAndGet(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis)); }
    }
}
