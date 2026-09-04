package ai.ravenroot.extensions.mail;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.security.CredentialResolver;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MailConcurrencyAdmissionTest {
    @Test void perProfileAdmissionFailsFastWithoutSubmittingWorkAndKeepsOtherScopesIndependent() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger slowLookups = new AtomicInteger();
        AtomicInteger independentLookups = new AtomicInteger();
        CredentialResolver credentials = ref -> {
            if (ref.equals("slow")) {
                slowLookups.incrementAndGet();
                entered.countDown();
                await(release);
            } else independentLookups.incrementAndGet();
            return Optional.empty();
        };
        var behavior = MailTestSupport.loopbackBehavior(credentials, (tenant, name) -> Optional.of(MailTestSupport.profile(
                tenant, name, "127.0.0.1", 1, "STARTTLS", "smtp-user",
                tenant.equals("tenant-a") && name.equals("slow") ? "slow" : "independent", 0, 1)), "127.0.0.1");
        NodeAction slow = behavior.create(MailTestSupport.configuration(Map.of("mailProfile", "slow")));
        CompletionStage<NodeResult> first = slow.handle(message("tenant-a"));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "the admitted request must reach the credential boundary");
            CompletionStage<NodeResult> rejected = slow.handle(message("tenant-a"));
            assertTrue(rejected.toCompletableFuture().isDone(), "profile saturation must fail before task submission");
            assertCode(MailSendException.Code.CAPACITY_UNAVAILABLE, rejected);
            assertEquals(1, slowLookups.get(), "a rejected request must not start a virtual task");

            NodeAction otherProfile = behavior.create(MailTestSupport.configuration(Map.of("mailProfile", "other")));
            assertCode(MailSendException.Code.CREDENTIAL_UNAVAILABLE, otherProfile.handle(message("tenant-a")));
            assertCode(MailSendException.Code.CREDENTIAL_UNAVAILABLE, slow.handle(message("tenant-b")));
            assertEquals(2, independentLookups.get(), "another profile and tenant must progress independently");
        } finally {
            release.countDown();
        }
        assertCode(MailSendException.Code.CREDENTIAL_UNAVAILABLE, first);
        assertGateAbsent("tenant-a", "slow");
        assertGateAbsent("tenant-a", "other");
        assertGateAbsent("tenant-b", "slow");
        assertTenantGateAbsent("tenant-a");
        assertTenantGateAbsent("tenant-b");
    }

    @Test void globalAdmissionIsBoundedFailFastAndRecoversWithoutLeakingProfileGateReferences() throws Exception {
        CountDownLatch entered = new CountDownLatch(32);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger lookups = new AtomicInteger();
        CredentialResolver credentials = ref -> {
            lookups.incrementAndGet();
            entered.countDown();
            await(release);
            return Optional.empty();
        };
        var behavior = MailTestSupport.loopbackBehavior(credentials, (tenant, name) -> Optional.of(MailTestSupport.profile(
                tenant, name, "127.0.0.1", 1, "STARTTLS", "smtp-user", "slow", 0, 16)), "127.0.0.1");
        NodeAction action = behavior.create(MailTestSupport.configuration(Map.of("mailProfile", "global")));
        List<CompletionStage<NodeResult>> admitted = new ArrayList<>();
        for (int i = 0; i < 32; i++) admitted.add(action.handle(message("tenant-global-" + i)));
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS), "all global permits must be observably occupied");
            CompletionStage<NodeResult> rejected = action.handle(message("tenant-global"));
            assertTrue(rejected.toCompletableFuture().isDone(), "global saturation must not queue another task");
            assertCode(MailSendException.Code.CAPACITY_UNAVAILABLE, rejected);
            assertEquals(32, lookups.get());
        } finally {
            release.countDown();
        }
        awaitAll(admitted);
        for (int i = 0; i < 32; i++) {
            assertGateAbsent("tenant-global-" + i, "global");
            assertTenantGateAbsent("tenant-global-" + i);
        }

        assertCode(MailSendException.Code.CREDENTIAL_UNAVAILABLE, action.handle(message("tenant-global-recovery")));
        assertEquals(33, lookups.get(), "capacity and profile gate references must recover after completion");
        assertGateAbsent("tenant-global-recovery", "global");
        assertTenantGateAbsent("tenant-global-recovery");
    }

    @Test void graphConcurrencyTighteningIsPerActionAndOrderIndependent() throws Exception {
        for (boolean strictFirst : List.of(false, true)) {
            CountDownLatch entered = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger lookups = new AtomicInteger();
            CredentialResolver credentials = ref -> { lookups.incrementAndGet(); entered.countDown(); await(release); return Optional.empty(); };
            var behavior = MailTestSupport.loopbackBehavior(credentials, (tenant, name) -> Optional.of(MailTestSupport.profile(
                    tenant, name, "127.0.0.1", 1, "STARTTLS", "smtp-user", "slow", 0, 16)), "127.0.0.1");
            NodeAction lax = behavior.create(MailTestSupport.configuration(Map.of("mailProfile", "primary")));
            NodeAction strict = behavior.create(MailTestSupport.configuration(Map.of("mailProfile", "primary", "maxConcurrency", "1")));
            CompletionStage<NodeResult> first = (strictFirst ? strict : lax).handle(message("tenant-tightening"));
            CompletionStage<NodeResult> second = (strictFirst ? lax : strict).handle(message("tenant-tightening"));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertCode(MailSendException.Code.CAPACITY_UNAVAILABLE, strict.handle(message("tenant-tightening")));
                assertEquals(2, lookups.get(), "the strict action must reject before credential resolution");
            } finally {
                release.countDown();
            }
            assertCode(MailSendException.Code.CREDENTIAL_UNAVAILABLE, first);
            assertCode(MailSendException.Code.CREDENTIAL_UNAVAILABLE, second);
            assertGateAbsent("tenant-tightening", "primary");
            assertTenantGateAbsent("tenant-tightening");
        }
    }

    @Test void tenantAdmissionReservesGlobalHeadroomAcrossProfiles() throws Exception {
        CountDownLatch entered = new CountDownLatch(16);
        CountDownLatch otherTenantEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger lookups = new AtomicInteger();
        CredentialResolver credentials = ref -> {
            lookups.incrementAndGet();
            if (ref.equals("independent")) otherTenantEntered.countDown();
            else { entered.countDown(); await(release); }
            return Optional.empty();
        };
        var behavior = MailTestSupport.loopbackBehavior(credentials, (tenant, name) -> Optional.of(MailTestSupport.profile(
                tenant, name, "127.0.0.1", 1, "STARTTLS", "smtp-user",
                tenant.equals("tenant-independent") ? "independent" : "slow", 0, 16)), "127.0.0.1");
        List<CompletionStage<NodeResult>> admitted = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            NodeAction action = behavior.create(MailTestSupport.configuration(Map.of("mailProfile", "profile-" + i)));
            admitted.add(action.handle(message("tenant-reserved")));
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            NodeAction overflow = behavior.create(MailTestSupport.configuration(Map.of("mailProfile", "profile-overflow")));
            assertCode(MailSendException.Code.CAPACITY_UNAVAILABLE, overflow.handle(message("tenant-reserved")));
            assertEquals(16, lookups.get(), "the tenant cap must reject before credential resolution while global capacity remains");

            NodeAction independent = behavior.create(MailTestSupport.configuration(Map.of("mailProfile", "profile-independent")));
            assertCode(MailSendException.Code.CREDENTIAL_UNAVAILABLE, independent.handle(message("tenant-independent")));
            assertTrue(otherTenantEntered.await(5, TimeUnit.SECONDS), "another tenant must use reserved global headroom");
            assertEquals(17, lookups.get());
        } finally {
            release.countDown();
        }
        awaitAll(admitted);
        assertTenantGateAbsent("tenant-reserved");
        assertGateAbsent("tenant-independent", "profile-independent");
        assertTenantGateAbsent("tenant-independent");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("timed out waiting for test release");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test interrupted", interrupted);
        }
    }

    private static void awaitAll(List<CompletionStage<NodeResult>> stages) throws Exception {
        CompletableFuture<?>[] completions = stages.stream()
                .map(stage -> stage.handle((ignored, failure) -> null).toCompletableFuture())
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(completions).get(10, TimeUnit.SECONDS);
    }

    private static void assertCode(MailSendException.Code expected, CompletionStage<NodeResult> stage) {
        try {
            stage.toCompletableFuture().join();
            throw new AssertionError("expected mail failure " + expected);
        } catch (CompletionException failure) {
            assertEquals(expected, ((MailSendException) failure.getCause()).code());
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertGateAbsent(String tenant, String profile) throws Exception {
        Field field = MailSendNodeBehavior.class.getDeclaredField("PROFILE_SLOTS");
        field.setAccessible(true);
        ConcurrentMap<String, ?> gates = (ConcurrentMap<String, ?>) field.get(null);
        assertFalse(gates.containsKey(tenant + "\u0000" + profile), "completed profile gate must be retired");
    }

    @SuppressWarnings("unchecked")
    private static void assertTenantGateAbsent(String tenant) throws Exception {
        Field field = MailSendNodeBehavior.class.getDeclaredField("TENANT_SLOTS");
        field.setAccessible(true);
        ConcurrentMap<String, ?> gates = (ConcurrentMap<String, ?>) field.get(null);
        assertFalse(gates.containsKey(tenant), "completed tenant gate must be retired");
    }

    private static NodeMessage message(String tenant) {
        UUID id = UUID.randomUUID();
        return new NodeMessage(new SecurityContext("r", tenant, "s", PrincipalType.USER, "i"), id, id, id, id,
                Set.of(), "mail", Map.of("version", "mail.send.v1", "to", List.of("to@example.test"), "text", "body"), Map.of());
    }
}
