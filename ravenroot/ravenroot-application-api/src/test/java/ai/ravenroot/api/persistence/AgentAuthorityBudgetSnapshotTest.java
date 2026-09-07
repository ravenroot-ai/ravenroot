package ai.ravenroot.api.persistence;

import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AgentAuthorityBudgetSnapshotTest {
    private static final ExecutionKey KEY = new ExecutionKey("tenant", new UUID(0, 1));
    private static final Instant NOW = Instant.EPOCH;
    private static final String POLICY = "a".repeat(64), RATE = "b".repeat(64);
    private static final UUID GRANT = new UUID(0, 2), RESERVATION = new UUID(0, 3);
    private static final AgentBudgetVector MAXIMA = new AgentBudgetVector(100, 1000, 1000, 10000, 10000, 100, 10, 10, 5);
    private static final AgentBudgetVector REQUEST = new AgentBudgetVector(1, 2, 3, 4, 5, 0, 0, 0, 0);

    @Test void validatesCompleteFingerprintsAndExactRootBinding() {
        var root = root();
        for (String invalid : new String[] {null, "", "A".repeat(64), "a".repeat(63), "g".repeat(64)}) {
            assertThrows(IllegalArgumentException.class, () -> new PinnedAgentAuthorityRoot(root, invalid, RATE));
            assertThrows(IllegalArgumentException.class, () -> new PinnedAgentAuthorityRoot(root, POLICY, invalid));
        }
        var snapshot = pinned();
        assertEquals(root, snapshot.pinnedRoot().orElseThrow().root());
        assertThrows(IllegalArgumentException.class, () -> AgentAuthorityBudgetSnapshot.pinned(snapshot.budget(),
                new PinnedAgentAuthorityRoot(replacement(root, 8, root.policyVersion(), root.maxima(),
                        root.dataScopes(), root.authorityScopes(), root.currency(), root.rateCardVersion()), POLICY, RATE)));
        assertThrows(NullPointerException.class, () -> new AgentAuthorityBudgetSnapshot(snapshot.budget(), null));
        assertTrue(AgentAuthorityBudgetSnapshot.legacy(snapshot.budget()).pinnedRoot().isEmpty());
    }

    @Test void oldRecordPatternsAndSealedSwitchRemainSourceCompatible() {
        assertEquals(10, AgentAuthorityRootRegistration.class.getRecordComponents().length);
        assertEquals(8, DurableAgentAuthorityBudget.class.getRecordComponents().length);
        assertEquals("runtime", rootPattern(root()));
        assertEquals(KEY, budgetPattern(pinned().budget()));
        assertEquals("cancel", oldOperationSwitch(new AgentBudgetOperation.CancelRoot()));
        // Adding an apply overload on the nullable current type would make this historic call ambiguous.
        assertNotNull(AgentAuthorityBudgetFold.apply(KEY, null, new AgentBudgetOperation.RegisterRoot(root(), 0), NOW));
    }

    @Test void oldCustomStoreDefaultsProjectLegacyAndRefusePinnedWritesWithoutCallingApply() {
        var calls = new AtomicInteger();
        ExecutionStore store = (ExecutionStore) Proxy.newProxyInstance(ExecutionStore.class.getClassLoader(),
                new Class<?>[] {ExecutionStore.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("loadAgentAuthorityBudget")) {
                        return CompletableFuture.completedFuture(Optional.of(pinned().budget()));
                    }
                    if (method.getName().equals("apply")) { calls.incrementAndGet(); throw new AssertionError("legacy write called"); }
                    if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, arguments);
                    throw new AssertionError("unexpected call " + method.getName());
                });
        var loaded = store.loadAgentAuthorityBudgetSnapshot(KEY).toCompletableFuture().join().orElseThrow();
        assertEquals(pinned().budget(), loaded.budget());
        assertTrue(loaded.pinnedRoot().isEmpty());
        var failed = assertThrows(CompletionException.class, () -> store.applyWithPinnedAgentAuthorityRoot(
                batch(new AgentBudgetOperation.RegisterRoot(root(), 0)), pin(root())).toCompletableFuture().join());
        assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class,
                assertInstanceOf(ExecutionStoreException.class, failed.getCause()).failure());
        assertEquals(0, calls.get());
    }

    @Test void exactRegistrationIsIdempotentAndLegacyCannotAcquirePins() {
        var root = root();
        var operation = new AgentBudgetOperation.RegisterRoot(root, 0);
        var snapshot = pinned();
        assertEquals(snapshot, AgentAuthorityBudgetFold.registerPinnedRoot(KEY, snapshot, operation, pin(root), NOW));
        assertEquals(snapshot, AgentAuthorityBudgetFold.applySnapshot(KEY, snapshot, operation, NOW));
        assertThrows(IllegalStateException.class, () -> AgentAuthorityBudgetFold.registerPinnedRoot(KEY,
                AgentAuthorityBudgetSnapshot.legacy(snapshot.budget()), operation, pin(root), NOW));
        assertThrows(IllegalStateException.class, () -> AgentAuthorityBudgetFold.registerPinnedRoot(KEY,
                snapshot, operation, new PinnedAgentAuthorityRoot(root, "c".repeat(64), RATE), NOW));
        assertThrows(IllegalStateException.class, () -> AgentAuthorityBudgetFold.requirePinnedRoot(null, pin(root)));
    }

    @Test void registrationBatchCannotOmitDuplicateMismatchOrReplaceTheRoot() {
        var register = new AgentBudgetOperation.RegisterRoot(root(), 0);
        AgentAuthorityBudgetFold.requirePinnedRegistrationBatch(batch(register), pin(root()));
        for (ExecutionBatch invalid : List.of(batch(new AgentBudgetOperation.CancelRoot()), batch(register, register),
                batch(register, new AgentBudgetOperation.ResetRoot(root(), 0)),
                batch(register, new AgentBudgetOperation.RebootRoot(root(), 0)))) {
            assertThrows(IllegalArgumentException.class,
                    () -> AgentAuthorityBudgetFold.requirePinnedRegistrationBatch(invalid, pin(root())));
        }
        var other = replacement(root(), 8, "policy", MAXIMA, Set.of("data"), Set.of("tool"), "USD", "rates");
        assertThrows(IllegalArgumentException.class,
                () -> AgentAuthorityBudgetFold.requirePinnedRegistrationBatch(batch(register), pin(other)));
    }

    @Test void everyOrdinaryCleanupKeepsExistingPinsAndLegacyRemainsUnverified() {
        for (AgentBudgetOperation cleanup : List.of(new AgentBudgetOperation.Release(RESERVATION),
                new AgentBudgetOperation.Settle(RESERVATION, REQUEST), new AgentBudgetOperation.MarkIndeterminate(RESERVATION),
                new AgentBudgetOperation.Breach(RESERVATION, new AgentBudgetVector(1, 3, 3, 4, 5, 0, 0, 0, 0)),
                new AgentBudgetOperation.CancelGrant(GRANT), new AgentBudgetOperation.ExhaustGrant(GRANT),
                new AgentBudgetOperation.CancelRoot(), new AgentBudgetOperation.KillRoot(0))) {
            var before = withReservation(!(cleanup instanceof AgentBudgetOperation.Release));
            var after = AgentAuthorityBudgetFold.applySnapshot(KEY, before, cleanup, NOW);
            assertEquals(before.pinnedRoot(), after.pinnedRoot(), cleanup.getClass().getSimpleName());
            var legacy = AgentAuthorityBudgetSnapshot.legacy(before.budget());
            assertTrue(AgentAuthorityBudgetFold.applySnapshot(KEY, legacy, cleanup, NOW).pinnedRoot().isEmpty());
        }
    }

    @Test void resetLosesProofAndRebootRebindsOnlyUnchangedConfiguration() {
        var before = pinned();
        var rebootedRoot = replacement(root(), 8, "policy", MAXIMA, Set.of("data"), Set.of("tool"), "USD", "rates");
        var rebooted = AgentAuthorityBudgetFold.applySnapshot(KEY, before,
                new AgentBudgetOperation.RebootRoot(rebootedRoot, 0), NOW);
        assertEquals(rebootedRoot, rebooted.pinnedRoot().orElseThrow().root());
        assertEquals(POLICY, rebooted.pinnedRoot().orElseThrow().policyFingerprint());
        assertEquals(1, rebooted.budget().controlEpoch());
        var killed = AgentAuthorityBudgetFold.applySnapshot(KEY, before, new AgentBudgetOperation.KillRoot(0), NOW);
        var reset = AgentAuthorityBudgetFold.applySnapshot(KEY, killed, new AgentBudgetOperation.ResetRoot(rebootedRoot, 1), NOW);
        assertTrue(reset.pinnedRoot().isEmpty());
        assertEquals(2, reset.budget().controlEpoch());
        assertEquals(killed.budget().spent(), reset.budget().spent());
        assertEquals(killed.budget().reserved(), reset.budget().reserved());
        assertTrue(AgentAuthorityBudgetFold.applySnapshot(KEY, AgentAuthorityBudgetSnapshot.legacy(before.budget()),
                new AgentBudgetOperation.RebootRoot(rebootedRoot, 0), NOW).pinnedRoot().isEmpty());
        for (var changed : List.of(
                replacement(root(), 8, "other", MAXIMA, Set.of("data"), Set.of("tool"), "USD", "rates"),
                replacement(root(), 8, "policy", MAXIMA, Set.of("data"), Set.of("tool"), "EUR", "rates"),
                replacement(root(), 8, "policy", MAXIMA, Set.of("data"), Set.of("tool"), "USD", "other"),
                replacement(root(), 8, "policy", AgentBudgetVector.ZERO, Set.of("data"), Set.of("tool"), "USD", "rates"),
                replacement(root(), 8, "policy", MAXIMA, Set.of(), Set.of("tool"), "USD", "rates"),
                replacement(root(), 8, "policy", MAXIMA, Set.of("data"), Set.of(), "USD", "rates"))) {
            assertTrue(AgentAuthorityBudgetFold.applySnapshot(KEY, before,
                    new AgentBudgetOperation.RebootRoot(changed, 0), NOW).pinnedRoot().isEmpty());
        }
        assertThrows(IllegalStateException.class, () -> AgentAuthorityBudgetFold.applySnapshot(KEY, before,
                new AgentBudgetOperation.RebootRoot(root(), 0), NOW), "unchanged boot still fails old fold validation");
    }

    private static AgentAuthorityBudgetSnapshot withReservation(boolean dispatch) {
        var budget = pinned();
        var grant = new AgentAuthorityGrantRegistration(GRANT, null, Set.of(), 1, Set.of("data"), Set.of("tool"),
                MAXIMA, Instant.ofEpochSecond(50));
        budget = AgentAuthorityBudgetFold.applySnapshot(KEY, budget, new AgentBudgetOperation.RegisterGrant(grant,
                new AgentAuthorityBinding(GRANT, "agent", new UUID(0, 4), Set.of()), 7, 0), NOW);
        budget = AgentAuthorityBudgetFold.applySnapshot(KEY, budget, new AgentBudgetOperation.Hold(
                new AgentBudgetReservation(RESERVATION, GRANT, "operation", REQUEST, AgentBudgetVector.ZERO,
                        AgentReservationState.HELD), 7, 0), NOW);
        return dispatch ? AgentAuthorityBudgetFold.applySnapshot(KEY, budget,
                new AgentBudgetOperation.Dispatch(RESERVATION, 7, 0), NOW) : budget;
    }

    private static AgentAuthorityRootRegistration root() {
        return new AgentAuthorityRootRegistration("runtime", 7,
                new SecurityContext("request", "tenant", "operator", PrincipalType.USER, "issuer"),
                "policy", "rates", Instant.ofEpochSecond(100), Set.of("data"), Set.of("tool"), MAXIMA, "USD");
    }
    private static PinnedAgentAuthorityRoot pin(AgentAuthorityRootRegistration root) { return new PinnedAgentAuthorityRoot(root, POLICY, RATE); }
    private static AgentAuthorityBudgetSnapshot pinned() {
        return AgentAuthorityBudgetFold.registerPinnedRoot(KEY, null, new AgentBudgetOperation.RegisterRoot(root(), 0), pin(root()), NOW);
    }
    private static AgentAuthorityRootRegistration replacement(AgentAuthorityRootRegistration root, long boot,
            String policy, AgentBudgetVector maxima, Set<String> data, Set<String> authority, String currency, String rates) {
        return new AgentAuthorityRootRegistration(root.runtimeInstanceId(), boot, root.security(), policy, rates,
                root.absoluteDeadline().minusSeconds(1), data, authority, maxima, currency);
    }
    private static ExecutionBatch batch(AgentBudgetOperation... operations) {
        var builder = ExecutionBatch.to(KEY).expecting(RevisionExpectation.exactly(1));
        for (var operation : operations) builder.applyAgentBudget(operation);
        return builder.build();
    }
    private static String rootPattern(Object value) {
        if (value instanceof AgentAuthorityRootRegistration(var runtime, var boot, var security, var policy,
                var rates, var deadline, var data, var authority, var maxima, var currency)) return runtime;
        throw new AssertionError();
    }
    private static ExecutionKey budgetPattern(Object value) {
        if (value instanceof DurableAgentAuthorityBudget(var key, var root, var state, var epoch,
                var spent, var reserved, var grants, var reservations)) return key;
        throw new AssertionError();
    }
    private static String oldOperationSwitch(AgentBudgetOperation value) {
        return switch (value) {
            case AgentBudgetOperation.RegisterRoot(var root, var epoch) -> "root";
            case AgentBudgetOperation.RegisterGrant(var grant, var binding, var boot, var epoch) -> "grant";
            case AgentBudgetOperation.Hold(var reservation, var boot, var epoch) -> "hold";
            case AgentBudgetOperation.Dispatch(var reservation, var boot, var epoch) -> "dispatch";
            case AgentBudgetOperation.Settle(var reservation, var actual) -> "settle";
            case AgentBudgetOperation.Breach(var reservation, var observed) -> "breach";
            case AgentBudgetOperation.MarkIndeterminate(var reservation) -> "indeterminate";
            case AgentBudgetOperation.Release(var reservation) -> "release";
            case AgentBudgetOperation.CancelGrant(var grant) -> "cancel-grant";
            case AgentBudgetOperation.ExhaustGrant(var grant) -> "exhaust-grant";
            case AgentBudgetOperation.CancelRoot() -> "cancel";
            case AgentBudgetOperation.KillRoot(var epoch) -> "kill";
            case AgentBudgetOperation.ResetRoot(var replacement, var epoch) -> "reset";
            case AgentBudgetOperation.RebootRoot(var replacement, var epoch) -> "reboot";
        };
    }
}
