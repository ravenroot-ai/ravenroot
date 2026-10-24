package ai.ravenroot.api.persistence;

import ai.ravenroot.api.correlation.*;
import ai.ravenroot.api.security.*;
import java.io.*;
import java.time.Instant;
import java.util.*;

/** Strict versioned input-custody encoding; it never uses Java object deserialization. */
public final class DeploymentEntryCodec {
    /** Finite input plus bounded identity and causal metadata ceiling. */
    public static final int MAX_BYTES = 65 * 1024 * 1024;
    private DeploymentEntryCodec() { }
    /** Encodes one validated original input-custody record.
     * @param value immutable original admission and its runtime-owned checkpoint
     * @return bounded version-two binary record
     */
    public static byte[] encode(DeploymentEntryIntent value) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.writeInt(2); text(out, value.scope().tenantId()); text(out, value.scope().deploymentId()); text(out, value.scope().graphVersion());
                uuid(out, value.processInstanceId()); uuid(out, value.traversalId()); text(out, value.entryNodeId());
                context(out, value.context()); security(out, value.identity()); payload(out, value.input(), 64 * 1024 * 1024);
                payload(out, value.attributes(), 64 * 1024 * 1024); text(out, value.command().name());
                time(out, value.acceptedAt()); time(out, value.expiresAt()); text(out, value.cause().name());
                out.writeBoolean(value.derivedFrom() != null);
                if (value.derivedFrom() != null) {
                    var cause = value.derivedFrom(); uuid(out, cause.processInstanceId()); uuid(out, cause.traversalId());
                    uuid(out, cause.invocationId()); uuid(out, cause.attemptId()); security(out, cause.security());
                }
                text(out, value.derivationReason()); out.writeBoolean(value.checkpoint() != null);
                if (value.checkpoint() != null) {
                    var checkpoint = value.checkpoint(); uuid(out, checkpoint.invocationId()); uuid(out, checkpoint.attemptId());
                    uuid(out, checkpoint.startedEventId()); out.writeBoolean(checkpoint.completedEventId() != null);
                    if (checkpoint.completedEventId() != null) uuid(out, checkpoint.completedEventId());
                    var state = checkpoint.runtimeState(); out.writeLong(state.traversalSteps()); out.writeLong(state.amplifiedDeliveries());
                    out.writeLong(state.payloadBytes()); out.writeBoolean(state.rootFanOutReserved());
                }
            }
            if (bytes.size() > MAX_BYTES) throw invalid();
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalArgumentException("invalid entry encoding", impossible); }
    }
    /** Decodes exact bounded original input and causal identities without instantiating arbitrary classes.
     * @param encoded bounded version-one or version-two custody record
     * @return validated immutable original entry
     */
    public static DeploymentEntryIntent decode(byte[] encoded) {
        if (encoded == null || encoded.length > MAX_BYTES) throw invalid();
        try (var in = new DataInputStream(new ByteArrayInputStream(encoded))) {
            int version = in.readInt(); if (version != 1 && version != 2) throw invalid();
            var scope = new BusinessScope(text(in), text(in), text(in));
            UUID process = uuid(in), traversal = uuid(in); String entry = text(in);
            var context = context(in); var identity = security(in); var payload = payload(in, 64 * 1024 * 1024);
            var attributes = version == 1 ? OpaquePayload.of(new byte[]{'{', '}'}, "application/json") : payload(in, 64 * 1024 * 1024);
            var command = version == 1 ? ai.ravenroot.api.execution.NodeCommand.PROCESS : ai.ravenroot.api.execution.NodeCommand.parse(text(in));
            Instant accepted = time(in), expires = time(in); var cause = CorrelatedTraversal.Cause.valueOf(text(in));
            var original = in.readBoolean() ? new JoinCause(uuid(in), uuid(in), uuid(in), uuid(in), security(in)) : null;
            String reason = text(in); DeploymentEntryCheckpoint checkpoint = null;
            if (in.readBoolean()) {
                UUID invocation = uuid(in), attempt = uuid(in), started = uuid(in);
                UUID completed = in.readBoolean() ? uuid(in) : null;
                checkpoint = new DeploymentEntryCheckpoint(invocation, attempt, started, completed,
                        new DeploymentEntryRuntimeState(in.readLong(), in.readLong(), in.readLong(), in.readBoolean()));
            }
            if (in.available() != 0) throw invalid();
            return new DeploymentEntryIntent(scope, process, traversal, entry, context, identity, payload,
                    accepted, expires, cause, original, reason, checkpoint, attributes, command);
        } catch (IOException invalid) { throw new IllegalArgumentException("invalid entry encoding", invalid); }
    }
    private static void text(DataOutputStream out, String text) throws IOException {
        if (text.length() > 4096) throw invalid(); out.writeUTF(text);
    }
    private static String text(DataInputStream in) throws IOException {
        String value = in.readUTF(); if (value.length() > 4096) throw invalid(); return value;
    }
    private static void uuid(DataOutputStream out, UUID value) throws IOException { out.writeLong(value.getMostSignificantBits()); out.writeLong(value.getLeastSignificantBits()); }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static void time(DataOutputStream out, Instant value) throws IOException { out.writeLong(value.getEpochSecond()); out.writeInt(value.getNano()); }
    private static Instant time(DataInputStream in) throws IOException {
        long seconds = in.readLong(); int nano = in.readInt();
        if (nano < 0 || nano >= 1_000_000_000) throw invalid();
        return Instant.ofEpochSecond(seconds, nano);
    }
    private static void security(DataOutputStream out, SecurityContext value) throws IOException {
        text(out, value.requestId()); text(out, value.tenantId()); text(out, value.subject()); text(out, value.principalType().name()); text(out, value.issuer());
    }
    private static SecurityContext security(DataInputStream in) throws IOException {
        return new SecurityContext(text(in), text(in), text(in), PrincipalType.valueOf(text(in)), text(in));
    }
    private static void context(DataOutputStream out, ApplicationCorrelationContext value) throws IOException {
        text(out, value.applicationCorrelationId()); text(out, value.contributionId().orElse("")); text(out, value.contributionKind().orElse(""));
        text(out, value.roundId()); text(out, value.publicReference().orElse(""));
    }
    private static ApplicationCorrelationContext context(DataInputStream in) throws IOException {
        return new ApplicationCorrelationContext(text(in), optional(text(in)), optional(text(in)), text(in), optional(text(in)));
    }
    private static Optional<String> optional(String value) { return value.isEmpty() ? Optional.empty() : Optional.of(value); }
    private static void payload(DataOutputStream out, OpaquePayload value, int maximum) throws IOException {
        if (value.size() > maximum) throw invalid(); text(out, value.contentType());
        byte[] data = value.bytes(); out.writeInt(data.length); out.write(data);
    }
    private static OpaquePayload payload(DataInputStream in, int maximum) throws IOException {
        String type = text(in); int size = in.readInt();
        if (size < 0 || size > maximum || size > in.available()) throw invalid();
        return OpaquePayload.of(in.readNBytes(size), type);
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid entry encoding"); }
}
