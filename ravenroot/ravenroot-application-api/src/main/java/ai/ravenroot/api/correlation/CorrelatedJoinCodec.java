package ai.ravenroot.api.correlation;

import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.security.*;
import java.io.*;
import java.time.Instant;
import java.util.*;

/** Versioned bounded binary encoding for dedicated join state, with no Java deserialization. */
public final class CorrelatedJoinCodec {
    /** Absolute encoded checkpoint ceiling, including bounded causal metadata and contribution data. */
    public static final int MAX_ENCODED_BYTES = 64 * 1024 * 1024;
    private CorrelatedJoinCodec() { }
    /** Encodes a bounded versioned binary join checkpoint without Java object serialization.
 * @return bounded versioned binary snapshot of the immutable join checkpoint
 * @param value immutable join checkpoint to encode
 */
    public static byte[] encode(CorrelatedJoinRecord value) {
        try {
            var bytes = new BoundedOutput();
            try (var out = new DataOutputStream(bytes)) {
                out.writeInt(1); var key = value.key();
                text(out, key.scope().tenantId()); text(out, key.scope().deploymentId()); text(out, key.scope().graphVersion());
                text(out, key.applicationCorrelationId()); text(out, key.nodeId()); text(out, key.roundId());
                out.writeLong(value.revision()); text(out, value.phase().name());
                var cause = value.openedBy();
                text(out, cause.processInstanceId().toString()); text(out, cause.traversalId().toString());
                text(out, cause.invocationId().toString()); text(out, cause.attemptId().toString());
                var owner = cause.security(); text(out, owner.requestId()); text(out, owner.tenantId()); text(out, owner.subject());
                text(out, owner.principalType().name()); text(out, owner.issuer());
                var ids = value.expectedIds().stream().sorted().toList(); out.writeInt(ids.size());
                for (var id : ids) text(out, id);
                out.writeInt(value.expectedCount() == null ? -1 : value.expectedCount()); out.writeBoolean(value.sealed());
                time(out, value.openedAt()); time(out, value.deadlineAt()); time(out, value.updatedAt());
                out.writeInt(value.contributions().size());
                for (var c : value.contributions().values()) {
                    text(out, c.contributionId()); text(out, c.kind() == null ? "" : c.kind());
                    text(out, c.processInstanceId().toString()); text(out, c.traversalId().toString());
                    text(out, c.invocationId().toString()); text(out, c.attemptId().toString());
                    var identity = c.security(); text(out, identity.requestId()); text(out, identity.tenantId());
                    text(out, identity.subject()); text(out, identity.principalType().name()); text(out, identity.issuer());
                    text(out, c.payload().contentType()); var data = c.payload().bytes(); out.writeInt(data.length); out.write(data);
                    text(out, c.payloadDigest()); time(out, c.acceptedAt());
                }
            }
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
    }
    /** Decodes and validates a bounded join checkpoint without instantiating arbitrary classes.
 * @param encoded bounded versioned join snapshot bytes; decoding does not instantiate arbitrary classes
 * @return validated immutable join checkpoint with original contribution causal identities
 */
    public static CorrelatedJoinRecord decode(byte[] encoded) {
        if (encoded == null || encoded.length > MAX_ENCODED_BYTES) throw new IllegalArgumentException("invalid join encoding");
        try (var in = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (in.readInt() != 1) throw new IllegalArgumentException("unsupported join encoding");
            var key = new CorrelatedJoinKey(new BusinessScope(text(in), text(in), text(in)), text(in), text(in), text(in));
            long revision = in.readLong(); var phase = CorrelatedJoinRecord.Phase.valueOf(text(in));
            var cause = new JoinCause(UUID.fromString(text(in)), UUID.fromString(text(in)), UUID.fromString(text(in)),
                    UUID.fromString(text(in)), new SecurityContext(text(in), text(in), text(in),
                            PrincipalType.valueOf(text(in)), text(in)));
            var expected = new TreeSet<String>(); int size = count(in);
            for (int i = 0; i < size; i++) if (!expected.add(text(in))) throw new IllegalArgumentException("duplicate expected ID");
            int count = in.readInt(); boolean sealed = in.readBoolean();
            var opened = time(in); var deadline = time(in); var updated = time(in);
            var contributions = new TreeMap<String, JoinContribution>(); size = count(in);
            for (int i = 0; i < size; i++) {
                String id = text(in), kind = text(in);
                UUID process = UUID.fromString(text(in)), traversal = UUID.fromString(text(in));
                UUID invocation = UUID.fromString(text(in)), attempt = UUID.fromString(text(in));
                var security = new SecurityContext(text(in), text(in), text(in), PrincipalType.valueOf(text(in)), text(in));
                String contentType = text(in); int length = in.readInt();
                if (length < 0 || length > in.available()) throw new IllegalArgumentException("invalid join payload length");
                var payload = OpaquePayload.of(in.readNBytes(length), contentType);
                var item = new JoinContribution(id, kind.isEmpty() ? null : kind, process, traversal, invocation, attempt,
                        security, payload, text(in), time(in));
                if (contributions.putIfAbsent(id, item) != null) throw new IllegalArgumentException("duplicate contribution ID");
            }
            if (in.available() != 0 || count < -1) throw new IllegalArgumentException("invalid trailing join data");
            return new CorrelatedJoinRecord(key, revision, phase, expected, count == -1 ? null : count,
                    sealed, contributions, cause, opened, deadline, updated);
        } catch (IOException invalid) { throw new IllegalArgumentException("invalid join encoding", invalid); }
    }
    private static void text(DataOutputStream out, String value) throws IOException {
        if (value.length() > 4096) throw new IllegalArgumentException("join text exceeds limit"); out.writeUTF(value);
    }
    private static String text(DataInputStream in) throws IOException {
        String value = in.readUTF(); if (value.length() > 4096) throw new IllegalArgumentException("join text exceeds limit"); return value;
    }
    private static void time(DataOutputStream out, Instant value) throws IOException { out.writeLong(value.getEpochSecond()); out.writeInt(value.getNano()); }
    private static Instant time(DataInputStream in) throws IOException { return Instant.ofEpochSecond(in.readLong(), in.readInt()); }
    private static int count(DataInputStream in) throws IOException {
        int value = in.readInt(); if (value < 0 || value > 10000) throw new IllegalArgumentException("join count exceeds limit"); return value;
    }

    /** Bounds every encoded field, including manifests with no contributions, before buffer growth. */
    private static final class BoundedOutput extends ByteArrayOutputStream {
        private void reserve(int length) {
            if (length > MAX_ENCODED_BYTES - count) throw new IllegalArgumentException("join encoding exceeds limit");
            int required = count + length;
            if (required > buf.length) buf = Arrays.copyOf(buf,
                    Math.min(MAX_ENCODED_BYTES, Math.max(required, buf.length * 2)));
        }
        @Override public synchronized void write(int value) {
            reserve(1); buf[count++] = (byte) value;
        }
        @Override public synchronized void write(byte[] values, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, values.length);
            reserve(length); System.arraycopy(values, offset, buf, count, length); count += length;
        }
    }
}
