package ai.ravenroot.persistence.postgresql;

import ai.ravenroot.api.persistence.AgentAuthorityBinding;
import ai.ravenroot.api.persistence.AgentAuthorityGrantRegistration;
import ai.ravenroot.api.persistence.AgentAuthorityRootRegistration;
import ai.ravenroot.api.persistence.AgentAuthorityState;
import ai.ravenroot.api.persistence.AgentBudgetReservation;
import ai.ravenroot.api.persistence.AgentBudgetVector;
import ai.ravenroot.api.persistence.AgentGrantState;
import ai.ravenroot.api.persistence.AgentReservationState;
import ai.ravenroot.api.persistence.DurableAgentAuthorityBudget;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Strict versioned binary encoding of the agent-authority aggregate, for the one {@code BYTEA} column
 * that holds it.
 *
 * <h2>Why the aggregate is one column rather than a table per part</h2>
 * <p>The aggregate is decided as a whole: {@code AgentAuthorityBudgetFold} reads every grant and every
 * reservation to answer whether one hold fits, and a partial write of it has no meaning at all. Spread
 * across three tables it would still have to be read whole, written whole and locked whole, so the
 * tables would buy nothing but the opportunity to write half of one. Stored as one value it is read
 * and replaced under the instance's own row lock, which is the granularity the fold already assumes.
 * The cost is that the database cannot query inside it — accepted, because no operation on this port
 * asks it to: every read here names a process instance.</p>
 *
 * <h2>Why this encoding is this adapter's own</h2>
 * <p>Byte-for-byte agreement with the single-host adapter's encoding is deliberately <em>not</em> a
 * contract. Nothing ever moves these bytes between the two stores — one is a file, the other a server,
 * and no path in the product reads a value written by the other — so a shared format would be a
 * coupling maintained on the strength of a scenario that does not exist, and the first divergence
 * would be discovered as a decode failure rather than as a compile error. What <em>is</em> shared is
 * the aggregate type and its fold, which are in the port; this class only has to be able to read back
 * exactly what it wrote.</p>
 *
 * <h2>Why every read is bounded and every failure is loud</h2>
 * <p>A length prefix read from storage is an instruction to allocate, so each one is checked against a
 * ceiling before it is used: a corrupted count would otherwise be an allocation the process cannot
 * survive, reported as an {@link OutOfMemoryError} that names nothing. Every malformed value —
 * including trailing bytes, a duplicate identifier or an unknown version — is an
 * {@link IllegalArgumentException}, which the store turns into
 * {@link ai.ravenroot.api.persistence.ExecutionStoreFailure.Corrupted} rather than letting a
 * half-decoded ledger reach a caller that would then reason about authority it does not have.</p>
 */
final class AgentAuthorityBudgetCodec {

    /**
     * The format's own version, and the first thing written.
     *
     * <p>It is checked for equality rather than for "at least", because an older binary cannot
     * meaningfully skip a field it does not know is there: the encoding is positional, so a value it
     * failed to recognise would be read as the next field's bytes and would decode into a plausible,
     * wrong ledger. Refusing outright is the only answer that cannot silently understate spend.</p>
     */
    private static final int VERSION = 1;

    private static final int MAX_TEXT_BYTES = 4096;
    private static final int MAX_ITEMS = 100_000;

    private AgentAuthorityBudgetCodec() {
    }

