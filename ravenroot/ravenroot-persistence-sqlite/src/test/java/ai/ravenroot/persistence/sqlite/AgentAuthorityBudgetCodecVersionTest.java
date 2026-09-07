package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.persistence.*;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AgentAuthorityBudgetCodecVersionTest {
    private static final ExecutionKey KEY = new ExecutionKey("tenant", new UUID(0, 1));
    // Frozen from the documented v1 positional layout, independently of either codec writer.
    private static final byte[] LEGACY = Base64.getDecoder().decode("AAAAAQAAAAdydW50aW1lAAAAAAAAAAcAAAAHcmVxdWVzdAAAAAZ0ZW5hbnQAAAAIb3BlcmF0b3IAAAAEVVNFUgAAAAZpc3N1ZXIAAAAGcG9saWN5AAAABXJhdGVzAAAAAAAAAGQAAAAAAAAAAQAAAARkYXRhAAAAAQAAAAR0b29sAAAAAAAAAGQAAAAAAAAD6AAAAAAAAAPoAAAAAAAAJxAAAAAAAAAnEAAAAAAAAABkAAAAAAAAAAoAAAAAAAAACgAAAAAAAAAFAAAAA1VTRAAAAAZBQ1RJVkUAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
    private static final byte[] KILLED_LEGACY = Base64.getDecoder().decode("AAAAAQAAAAdydW50aW1lAAAAAAAAAAcAAAAHcmVxdWVzdAAAAAZ0ZW5hbnQAAAAIb3BlcmF0b3IAAAAEVVNFUgAAAAZpc3N1ZXIAAAAGcG9saWN5AAAABXJhdGVzAAAAAAAAAGQAAAAAAAAAAQAAAARkYXRhAAAAAQAAAAR0b29sAAAAAAAAAGQAAAAAAAAD6AAAAAAAAAPoAAAAAAAAJxAAAAAAAAAnEAAAAAAAAABkAAAAAAAAAAoAAAAAAAAACgAAAAAAAAAFAAAAA1VTRAAAAAZLSUxMRUQAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
    private static final int ROOT_END = 208;

    @Test void literalV1ReadWriteAndCleanupKeepHistoricalBytes() {
        var snapshot = AgentAuthorityBudgetCodec.readSnapshot(KEY, LEGACY);
        assertTrue(snapshot.pinnedRoot().isEmpty());
        assertEquals("runtime", snapshot.budget().root().runtimeInstanceId());
        assertEquals(10000, snapshot.budget().root().maxima().elapsedMillis());
        assertArrayEquals(LEGACY, AgentAuthorityBudgetCodec.writeSnapshot(snapshot));
        assertArrayEquals(LEGACY, AgentAuthorityBudgetCodec.write(AgentAuthorityBudgetCodec.read(KEY, LEGACY)));
        var killed = AgentAuthorityBudgetFold.applySnapshot(KEY, snapshot, new AgentBudgetOperation.KillRoot(0), Instant.EPOCH);
        assertTrue(killed.pinnedRoot().isEmpty());
        assertArrayEquals(KILLED_LEGACY, AgentAuthorityBudgetCodec.writeSnapshot(killed));
    }

    @Test void v2AddsExactly136BytesAndCleanupPreservesItsProof() {
        var legacy = AgentAuthorityBudgetCodec.read(KEY, LEGACY);
        var pin = new PinnedAgentAuthorityRoot(legacy.root(), "a".repeat(64), "b".repeat(64));
        var snapshot = AgentAuthorityBudgetSnapshot.pinned(legacy, pin);
        byte[] encoded = AgentAuthorityBudgetCodec.writeSnapshot(snapshot);
        assertEquals(LEGACY.length + 136, encoded.length);
        assertEquals(2, ByteBuffer.wrap(encoded).getInt());
        assertArrayEquals(Arrays.copyOfRange(LEGACY, 4, ROOT_END), Arrays.copyOfRange(encoded, 4, ROOT_END));
        assertArrayEquals(Arrays.copyOfRange(LEGACY, ROOT_END, LEGACY.length),
                Arrays.copyOfRange(encoded, ROOT_END + 136, encoded.length));
        assertEquals(snapshot, AgentAuthorityBudgetCodec.readSnapshot(KEY, encoded));
        assertEquals(legacy, AgentAuthorityBudgetCodec.read(KEY, encoded));
        var killed = AgentAuthorityBudgetFold.applySnapshot(KEY, snapshot, new AgentBudgetOperation.KillRoot(0), Instant.EPOCH);
        var reopened = AgentAuthorityBudgetCodec.readSnapshot(KEY, AgentAuthorityBudgetCodec.writeSnapshot(killed));
        assertEquals(killed, reopened);
        assertEquals(snapshot.pinnedRoot(), reopened.pinnedRoot());
    }

    @Test void malformedPartialUnknownAndTrailingDataAreRefused() {
        var legacy = AgentAuthorityBudgetCodec.read(KEY, LEGACY);
        byte[] v2 = AgentAuthorityBudgetCodec.writeSnapshot(AgentAuthorityBudgetSnapshot.pinned(legacy,
                new PinnedAgentAuthorityRoot(legacy.root(), "a".repeat(64), "b".repeat(64))));
        for (int version : new int[] {0, 3, Integer.MAX_VALUE}) {
            byte[] bad = v2.clone(); ByteBuffer.wrap(bad).putInt(version); rejects(bad);
        }
        for (int length : new int[] {-1, 0, 63, 65, 4097}) {
            for (int offset : new int[] {ROOT_END, ROOT_END + 68}) {
                byte[] bad = v2.clone(); ByteBuffer.wrap(bad).putInt(offset, length); rejects(bad);
            }
        }
        for (byte malformed : new byte[] {'A', 'g', 0, (byte) 0xff}) {
            byte[] bad = v2.clone(); bad[ROOT_END + 4] = malformed; rejects(bad);
            bad = v2.clone(); bad[ROOT_END + 72] = malformed; rejects(bad);
        }
        rejects(Arrays.copyOf(v2, ROOT_END + 68)); // policy present, rate absent
        rejects(Arrays.copyOf(v2, v2.length - 1));
        rejects(Arrays.copyOf(v2, v2.length + 1));
        rejects(Arrays.copyOf(LEGACY, LEGACY.length + 1));
    }

    @Test void explicitResetReturnsToUnverifiedV1WithoutBackfill() {
        var legacy = AgentAuthorityBudgetCodec.read(KEY, LEGACY);
        var pinned = AgentAuthorityBudgetSnapshot.pinned(legacy,
                new PinnedAgentAuthorityRoot(legacy.root(), "a".repeat(64), "b".repeat(64)));
        var killed = AgentAuthorityBudgetFold.applySnapshot(KEY, pinned, new AgentBudgetOperation.KillRoot(0), Instant.EPOCH);
        var reset = AgentAuthorityBudgetFold.applySnapshot(KEY, killed,
                new AgentBudgetOperation.ResetRoot(legacy.root(), 1), Instant.EPOCH);
        byte[] encoded = AgentAuthorityBudgetCodec.writeSnapshot(reset);
        assertEquals(1, ByteBuffer.wrap(encoded).getInt());
        assertTrue(AgentAuthorityBudgetCodec.readSnapshot(KEY, encoded).pinnedRoot().isEmpty());
    }

    private static void rejects(byte[] bytes) {
        assertThrows(IllegalArgumentException.class, () -> AgentAuthorityBudgetCodec.readSnapshot(KEY, bytes));
    }
}
