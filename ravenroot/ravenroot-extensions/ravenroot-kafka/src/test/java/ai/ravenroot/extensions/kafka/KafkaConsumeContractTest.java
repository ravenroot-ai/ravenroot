package ai.ravenroot.extensions.kafka;

import ai.ravenroot.api.catalog.NodeTypeDescriptorValidator;
import ai.ravenroot.api.deployment.IngressReceipt;
import ai.ravenroot.api.security.SecretValue;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class KafkaConsumeContractTest {
    @Test void descriptorIsConditionalCompleteAndCatalogValid() {
        var descriptor = new KafkaConsumeNodeBehavior().descriptor();
        NodeTypeDescriptorValidator.validate(descriptor);
        assertEquals(KafkaConsumeNodeBehavior.BEHAVIOR, descriptor.behavior());
        assertTrue(descriptor.capabilities().contains("inbound-source"));
        var topics = descriptor.properties().stream().filter(p -> p.name().equals("topics")).findFirst().orElseThrow();
        assertEquals("subscriptionMode", topics.visibleWhen().property());
        assertEquals(topics.visibleWhen(), topics.requiredWhen());
        var dlq = descriptor.properties().stream().filter(p -> p.name().equals("deadLetterTopic")).findFirst().orElseThrow();
        assertEquals("poisonPolicy", dlq.visibleWhen().property());
    }

    @Test void officialPropertiesAreManualByteSafeReadCommittedAndProfileOwned() {
        Map<String, Object> values = ApacheKafkaConsumerProtocol.consumerProperties(
                KafkaConsumerTestSupport.profile(), "secret".toCharArray());
        assertEquals(false, values.get("enable.auto.commit"));
        assertEquals("read_committed", values.get("isolation.level"));
        assertEquals(false, values.get("allow.auto.create.topics"));
        assertEquals("rr-orders-v1", values.get("group.id"));
        assertFalse(values.containsKey("key.deserializer"));
        assertFalse(values.containsKey("value.deserializer"));
        assertFalse(values.containsKey("interceptor.classes"));
        assertInstanceOf(org.apache.kafka.common.config.types.Password.class, values.get("sasl.jaas.config"));
        assertEquals("[hidden]", values.get("sasl.jaas.config").toString());
        assertFalse(values.toString().contains("secret"), "raw properties snapshots must mask JAAS");
        var jaasKey = org.apache.kafka.clients.consumer.ConsumerConfig.configDef().configKeys()
                .get(org.apache.kafka.common.config.SaslConfigs.SASL_JAAS_CONFIG);
        assertEquals(org.apache.kafka.common.config.ConfigDef.Type.PASSWORD, jaasKey.type);
        assertEquals("[hidden]", org.apache.kafka.common.config.ConfigDef.convertToString(
                values.get("sasl.jaas.config"), jaasKey.type));
    }

    @Test void noBrokerOrSecretBeforeStartAndUnsupportedDurabilityFailsCleanly() {
        AtomicInteger credentials = new AtomicInteger(), opens = new AtomicInteger();
        KafkaConsumerProtocol protocol = (profile, password) -> { opens.incrementAndGet(); return new KafkaConsumerTestSupport.FakeOwner(); };
        var behavior = new KafkaConsumeNodeBehavior(ref -> { credentials.incrementAndGet(); return Optional.of(new SecretValue("s".toCharArray())); },
                (tenant, name) -> Optional.of(KafkaConsumerTestSupport.profile()), protocol, Runnable::run, Clock.systemUTC());
        var context = new KafkaConsumerTestSupport.FakeContext(new KafkaConsumerTestSupport.RecordingIngress());
        var source = behavior.createSource(KafkaConsumerTestSupport.configuration(), context);
        assertEquals(0, credentials.get()); assertEquals(0, opens.get());
        context.ingress.checkpoint = false;
        assertThrows(Exception.class, () -> source.start(context).toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertEquals(0, credentials.get()); assertEquals(0, opens.get());
        assertTrue(context.degraded.contains("durable-ingress-required"));
    }

    @Test void assignmentDefinesReadinessRecordsStayOrderedAndOnlyContiguousDurableOffsetsCommit() throws Exception {
        var owner = new KafkaConsumerTestSupport.FakeOwner();
        owner.polls.add(List.of(KafkaConsumerTestSupport.record(10, "a"), KafkaConsumerTestSupport.record(11, "b")));
        var ingress = new KafkaConsumerTestSupport.RecordingIngress(3);
        ingress.receipts.add(new IngressReceipt.Ambiguous("k", "unknown"));
        ingress.receipts.add(new IngressReceipt.DurablyCommitted("k"));
        ingress.receipts.add(new IngressReceipt.DurablyCommitted("k2"));
        var context = new KafkaConsumerTestSupport.FakeContext(ingress);
        var source = source(owner);
        source.start(context).toCompletableFuture().get(1, TimeUnit.SECONDS);
        await(ingress.offered); await(owner.committed);
        source.stop().toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertEquals(List.of(10L, 10L, 11L), ingress.payloads.stream().map(p -> (Long) p.get("offset")).toList());
        assertEquals(12L, owner.commits.getLast().get(KafkaConsumerTestSupport.ORDERS));
        assertTrue(owner.pauses.stream().anyMatch(p -> p.contains(KafkaConsumerTestSupport.ORDERS)));
        assertEquals("kafka.record.v1", ingress.payloads.getFirst().get("version"));
        assertEquals("reader/orders-logical/orders/0/10", ingress.keys.getFirst());
    }

    @Test void legalKafkaOffsetGapsAdvanceInConsumedOrderWithoutInventingMissingRecords() throws Exception {
        var owner = new KafkaConsumerTestSupport.FakeOwner();
        owner.polls.add(List.of(KafkaConsumerTestSupport.record(10, "a"), KafkaConsumerTestSupport.record(12, "b")));
        var ingress = new KafkaConsumerTestSupport.RecordingIngress();
        var context = new KafkaConsumerTestSupport.FakeContext(ingress);
        var source = source(owner);
        source.start(context).toCompletableFuture().get(1, TimeUnit.SECONDS);
        await(ingress.offered); await(owner.committed);
        source.stop().toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertEquals(List.of(10L, 12L), ingress.payloads.stream().map(p -> (Long) p.get("offset")).toList());
    }

    @Test void poisonDeadLetterMustAcknowledgeBeforeOffsetAdvances() throws Exception {
        var owner = new KafkaConsumerTestSupport.FakeOwner(); owner.deadLetterSucceeds = true;
        owner.polls.add(List.of(KafkaConsumerTestSupport.record(3, "bad")));
        var ingress = new KafkaConsumerTestSupport.RecordingIngress(2);
        ingress.receipts.add(new IngressReceipt.Refused("full"));
        ingress.receipts.add(new IngressReceipt.Refused("full"));
        var context = new KafkaConsumerTestSupport.FakeContext(ingress);
        var source = source(owner, KafkaConsumerTestSupport.deadLetterProfile(), Map.of(
                "poisonPolicy", "dead-letter", "deadLetterTopic", "orders-dlq"));
        source.start(context).toCompletableFuture().get(1, TimeUnit.SECONDS);
        await(owner.deadLettered); await(owner.committed);
        source.stop().toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertEquals(4L, owner.commits.getLast().get(KafkaConsumerTestSupport.ORDERS));
    }

    @Test void hiddenModeFieldsAreIgnoredAndGraphTighteningReachesKafkaMaxPollRecords() throws Exception {
        var owner = new KafkaConsumerTestSupport.FakeOwner();
        java.util.concurrent.atomic.AtomicReference<KafkaConsumerProfile> opened = new java.util.concurrent.atomic.AtomicReference<>();
        KafkaConsumerProtocol protocol = (profile, password) -> { opened.set(profile); return owner; };
        var behavior = new KafkaConsumeNodeBehavior(ref -> Optional.of(new SecretValue("secret".toCharArray())),
                (tenant, name) -> Optional.of(KafkaConsumerTestSupport.profile()), protocol,
                task -> Thread.ofVirtual().start(task), Clock.systemUTC());
        var properties = new java.util.LinkedHashMap<String,Object>();
        properties.put("clusterProfile", "reader"); properties.put("maxInFlight", "2");
        properties.put("topics", "forbidden-hidden"); properties.put("deadLetterTopic", "forbidden-hidden");
        var source = behavior.createSource(new ai.ravenroot.api.node.NodeConfiguration("consume",
                KafkaConsumeNodeBehavior.BEHAVIOR, properties),
                new KafkaConsumerTestSupport.FakeContext(new KafkaConsumerTestSupport.RecordingIngress()));
        var context = new KafkaConsumerTestSupport.FakeContext(new KafkaConsumerTestSupport.RecordingIngress());
        source.start(context).toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertEquals(2, opened.get().maxInFlight());
        source.stop().toCompletableFuture().get(1, TimeUnit.SECONDS);
    }

    @Test void volatileCustodyNeverCommitsAndStopCleansUpWithoutShutdown() throws Exception {
        var owner = new KafkaConsumerTestSupport.FakeOwner();
        owner.polls.add(List.of(KafkaConsumerTestSupport.record(0, "a")));
        var ingress = new KafkaConsumerTestSupport.RecordingIngress();
        ingress.receipts.add(new IngressReceipt.VolatileCustody());
        var context = new KafkaConsumerTestSupport.FakeContext(ingress);
        var source = source(owner);
        assertThrows(Exception.class,
                () -> source.start(context).toCompletableFuture().get(1, TimeUnit.SECONDS));
        await(context.degradedReported);
        source.stop().toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertTrue(owner.commits.isEmpty());
        assertEquals(1, owner.closes.get());
    }

    @Test void revokedGenerationCommitsSafeFrontierOnlyAndLostNeverCommits() throws Exception {
        var owner = new KafkaConsumerTestSupport.FakeOwner();
        owner.polls.add(List.of(KafkaConsumerTestSupport.record(5, "a")));
        var ingress = new KafkaConsumerTestSupport.RecordingIngress();
        var context = new KafkaConsumerTestSupport.FakeContext(ingress);
        var source = source(owner);
        source.start(context).toCompletableFuture().get(1, TimeUnit.SECONDS);
        await(owner.committed);
        int before = owner.commits.size();
        owner.revoke(true);
        await(owner.rebalanceProcessed);
        assertEquals(before, owner.commits.size());
        source.stop().toCompletableFuture().get(1, TimeUnit.SECONDS);
    }

    @Test void revokedDrainCommitsOnlyPriorContiguousSafeFrontierAndFencesPendingGeneration() throws Exception {
        var owner = new KafkaConsumerTestSupport.FakeOwner().gateSecondPoll();
        owner.polls.add(List.of(KafkaConsumerTestSupport.record(0, "safe"),
                KafkaConsumerTestSupport.record(1, "pending")));
        var ingress = new KafkaConsumerTestSupport.RecordingIngress(2);
        ingress.receipts.add(new IngressReceipt.DurablyCommitted("safe"));
        ingress.receipts.add(new IngressReceipt.Ambiguous("pending", "unknown"));
        var context = new KafkaConsumerTestSupport.FakeContext(ingress);
        var source = source(owner, drainZeroProfile(), Map.of());
        source.start(context).toCompletableFuture().get(1, TimeUnit.SECONDS);
        await(owner.committed); await(owner.secondPollEntered);
        long beforeGeneration = source.generation();
        owner.revoke(false); owner.releaseSecondPoll(); await(owner.rebalanceProcessed);
        assertTrue(source.generation() > beforeGeneration);
        assertEquals(1L, owner.commits.stream()
                .map(offsets -> offsets.get(KafkaConsumerTestSupport.ORDERS))
                .filter(java.util.Objects::nonNull).mapToLong(Long::longValue).max().orElseThrow());
        assertEquals(List.of(0L, 1L), ingress.payloads.stream().map(p -> (Long) p.get("offset")).toList());
        source.stop().toCompletableFuture().get(1, TimeUnit.SECONDS);
    }

    @Test void stopAfterFinalAssignmentObservationCannotBeOverwrittenByReadiness() throws Exception {
        var owner = new KafkaConsumerTestSupport.FakeOwner().gateReadinessCheck();
        var context = new KafkaConsumerTestSupport.FakeContext(new KafkaConsumerTestSupport.RecordingIngress());
        var source = source(owner);
        var start = source.start(context).toCompletableFuture();
        await(owner.readinessCheckEntered);
        var stopped = source.stop().toCompletableFuture();
        assertEquals(KafkaConsumerSource.State.STOPPING, source.state());
        owner.releaseReadinessCheck();
        stopped.get(1, TimeUnit.SECONDS); await(owner.closed);
        assertTrue(start.isCompletedExceptionally());
        assertEquals(0, context.healthy.get());
        assertEquals(KafkaConsumerSource.State.STOPPED, source.state());
        assertEquals(1, owner.closes.get());
    }

    @Test void readinessRemainsStartingUntilAssignmentAndReportsHealthyExactlyOnce() throws Exception {
        var owner = new KafkaConsumerTestSupport.FakeOwner(true, false);
        var context = new KafkaConsumerTestSupport.FakeContext(new KafkaConsumerTestSupport.RecordingIngress());
        var source = source(owner);
        var start = source.start(context).toCompletableFuture();
        await(owner.pollEntered);
        assertEquals(KafkaConsumerSource.State.STARTING, source.state());
        assertEquals(0, context.healthy.get());
        assertFalse(start.isDone());
        owner.releaseAssignment();
        start.get(1, TimeUnit.SECONDS);
        await(context.healthyReported);
        assertEquals(KafkaConsumerSource.State.READY, source.state());
        assertEquals(1, context.healthy.get());
        source.stop().toCompletableFuture().get(1, TimeUnit.SECONDS);
    }

    @Test void stopAndRollbackDuringStartWakeAndCloseWithoutPublishingReadiness() throws Exception {
        for (boolean rollback : List.of(false, true)) {
            var owner = new KafkaConsumerTestSupport.FakeOwner(true, false);
            var context = new KafkaConsumerTestSupport.FakeContext(new KafkaConsumerTestSupport.RecordingIngress());
            var source = source(owner);
            var start = source.start(context).toCompletableFuture();
            await(owner.pollEntered);
            var terminal = (rollback ? source.rollback() : source.stop()).toCompletableFuture();
            terminal.get(1, TimeUnit.SECONDS); await(owner.closed);
            assertTrue(start.isCompletedExceptionally());
            assertEquals(0, context.healthy.get());
            assertEquals(1, owner.closes.get());
            assertEquals(KafkaConsumerSource.State.STOPPED, source.state());
        }
    }

    @Test void poisonHaltTerminatesOwnerAndRestartWaitsForCloseBeforeOpeningOneReplacement() throws Exception {
        var first = new KafkaConsumerTestSupport.FakeOwner(false, true);
        first.polls.add(List.of(KafkaConsumerTestSupport.record(0, "poison")));
        var second = new KafkaConsumerTestSupport.FakeOwner();
        var owners = new java.util.concurrent.ConcurrentLinkedQueue<>(List.of(first, second));
        var opens = new AtomicInteger();
        KafkaConsumerProtocol protocol = (profile, password) -> { opens.incrementAndGet(); return owners.remove(); };
        var behavior = behavior(protocol, KafkaConsumerTestSupport.haltImmediatelyProfile());
        var ingress = new KafkaConsumerTestSupport.RecordingIngress();
        ingress.receipts.add(new IngressReceipt.Refused("full"));
        var context = new KafkaConsumerTestSupport.FakeContext(ingress);
        var source = (KafkaConsumerSource) behavior.createSource(KafkaConsumerTestSupport.configuration(), context);
        assertThrows(Exception.class, () -> source.start(context).toCompletableFuture().get(1, TimeUnit.SECONDS));
        await(first.closeEntered);
        var restart = source.start(context).toCompletableFuture();
        assertFalse(restart.isDone());
        assertEquals(1, opens.get());
        first.releaseClose(); await(first.closed);
        restart.get(1, TimeUnit.SECONDS);
        assertEquals(2, opens.get());
        assertEquals(1, first.closes.get());
        assertEquals(0, second.closes.get());
        source.stop().toCompletableFuture().get(1, TimeUnit.SECONDS); await(second.closed);
        assertEquals(1, second.closes.get());
    }

    @Test void pauseStillPollsForHeartbeatsAndResumesAfterAmbiguousRecordBecomesSafe() throws Exception {
        var owner = new KafkaConsumerTestSupport.FakeOwner();
        owner.polls.add(List.of(KafkaConsumerTestSupport.record(0, "a")));
        var ingress = new KafkaConsumerTestSupport.RecordingIngress(2);
        ingress.receipts.add(new IngressReceipt.Ambiguous("k", "unknown"));
        ingress.receipts.add(new IngressReceipt.DurablyCommitted("k"));
        var context = new KafkaConsumerTestSupport.FakeContext(ingress);
        var source = source(owner);
        source.start(context).toCompletableFuture().get(1, TimeUnit.SECONDS);
        await(owner.paused); await(owner.resumed); await(owner.committed);
        assertTrue(owner.pollCalls.get() >= 2, "poll must continue while partitions are paused");
        source.stop().toCompletableFuture().get(1, TimeUnit.SECONDS);
    }

    @Test void restartAfterCleanStopCreatesExactlyOneNewOwner() throws Exception {
        var first = new KafkaConsumerTestSupport.FakeOwner();
        var second = new KafkaConsumerTestSupport.FakeOwner();
        var owners = new java.util.concurrent.ConcurrentLinkedQueue<>(List.of(first, second));
        var opens = new AtomicInteger();
        KafkaConsumerProtocol protocol = (profile, password) -> { opens.incrementAndGet(); return owners.remove(); };
        var behavior = behavior(protocol, KafkaConsumerTestSupport.profile());
        var context = new KafkaConsumerTestSupport.FakeContext(new KafkaConsumerTestSupport.RecordingIngress());
        var source = (KafkaConsumerSource) behavior.createSource(KafkaConsumerTestSupport.configuration(), context);
        source.start(context).toCompletableFuture().get(1, TimeUnit.SECONDS);
        source.stop().toCompletableFuture().get(1, TimeUnit.SECONDS); await(first.closed);
        source.start(context).toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertEquals(2, opens.get());
        source.stop().toCompletableFuture().get(1, TimeUnit.SECONDS); await(second.closed);
    }

    @Test void rebalanceReconnectReturnsToReadyOutsideAssignmentCallback() throws Exception {
        var owner = new KafkaConsumerTestSupport.FakeOwner();
        var context = new KafkaConsumerTestSupport.FakeContext(
                new KafkaConsumerTestSupport.RecordingIngress(), 2);
        var source = source(owner);
        source.start(context).toCompletableFuture().get(1, TimeUnit.SECONDS);
        long firstGeneration = source.generation();
        owner.reconnect(); await(owner.rebalanceProcessed); await(context.healthyReported);
        assertTrue(source.generation() > firstGeneration);
        assertEquals(KafkaConsumerSource.State.READY, source.state());
        assertEquals(2, context.healthy.get(), "initial readiness plus recovered service");
        source.stop().toCompletableFuture().get(1, TimeUnit.SECONDS);
    }

    @Test void oversizedValueAndAllowlistedHeadersAreRejectedBeforeIngress() {
        KafkaConsumerProfile p = KafkaConsumerTestSupport.profile();
        byte[] huge = new byte[p.maxValueBytes() + 1];
        var oversized = new KafkaConsumerProtocol.Record(KafkaConsumerTestSupport.ORDERS, 0, 0, "CREATE_TIME",
                null, huge, List.of(), 1, -1, huge.length);
        assertEquals("record-too-large", assertThrows(KafkaRecordEvent.InvalidRecord.class,
                () -> KafkaRecordEvent.from(oversized, p, 1, "c")).safeReason());
        byte[] header = new byte[p.maxHeaderBytes() + 1];
        var headers = new KafkaConsumerProtocol.Record(KafkaConsumerTestSupport.ORDERS, 0, 0, "CREATE_TIME",
                null, new byte[]{1}, List.of(new KafkaConsumerProtocol.Header("trace", header)), 1, -1, 1);
        assertEquals("headers-too-large", assertThrows(KafkaRecordEvent.InvalidRecord.class,
                () -> KafkaRecordEvent.from(headers, p, 1, "c")).safeReason());
    }

    private static KafkaConsumerSource source(KafkaConsumerTestSupport.FakeOwner owner) {
        return source(owner, KafkaConsumerTestSupport.profile(), Map.of());
    }

    private static KafkaConsumerSource source(KafkaConsumerTestSupport.FakeOwner owner,
                                               KafkaConsumerProfile profile, Map<String,Object> overrides) {
        KafkaConsumerProtocol protocol = (openedProfile, password) -> owner;
        var properties = new java.util.LinkedHashMap<String,Object>();
        properties.put("clusterProfile", "reader"); properties.putAll(overrides);
        return (KafkaConsumerSource) behavior(protocol, profile)
                .createSource(new ai.ravenroot.api.node.NodeConfiguration("consume",
                                KafkaConsumeNodeBehavior.BEHAVIOR, properties),
                        new KafkaConsumerTestSupport.FakeContext(new KafkaConsumerTestSupport.RecordingIngress()));
    }

    private static KafkaConsumeNodeBehavior behavior(KafkaConsumerProtocol protocol, KafkaConsumerProfile profile) {
        return new KafkaConsumeNodeBehavior(ref -> Optional.of(new SecretValue("secret".toCharArray())),
                (tenant, name) -> Optional.of(profile), protocol,
                task -> Thread.ofVirtual().start(task), Clock.systemUTC());
    }

    private static KafkaConsumerProfile drainZeroProfile() {
        KafkaConsumerProfile p = KafkaConsumerTestSupport.profile();
        return new KafkaConsumerProfile(p.tenant(), p.name(), p.bootstrapServers(), p.clientDnsLookup(), p.tls(),
                p.saslMechanism(), p.username(), p.credentialRef(), p.clientId(), p.groupLogicalName(), p.groupId(),
                p.staticMemberId(), p.topics(), p.topicPattern(), p.headers(), p.assignmentStrategy(),
                p.autoOffsetReset(), p.isolationLevel(), p.startupTimeoutMs(), p.pollTimeoutMs(), p.maxPollIntervalMs(),
                p.sessionTimeoutMs(), p.heartbeatIntervalMs(), p.maxInFlight(), p.maxFetchBytes(),
                p.maxPartitionFetchBytes(), p.maxRecordBytes(), p.maxKeyBytes(), p.maxValueBytes(), p.maxHeaderBytes(),
                0, p.retryBackoffMs(), p.maxRetryBackoffMs(), p.poisonAttempts(), p.poisonPolicy(), p.deadLetterTopic());
    }

    private static void await(CountDownLatch latch) throws Exception {
        assertTrue(latch.await(1, TimeUnit.SECONDS), "deterministic test gate timed out");
    }
}
