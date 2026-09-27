package ai.ravenroot.api.persistence;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SagaSnapshotCodecTest {

    @Test
    void roundTripCanonicalizesDependencyOrderBeforePersistence() {
        var step = new SagaStepDefinition("publish", "publish-node", "amqp-inbox-v1", "undo-publish",
                List.of("write-two", "write-one"), false, true);
        var definition = new SagaDefinition(1, "order", "a".repeat(64), "b".repeat(64), Map.of(
                "write-one", pure("write-one"), "write-two", pure("write-two"), "publish", step));
        var now = Instant.parse("2026-09-27T00:00:00Z");
        var snapshot = new SagaSnapshot(new ExecutionKey("tenant", UUID.randomUUID()), UUID.randomUUID(),
                UUID.randomUUID(), definition, 1, SagaDisposition.RUNNING, false, Map.of(), null, now, now,
                "", true);

        assertEquals(snapshot, SagaSnapshotCodec.decode(SagaSnapshotCodec.encode(snapshot)));
        assertEquals(List.of("write-one", "write-two"), step.dependencies());
    }

    @Test
    void duplicateDependenciesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SagaStepDefinition(
                "publish", "publish-node", "amqp-inbox-v1", "undo-publish",
                List.of("write", "write"), false, true));
    }

    @Test
    void recoveryEnvelopeRoundTripsBothImmutableParticipantOperations() throws Exception {
        UUID saga = UUID.randomUUID();
        var payload = OpaquePayload.of("{\"operation\":\"bound\"}"
                .getBytes(StandardCharsets.UTF_8), "application/json");
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(payload.bytes()));
        var forward = new SagaCommandIntent(UUID.randomUUID(), saga, "forward:one", "participant:one",
                "forward.v1", 1, payload, digest, null, Instant.EPOCH, 5);
        var compensation = new SagaCommandIntent(UUID.randomUUID(), saga, "compensate:one", "participant:one",
                "compensate.v1", 1, payload, digest, forward.messageId(), Instant.EPOCH, 5);

        assertEquals(new SagaRecoveryEnvelope(forward, compensation),
                SagaRecoveryEnvelope.decode(new SagaRecoveryEnvelope(forward, compensation).encode()));
        assertThrows(IllegalArgumentException.class, () -> SagaRecoveryEnvelope.decode(
                OpaquePayload.of(new byte[]{0, 0, 0, 2}, SagaRecoveryEnvelope.CONTENT_TYPE)));
    }

    @Test
    void formatTwoDecodesReceiptsAcrossTheOldSixtyFourKibBoundaryToTheDeclaredMaximum() {
        for (int size : List.of(64 * 1024, 64 * 1024 + 1, SagaStepSnapshot.MAX_RECEIPT_BYTES)) {
            var snapshot = snapshotWithReceipt(size);
            byte[] encoded = SagaSnapshotCodec.encode(snapshot);

            assertEquals(2, ByteBuffer.wrap(encoded, Integer.BYTES, Integer.BYTES).getInt(),
                    "the persisted format version remains unchanged");
            assertEquals(snapshot, SagaSnapshotCodec.decode(encoded));
        }
        assertThrows(IllegalArgumentException.class,
                () -> snapshotWithReceipt(SagaStepSnapshot.MAX_RECEIPT_BYTES + 1));
    }

    @Test
    void maximumCommandPayloadAndMetadataFitOneOrTwoOperationRecoveryEnvelopes() throws Exception {
        UUID saga = UUID.randomUUID();
        byte[] bytes = new byte[SagaCommandIntent.MAX_PAYLOAD_BYTES];
        java.util.Arrays.fill(bytes, (byte) 7);
        OpaquePayload payload = OpaquePayload.of(bytes,
                "€".repeat(SagaCommandIntent.MAX_CONTENT_TYPE_CHARACTERS));
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var forward = new SagaCommandIntent(UUID.randomUUID(), saga, "€".repeat(256), "文".repeat(256),
                "€".repeat(128), Integer.MAX_VALUE, payload, digest, null, Instant.MAX, 100);
        var compensation = new SagaCommandIntent(UUID.randomUUID(), saga, "文".repeat(256), "€".repeat(256),
                "文".repeat(128), Integer.MAX_VALUE, payload, digest, forward.messageId(), Instant.MAX, 100);

        assertEquals(new SagaRecoveryEnvelope(forward, null),
                SagaRecoveryEnvelope.decode(new SagaRecoveryEnvelope(forward, null).encode()));
        var both = new SagaRecoveryEnvelope(forward, compensation);
        assertEquals(both, SagaRecoveryEnvelope.decode(both.encode()));
        assertEquals(true, SagaCommandCodec.encode(forward).length <= SagaCommandCodec.MAX_ENCODED_BYTES);
        assertEquals(true, both.encode().size() <= SagaStepSnapshot.MAX_RECEIPT_BYTES);
    }

    @Test
    void commandAndEnvelopeBoundsRejectOnlyValuesOutsideTheirDeclaredContracts() throws Exception {
        byte[] oversized = new byte[SagaCommandIntent.MAX_PAYLOAD_BYTES + 1];
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(oversized));
        assertThrows(IllegalArgumentException.class, () -> new SagaCommandIntent(
                UUID.randomUUID(), UUID.randomUUID(), "operation", "destination", "command", 1,
                OpaquePayload.of(oversized, "application/octet-stream"), digest, null, Instant.EPOCH, 1));

        byte[] encoded = new byte[SagaCommandCodec.MAX_ENCODED_BYTES + 1];
        assertThrows(IllegalArgumentException.class, () -> SagaCommandCodec.decode(encoded));
        byte[] oversizedEnvelope = ByteBuffer.allocate(Integer.BYTES * 2)
                .putInt(1).putInt(SagaCommandCodec.MAX_ENCODED_BYTES + 1).array();
        assertThrows(IllegalArgumentException.class, () -> SagaRecoveryEnvelope.decode(
                OpaquePayload.of(oversizedEnvelope, SagaRecoveryEnvelope.CONTENT_TYPE)));
    }

    private static SagaSnapshot snapshotWithReceipt(int receiptBytes) {
        var definition = new SagaDefinition(1, "receipt-bound", "a".repeat(64), "b".repeat(64), Map.of(
                "effect", new SagaStepDefinition("effect", "effect-node", "jdbc-receipt-v1",
                        "undo-effect", List.of(), false, false)));
        var now = Instant.parse("2026-09-27T00:00:00Z");
        UUID occurrence = UUID.randomUUID();
        var step = new SagaStepSnapshot(occurrence, "effect", UUID.randomUUID(), "forward", "compensation",
                "c".repeat(64), SagaStepStatus.CONFIRMED_SUCCESS,
                OpaquePayload.of(new byte[receiptBytes], "application/octet-stream"), "", now);
        return new SagaSnapshot(new ExecutionKey("tenant", UUID.randomUUID()), UUID.randomUUID(),
                UUID.randomUUID(), definition, 1, SagaDisposition.RUNNING, false,
                Map.of(occurrence, step), null, now, now, "", false);
    }

    private static SagaStepDefinition pure(String id) {
        return new SagaStepDefinition(id, id + "-node", "pure", null, List.of(), false, false);
    }
}
