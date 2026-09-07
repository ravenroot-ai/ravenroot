package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.persistence.*;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.*;

class AgentAuthorityBudgetStorageTest {
    @TempDir Path directory;
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test void pinnedMetadataCountsTowardTheExactEncodedLimitAndRefusalRollsBackEverything() {
        var key = new ExecutionKey("tenant", new UUID(0, 1));
        var root = root(key);
        var pin = pin(root);
        var register = new AgentBudgetOperation.RegisterRoot(root, 0);
        var legacy = AgentAuthorityBudgetFold.apply(key, null, register, NOW);
        int legacyBytes = AgentAuthorityBudgetCodec.write(legacy).length;
        int pinnedBytes = AgentAuthorityBudgetCodec.writeSnapshot(AgentAuthorityBudgetSnapshot.pinned(legacy, pin)).length;
        assertEquals(136, pinnedBytes - legacyBytes);
        for (int maximum : new int[] {legacyBytes, pinnedBytes - 1, pinnedBytes}) {
            try (var store = new SqliteExecutionStore(directory.resolve("cap-" + maximum + ".db"), CLOCK, config(maximum))) {
                var traversal = UUID.randomUUID();
                var before = await(store.apply(Fixtures.creationBatch(key, traversal)));
                var batch = ExecutionBatch.to(key).expecting(RevisionExpectation.exactly(before.revision()))
                        .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                        .publish(EventEnvelope.of(UUID.randomUUID(), key.tenantId(), "ROOT", key.processInstanceId(), traversal,
                                null, null, null, "request", "graph-v1", NOW, OpaquePayload.empty("text/plain")))
                        .applyAgentBudget(register).build();
                if (maximum < pinnedBytes) {
                    assertInstanceOf(ExecutionStoreFailure.PayloadTooLarge.class,
                            failure(() -> await(store.applyWithPinnedAgentAuthorityRoot(batch, pin))));
                    assertEquals(before, await(store.load(key)));
                    assertTrue(await(store.loadAgentAuthorityBudgetSnapshot(key)).isEmpty());
                    assertTrue(await(store.readJournal(key.tenantId(), 0, 10)).isEmpty());
                    await(store.apply(batch)); // the same v1 registration fits; pin bytes caused the refusal
                    assertTrue(await(store.loadAgentAuthorityBudgetSnapshot(key)).orElseThrow().pinnedRoot().isEmpty());
                } else {
                    await(store.applyWithPinnedAgentAuthorityRoot(batch, pin));
                    assertEquals(pin, await(store.loadAgentAuthorityBudgetSnapshot(key)).orElseThrow().pinnedRoot().orElseThrow());
                    assertEquals(1, await(store.readJournal(key.tenantId(), 0, 10)).size());
                }
            }
        }
    }

    @Test void unknownStoredCodecVersionIsClassifiedOnBothLoadPortsAndCleanup() throws Exception {
        var key = new ExecutionKey("tenant", new UUID(0, 1));
        Path file = directory.resolve("corrupt.db");
        try (var store = new SqliteExecutionStore(file, CLOCK)) {
            var before = await(store.apply(Fixtures.creationBatch(key, UUID.randomUUID())));
            await(store.applyWithPinnedAgentAuthorityRoot(ExecutionBatch.to(key)
                    .expecting(RevisionExpectation.exactly(before.revision()))
                    .applyAgentBudget(new AgentBudgetOperation.RegisterRoot(root(key), 0)).build(), pin(root(key))));
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             var statement = connection.prepareStatement("UPDATE agent_authority_budget SET aggregate = ?")) {
            statement.setBytes(1, ByteBuffer.allocate(4).putInt(3).array());
            assertEquals(1, statement.executeUpdate());
        }
        try (var store = new SqliteExecutionStore(file, CLOCK)) {
            assertInstanceOf(ExecutionStoreFailure.Corrupted.class,
                    failure(() -> await(store.loadAgentAuthorityBudget(key))));
            assertInstanceOf(ExecutionStoreFailure.Corrupted.class,
                    failure(() -> await(store.loadAgentAuthorityBudgetSnapshot(key))));
            var before = await(store.load(key));
            assertInstanceOf(ExecutionStoreFailure.Corrupted.class, failure(() -> await(store.apply(ExecutionBatch.to(key)
                    .expecting(RevisionExpectation.exactly(before.revision()))
                    .applyAgentBudget(new AgentBudgetOperation.CancelRoot()).build()))));
            assertEquals(before, await(store.load(key)));
        }
    }

    private static AgentAuthorityRootRegistration root(ExecutionKey key) {
        return new AgentAuthorityRootRegistration("runtime", 7,
                new SecurityContext("request", key.tenantId(), "operator", PrincipalType.USER, "issuer"),
                "policy", "rates", NOW.plusSeconds(100), Set.of("data"), Set.of("tool"),
                new AgentBudgetVector(100, 1000, 1000, 10000, 10000, 100, 10, 10, 5), "USD");
    }
    private static PinnedAgentAuthorityRoot pin(AgentAuthorityRootRegistration root) {
        return new PinnedAgentAuthorityRoot(root, "a".repeat(64), "b".repeat(64));
    }
    private static SqliteStoreConfig config(int bytes) {
        var defaults = SqliteStoreConfig.defaults();
        return new SqliteStoreConfig(defaults.synchronousMode(), defaults.busyTimeout(), defaults.maxLeaseTtl(),
                bytes, defaults.maxClockSkew(), defaults.journalRetention(), defaults.maxInventoryPageSize(),
                defaults.terminalRetention(), defaults.executionResultRetention());
    }
    private static <T> T await(CompletionStage<T> stage) { return stage.toCompletableFuture().join(); }
    private static ExecutionStoreFailure failure(Runnable operation) {
        var thrown = assertThrows(CompletionException.class, operation::run);
        return assertInstanceOf(ExecutionStoreException.class, thrown.getCause()).failure();
    }
}
