package ai.ravenroot.api.persistence;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Frozen participant operations needed to reconcile or compensate one saga occurrence.
 * Its deterministic format is capped at the 640 KiB saga-receipt boundary and can hold two
 * maximum-payload commands because each encoded command has a 272 KiB ceiling.
 *
 * @param forward frozen forward participant operation
 * @param compensation frozen compensation operation, or {@code null} for an irreversible step
 */
public record SagaRecoveryEnvelope(SagaCommandIntent forward, SagaCommandIntent compensation) {
    /** Media type stored in the bounded step receipt. */
    public static final String CONTENT_TYPE = "application/vnd.ravenroot.saga-recovery.v1";

    /** Validates that compensation, when present, belongs to the same saga and a distinct operation. */
    public SagaRecoveryEnvelope {
        Objects.requireNonNull(forward, "forward");
        if (compensation != null) {
            if (!forward.sagaId().equals(compensation.sagaId())) {
                throw new IllegalArgumentException("recovery operations belong to different sagas");
            }
            if (forward.operationId().equals(compensation.operationId())) {
                throw new IllegalArgumentException("forward and compensation operations must differ");
            }
        }
    }

    /**
     * Encodes this envelope into the public bounded opaque-payload contract.
     *
     * @return encoded recovery envelope
     */
    public OpaquePayload encode() {
        try {
            byte[] forwardBytes = SagaCommandCodec.encode(forward);
            byte[] compensationBytes = compensation == null ? new byte[0] : SagaCommandCodec.encode(compensation);
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                output.writeInt(1);
                output.writeInt(forwardBytes.length);
                output.write(forwardBytes);
                output.writeInt(compensationBytes.length);
                output.write(compensationBytes);
            }
            byte[] encoded = bytes.toByteArray();
            if (encoded.length > SagaStepSnapshot.MAX_RECEIPT_BYTES) {
                throw new IllegalArgumentException("saga recovery envelope exceeds 640 KiB");
            }
            return OpaquePayload.of(encoded, CONTENT_TYPE);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Decodes and bounds one stored recovery envelope.
     *
     * @param payload encoded recovery envelope
     * @return decoded recovery envelope
     */
    public static SagaRecoveryEnvelope decode(OpaquePayload payload) {
        Objects.requireNonNull(payload, "payload");
        if (!CONTENT_TYPE.equals(payload.contentType())) {
            throw new IllegalArgumentException("step receipt is not a saga recovery envelope");
        }
        try (var input = new DataInputStream(new ByteArrayInputStream(payload.bytes()))) {
            if (input.readInt() != 1) throw new IllegalArgumentException("unsupported recovery envelope version");
            byte[] forward = read(input);
            byte[] compensation = read(input);
            if (input.read() != -1) throw new IllegalArgumentException("trailing recovery envelope bytes");
            return new SagaRecoveryEnvelope(SagaCommandCodec.decode(forward),
                    compensation.length == 0 ? null : SagaCommandCodec.decode(compensation));
        } catch (IOException malformed) {
            throw new IllegalArgumentException("malformed saga recovery envelope", malformed);
        }
    }

    private static byte[] read(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > SagaCommandCodec.MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException("recovery intent is oversized");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new IllegalArgumentException("truncated recovery envelope");
        return Arrays.copyOf(bytes, bytes.length);
    }
}