    static byte[] write(DurableAgentAuthorityBudget aggregate) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeInt(VERSION);
            root(out, aggregate.root());
            text(out, aggregate.state().name());
            out.writeLong(aggregate.controlEpoch());
            vector(out, aggregate.spent());
            vector(out, aggregate.reserved());
            // Sorted by identifier, so the same aggregate always encodes to the same bytes. An
            // encoding that followed map iteration order would make two equal ledgers compare
            // unequal as bytes, which is exactly the comparison a diagnosis of "did this write
            // change anything" would reach for.
            out.writeInt(aggregate.grants().size());
            for (Map.Entry<UUID, DurableAgentAuthorityBudget.DurableAgentGrant> entry
                    : sorted(aggregate.grants())) {
                grant(out, entry.getValue());
            }
            out.writeInt(aggregate.reservations().size());
            for (Map.Entry<UUID, AgentBudgetReservation> entry : sorted(aggregate.reservations())) {
                reservation(out, entry.getValue());
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            // A ByteArrayOutputStream does not fail, so this arm exists only because the interface
            // declares it. Reporting it as a store failure would classify an impossibility.
            throw new IllegalStateException("cannot encode the agent authority aggregate", impossible);
        }
    }

    static DurableAgentAuthorityBudget read(ExecutionKey key, byte[] encoded) {
        try {
            var in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != VERSION) {
                throw new IllegalArgumentException("unknown agent authority aggregate version");
            }
            AgentAuthorityRootRegistration root = root(in);
            AgentAuthorityState state = AgentAuthorityState.valueOf(text(in));
            long controlEpoch = in.readLong();
            AgentBudgetVector spent = vector(in);
            AgentBudgetVector reserved = vector(in);
            int grantCount = count(in);
            var grants = new LinkedHashMap<UUID, DurableAgentAuthorityBudget.DurableAgentGrant>();
            for (int index = 0; index < grantCount; index++) {
                DurableAgentAuthorityBudget.DurableAgentGrant grant = grant(in);
                if (grants.put(grant.registration().grantId(), grant) != null) {
                    throw new IllegalArgumentException("duplicate agent grant");
                }
            }
            int reservationCount = count(in);
            var reservations = new LinkedHashMap<UUID, AgentBudgetReservation>();
            for (int index = 0; index < reservationCount; index++) {
                AgentBudgetReservation reservation = reservation(in);
                if (reservations.put(reservation.reservationId(), reservation) != null) {
                    throw new IllegalArgumentException("duplicate agent reservation");
                }
            }
            // Trailing bytes mean the writer and this reader disagree about the shape, which is the
            // same fault as a short read and must not be tolerated as harmless padding.
            if (in.read() != -1) {
                throw new IllegalArgumentException("trailing agent authority aggregate bytes");
            }
            return new DurableAgentAuthorityBudget(key, root, state, controlEpoch, spent, reserved,
                    grants, reservations);
        } catch (IOException | RuntimeException invalid) {
            throw new IllegalArgumentException("invalid stored agent authority aggregate", invalid);
        }
    }

    private static <T> java.util.List<Map.Entry<UUID, T>> sorted(Map<UUID, T> values) {
        return values.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
    }

    private static void root(DataOutputStream out, AgentAuthorityRootRegistration root)
            throws IOException {
        text(out, root.runtimeInstanceId());
        out.writeLong(root.bootEpoch());
        security(out, root.security());
        text(out, root.policyVersion());
        text(out, root.rateCardVersion());
        instant(out, root.absoluteDeadline());
        strings(out, root.dataScopes());
        strings(out, root.authorityScopes());
        vector(out, root.maxima());
        text(out, root.currency());
    }

    private static AgentAuthorityRootRegistration root(DataInputStream in) throws IOException {
        return new AgentAuthorityRootRegistration(text(in), in.readLong(), security(in), text(in),
                text(in), instant(in), strings(in), strings(in), vector(in), text(in));
    }

    private static void grant(DataOutputStream out,
                              DurableAgentAuthorityBudget.DurableAgentGrant grant) throws IOException {
        AgentAuthorityGrantRegistration registration = grant.registration();
        uuid(out, registration.grantId());
        nullableUuid(out, registration.parentGrantId());
        uuids(out, registration.contributingParentGrantIds());
        out.writeLong(registration.depth());
        strings(out, registration.dataScopes());
        strings(out, registration.authorityScopes());
        vector(out, registration.ceilings());
        out.writeLong(registration.maximumTotalTokens());
        instant(out, registration.absoluteDeadline());
        AgentAuthorityBinding binding = grant.binding();
        text(out, binding.nodeId());
        uuid(out, binding.invocationId());
        uuids(out, binding.causalParentInvocationIds());
        text(out, grant.state().name());
        vector(out, grant.spent());
        vector(out, grant.reserved());
    }

    private static DurableAgentAuthorityBudget.DurableAgentGrant grant(DataInputStream in)
            throws IOException {
        // Read into locals rather than inline arguments: Java evaluates arguments left to right, but
        // a reader checking a positional format against its writer should not have to know that in
        // order to see that the order matches.
        UUID grantId = uuid(in);
        UUID parentGrantId = nullableUuid(in);
        Set<UUID> contributingParents = uuids(in);
        long depth = in.readLong();
        Set<String> dataScopes = strings(in);
        Set<String> authorityScopes = strings(in);
        AgentBudgetVector ceilings = vector(in);
        long maximumTotalTokens = in.readLong();
        Instant deadline = instant(in);
        var registration = new AgentAuthorityGrantRegistration(grantId, parentGrantId,
                contributingParents, depth, dataScopes, authorityScopes, ceilings, maximumTotalTokens,
                deadline);
        var binding = new AgentAuthorityBinding(grantId, text(in), uuid(in), uuids(in));
        AgentGrantState state = AgentGrantState.valueOf(text(in));
        return new DurableAgentAuthorityBudget.DurableAgentGrant(registration, binding, state,
                vector(in), vector(in));
    }

    private static void reservation(DataOutputStream out, AgentBudgetReservation value)
            throws IOException {
        uuid(out, value.reservationId());
        uuid(out, value.grantId());
        text(out, value.operationKey());
        vector(out, value.requested());
        vector(out, value.actual());
        text(out, value.state().name());
    }

    private static AgentBudgetReservation reservation(DataInputStream in) throws IOException {
        UUID reservationId = uuid(in);
        UUID grantId = uuid(in);
        String operationKey = text(in);
        AgentBudgetVector requested = vector(in);
        AgentBudgetVector actual = vector(in);
        return new AgentBudgetReservation(reservationId, grantId, operationKey, requested, actual,
                AgentReservationState.valueOf(text(in)));
    }

    private static void vector(DataOutputStream out, AgentBudgetVector value) throws IOException {
        out.writeLong(value.turns());
        out.writeLong(value.inputTokens());
        out.writeLong(value.outputTokens());
        out.writeLong(value.elapsedMillis());
        out.writeLong(value.costMicros());
        out.writeLong(value.toolCalls());
        out.writeLong(value.delegationDepth());
        out.writeLong(value.teamCumulative());
        out.writeLong(value.teamActive());
    }

    private static AgentBudgetVector vector(DataInputStream in) throws IOException {
        long turns = in.readLong();
        long inputTokens = in.readLong();
        long outputTokens = in.readLong();
        long elapsedMillis = in.readLong();
        long costMicros = in.readLong();
        long toolCalls = in.readLong();
        long delegationDepth = in.readLong();
        long teamCumulative = in.readLong();
        return new AgentBudgetVector(turns, inputTokens, outputTokens, elapsedMillis, costMicros,
                toolCalls, delegationDepth, teamCumulative, in.readLong());
    }

    private static void security(DataOutputStream out, SecurityContext value) throws IOException {
        text(out, value.requestId());
        text(out, value.tenantId());
        text(out, value.subject());
        text(out, value.principalType().name());
        text(out, value.issuer());
    }

    private static SecurityContext security(DataInputStream in) throws IOException {
        String requestId = text(in);
        String tenantId = text(in);
        String subject = text(in);
        PrincipalType principalType = PrincipalType.valueOf(text(in));
        return new SecurityContext(requestId, tenantId, subject, principalType, text(in));
    }

    private static void instant(DataOutputStream out, Instant value) throws IOException {
        // Second and nanosecond, for the reason StoredInstant gives for the columns: the JVM's
        // instant is nanosecond-resolution and any encoding that truncates it makes a deadline
        // compare differently after a round trip than it did before one.
        out.writeLong(value.getEpochSecond());
        out.writeInt(value.getNano());
    }

    private static Instant instant(DataInputStream in) throws IOException {
        return Instant.ofEpochSecond(in.readLong(), in.readInt());
    }

    private static void uuid(DataOutputStream out, UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }

    private static UUID uuid(DataInputStream in) throws IOException {
        long high = in.readLong();
        return new UUID(high, in.readLong());
    }

    private static void nullableUuid(DataOutputStream out, UUID value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) {
            uuid(out, value);
        }
    }

    private static UUID nullableUuid(DataInputStream in) throws IOException {
        return in.readBoolean() ? uuid(in) : null;
    }

    private static void text(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("agent authority aggregate text is too large");
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String text(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("invalid agent authority aggregate text length");
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void strings(DataOutputStream out, Set<String> values) throws IOException {
        out.writeInt(values.size());
        for (String value : values.stream().sorted().toList()) {
            text(out, value);
        }
    }

    private static Set<String> strings(DataInputStream in) throws IOException {
        int count = count(in);
        var values = new LinkedHashSet<String>();
        for (int index = 0; index < count; index++) {
            if (!values.add(text(in))) {
                throw new IllegalArgumentException("duplicate agent authority scope");
            }
        }
        return Set.copyOf(values);
    }

    private static void uuids(DataOutputStream out, Set<UUID> values) throws IOException {
        out.writeInt(values.size());
        for (UUID value : values.stream().sorted().toList()) {
            uuid(out, value);
        }
    }

    private static Set<UUID> uuids(DataInputStream in) throws IOException {
        int count = count(in);
        var values = new LinkedHashSet<UUID>();
        for (int index = 0; index < count; index++) {
            if (!values.add(uuid(in))) {
                throw new IllegalArgumentException("duplicate agent authority identifier");
            }
        }
        return Set.copyOf(values);
    }

    /**
     * Reads a length prefix and refuses one that would be an instruction to allocate without bound.
     *
     * <p>The ceiling is not a capacity claim about the ledger; it is the point past which a value
     * read from storage is far more likely to be damage than data, and the difference between a
     * classified corruption and an {@link OutOfMemoryError} that names nothing.</p>
     */
    private static int count(DataInputStream in) throws IOException {
        int value = in.readInt();
        if (value < 0 || value > MAX_ITEMS) {
            throw new IllegalArgumentException("invalid agent authority aggregate item count");
        }
        return value;
    }
}
