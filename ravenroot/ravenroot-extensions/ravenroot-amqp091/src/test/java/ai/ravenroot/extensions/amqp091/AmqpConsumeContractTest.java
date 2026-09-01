package ai.ravenroot.extensions.amqp091;

import ai.ravenroot.api.catalog.NodeTypeDescriptorValidator;
import ai.ravenroot.api.deployment.IngressReceipt;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.security.SecretValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmqpConsumeContractTest {
    private AmqpConsumerSource source;

    @AfterEach
    void stopSource() {
        if (source != null) source.stop().toCompletableFuture().orTimeout(2, TimeUnit.SECONDS).join();
    }

    @Test
    void descriptorIsValidConditionalAndContainsNoOperatorSecretsOrEndpoints() {
        var descriptor = behavior(new AmqpConsumerTestSupport.FakeProtocol()).descriptor();
        NodeTypeDescriptorValidator.validate(descriptor);
        assertEquals(AmqpConsumeNodeBehavior.BEHAVIOR, descriptor.behavior());
        var properties = descriptor.properties();
        var deadLetter = properties.stream().filter(p -> p.name().equals("deadLetterMode")).findFirst().orElseThrow();
        assertEquals("poisonPolicy", deadLetter.visibleWhen().property());
        assertEquals(java.util.List.of("dead-letter"), deadLetter.visibleWhen().values());
        assertEquals(deadLetter.visibleWhen(), deadLetter.requiredWhen());
        assertTrue(descriptor.capabilities().containsAll(java.util.Set.of(
                "network", "credential-reference", "inbound-source")));
        assertFalse(properties.stream().map(p -> p.name()).anyMatch(
                name -> name.matches("(?i).*(host|port|password|credential|tls|vhost|username).*")));
    }

    @Test
    void constructionDoesNotResolveAuthorityOrCredentialOrOpenBroker() {
        var protocol = new AmqpConsumerTestSupport.FakeProtocol(new AmqpConsumerTestSupport.FakeOwner());
        AtomicInteger profileCalls = new AtomicInteger();
        AtomicInteger policyCalls = new AtomicInteger();
        AtomicInteger credentialCalls = new AtomicInteger();
        source = new AmqpConsumerSource(configuration(Map.of()), reference -> {
            credentialCalls.incrementAndGet(); return secret();
        }, (tenant, name) -> {
            profileCalls.incrementAndGet(); return Optional.of(AmqpTestSupport.profile());
        }, (tenant, name) -> {
            policyCalls.incrementAndGet(); return Optional.of(AmqpConsumerTestSupport.policy());
        }, protocol, virtualExecutor(), Clock.systemUTC());

        assertEquals(0, protocol.openCalls.get());
        assertEquals(0, profileCalls.get());
        assertEquals(0, policyCalls.get());
        assertEquals(0, credentialCalls.get());
        assertEquals(AmqpConsumerSource.State.STOPPED, source.state());
    }

    @Test
    void durabilityProbeFailsClosedBeforeCredentialAndBroker() {
        var protocol = new AmqpConsumerTestSupport.FakeProtocol(new AmqpConsumerTestSupport.FakeOwner());
        AtomicInteger credentials = new AtomicInteger();
        var ingress = new AmqpConsumerTestSupport.Ingress();
        ingress.durable = false;
        var context = new AmqpConsumerTestSupport.Context(ingress);
        source = source(protocol, AmqpConsumerTestSupport.policy(), configuration(Map.of()), reference -> {
            credentials.incrementAndGet(); return secret();
        });

        assertThrows(CompletionException.class, () -> source.start(context).toCompletableFuture().join());
        assertEquals(0, credentials.get());
        assertEquals(0, protocol.openCalls.get());
        assertTrue(context.degraded.contains("durable-ingress-required"));
        assertEquals(AmqpConsumerSource.State.FAILED, source.state());
    }

    @Test
    void consumeOkReadinessThenDurableCommitAcksOnOwningSession() {
        var owner = new AmqpConsumerTestSupport.FakeOwner();
        var protocol = new AmqpConsumerTestSupport.FakeProtocol(owner);
        var ingress = new AmqpConsumerTestSupport.Ingress();
        var context = new AmqpConsumerTestSupport.Context(ingress);
        source = source(protocol, AmqpConsumerTestSupport.policy(), configuration(Map.of(
                "prefetch", 2, "maxInFlight", 3)), ignored -> secret());

        source.start(context).toCompletableFuture().join();
        assertEquals(1, context.healthy.get());
        assertEquals(2, protocol.observedPrefetch);
        owner.deliver(AmqpConsumerTestSupport.delivery(11, "message-1", false));
        AmqpConsumerTestSupport.await(owner.acked);

        assertEquals(java.util.List.of(11L), owner.acks);
        assertTrue(owner.nacks.isEmpty());
        Map<String, Object> event = ingress.payloads.getFirst();
        assertEquals("amqp.delivery.v1", event.get("version"));
        assertEquals("message-1", event.get("messageId"));
        assertFalse(event.containsKey("deliveryTag"));
        assertFalse(event.toString().contains(AmqpTestSupport.SECRET));
        assertTrue(ingress.keys.getFirst().contains("/bWVzc2FnZS0x/"));
    }

    @Test
    void startIsIdempotentAndStopAllowsCleanRestartWithoutDuplicateOwner() {
        var first = new AmqpConsumerTestSupport.FakeOwner();
        var second = new AmqpConsumerTestSupport.FakeOwner();
        var protocol = new AmqpConsumerTestSupport.FakeProtocol(first, second);
        var context = new AmqpConsumerTestSupport.Context(new AmqpConsumerTestSupport.Ingress(), 2);
        source = source(protocol, AmqpConsumerTestSupport.policy(), configuration(Map.of()), ignored -> secret());

        source.start(context).toCompletableFuture().join();
        source.start(context).toCompletableFuture().join();
        assertEquals(1, protocol.openCalls.get());
        source.stop().toCompletableFuture().join();
        assertEquals(1, first.closes.get());
        assertEquals(AmqpConsumerSource.State.STOPPED, source.state());

        source.start(context).toCompletableFuture().join();
        assertEquals(2, protocol.openCalls.get());
        assertEquals(0, second.closes.get());
    }

    @Test
    void processLocalQueueLeaseRefusesASecondActiveConsumerBeforeCredentialOrOpen() {
        var firstOwner = new AmqpConsumerTestSupport.FakeOwner();
        var firstProtocol = new AmqpConsumerTestSupport.FakeProtocol(firstOwner);
        var first = source(firstProtocol, AmqpConsumerTestSupport.policy(), configuration(Map.of()), ignored -> secret());
        first.start(new AmqpConsumerTestSupport.Context(new AmqpConsumerTestSupport.Ingress()))
                .toCompletableFuture().join();
        var secondProtocol = new AmqpConsumerTestSupport.FakeProtocol(new AmqpConsumerTestSupport.FakeOwner());
        AtomicInteger secondCredentials = new AtomicInteger();
        var second = new AmqpConsumerSource(configuration(Map.of()), ignored -> {
            secondCredentials.incrementAndGet(); return secret();
        }, (tenant, name) -> Optional.of(AmqpTestSupport.profile(tenant, name, 4, 100, 1_000, 2)),
                (tenant, name) -> Optional.of(AmqpConsumerTestSupport.policy()), secondProtocol,
                virtualExecutor(), Clock.systemUTC());
        var secondContext = new AmqpConsumerTestSupport.Context(new AmqpConsumerTestSupport.Ingress());

        assertThrows(CompletionException.class, () -> second.start(secondContext).toCompletableFuture().join());
        assertEquals(0, secondCredentials.get());
        assertEquals(0, secondProtocol.openCalls.get());
        assertTrue(secondContext.degraded.contains("amqp-consumer-already-active"));

        first.stop().toCompletableFuture().join();
        source = second;
    }

    @Test
    void transientDisconnectRevokesGenerationAndReconnectsExactlyOnce() {
        var first = new AmqpConsumerTestSupport.FakeOwner();
        var second = new AmqpConsumerTestSupport.FakeOwner();
        var protocol = new AmqpConsumerTestSupport.FakeProtocol(first, second);
        var context = new AmqpConsumerTestSupport.Context(new AmqpConsumerTestSupport.Ingress(), 2);
        source = source(protocol, AmqpConsumerTestSupport.policy(), configuration(Map.of()), ignored -> secret());

        source.start(context).toCompletableFuture().join();
        long firstGeneration = source.generation();
        first.disconnect();
        AmqpConsumerTestSupport.await(context.healthyLatch);

        assertEquals(2, protocol.openCalls.get());
        assertEquals(1, first.closes.get());
        assertNotEquals(firstGeneration, source.generation());
        assertEquals(AmqpConsumerSource.State.READY, source.state());
        assertTrue(context.degraded.contains("amqp-consumer-reconnecting"));
    }

    @Test
    void reconnectBackoffGrowsExponentiallyCapsAndStopInterruptsTheWait() {
        var owners = java.util.List.of(new AmqpConsumerTestSupport.FakeOwner(),
                new AmqpConsumerTestSupport.FakeOwner(), new AmqpConsumerTestSupport.FakeOwner(),
                new AmqpConsumerTestSupport.FakeOwner(), new AmqpConsumerTestSupport.FakeOwner());
        var protocol = new AmqpConsumerTestSupport.FakeProtocol(owners.toArray());
        var delays = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
        var scheduled = new java.util.concurrent.CountDownLatch(5);
        var samples = new java.util.ArrayDeque<>(java.util.List.of(
                0.0d, 0.5d, Math.nextDown(1.0d), 0.0d, 0.5d));
        source = source(protocol, AmqpConsumerTestSupport.policy(), configuration(Map.of()), ignored -> secret(),
                delay -> { delays.add(delay); scheduled.countDown(); }, samples::removeFirst);
        source.start(new AmqpConsumerTestSupport.Context(new AmqpConsumerTestSupport.Ingress(), 5))
                .toCompletableFuture().join();

        for (int index = 0; index < owners.size() - 1; index++) {
            owners.get(index).disconnect();
            AmqpConsumerTestSupport.await(owners.get(index + 1).pollEntered);
        }
        owners.getLast().disconnect();
        AmqpConsumerTestSupport.await(scheduled);
        assertEquals(java.util.List.of(100, 300, 800, 500, 750), delays);
        var minimums = java.util.List.of(100, 200, 400, 500, 500);
        var caps = java.util.List.of(200, 400, 800, 1_000, 1_000);
        for (int index = 0; index < delays.size(); index++) {
            assertTrue(delays.get(index) >= minimums.get(index));
            assertTrue(delays.get(index) <= caps.get(index));
        }

        long before = System.nanoTime();
        source.stop().toCompletableFuture().join();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);
        assertTrue(elapsedMs < 500, "stop must interrupt reconnect backoff, elapsed=" + elapsedMs);
    }

    @Test
    void durableDeliveryResetsReconnectFailureStreak() {
        var first = new AmqpConsumerTestSupport.FakeOwner();
        var second = new AmqpConsumerTestSupport.FakeOwner();
        var third = new AmqpConsumerTestSupport.FakeOwner();
        var delays = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
        var scheduled = new java.util.concurrent.CountDownLatch(2);
        source = source(new AmqpConsumerTestSupport.FakeProtocol(first, second, third),
                AmqpConsumerTestSupport.policy(), configuration(Map.of()), ignored -> secret(),
                delay -> { delays.add(delay); scheduled.countDown(); }, () -> 0.5d);
        source.start(new AmqpConsumerTestSupport.Context(new AmqpConsumerTestSupport.Ingress(), 3))
                .toCompletableFuture().join();

        first.disconnect();
        AmqpConsumerTestSupport.await(second.pollEntered);
        second.deliver(AmqpConsumerTestSupport.delivery(18, "healthy", false));
        AmqpConsumerTestSupport.await(second.acked);
        second.disconnect();
        AmqpConsumerTestSupport.await(third.pollEntered);
        AmqpConsumerTestSupport.await(scheduled);

        assertEquals(java.util.List.of(150, 150), delays);
    }

    @Test
    void ambiguousReceiptReoffersStableKeyUnackedUntilDuplicate() {
        var owner = new AmqpConsumerTestSupport.FakeOwner();
        var ingress = new AmqpConsumerTestSupport.Ingress(2);
        ingress.receipts.add(new IngressReceipt.Ambiguous("ignored", "timeout"));
        ingress.receipts.add(new IngressReceipt.Duplicate("ignored"));
        source = source(new AmqpConsumerTestSupport.FakeProtocol(owner), AmqpConsumerTestSupport.policy(),
                configuration(Map.of()), ignored -> secret());
        source.start(new AmqpConsumerTestSupport.Context(ingress)).toCompletableFuture().join();

        owner.deliver(AmqpConsumerTestSupport.delivery(21, "stable", false));
        AmqpConsumerTestSupport.await(owner.acked);

        assertEquals(java.util.List.of(21L), owner.acks);
        assertTrue(owner.nacks.isEmpty());
        assertEquals(2, ingress.payloads.size());
        assertEquals(ingress.keys.get(0), ingress.keys.get(1));
        assertEquals(1, ingress.payloads.get(0).get("attempt"));
        assertEquals(2, ingress.payloads.get(1).get("attempt"));
    }

    @Test
    void refusedDeliveryRequeuesUntilBoundThenRejectsForBrokerDlx() {
        var owner = new AmqpConsumerTestSupport.FakeOwner(2);
        var ingress = new AmqpConsumerTestSupport.Ingress(2);
        ingress.receipts.add(new IngressReceipt.Refused("full"));
        ingress.receipts.add(new IngressReceipt.Refused("full"));
        source = source(new AmqpConsumerTestSupport.FakeProtocol(owner), AmqpConsumerTestSupport.policy(),
                configuration(Map.of("poisonAttempts", 2)), ignored -> secret());
        var context = new AmqpConsumerTestSupport.Context(ingress);
        source.start(context).toCompletableFuture().join();

        owner.deliver(AmqpConsumerTestSupport.delivery(31, "poison", false));
        AmqpConsumerTestSupport.await(owner.firstNack);
        owner.deliver(AmqpConsumerTestSupport.delivery(32, "poison", true));
        AmqpConsumerTestSupport.await(owner.nacked);

        assertEquals(java.util.List.of("31:true", "32:false"), owner.nacks);
        assertTrue(context.degraded.contains("amqp-delivery-dead-lettered"));
    }

    @Test
    void stopRevokesGenerationBeforeLateDurableCompletionCanAck() {
        var owner = new AmqpConsumerTestSupport.FakeOwner();
        var ingress = new AmqpConsumerTestSupport.Ingress().gateOffer();
        source = source(new AmqpConsumerTestSupport.FakeProtocol(owner), AmqpConsumerTestSupport.policy(),
                configuration(Map.of()), ignored -> secret());
        source.start(new AmqpConsumerTestSupport.Context(ingress)).toCompletableFuture().join();
        owner.deliver(AmqpConsumerTestSupport.delivery(41, "late", false));
        AmqpConsumerTestSupport.await(ingress.offered);

        var stopping = source.stop().toCompletableFuture();
        assertEquals(AmqpConsumerSource.State.STOPPING, source.state());
        ingress.releaseOffer();
        stopping.join();

        assertTrue(owner.acks.isEmpty());
        assertTrue(owner.nacks.isEmpty());
        assertEquals(1, owner.closes.get());
    }

    @Test
    void stopDuringOpenNeverReportsReadyAndClosesClaimedOwner() {
        var owner = new AmqpConsumerTestSupport.FakeOwner();
        var protocol = new AmqpConsumerTestSupport.FakeProtocol(owner).gateOpen();
        var context = new AmqpConsumerTestSupport.Context(new AmqpConsumerTestSupport.Ingress());
        source = source(protocol, AmqpConsumerTestSupport.policy(), configuration(Map.of()), ignored -> secret());
        var starting = source.start(context).toCompletableFuture();
        AmqpConsumerTestSupport.await(protocol.openEntered);

        var stopping = source.stop().toCompletableFuture();
        protocol.releaseOpen();
        stopping.join();

        assertThrows(CompletionException.class, starting::join);
        assertEquals(0, context.healthy.get());
        assertEquals(1, owner.closes.get());
        assertEquals(AmqpConsumerSource.State.STOPPED, source.state());
    }

    @Test
    void hiddenDeadLetterFieldIsNotReadWhenConditionIsFalse() {
        var owner = new AmqpConsumerTestSupport.FakeOwner();
        source = source(new AmqpConsumerTestSupport.FakeProtocol(owner), AmqpConsumerTestSupport.policy(),
                configuration(Map.of("poisonPolicy", "profile", "deadLetterMode", new Object() {
                    @Override public String toString() { throw new AssertionError("hidden field read"); }
                })), ignored -> secret());

        source.start(new AmqpConsumerTestSupport.Context(new AmqpConsumerTestSupport.Ingress()))
                .toCompletableFuture().join();
        assertEquals(AmqpConsumerSource.State.READY, source.state());
    }

    @Test
    void missingStableIdentityAndOversizedBodiesRejectWithoutTraversal() {
        var owner = new AmqpConsumerTestSupport.FakeOwner(2);
        var ingress = new AmqpConsumerTestSupport.Ingress();
        source = source(new AmqpConsumerTestSupport.FakeProtocol(owner), AmqpConsumerTestSupport.policy(),
                configuration(Map.of()), ignored -> secret());
        var context = new AmqpConsumerTestSupport.Context(ingress);
        source.start(context).toCompletableFuture().join();
        var valid = AmqpConsumerTestSupport.delivery(51, null, false);
        owner.deliver(valid);
        byte[] tooLarge = new byte[AmqpConsumerTestSupport.policy().maxBodyBytes() + 1];
        owner.deliver(new AmqpConsumerProtocol.Delivery(52, false, valid.exchange(), valid.routingKey(), tooLarge,
                new AmqpConsumerProtocol.Properties(null, null, "large", null, null, null, null, -1, Map.of())));
        AmqpConsumerTestSupport.await(owner.nacked);

        assertEquals(java.util.List.of("51:false", "52:false"), owner.nacks);
        assertTrue(ingress.payloads.isEmpty());
        assertTrue(context.degraded.containsAll(java.util.List.of("missing-stable-message-identity", "body-too-large")));
    }

    @Test
    void adapterSideBoundedRejectionNacksWithoutMaterializingTraversalPayload() {
        var owner = new AmqpConsumerTestSupport.FakeOwner();
        var ingress = new AmqpConsumerTestSupport.Ingress();
        source = source(new AmqpConsumerTestSupport.FakeProtocol(owner), AmqpConsumerTestSupport.policy(),
                configuration(Map.of()), ignored -> secret());
        var context = new AmqpConsumerTestSupport.Context(ingress);
        source.start(context).toCompletableFuture().join();

        owner.reject(55, "headers-too-large");
        AmqpConsumerTestSupport.await(owner.nacked);

        assertEquals(java.util.List.of("55:false"), owner.nacks);
        assertTrue(ingress.payloads.isEmpty());
        assertTrue(context.degraded.contains("headers-too-large"));
    }

    @Test
    void authorizedRabbitLongStringIdentityAndEmptyWireNamesProjectSafely() {
        var policy = new AmqpConsumerPolicy(AmqpTestSupport.TENANT, AmqpTestSupport.PROFILE, "orders.q", 4,
                java.util.Set.of("identity"), "identity", 4_096, 1_024, 100, 1_000, 3, "dead-letter", 10);
        byte[] identity = "header-id".getBytes(StandardCharsets.UTF_8);
        var delivery = new AmqpConsumerProtocol.Delivery(61, false, "", "", "body".getBytes(StandardCharsets.UTF_8),
                new AmqpConsumerProtocol.Properties(null, null, null, null, null, null, null, -1,
                        Map.of("identity", identity)));
        identity[0] = 'X';

        var projected = AmqpDeliveryEvent.project(delivery, policy, 1);

        assertEquals("header-id", projected.identity());
        assertEquals("", projected.payload().get("exchange"));
        assertEquals("", projected.payload().get("routingKey"));
        assertFalse(projected.idempotentKey().contains("header-id"));
    }

    @Test
    void consumerAuthorityIsSeparateTenantScopedAndStrict() {
        String key = EnvironmentAmqpConsumerPolicyResolver.variableName(AmqpTestSupport.TENANT,
                AmqpTestSupport.PROFILE);
        String value = "orders.q;4;trace,identity;identity;4096;1024;100;1000;3;dead-letter;500";
        var resolver = new EnvironmentAmqpConsumerPolicyResolver(Map.of(key, value));

        assertTrue(resolver.resolve(AmqpTestSupport.TENANT, AmqpTestSupport.PROFILE).isPresent());
        assertTrue(resolver.resolve("other", AmqpTestSupport.PROFILE).isEmpty());
        assertTrue(new EnvironmentAmqpConsumerPolicyResolver(Map.of()).resolve(
                AmqpTestSupport.TENANT, AmqpTestSupport.PROFILE).isEmpty());
        assertTrue(new EnvironmentAmqpConsumerPolicyResolver(Map.of(key, value + ";extra")).resolve(
                AmqpTestSupport.TENANT, AmqpTestSupport.PROFILE).isEmpty());
        assertFalse(key.equals(EnvironmentAmqpProfileResolver.environmentVariableName(
                AmqpTestSupport.TENANT, AmqpTestSupport.PROFILE)));
    }

    @Test
    void reconnectPolicyRejectsStormProneBackoffBounds() {
        assertThrows(IllegalArgumentException.class, () -> new AmqpConsumerPolicy(
                AmqpTestSupport.TENANT, AmqpTestSupport.PROFILE, "orders.q", 4, java.util.Set.of(), "",
                4_096, 1_024, 1, 1_000, 3, "reject", 10));
        assertThrows(IllegalArgumentException.class, () -> new AmqpConsumerPolicy(
                AmqpTestSupport.TENANT, AmqpTestSupport.PROFILE, "orders.q", 4, java.util.Set.of(), "",
                4_096, 1_024, 100, 999, 3, "reject", 10));
    }

    @Test
    void producerIdentityReplayDeduplicatesButContentCollisionsRemainDistinct() {
        var owner = new AmqpConsumerTestSupport.FakeOwner(4, 1);
        var ingress = new AmqpConsumerTestSupport.Ingress(4).deduplicate();
        source = source(new AmqpConsumerTestSupport.FakeProtocol(owner), AmqpConsumerTestSupport.policy(),
                configuration(Map.of()), ignored -> secret());
        source.start(new AmqpConsumerTestSupport.Context(ingress)).toCompletableFuture().join();
        var original = delivery(71, true, "created", "hello");

        owner.deliver(delivery(70, false, "created", "hello"));
        owner.deliver(original);
        owner.deliver(delivery(72, true, "created", "different-body"));
        owner.deliver(delivery(73, true, "different-route", "hello"));
        AmqpConsumerTestSupport.await(owner.acked);

        assertEquals(ingress.keys.get(0), ingress.keys.get(1));
        assertNotEquals(ingress.keys.get(0), ingress.keys.get(2));
        assertNotEquals(ingress.keys.get(0), ingress.keys.get(3));
        assertEquals(java.util.List.of("DurablyCommitted", "Duplicate", "DurablyCommitted", "DurablyCommitted"),
                ingress.outcomes);
        assertEquals(java.util.List.of(70L, 71L, 72L, 73L), owner.acks);
    }

    @Test
    void unknownGraphFieldsAndMismatchedResolvedAuthorityFailBeforeCredentialOrNetwork() {
        var protocol = new AmqpConsumerTestSupport.FakeProtocol(new AmqpConsumerTestSupport.FakeOwner());
        AtomicInteger credentials = new AtomicInteger();
        source = new AmqpConsumerSource(configuration(Map.of("host", "attacker.example")), reference -> {
            credentials.incrementAndGet(); return secret();
        }, (tenant, name) -> Optional.of(AmqpTestSupport.profile(tenant, name, 4, 100, 1_000, 2)),
                (tenant, name) -> Optional.of(AmqpConsumerTestSupport.policy()), protocol,
                virtualExecutor(), Clock.systemUTC());
        var context = new AmqpConsumerTestSupport.Context(new AmqpConsumerTestSupport.Ingress());

        assertThrows(CompletionException.class, () -> source.start(context).toCompletableFuture().join());
        assertEquals(0, credentials.get());
        assertEquals(0, protocol.openCalls.get());
        assertTrue(context.degraded.contains("unknown-graph-property"));

        source = new AmqpConsumerSource(configuration(Map.of()), ignored -> secret(),
                (tenant, name) -> Optional.of(AmqpTestSupport.profile("other", name, 4, 100, 1_000, 2)),
                (tenant, name) -> Optional.of(AmqpConsumerTestSupport.policy()), protocol,
                virtualExecutor(), Clock.systemUTC());
        var mismatched = new AmqpConsumerTestSupport.Context(new AmqpConsumerTestSupport.Ingress());
        assertThrows(CompletionException.class, () -> source.start(mismatched).toCompletableFuture().join());
        assertEquals(0, protocol.openCalls.get());
        assertTrue(mismatched.degraded.contains("amqp-profile-unavailable"));
    }

    private AmqpConsumeNodeBehavior behavior(AmqpConsumerProtocol protocol) {
        return new AmqpConsumeNodeBehavior(ignored -> secret(),
                (tenant, name) -> Optional.of(AmqpTestSupport.profile(tenant, name, 4, 100, 1_000, 2)),
                (tenant, name) -> Optional.of(AmqpConsumerTestSupport.policy()), protocol,
                virtualExecutor(), Clock.systemUTC());
    }

    private AmqpConsumerSource source(AmqpConsumerProtocol protocol, AmqpConsumerPolicy policy,
                                      NodeConfiguration configuration,
                                      ai.ravenroot.api.security.CredentialResolver credentials) {
        source = new AmqpConsumerSource(configuration, credentials,
                (tenant, name) -> Optional.of(AmqpTestSupport.profile(tenant, name, 4, 100, 1_000, 2)),
                (tenant, name) -> Optional.of(policy), protocol, virtualExecutor(), Clock.systemUTC());
        return source;
    }

    private AmqpConsumerSource source(AmqpConsumerProtocol protocol, AmqpConsumerPolicy policy,
                                      NodeConfiguration configuration,
                                      ai.ravenroot.api.security.CredentialResolver credentials,
                                      java.util.function.IntConsumer reconnectObserver,
                                      java.util.function.DoubleSupplier reconnectJitter) {
        source = new AmqpConsumerSource(configuration, credentials,
                (tenant, name) -> Optional.of(AmqpTestSupport.profile(tenant, name, 4, 100, 1_000, 2)),
                (tenant, name) -> Optional.of(policy), protocol, virtualExecutor(), Clock.systemUTC(),
                reconnectObserver, reconnectJitter);
        return source;
    }

    private static AmqpConsumerProtocol.Delivery delivery(long tag, boolean redelivered,
                                                           String routingKey, String body) {
        return new AmqpConsumerProtocol.Delivery(tag, redelivered, "orders", routingKey,
                body.getBytes(StandardCharsets.UTF_8), new AmqpConsumerProtocol.Properties(
                "text/plain", "utf-8", "collision", "c-1", null, "order", "test", 1_000, Map.of()));
    }

    private static Optional<SecretValue> secret() {
        return Optional.of(new SecretValue(AmqpTestSupport.SECRET.toCharArray()));
    }

    private static java.util.concurrent.Executor virtualExecutor() {
        return task -> Thread.ofVirtual().name("amqp-consumer-test").start(task);
    }

    private static NodeConfiguration configuration(Map<String, Object> overrides) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("brokerProfile", AmqpTestSupport.PROFILE);
        values.putAll(overrides);
        return new NodeConfiguration("consume", AmqpConsumeNodeBehavior.BEHAVIOR, values);
    }
}
