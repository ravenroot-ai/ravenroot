package ai.ravenroot.api.persistence;

import org.junit.jupiter.api.Test;

import java.time.Instant;
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

    private static SagaStepDefinition pure(String id) {
        return new SagaStepDefinition(id, id + "-node", "pure", null, List.of(), false, false);
    }
}
