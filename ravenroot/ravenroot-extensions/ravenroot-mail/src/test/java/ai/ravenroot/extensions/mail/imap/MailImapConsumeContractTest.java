package ai.ravenroot.extensions.mail.imap;

import ai.ravenroot.api.catalog.NodeTypeDescriptorValidator;
import ai.ravenroot.api.deployment.IngressReceipt;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.security.SecretValue;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MailImapConsumeContractTest {
    private ImapConsumerSource source;

    @AfterEach void stopSource() {
        if (source != null) source.stop().toCompletableFuture().orTimeout(3, TimeUnit.SECONDS).join();
    }

    @Test void descriptorIsConditionalCompleteAndContainsNoEndpointOrCredentialField() {
        var descriptor = behavior(new ImapConsumerTestSupport.FakeProtocol()).descriptor();
        NodeTypeDescriptorValidator.validate(descriptor);
        assertEquals("mail.imap.consume", descriptor.behavior());
        var preview = descriptor.properties().stream().filter(p -> p.name().equals("previewChars"))
                .findFirst().orElseThrow();
        assertEquals("contentMode", preview.visibleWhen().property());
        assertEquals(preview.visibleWhen(), preview.requiredWhen());
        assertTrue(descriptor.capabilities().contains("inbound-source"));
        assertFalse(descriptor.properties().stream().map(p -> p.name()).anyMatch(
                name -> name.matches("(?i).*(host|port|password|credential|username|tls).*")));
    }

    @Test void constructionIsInert() {
        AtomicInteger profile = new AtomicInteger(), policy = new AtomicInteger(), credential = new AtomicInteger();
        var protocol = new ImapConsumerTestSupport.FakeProtocol(new ImapConsumerTestSupport.FakeOwner());
        source = new ImapConsumerSource(configuration(Map.of()), ignored -> {
            credential.incrementAndGet(); return secret();
        }, (tenant, id) -> { profile.incrementAndGet(); return Optional.of(ImapConsumerTestSupport.profile()); },
                (tenant, id) -> { policy.incrementAndGet(); return Optional.of(ImapConsumerTestSupport.policy()); },
                protocol, virtualExecutor(), Clock.systemUTC());
        assertEquals(0, profile.get() + policy.get() + credential.get() + protocol.openCalls.get());
    }

    @Test void durabilityProbePrecedesCredentialAndNetwork() {
        var ingress = new ImapConsumerTestSupport.Ingress();
        ingress.durable = false;
        AtomicInteger credential = new AtomicInteger();
        var protocol = new ImapConsumerTestSupport.FakeProtocol(new ImapConsumerTestSupport.FakeOwner());
        source = source(protocol, configuration(Map.of()), ignored -> {
            credential.incrementAndGet(); return secret();
        });
        var context = new ImapConsumerTestSupport.Context(ingress);
        assertThrows(CompletionException.class, () -> source.start(context).toCompletableFuture().join());
        assertEquals(0, credential.get());
        assertEquals(0, protocol.openCalls.get());
        assertTrue(context.degraded.contains("durable-ingress-required"));
    }

    @Test void deploymentScopedOpaqueCheckpointDestinationIsAcceptedAndPreserved() {
        var ingress = new ImapConsumerTestSupport.Ingress();
        String sourceId = ImapMessageEvent.sourceId("consume", ImapConsumerTestSupport.PROFILE, "INBOX", 42);
        ingress.checkpointOverride = java.util.concurrent.CompletableFuture.completedFuture(
                new ai.ravenroot.api.persistence.JournalCursor(ImapConsumerTestSupport.TENANT,
                        "stable-deployment/" + sourceId, 0));
        source = source(new ImapConsumerTestSupport.FakeProtocol(new ImapConsumerTestSupport.FakeOwner()),
                configuration(Map.of()), ignored -> secret());

        source.start(new ImapConsumerTestSupport.Context(ingress)).toCompletableFuture().join();

        assertEquals(ImapConsumerSource.State.READY, source.state());
        assertEquals(0, ingress.checkpointOverride.join().deliveredThrough());
    }

    @Test void graphMlRereadPreservesHiddenPreviewValueButMetadataModeNeverReadsIt() throws Exception {
        var definition = new ai.ravenroot.core.graph.GraphDefinition(java.util.List.of(
                ai.ravenroot.core.graph.GraphNode.start("start"),
                new ai.ravenroot.core.graph.GraphNode("consume", ai.ravenroot.core.graph.NodeKind.BEHAVIOR,
                        MailImapConsumeNodeBehavior.BEHAVIOR, Map.of("profile", ImapConsumerTestSupport.PROFILE,
                        "contentMode", "metadata", "previewChars", "999999")),
                ai.ravenroot.core.graph.GraphNode.error("error"), ai.ravenroot.core.graph.GraphNode.end("end")),
                java.util.List.of());
        byte[] xml;
        try (var graph = ai.ravenroot.core.graph.GraphManager.from(definition);
             var output = new java.io.ByteArrayOutputStream()) {
            graph.writeGraphMl(output);
            xml = output.toByteArray();
        }
        Map<String, Object> rereadProperties;
        try (var reread = ai.ravenroot.core.graph.GraphManager.readGraphMl(
                new java.io.ByteArrayInputStream(xml))) {
            rereadProperties = Map.copyOf(reread.definition().node("consume").properties());
        }
        assertEquals("999999", rereadProperties.get("previewChars"),
                "GraphML round-trip preserves hidden author data");
        source = source(new ImapConsumerTestSupport.FakeProtocol(new ImapConsumerTestSupport.FakeOwner()),
                new NodeConfiguration("consume", MailImapConsumeNodeBehavior.BEHAVIOR, rereadProperties),
                ignored -> secret());

        source.start(new ImapConsumerTestSupport.Context(new ImapConsumerTestSupport.Ingress()))
                .toCompletableFuture().join();

        assertEquals(ImapConsumerSource.State.READY, source.state(),
                "hidden previewChars is inert when metadata mode is active");
    }

    @Test void readyThenMessageProducesBoundedVersionedIdentityAndCheckpoint() {
        var owner = new ImapConsumerTestSupport.FakeOwner();
        var ingress = new ImapConsumerTestSupport.Ingress().gateOffer();
        source = source(new ImapConsumerTestSupport.FakeProtocol(owner),
                configuration(Map.of("contentMode", "preview", "previewChars", "64")), ignored -> secret());
        var context = new ImapConsumerTestSupport.Context(ingress);
        source.start(context).toCompletableFuture().join();
        var message = ImapConsumerTestSupport.message("<m7>", "hello", "body");
        try {
            message.setReplyTo(new jakarta.mail.Address[]{
                    new jakarta.mail.internet.InternetAddress("reply@example.test")});
            message.saveChanges();
        } catch (jakarta.mail.MessagingException failure) {
            throw new AssertionError(failure);
        }
        owner.deliver(7, message);
        ImapConsumerTestSupport.await(ingress.offered);

        Map<String, Object> event = ingress.payloads.getFirst();
        assertEquals("mail.imap.message.v1", event.get("version"));
        assertEquals("message", event.get("kind"));
        assertEquals("INBOX", event.get("sourceFolder"));
        assertEquals(42L, event.get("uidValidity"));
        assertEquals(7L, event.get("uid"));
        assertEquals(java.util.List.of("reply@example.test"), event.get("replyTo"));
        assertEquals(Map.of("version", "mail.imap.checkpoint.v1", "sourceFolder", "INBOX",
                "uidValidity", 42L, "candidateDeliveredThroughUid", 7L), event.get("checkpoint"));
        assertTrue(ingress.advances.isEmpty(),
                "candidate checkpoint must not claim durable advancement before acknowledgement");
        assertFalse(event.toString().contains(ImapConsumerTestSupport.SECRET));
        assertFalse(event.toString().contains("mail.example.test"));
        assertTrue(ingress.keys.getFirst().contains("/cmVhZGVy/SU5CT1g/42/7"));
        ingress.releaseOffer();
        awaitAdvances(ingress, 1);
        assertEquals(java.util.List.of(7L), ingress.advances);
    }

    @Test void graphHeaderSelectionCanOnlyTightenOperatorAuthority() throws Exception {
        var owner = new ImapConsumerTestSupport.FakeOwner();
        var ingress = new ImapConsumerTestSupport.Ingress();
        var policy = new ImapConsumerPolicy(ImapConsumerTestSupport.TENANT,
                ImapConsumerTestSupport.PROFILE, "INBOX", 100, 4, 32, 100, 1_000, 3,
                65_536, "metadata", 0, Set.of("x-trace", "x-operator-only"));
        source = new ImapConsumerSource(configuration(Map.of("allowedHeaders", "X-Trace")),
                ignored -> secret(), (tenant, id) -> Optional.of(ImapConsumerTestSupport.profile()),
                (tenant, id) -> Optional.of(policy), new ImapConsumerTestSupport.FakeProtocol(owner),
                virtualExecutor(), Clock.systemUTC());
        source.start(new ImapConsumerTestSupport.Context(ingress)).toCompletableFuture().join();
        var message = ImapConsumerTestSupport.message("<headers>", "hello", "body");
        message.setHeader("X-Trace", "kept");
        message.setHeader("X-Operator-Only", "tightened-away");
        message.setHeader("Authorization", "secret-value");
        message.saveChanges();
        owner.deliver(13, message);
        ImapConsumerTestSupport.await(ingress.offered);
        @SuppressWarnings("unchecked")
        var headers = (Map<String, java.util.List<String>>) ingress.payloads.getFirst().get("headers");
        assertEquals(Map.of("x-trace", java.util.List.of("kept")), headers);

        source.stop().toCompletableFuture().join();
        var denied = new ImapConsumerSource(configuration(Map.of("allowedHeaders", "X-Not-Granted")),
                ignored -> secret(), (tenant, id) -> Optional.of(ImapConsumerTestSupport.profile()),
                (tenant, id) -> Optional.of(policy),
                new ImapConsumerTestSupport.FakeProtocol(new ImapConsumerTestSupport.FakeOwner()),
                virtualExecutor(), Clock.systemUTC());
        source = denied;
        assertThrows(CompletionException.class, () -> denied.start(
                new ImapConsumerTestSupport.Context(new ImapConsumerTestSupport.Ingress()))
                .toCompletableFuture().join());
    }

    @Test void ambiguousReoffersSameKeyAndOnlyDuplicateAdvances() {
        var owner = new ImapConsumerTestSupport.FakeOwner();
        var ingress = new ImapConsumerTestSupport.Ingress(2);
        ingress.receipts.add(new IngressReceipt.Ambiguous("key", "timeout"));
        ingress.receipts.add(new IngressReceipt.Duplicate("key"));
        source = source(new ImapConsumerTestSupport.FakeProtocol(owner), configuration(Map.of()), ignored -> secret());
        source.start(new ImapConsumerTestSupport.Context(ingress)).toCompletableFuture().join();
        owner.deliver(8, ImapConsumerTestSupport.message("<m8>", "hello", "body"));
        ImapConsumerTestSupport.await(ingress.offered);
        assertEquals(ingress.keys.get(0), ingress.keys.get(1));
        assertEquals(ingress.payloads.get(0).get("checkpoint"), ingress.payloads.get(1).get("checkpoint"));
        awaitAdvances(ingress, 1);
        assertEquals(java.util.List.of(8L), ingress.advances);
    }

    @Test void refusedAndVolatileNeverAdvanceAndPoisonHaltsTruthfully() {
        var owner = new ImapConsumerTestSupport.FakeOwner();
        var ingress = new ImapConsumerTestSupport.Ingress(2);
        ingress.receipts.add(new IngressReceipt.Refused("full"));
        ingress.receipts.add(new IngressReceipt.Refused("full"));
        source = source(new ImapConsumerTestSupport.FakeProtocol(owner),
                configuration(Map.of("poisonAttempts", "2")), ignored -> secret());
        var context = new ImapConsumerTestSupport.Context(ingress);
        source.start(context).toCompletableFuture().join();
        owner.deliver(9, ImapConsumerTestSupport.message("<m9>", "hello", "body"));
        ImapConsumerTestSupport.await(ingress.offered);
        awaitState(ImapConsumerSource.State.FAILED);
        assertTrue(ingress.advances.isEmpty());
        assertTrue(context.degraded.contains("imap-message-poison-halted"));

        source.stop().toCompletableFuture().join();
        var volatileIngress = new ImapConsumerTestSupport.Ingress();
        volatileIngress.receipts.add(new IngressReceipt.VolatileCustody());
        var secondOwner = new ImapConsumerTestSupport.FakeOwner();
        source = source(new ImapConsumerTestSupport.FakeProtocol(secondOwner), configuration(Map.of()), ignored -> secret());
        var secondContext = new ImapConsumerTestSupport.Context(volatileIngress);
        source.start(secondContext).toCompletableFuture().join();
        secondOwner.deliver(10, ImapConsumerTestSupport.message("<m10>", "hello", "body"));
        ImapConsumerTestSupport.await(volatileIngress.offered);
        awaitState(ImapConsumerSource.State.FAILED);
        assertTrue(volatileIngress.advances.isEmpty());
        assertTrue(secondContext.degraded.contains("durable-ingress-lost"));
    }

    @Test void projectionFailureEmitsTypedPoisonOnlyAndAdvancesAfterDurableReceipt() throws Exception {
        MimeMessage oversized = ImapConsumerTestSupport.message("<bad>", "bad", "body");
        oversized.setHeader("Content-Length", "999999");
        Message hostile = new MimeMessage(oversized) { @Override public int getSize() { return 999_999; } };
        var owner = new ImapConsumerTestSupport.FakeOwner();
        var ingress = new ImapConsumerTestSupport.Ingress();
        source = source(new ImapConsumerTestSupport.FakeProtocol(owner), configuration(Map.of()), ignored -> secret());
        source.start(new ImapConsumerTestSupport.Context(ingress)).toCompletableFuture().join();
        owner.deliver(11, hostile);
        ImapConsumerTestSupport.await(ingress.offered);
        assertEquals("poison", ingress.payloads.getFirst().get("kind"));
        assertEquals(Map.of("type", "projection", "reason", "message-size-invalid"),
                ingress.payloads.getFirst().get("failure"));
        assertEquals(java.util.List.of(11L), ingress.advances);
    }

    @Test void transientLazyProjectionFailureReconnectsAndNeverPoisonsOrAdvances() throws Exception {
        var first = new ImapConsumerTestSupport.FakeOwner();
        var second = new ImapConsumerTestSupport.FakeOwner();
        var protocol = new ImapConsumerTestSupport.FakeProtocol(first, second);
        var ingress = new ImapConsumerTestSupport.Ingress();
        source = source(protocol, configuration(Map.of()), ignored -> secret());
        var context = new ImapConsumerTestSupport.Context(ingress, 2);
        source.start(context).toCompletableFuture().join();
        Message disconnected = new MimeMessage(Session.getInstance(new Properties())) {
            @Override public int getSize() { return 128; }
            @Override public String getSubject() throws jakarta.mail.MessagingException {
                throw new jakarta.mail.MessagingException("raw-server-sentinel");
            }
        };
        first.deliver(13, disconnected);
        ImapConsumerTestSupport.await(second.pollEntered);
        assertTrue(ingress.payloads.isEmpty());
        assertTrue(ingress.advances.isEmpty());
        assertTrue(context.degraded.contains("message-projection-unavailable"));
        assertFalse(context.degraded.toString().contains("raw-server-sentinel"));

        second.deliver(13, ImapConsumerTestSupport.message("<m13>", "recovered", "body"));
        ImapConsumerTestSupport.await(ingress.offered);
        awaitAdvances(ingress, 1);
        assertEquals("message", ingress.payloads.getFirst().get("kind"));
        assertEquals(java.util.List.of(13L), ingress.advances);
    }

    @Test void credentialBuffersAreErasedAfterTheSessionOwnsItsSocket() {
        var owner = new ImapConsumerTestSupport.FakeOwner();
        var protocol = new ImapConsumerTestSupport.FakeProtocol(owner);
        SecretValue supplied = new SecretValue(ImapConsumerTestSupport.SECRET.toCharArray());
        source = source(protocol, configuration(Map.of()), ignored -> Optional.of(supplied));
        source.start(new ImapConsumerTestSupport.Context(new ImapConsumerTestSupport.Ingress()))
                .toCompletableFuture().join();
        ImapConsumerTestSupport.await(owner.pollEntered);
        assertTrue(new String(protocol.observedPassword).chars().allMatch(value -> value == 0));
        assertTrue(new String(supplied.copy()).chars().allMatch(value -> value == 0));
    }

    @Test void credentialBuffersAreErasedWhenOpenFails() {
        var protocol = new ImapConsumerTestSupport.FakeProtocol(
                new ImapConsumerProtocol.Failure(false, "imap-connect-failed"));
        SecretValue supplied = new SecretValue(ImapConsumerTestSupport.SECRET.toCharArray());
        source = source(protocol, configuration(Map.of()), ignored -> Optional.of(supplied));
        assertThrows(java.util.concurrent.CompletionException.class, () -> source.start(
                new ImapConsumerTestSupport.Context(new ImapConsumerTestSupport.Ingress()))
                .toCompletableFuture().join());
        assertTrue(new String(protocol.observedPassword).chars().allMatch(value -> value == 0));
        assertTrue(new String(supplied.copy()).chars().allMatch(value -> value == 0));
    }

    @Test void credentialBuffersAreErasedWhenOpeningIsCancelled() {
        var protocol = new ImapConsumerTestSupport.FakeProtocol(new ImapConsumerTestSupport.FakeOwner()).gateOpen();
        SecretValue supplied = new SecretValue(ImapConsumerTestSupport.SECRET.toCharArray());
        source = source(protocol, configuration(Map.of()), ignored -> Optional.of(supplied));
        var start = source.start(new ImapConsumerTestSupport.Context(new ImapConsumerTestSupport.Ingress()))
                .toCompletableFuture();
        ImapConsumerTestSupport.await(protocol.openEntered);
        var stop = source.stop().toCompletableFuture();
        protocol.releaseOpen();
        assertThrows(java.util.concurrent.CompletionException.class, start::join);
        stop.join();
        assertTrue(new String(protocol.observedPassword).chars().allMatch(value -> value == 0));
        assertTrue(new String(supplied.copy()).chars().allMatch(value -> value == 0));
    }

    @Test void blockedCredentialResolverCannotBlockStopAndLateSecretIsErasedBeforeRestart() {
        var owner = new ImapConsumerTestSupport.FakeOwner();
        var protocol = new ImapConsumerTestSupport.FakeProtocol(owner);
        var resolverEntered = new java.util.concurrent.CountDownLatch(1);
        var releaseResolver = new java.util.concurrent.CountDownLatch(1);
        var resolverReturned = new java.util.concurrent.CountDownLatch(1);
        var calls = new AtomicInteger();
        var lateSecret = new java.util.concurrent.atomic.AtomicReference<SecretValue>();
        source = source(protocol, configuration(Map.of()), ignored -> {
            if (calls.getAndIncrement() == 0) {
                resolverEntered.countDown();
                boolean interrupted = false;
                while (true) try { releaseResolver.await(); break; }
                catch (InterruptedException ignoredInterrupt) { interrupted = true; }
                if (interrupted) Thread.currentThread().interrupt();
                SecretValue value = new SecretValue(ImapConsumerTestSupport.SECRET.toCharArray());
                lateSecret.set(value);
                resolverReturned.countDown();
                return Optional.of(value);
            }
            return secret();
        });
        var firstStart = source.start(new ImapConsumerTestSupport.Context(
                new ImapConsumerTestSupport.Ingress())).toCompletableFuture();
        ImapConsumerTestSupport.await(resolverEntered);
        source.stop().toCompletableFuture().orTimeout(500, TimeUnit.MILLISECONDS).join();
        assertEquals(0, protocol.openCalls.get());
        assertThrows(CompletionException.class, firstStart::join);
        assertEquals(1, ImapConsumerSource.activeResolverTasks());
        assertEquals(1, ImapConsumerSource.activeResolverProfiles());

        for (int attempt = 0; attempt < 20; attempt++) {
            assertThrows(CompletionException.class, () -> source.start(
                    new ImapConsumerTestSupport.Context(new ImapConsumerTestSupport.Ingress()))
                    .toCompletableFuture().join());
            source.stop().toCompletableFuture().join();
            assertEquals(1, ImapConsumerSource.activeResolverTasks(),
                    "ignored-interrupt resolver retries must remain bounded");
        }
        assertEquals(1, calls.get(), "busy admission must reject before spawning another resolver");

        releaseResolver.countDown();
        ImapConsumerTestSupport.await(resolverReturned);
        long erasedBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!zeroed(lateSecret.get()) && System.nanoTime() < erasedBy) Thread.onSpinWait();
        assertTrue(zeroed(lateSecret.get()));
        while (ImapConsumerSource.activeResolverTasks() != 0 && System.nanoTime() < erasedBy)
            Thread.onSpinWait();
        assertEquals(0, ImapConsumerSource.activeResolverTasks());
        assertEquals(0, ImapConsumerSource.activeResolverProfiles());

        source.start(new ImapConsumerTestSupport.Context(new ImapConsumerTestSupport.Ingress()))
                .toCompletableFuture().join();
        ImapConsumerTestSupport.await(owner.pollEntered);
        assertEquals(1, protocol.openCalls.get());
    }

    @Test void blockedDurabilityProbeWithHugeProfileTimeoutCannotBlockStopOrReachSecrets() {
        var ingress = new ImapConsumerTestSupport.Ingress();
        ingress.checkpointOverride = new java.util.concurrent.CompletableFuture<>();
        var profile = new ImapProfile(ImapConsumerTestSupport.TENANT, ImapConsumerTestSupport.PROFILE,
                "mail.example.test", 993, "IMAPS", "reader", "credential-ref", Set.of("INBOX"),
                Integer.MAX_VALUE, Integer.MAX_VALUE, 1, 20, 256);
        var protocol = new ImapConsumerTestSupport.FakeProtocol(new ImapConsumerTestSupport.FakeOwner());
        var credentialCalls = new AtomicInteger();
        source = new ImapConsumerSource(configuration(Map.of()), ignored -> {
            credentialCalls.incrementAndGet();
            return secret();
        }, (tenant, id) -> Optional.of(profile),
                (tenant, id) -> Optional.of(ImapConsumerTestSupport.policy()), protocol,
                virtualExecutor(), Clock.systemUTC());
        var start = source.start(new ImapConsumerTestSupport.Context(ingress)).toCompletableFuture();
        ImapConsumerTestSupport.await(ingress.checkpointRequested);
        long began = System.nanoTime();
        source.stop().toCompletableFuture().orTimeout(500, TimeUnit.MILLISECONDS).join();
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < 500);
        assertThrows(CompletionException.class, start::join);
        assertEquals(0, credentialCalls.get());
        assertEquals(0, protocol.openCalls.get());
    }

    @Test void graphCannotLowerOperatorPollingOrReconnectFloors() {
        var policy = new ImapConsumerPolicy(ImapConsumerTestSupport.TENANT,
                ImapConsumerTestSupport.PROFILE, "INBOX", 60_000, 4, 32,
                60_000, 60_000, 3, 65_536, "metadata", 0);
        for (Map<String, Object> unsafe : java.util.List.<Map<String, Object>>of(
                Map.of("pollIntervalMs", "100"),
                Map.of("retryBackoffMs", "100"),
                Map.of("maxRetryBackoffMs", "100"))) {
            var protocol = new ImapConsumerTestSupport.FakeProtocol(new ImapConsumerTestSupport.FakeOwner());
            var credentials = new AtomicInteger();
            source = new ImapConsumerSource(configuration(unsafe), ignored -> {
                credentials.incrementAndGet();
                return secret();
            }, (tenant, id) -> Optional.of(ImapConsumerTestSupport.profile()),
                    (tenant, id) -> Optional.of(policy), protocol, virtualExecutor(), Clock.systemUTC());
            assertThrows(CompletionException.class, () -> source.start(
                    new ImapConsumerTestSupport.Context(new ImapConsumerTestSupport.Ingress()))
                    .toCompletableFuture().join());
            assertEquals(0, credentials.get());
            assertEquals(0, protocol.openCalls.get());
            source.stop().toCompletableFuture().join();
        }
    }

    @Test void unknownTopLevelSizeReconnectsWithoutPoisonOrCheckpoint() {
        var first = new ImapConsumerTestSupport.FakeOwner();
        var second = new ImapConsumerTestSupport.FakeOwner();
        var protocol = new ImapConsumerTestSupport.FakeProtocol(first, second);
        var ingress = new ImapConsumerTestSupport.Ingress();
        var context = new ImapConsumerTestSupport.Context(ingress);
        source = source(protocol, configuration(Map.of()), ignored -> secret());
        source.start(context).toCompletableFuture().join();
        Message unknown = new MimeMessage(Session.getInstance(new Properties())) {
            @Override public int getSize() { return -1; }
        };
        first.deliver(21, unknown);
        ImapConsumerTestSupport.await(second.pollEntered);
        assertTrue(ingress.payloads.isEmpty());
        assertTrue(ingress.advances.isEmpty());
        assertTrue(context.degraded.contains("message-size-unavailable"));
    }

    @Test void rolloverReconnectUsesCappedObservedBackoffAndStopInterruptsIt() {
        var first = new ImapConsumerTestSupport.FakeOwner();
        var second = new ImapConsumerTestSupport.FakeOwner("INBOX", 43);
        var protocol = new ImapConsumerTestSupport.FakeProtocol(first, second);
        var delays = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
        var scheduled = new java.util.concurrent.CountDownLatch(1);
        source = new ImapConsumerSource(configuration(Map.of()), ignored -> secret(),
                (tenant, id) -> Optional.of(ImapConsumerTestSupport.profile()),
                (tenant, id) -> Optional.of(ImapConsumerTestSupport.policy()), protocol,
                virtualExecutor(), Clock.systemUTC(), delay -> { delays.add(delay); scheduled.countDown(); },
                () -> 0.5d);
        source.start(new ImapConsumerTestSupport.Context(new ImapConsumerTestSupport.Ingress()))
                .toCompletableFuture().join();
        first.rollover(43);
        ImapConsumerTestSupport.await(scheduled);
        assertEquals(java.util.List.of(150), delays);
        long started = System.nanoTime();
        source.stop().toCompletableFuture().join();
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 500);
    }

    @Test void rejectedExecutorStillStopsInATerminalState() {
        source = new ImapConsumerSource(configuration(Map.of()), ignored -> secret(),
                (tenant, id) -> Optional.of(ImapConsumerTestSupport.profile()),
                (tenant, id) -> Optional.of(ImapConsumerTestSupport.policy()),
                new ImapConsumerTestSupport.FakeProtocol(), task -> { throw new java.util.concurrent.RejectedExecutionException(); },
                Clock.systemUTC());
        assertThrows(CompletionException.class, () -> source.start(
                new ImapConsumerTestSupport.Context(new ImapConsumerTestSupport.Ingress()))
                .toCompletableFuture().join());
        source.stop().toCompletableFuture().join();
        assertEquals(ImapConsumerSource.State.STOPPED, source.state());
    }

    @Test void stopRevokesGenerationBeforeLateAdmissionCanCheckpoint() {
        var owner = new ImapConsumerTestSupport.FakeOwner();
        var ingress = new ImapConsumerTestSupport.Ingress().gateOffer();
        source = source(new ImapConsumerTestSupport.FakeProtocol(owner), configuration(Map.of()), ignored -> secret());
        source.start(new ImapConsumerTestSupport.Context(ingress)).toCompletableFuture().join();
        owner.deliver(12, ImapConsumerTestSupport.message("<m12>", "hello", "body"));
        ImapConsumerTestSupport.await(ingress.offered);
        var stopping = source.stop().toCompletableFuture();
        ingress.releaseOffer();
        stopping.join();
        assertTrue(ingress.advances.isEmpty());
        assertEquals(1, owner.closes.get());
    }

    @Test void stopDuringOpenCancelsBeforeReadyAndAllowsCleanRestart() {
        var first = new ImapConsumerTestSupport.FakeOwner();
        var second = new ImapConsumerTestSupport.FakeOwner();
        var protocol = new ImapConsumerTestSupport.FakeProtocol(first, second).gateOpen();
        var context = new ImapConsumerTestSupport.Context(new ImapConsumerTestSupport.Ingress());
        source = source(protocol, configuration(Map.of()), ignored -> secret());
        var starting = source.start(context).toCompletableFuture();
        ImapConsumerTestSupport.await(protocol.openEntered);
        var stopping = source.stop().toCompletableFuture();
        assertTrue(protocol.observedOpening.cancelled());
        protocol.releaseOpen();
        stopping.join();
        assertThrows(CompletionException.class, starting::join);
        assertEquals(0, context.healthy.get());
        assertEquals(ImapConsumerSource.State.STOPPED, source.state());

        source.start(context).toCompletableFuture().join();
        assertEquals(2, protocol.openCalls.get());
    }

    @Test void processLocalTenantProfileFolderLeaseRefusesDuplicateOwner() {
        var first = source(new ImapConsumerTestSupport.FakeProtocol(new ImapConsumerTestSupport.FakeOwner()),
                configuration(Map.of()), ignored -> secret());
        first.start(new ImapConsumerTestSupport.Context(new ImapConsumerTestSupport.Ingress()))
                .toCompletableFuture().join();
        AtomicInteger credential = new AtomicInteger();
        var secondProtocol = new ImapConsumerTestSupport.FakeProtocol(new ImapConsumerTestSupport.FakeOwner());
        var second = source(secondProtocol, configuration(Map.of()), ignored -> {
            credential.incrementAndGet(); return secret();
        });
        var context = new ImapConsumerTestSupport.Context(new ImapConsumerTestSupport.Ingress());
        assertThrows(CompletionException.class, () -> second.start(context).toCompletableFuture().join());
        assertEquals(0, credential.get());
        assertEquals(0, secondProtocol.openCalls.get());
        assertTrue(context.degraded.contains("imap-consumer-already-active"));
        first.stop().toCompletableFuture().join();
        source = second;
    }

    @Test void uidValidityRolloverUsesFreshInjectiveNamespaceAndFencesOldGeneration() {
        var first = new ImapConsumerTestSupport.FakeOwner("INBOX", 42);
        var second = new ImapConsumerTestSupport.FakeOwner("INBOX", 43);
        var ingress = new ImapConsumerTestSupport.Ingress(2);
        var context = new ImapConsumerTestSupport.Context(ingress, 2);
        var protocol = new ImapConsumerTestSupport.FakeProtocol(first, second);
        source = source(protocol, configuration(Map.of()), ignored -> secret());
        source.start(context).toCompletableFuture().join();
        first.deliver(0xffff_ffffL, ImapConsumerTestSupport.message("<max>", "max", "body"));
        awaitPayloads(ingress, 1);
        first.rollover(43);
        long reconnectDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (second.pollEntered.getCount() != 0 && System.nanoTime() < reconnectDeadline) Thread.onSpinWait();
        assertEquals(0, second.pollEntered.getCount(), "state=" + source.state() + " degraded="
                + context.degraded + " opens=" + protocol.openCalls.get());
        second.deliver(1, ImapConsumerTestSupport.message("<new>", "new", "body"));
        awaitPayloads(ingress, 2);
        assertNotEquals(ingress.sourceIds.get(0), ingress.sourceIds.get(1));
        assertTrue(ingress.sourceIds.get(0).endsWith("/42"));
        assertTrue(ingress.sourceIds.get(1).endsWith("/43"));
        @SuppressWarnings("unchecked")
        var before = (Map<String, Object>) ingress.payloads.get(0).get("checkpoint");
        @SuppressWarnings("unchecked")
        var after = (Map<String, Object>) ingress.payloads.get(1).get("checkpoint");
        assertEquals(42L, before.get("uidValidity"));
        assertEquals(0xffff_ffffL, before.get("candidateDeliveredThroughUid"));
        assertEquals(43L, after.get("uidValidity"));
        assertEquals(1L, after.get("candidateDeliveredThroughUid"));
        awaitAdvances(ingress, 2);
        assertArrayEquals(new Long[]{0xffff_ffffL, 1L}, ingress.advances.toArray(Long[]::new));
    }

    @Test void hiddenInactivePreviewValueIsNeverReadAndUnknownPropertyFailsBeforeCredentialOrOpen() {
        Object hostile = new Object() { @Override public String toString() { throw new AssertionError("hidden read"); } };
        source = source(new ImapConsumerTestSupport.FakeProtocol(new ImapConsumerTestSupport.FakeOwner()),
                configuration(Map.of("contentMode", "metadata", "previewChars", hostile)), ignored -> secret());
        source.start(new ImapConsumerTestSupport.Context(new ImapConsumerTestSupport.Ingress()))
                .toCompletableFuture().join();
        source.stop().toCompletableFuture().join();

        AtomicInteger credentials = new AtomicInteger();
        var protocol = new ImapConsumerTestSupport.FakeProtocol(new ImapConsumerTestSupport.FakeOwner());
        source = source(protocol, configuration(Map.of("host", "attacker")), ignored -> {
            credentials.incrementAndGet(); return secret();
        });
        assertThrows(CompletionException.class, () -> source.start(
                new ImapConsumerTestSupport.Context(new ImapConsumerTestSupport.Ingress()))
                .toCompletableFuture().join());
        assertEquals(0, credentials.get());
        assertEquals(0, protocol.openCalls.get());
    }

    @Test void identityEncodingIsInjectiveAndAcceptsUnsigned32Maximum() {
        String one = ImapMessageEvent.sourceId("a/b", "c", "d", 0xffff_ffffL);
        String two = ImapMessageEvent.sourceId("a", "b/c", "d", 0xffff_ffffL);
        assertNotEquals(one, two);
        assertTrue(one.endsWith("/4294967295"));
        assertNotEquals(ImapMessageEvent.key("a/b", "c", 1, 2),
                ImapMessageEvent.key("a", "b/c", 1, 2));
        assertThrows(IllegalArgumentException.class,
                () -> ImapMessageEvent.sourceId("node", "profile", "é".repeat(129), 1));
    }

    @Test void projectionFailureTrackingStaysBoundedAcrossHostileUidValidityRollovers() {
        var tracker = new ImapConsumerSource.ProjectionFailureTracker();
        for (int validity = 1; validity <= 10_000; validity++) {
            assertEquals(1, tracker.failed("reader\0INBOX\0" + validity + "\0" + 1));
            assertEquals(1, tracker.size());
        }
        String current = "reader\0INBOX\0" + 10_000 + "\0" + 1;
        assertEquals(2, tracker.failed(current));
        tracker.succeeded(current);
        assertEquals(0, tracker.size());
    }

    @Test void recursiveMimeBecomesTypedPoisonAndFatalErrorsAreNotConverted() throws Exception {
        MimeBodyPart leaf = new MimeBodyPart();
        leaf.setText("leaf");
        for (int index = 0; index < 10; index++) {
            MimeMultipart multipart = new MimeMultipart();
            multipart.addBodyPart(leaf);
            MimeBodyPart parent = new MimeBodyPart();
            parent.setContent(multipart);
            leaf = parent;
        }
        MimeMessage deep = ImapConsumerTestSupport.message("<deep>", "deep", "body");
        deep.setContent(leaf.getContent(), leaf.getContentType());
        deep.saveChanges();
        assertThrows(ImapMessageEvent.Invalid.class, () -> ImapMessageEvent.project(
                new ImapConsumerProtocol.Item(1, deep), "reader", "INBOX", 1,
                new ImapMessageEvent.Limits(65_536, "preview", 32), 1));

        Message fatal = new MimeMessage(Session.getInstance(new Properties())) {
            @Override public int getSize() { throw new AssertionError("fatal"); }
        };
        assertThrows(AssertionError.class, () -> ImapMessageEvent.project(
                new ImapConsumerProtocol.Item(1, fatal), "reader", "INBOX", 1,
                new ImapMessageEvent.Limits(65_536, "metadata", 0), 1));
    }

    private ImapConsumerSource source(ImapConsumerTestSupport.FakeProtocol protocol,
                                      NodeConfiguration configuration,
                                      ai.ravenroot.api.security.CredentialResolver credential) {
        source = new ImapConsumerSource(configuration, credential,
                (tenant, id) -> Optional.of(ImapConsumerTestSupport.profile()),
                (tenant, id) -> Optional.of(ImapConsumerTestSupport.policy()), protocol,
                virtualExecutor(), Clock.systemUTC());
        return source;
    }

    private MailImapConsumeNodeBehavior behavior(ImapConsumerTestSupport.FakeProtocol protocol) {
        return new MailImapConsumeNodeBehavior((tenant, id) -> Optional.of(ImapConsumerTestSupport.profile()),
                ignored -> secret(), (tenant, id) -> Optional.of(ImapConsumerTestSupport.policy()),
                protocol, virtualExecutor(), Clock.systemUTC());
    }

    private static NodeConfiguration configuration(Map<String, Object> additions) {
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("profile", ImapConsumerTestSupport.PROFILE);
        values.putAll(additions);
        return new NodeConfiguration("consume", "mail.imap.consume", values);
    }

    private static Optional<SecretValue> secret() {
        return Optional.of(new SecretValue(ImapConsumerTestSupport.SECRET.toCharArray()));
    }
    private static boolean zeroed(SecretValue value) {
        return value != null && new String(value.copy()).chars().allMatch(character -> character == 0);
    }
    private static java.util.concurrent.Executor virtualExecutor() {
        return task -> Thread.ofVirtual().name("imap-consumer-test").start(task);
    }
    private void awaitState(ImapConsumerSource.State expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (source.state() != expected && System.nanoTime() < deadline) Thread.onSpinWait();
        assertEquals(expected, source.state());
    }
    private static void awaitAdvances(ImapConsumerTestSupport.Ingress ingress, int count) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (ingress.advances.size() < count && System.nanoTime() < deadline) Thread.onSpinWait();
        assertEquals(count, ingress.advances.size());
    }
    private static void awaitPayloads(ImapConsumerTestSupport.Ingress ingress, int count) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (ingress.payloads.size() < count && System.nanoTime() < deadline) Thread.onSpinWait();
        assertEquals(count, ingress.payloads.size());
    }
}
