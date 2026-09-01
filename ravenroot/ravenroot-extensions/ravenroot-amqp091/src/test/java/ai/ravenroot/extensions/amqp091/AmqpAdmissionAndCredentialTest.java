package ai.ravenroot.extensions.amqp091;

import ai.ravenroot.api.security.CredentialResolver;
import ai.ravenroot.api.security.SecretValue;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static ai.ravenroot.extensions.amqp091.AmqpTestSupport.Event;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmqpAdmissionAndCredentialTest {
    @Test
    void everyAdmissionLayerSaturatesAndRecoversWithoutLeakingKeys() {
        var ticker = new AmqpTestSupport.MutableTicker();

        var global = new AmqpRuntimeControls(ticker, Runnable::run, 1, 4, 16);
        var firstActions = new ConcurrentHashMap<String, AmqpRuntimeControls.Gate>();
        var secondActions = new ConcurrentHashMap<String, AmqpRuntimeControls.Gate>();
        var first = global.acquire("tenant-a", AmqpTestSupport.profile("tenant-a", "one", 4, 10, 1_000, 0),
                4, firstActions);
        var blockedGlobal = global.acquire("tenant-b", AmqpTestSupport.profile("tenant-b", "two", 4, 10, 1_000, 0),
                4, secondActions);
        assertTrue(first.acquired());
        assertFalse(blockedGlobal.acquired());
        first.release();
        var recoveredGlobal = global.acquire("tenant-b", AmqpTestSupport.profile("tenant-b", "two", 4, 10, 1_000, 0),
                4, secondActions);
        assertTrue(recoveredGlobal.acquired());
        recoveredGlobal.release();

        var tenant = new AmqpRuntimeControls(ticker, Runnable::run, 8, 1, 16);
        var tenantOne = tenant.acquire("shared", AmqpTestSupport.profile("shared", "one", 4, 10, 1_000, 0),
                4, new ConcurrentHashMap<>());
        var tenantBlocked = tenant.acquire("shared", AmqpTestSupport.profile("shared", "two", 4, 10, 1_000, 0),
                4, new ConcurrentHashMap<>());
        assertTrue(tenantOne.acquired());
        assertFalse(tenantBlocked.acquired());
        tenantOne.release();

        var profileControls = new AmqpRuntimeControls(ticker, Runnable::run, 8, 8, 16);
        AmqpProfile limitedProfile = AmqpTestSupport.profile("shared", "limited", 1, 10, 1_000, 0);
        var profileOne = profileControls.acquire("shared", limitedProfile, 4, new ConcurrentHashMap<>());
        var profileBlocked = profileControls.acquire("shared", limitedProfile, 4, new ConcurrentHashMap<>());
        assertTrue(profileOne.acquired());
        assertFalse(profileBlocked.acquired());
        profileOne.release();

        var actionControls = new AmqpRuntimeControls(ticker, Runnable::run, 8, 8, 16);
        var sharedAction = new ConcurrentHashMap<String, AmqpRuntimeControls.Gate>();
        var actionOne = actionControls.acquire("shared", AmqpTestSupport.profile("shared", "one", 4, 10, 1_000, 0),
                1, sharedAction);
        var actionBlocked = actionControls.acquire("shared", AmqpTestSupport.profile("shared", "two", 4, 10, 1_000, 0),
                1, sharedAction);
        assertTrue(actionOne.acquired());
        assertFalse(actionBlocked.acquired());
        actionOne.release();
        var actionRecovered = actionControls.acquire("shared", AmqpTestSupport.profile("shared", "two", 4, 10, 1_000, 0),
                1, sharedAction);
        assertTrue(actionRecovered.acquired());
        actionRecovered.release();

        assertTrue(global.tenants.isEmpty());
        assertTrue(global.profiles.isEmpty());
        assertTrue(firstActions.isEmpty());
        assertTrue(secondActions.isEmpty());
        assertTrue(tenant.tenants.isEmpty());
        assertTrue(profileControls.profiles.isEmpty());
        assertTrue(sharedAction.isEmpty());
    }

    @Test
    void boundedRateLimiterSaturatesRecoversAndRejectsNewKeysWhenFull() {
        var ticker = new AmqpTestSupport.MutableTicker();
        var limiter = new AmqpRuntimeControls.RateLimiter(ticker, 1);
        assertTrue(limiter.allow("tenant", "one", 1));
        assertFalse(limiter.allow("tenant", "one", 1));
        assertFalse(limiter.allow("tenant", "two", 1));
        assertEquals(1, limiter.size());
        ticker.advanceMillis(1_000);
        assertTrue(limiter.allow("tenant", "two", 1));
        assertEquals(1, limiter.size());
    }

    @Test
    void behaviorRateLimitOccursBeforeASecondCredentialResolutionAndRecovers() {
        var ticker = new AmqpTestSupport.MutableTicker();
        var protocol = new AmqpTestSupport.FakeProtocol(Event.CONFIRM, Event.CONFIRM);
        AtomicInteger resolutions = new AtomicInteger();
        CredentialResolver credentials = reference -> {
            resolutions.incrementAndGet();
            return Optional.of(new SecretValue(AmqpTestSupport.SECRET.toCharArray()));
        };
        var controls = new AmqpRuntimeControls(ticker, Runnable::run, 8, 8, 16);
        var behavior = new AmqpPublishNodeBehavior(credentials,
                (tenant, name) -> Optional.of(AmqpTestSupport.profile(tenant, name, 4, 1, 1_000, 0)),
                protocol, controls, ticker, millis -> { });
        var action = behavior.create(AmqpTestSupport.configuration());
        assertEquals("CONFIRMED", AmqpTestSupport.output(action, AmqpTestSupport.payload()).get("status"));
        assertEquals("RATE_LIMITED", AmqpTestSupport.output(action, AmqpTestSupport.payload()).get("status"));
        assertEquals(1, resolutions.get());
        ticker.advanceMillis(1_000);
        assertEquals("CONFIRMED", AmqpTestSupport.output(action, AmqpTestSupport.payload()).get("status"));
        assertEquals(2, resolutions.get());
    }

    @Test
    void credentialsRotateRevokeAndEveryProtocolCopyIsCleared() {
        var protocol = new AmqpTestSupport.FakeProtocol(Event.CONFIRM, Event.CONFIRM);
        AtomicInteger calls = new AtomicInteger();
        CredentialResolver credentials = reference -> switch (calls.getAndIncrement()) {
            case 0 -> Optional.of(new SecretValue("first-secret".toCharArray()));
            case 1 -> Optional.of(new SecretValue("second-secret".toCharArray()));
            default -> Optional.empty();
        };
        var behavior = AmqpTestSupport.behavior(protocol, credentials,
                new AmqpRuntimeControls(System::nanoTime, Runnable::run, 8, 8, 16),
                System::nanoTime, millis -> { });
        var action = behavior.create(AmqpTestSupport.configuration());
        assertEquals("CONFIRMED", AmqpTestSupport.output(action, AmqpTestSupport.payload()).get("status"));
        assertEquals("CONFIRMED", AmqpTestSupport.output(action, AmqpTestSupport.payload()).get("status"));
        assertEquals("PERMANENT_FAILURE", AmqpTestSupport.output(action, AmqpTestSupport.payload()).get("status"));
        assertEquals(java.util.List.of("first-secret", "second-secret"), protocol.observedPasswords);
        assertTrue(protocol.passwordReferences.stream().allMatch(value -> {
            for (char character : value) if (character != '\0') return false;
            return true;
        }));
        assertEquals(3, calls.get());
        assertEquals(2, protocol.connects.get());
    }

    @Test
    void executorRejectionReleasesAdmissionAndDoesNotResolveCredentials() {
        AtomicInteger resolutions = new AtomicInteger();
        CredentialResolver credentials = reference -> {
            resolutions.incrementAndGet();
            return Optional.of(new SecretValue(AmqpTestSupport.SECRET.toCharArray()));
        };
        var controls = new AmqpRuntimeControls(System::nanoTime, command -> {
            throw new java.util.concurrent.RejectedExecutionException("full");
        }, 1, 1, 16);
        var behavior = AmqpTestSupport.behavior(new AmqpTestSupport.FakeProtocol(Event.CONFIRM), credentials,
                controls, System::nanoTime, millis -> { });
        Map<String, Object> output = AmqpTestSupport.output(
                behavior.create(AmqpTestSupport.configuration(Map.of("maxConcurrency", "1"))),
                AmqpTestSupport.payload());
        assertEquals("TEMPORARY_FAILURE", output.get("status"));
        assertEquals(0, resolutions.get());
        assertEquals(1, controls.global.availablePermits());
        assertTrue(controls.tenants.isEmpty());
        assertTrue(controls.profiles.isEmpty());
    }

    @Test
    void resolverAndTransportFailuresAreSanitized() {
        String sentinel = "do-not-leak-credential";
        var throwingCredentials = AmqpTestSupport.behavior(new AmqpTestSupport.FakeProtocol(Event.CONFIRM),
                reference -> { throw new IllegalStateException(sentinel); },
                new AmqpRuntimeControls(System::nanoTime, Runnable::run, 8, 8, 16),
                System::nanoTime, millis -> { });
        Map<String, Object> credentialFailure = AmqpTestSupport.output(
                throwingCredentials.create(AmqpTestSupport.configuration()), AmqpTestSupport.payload());
        assertEquals("PERMANENT_FAILURE", credentialFailure.get("status"));
        assertFalse(credentialFailure.toString().contains(sentinel));

        var transport = new AmqpTestSupport.FakeProtocol(Event.THROW);
        Map<String, Object> transportFailure = AmqpTestSupport.output(
                AmqpTestSupport.behavior(transport).create(AmqpTestSupport.configuration()), AmqpTestSupport.payload());
        assertEquals("AMBIGUOUS", transportFailure.get("status"));
        assertFalse(transportFailure.toString().contains(AmqpTestSupport.SECRET));
    }
}
