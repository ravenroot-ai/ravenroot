package ai.ravenroot.extensions.mail.imap;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.security.CredentialResolver;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecretValue;
import ai.ravenroot.api.security.SecurityContext;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MailImapCredentialIsolationTest {
    /**
     * The {@code elapsedMs} bound below is a wall-clock ceiling on {@code DeadlineWatchdog}'s own
     * 100ms deadline, not an ordinary client-side timeout. Applying the question "does the property
     * depend on time?" from {@code docs/qa/what-the-testkits-do-not-cover.md}, the answer is yes: the property under
     * test is that the deadline fires near 100ms, so widening the ceiling would weaken exactly what it
     * verifies, not just reduce noise. Left unwidened, and the number is a measurement rather than a
     * tuned guess:
     *
     * <p>The seam is not missing. {@code MailImapQueryNodeBehavior} already carries an injectable
     * {@code LongSupplier nanoTime} (field, package-private constructor, and two call sites at
     * {@code checkDeadline} and the pre-watchdog deadline computation) -- this test's own package could
     * reach it. What it cannot reach is {@code DeadlineWatchdog}: that class is a {@code private static}
     * nested class, so it has no access to the outer instance's field at all, and it computes its
     * deadline from {@code System.nanoTime()} directly, re-read at four sites (deadline computation,
     * {@code remainingNanos()}, and both places {@code state} is resolved). Wiring the existing supplier
     * through means adding a parameter to {@code DeadlineWatchdog}'s constructor and replacing those
     * four call sites -- a small but real production change to a timeout mechanism that a test-bound
     * adjustment does not make. That is the actual reason "measure the deadline without going
     * through wall-clock" is out of scope here: not that no seam exists, but that the one seam that does
     * exist doesn't reach the class that needs it.
     *
     * <p>So the number was verified as a measurement instead. Isolated (8 runs): 124-146ms. Under
     * artificial ~9-way CPU contention across this machine's 10 cores (6 runs, the same contention
     * methodology): 145-163ms, no failures. Both bands are measurements taken on this machine, not a
     * portable constant -- the margin argument itself does not depend on the absolute numbers ({@code
     * 1_000} is ~6x the slowest measured run, so it reads as generous rather than tuned, on any hardware
     * where the same ratio holds), but a reader retaking this measurement on different hardware should
     * know where 124-163ms came from before trusting the ratio. Both bands sit comfortably inside
     * {@code [50ms, 1000ms)}: the lower bound has ~2.5x headroom below the fastest observed run, and the
     * upper bound has ~6x headroom above the slowest contended one -- so {@code 1_000} is generous
     * margin for jitter, not a value tuned to the observed numbers, and {@code 50} rejects a spuriously
     * immediate return without ever being close to firing on a real run.
     */
    @Test void interruptClearingResolverCannotHoldQueryWorkerAndLateSecretIsZeroed() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<SecretValue> lateSecret = new AtomicReference<>();
        CredentialResolver resolver = ignored -> {
            int invocation = callbacks.incrementAndGet(); entered.countDown();
            awaitIgnoringInterrupts(release);
            if (invocation > 1) return Optional.empty();
            SecretValue secret = new SecretValue("late-secret-sentinel".toCharArray()); lateSecret.set(secret);
            return Optional.of(secret);
        };
        NodeAction action = action(resolver, 100, 1);

        long started = System.nanoTime();
        CompletableFuture<?> stage = action.handle(message("tenantA")).toCompletableFuture();
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertCode(stage, ImapQueryException.Code.TIMEOUT);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(elapsedMs >= 50 && elapsedMs < 1_000, "query must finish near its 100ms deadline, elapsed=" + elapsedMs);
        assertQueryRuntimeClean();
        assertEquals(1, activeResolvers());
        assertEquals(31, resolverGlobal().availablePermits());
        assertFalse(resolverMapsEmpty());
        List<ConcurrentHashMap<?, ?>> capturedGates = capturedGateMaps(action);
        assertEquals(2, capturedGates.size());
        assertEquals(1, capturedGates.stream().filter(map -> !map.isEmpty()).count(), "only the resolver action gate remains leased");

        int before = callbacks.get();
        assertCode(action.handle(message("tenantA")).toCompletableFuture(), ImapQueryException.Code.SATURATED);
        assertEquals(before, callbacks.get(), "resolver saturation must fail before another callback");
        assertQueryRuntimeClean();

        release.countDown();
        awaitTrue(() -> activeResolvers() == 0, Duration.ofSeconds(2));
        SecretValue closed = lateSecret.get(); assertNotNull(closed);
        assertTrue(secretChars(closed).chars().allMatch(value -> value == 0), "late secret must be zeroed by the abandoned handoff");
        assertResolverRuntimeClean();
        assertTrue(capturedGates.stream().allMatch(Map::isEmpty), "query and resolver per-action gate maps must drain");
        assertCode(action.handle(message("tenantA")).toCompletableFuture(), ImapQueryException.Code.CREDENTIAL_UNAVAILABLE);
        assertEquals(2, callbacks.get(), "same action and tenant must reacquire resolver profile/action permits");
        assertQueryRuntimeClean(); assertResolverRuntimeClean();
    }

    @Test void stuckResolversEnforceTenantProfileAndGlobalCeilingsWithCrossTenantHeadroom() throws Exception {
        CountDownLatch releaseA = new CountDownLatch(1), releaseB = new CountDownLatch(1);
        Map<String, CountDownLatch> blockers = Map.of("tenantA", releaseA, "tenantB", releaseB);
        ConcurrentHashMap<String, AtomicInteger> callbacks = new ConcurrentHashMap<>();
        CredentialResolver resolver = reference -> {
            callbacks.computeIfAbsent(reference, ignored -> new AtomicInteger()).incrementAndGet();
            CountDownLatch blocker = blockers.get(reference);
            if (blocker != null) awaitIgnoringInterrupts(blocker);
            return Optional.empty();
        };
        NodeAction action = action(resolver, 300, 16);

        List<CompletableFuture<?>> tenantA = start(action, "tenantA", 16);
        awaitTrue(() -> count(callbacks, "tenantA") == 16, Duration.ofSeconds(1));
        tenantA.forEach(stage -> assertCode(stage, ImapQueryException.Code.TIMEOUT));
        assertEquals(16, activeResolvers());
        assertCode(action.handle(message("tenantA")).toCompletableFuture(), ImapQueryException.Code.SATURATED);
        assertEquals(16, count(callbacks, "tenantA"));

        assertCode(action.handle(message("independent")).toCompletableFuture(), ImapQueryException.Code.CREDENTIAL_UNAVAILABLE);
        assertEquals(1, count(callbacks, "independent"), "another tenant retains resolver headroom");

        List<CompletableFuture<?>> tenantB = start(action, "tenantB", 16);
        awaitTrue(() -> count(callbacks, "tenantB") == 16, Duration.ofSeconds(1));
        tenantB.forEach(stage -> assertCode(stage, ImapQueryException.Code.TIMEOUT));
        assertEquals(32, activeResolvers()); assertEquals(0, resolverGlobal().availablePermits());
        assertCode(action.handle(message("globalOverflow")).toCompletableFuture(), ImapQueryException.Code.SATURATED);
        assertEquals(0, count(callbacks, "globalOverflow"));

        releaseB.countDown();
        awaitTrue(() -> activeResolvers() == 16, Duration.ofSeconds(2));
        assertCode(action.handle(message("globalRecovered")).toCompletableFuture(), ImapQueryException.Code.CREDENTIAL_UNAVAILABLE);
        assertEquals(1, count(callbacks, "globalRecovered"));

        releaseA.countDown();
        awaitTrue(() -> activeResolvers() == 0, Duration.ofSeconds(2));
        assertQueryRuntimeClean(); assertResolverRuntimeClean();
    }

    private static NodeAction action(CredentialResolver resolver, int readTimeoutMs, int maxConcurrency) {
        return new MailImapQueryNodeBehavior((tenant, name) -> Optional.of(new ImapProfile(tenant, name, "localhost", 1, "IMAPS", "reader", tenant,
                Set.of("INBOX"), 1_000, readTimeoutMs, maxConcurrency, 1, 1)), resolver)
                .create(new NodeConfiguration("imap", MailImapQueryNodeBehavior.BEHAVIOR,
                        Map.of("profile", "reader", "folder", "INBOX", "limit", "1", "maxConcurrency", Integer.toString(maxConcurrency))));
    }
    private static List<CompletableFuture<?>> start(NodeAction action, String tenant, int count) {
        List<CompletableFuture<?>> stages = new ArrayList<>();
        for (int i = 0; i < count; i++) stages.add(action.handle(message(tenant)).toCompletableFuture());
        return stages;
    }
    private static NodeMessage message(String tenant) {
        UUID id = UUID.randomUUID();
        return new NodeMessage(new SecurityContext("r", tenant, "s", PrincipalType.USER, "i"), id, id, id, id, Set.of(), "imap",
                Map.of("version", "mail.imap.query.v1"), Map.of());
    }
    private static void assertCode(CompletableFuture<?> stage, ImapQueryException.Code code) {
        CompletionException failure = assertThrows(CompletionException.class, stage::join);
        assertInstanceOf(ImapQueryException.class, failure.getCause()); assertEquals(code, ((ImapQueryException) failure.getCause()).code());
        assertNull(failure.getCause().getCause()); assertEquals(0, failure.getCause().getSuppressed().length);
    }
    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        while (latch.getCount() != 0) try { latch.await(); } catch (InterruptedException ignored) { Thread.interrupted(); }
    }
    private static void awaitTrue(BooleanSupplier condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() - deadline < 0) Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }
    private static int count(Map<String, AtomicInteger> values, String key) { AtomicInteger value = values.get(key); return value == null ? 0 : value.get(); }
    private static int activeResolvers() { return ((AtomicInteger) field("ACTIVE_RESOLVER_TASKS")).get(); }
    private static Semaphore resolverGlobal() { return (Semaphore) field("RESOLVER_GLOBAL_SLOTS"); }
    @SuppressWarnings("unchecked") private static boolean resolverMapsEmpty() { return ((Map<String, ?>) field("RESOLVER_TENANT_SLOTS")).isEmpty() && ((Map<String, ?>) field("RESOLVER_PROFILE_SLOTS")).isEmpty(); }
    private static void assertResolverRuntimeClean() { assertEquals(0, activeResolvers()); assertEquals(32, resolverGlobal().availablePermits()); assertTrue(resolverMapsEmpty()); }
    @SuppressWarnings("unchecked") private static void assertQueryRuntimeClean() {
        assertEquals(0, ((AtomicInteger) field("ACTIVE_WATCHDOGS")).get());
        assertEquals(32, ((Semaphore) field("GLOBAL_SLOTS")).availablePermits());
        assertTrue(((Map<String, ?>) field("TENANT_SLOTS")).isEmpty()); assertTrue(((Map<String, ?>) field("PROFILE_SLOTS")).isEmpty());
    }
    private static Object field(String name) {
        try { Field field = MailImapQueryNodeBehavior.class.getDeclaredField(name); field.setAccessible(true); return field.get(null); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static String secretChars(SecretValue secret) {
        try { Field field = SecretValue.class.getDeclaredField("value"); field.setAccessible(true); return new String((char[]) field.get(secret)); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static List<ConcurrentHashMap<?, ?>> capturedGateMaps(NodeAction action) {
        List<ConcurrentHashMap<?, ?>> maps = new ArrayList<>();
        try {
            for (Field field : action.getClass().getDeclaredFields()) if (ConcurrentHashMap.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true); maps.add((ConcurrentHashMap<?, ?>) field.get(action));
            }
            return maps;
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
