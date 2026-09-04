package ai.ravenroot.persistence.sqlite;

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

/** Strict versioned binary codec for the bounded agent-authority aggregate. */
final class AgentAuthorityBudgetCodec {
    private static final int VERSION = 1;
    private static final int MAX_TEXT_BYTES = 4096;
    private static final int MAX_ITEMS = 100_000;

    private AgentAuthorityBudgetCodec() { }

    static byte[] write(DurableAgentAuthorityBudget aggregate) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeInt(VERSION);
            root(out, aggregate.root());
            text(out, aggregate.state().name());
            out.writeLong(aggregate.controlEpoch());
            vector(out, aggregate.spent()); vector(out, aggregate.reserved());
            out.writeInt(aggregate.grants().size());
            aggregate.grants().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                try { grant(out, entry.getValue()); } catch (IOException failure) { throw new CodecFailure(failure); }
            });
            out.writeInt(aggregate.reservations().size());
            aggregate.reservations().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                try { reservation(out, entry.getValue()); } catch (IOException failure) { throw new CodecFailure(failure); }
            });
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("cannot encode agent authority", impossible);
        } catch (CodecFailure failure) {
            throw new IllegalStateException("cannot encode agent authority", failure.getCause());
        }
    }

    static DurableAgentAuthorityBudget read(ExecutionKey key, byte[] encoded) {
        try {
            var in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != VERSION) throw new IllegalArgumentException("unknown agent authority version");
            AgentAuthorityRootRegistration root = root(in);
            AgentAuthorityState state = AgentAuthorityState.valueOf(text(in));
            long controlEpoch = in.readLong();
            AgentBudgetVector spent = vector(in), reserved = vector(in);
            int grantCount = count(in);
            var grants = new LinkedHashMap<UUID, DurableAgentAuthorityBudget.DurableAgentGrant>();
            for (int i = 0; i < grantCount; i++) {
                var grant = grant(in);
                if (grants.put(grant.registration().grantId(), grant) != null) {
                    throw new IllegalArgumentException("duplicate agent grant");
                }
            }
            int reservationCount = count(in);
            var reservations = new LinkedHashMap<UUID, AgentBudgetReservation>();
            for (int i = 0; i < reservationCount; i++) {
                AgentBudgetReservation reservation = reservation(in);
                if (reservations.put(reservation.reservationId(), reservation) != null) {
                    throw new IllegalArgumentException("duplicate agent reservation");
                }
            }
            if (in.read() != -1) throw new IllegalArgumentException("trailing agent authority bytes");
            return new DurableAgentAuthorityBudget(key, root, state, controlEpoch, spent, reserved, grants, reservations);
        } catch (IOException | RuntimeException invalid) {
            throw new IllegalArgumentException("invalid stored agent authority", invalid);
        }
    }

    private static void root(DataOutputStream out, AgentAuthorityRootRegistration root) throws IOException {
        text(out, root.runtimeInstanceId()); out.writeLong(root.bootEpoch()); security(out, root.security());
        text(out, root.policyVersion()); text(out, root.rateCardVersion()); instant(out, root.absoluteDeadline());
        strings(out, root.dataScopes()); strings(out, root.authorityScopes()); vector(out, root.maxima());
        text(out, root.currency());
    }

    private static AgentAuthorityRootRegistration root(DataInputStream in) throws IOException {
        return new AgentAuthorityRootRegistration(text(in), in.readLong(), security(in), text(in), text(in),
                instant(in), strings(in), strings(in), vector(in), text(in));
    }

    private static void grant(DataOutputStream out, DurableAgentAuthorityBudget.DurableAgentGrant grant)
            throws IOException {
        AgentAuthorityGrantRegistration registration = grant.registration();
        uuid(out, registration.grantId()); nullableUuid(out, registration.parentGrantId());
        uuids(out, registration.contributingParentGrantIds()); out.writeLong(registration.depth());
        strings(out, registration.dataScopes()); strings(out, registration.authorityScopes());
        vector(out, registration.ceilings()); out.writeLong(registration.maximumTotalTokens());
        instant(out, registration.absoluteDeadline());
        AgentAuthorityBinding binding = grant.binding(); text(out, binding.nodeId());
        uuid(out, binding.invocationId()); uuids(out, binding.causalParentInvocationIds());
        text(out, grant.state().name()); vector(out, grant.spent()); vector(out, grant.reserved());
    }

    private static DurableAgentAuthorityBudget.DurableAgentGrant grant(DataInputStream in) throws IOException {
        UUID id = uuid(in), parent = nullableUuid(in); Set<UUID> parents = uuids(in); long depth = in.readLong();
        var registration = new AgentAuthorityGrantRegistration(id, parent, parents, depth, strings(in),
                strings(in), vector(in), in.readLong(), instant(in));
        var binding = new AgentAuthorityBinding(id, text(in), uuid(in), uuids(in));
        return new DurableAgentAuthorityBudget.DurableAgentGrant(registration, binding,
                AgentGrantState.valueOf(text(in)), vector(in), vector(in));
    }

    private static void reservation(DataOutputStream out, AgentBudgetReservation value) throws IOException {
        uuid(out, value.reservationId()); uuid(out, value.grantId()); text(out, value.operationKey());
        vector(out, value.requested()); vector(out, value.actual()); text(out, value.state().name());
    }

    private static AgentBudgetReservation reservation(DataInputStream in) throws IOException {
        return new AgentBudgetReservation(uuid(in), uuid(in), text(in), vector(in), vector(in),
                AgentReservationState.valueOf(text(in)));
    }

    private static void vector(DataOutputStream out, AgentBudgetVector value) throws IOException {
        out.writeLong(value.turns()); out.writeLong(value.inputTokens()); out.writeLong(value.outputTokens());
        out.writeLong(value.elapsedMillis()); out.writeLong(value.costMicros()); out.writeLong(value.toolCalls());
        out.writeLong(value.delegationDepth()); out.writeLong(value.teamCumulative()); out.writeLong(value.teamActive());
    }

    private static AgentBudgetVector vector(DataInputStream in) throws IOException {
        return new AgentBudgetVector(in.readLong(), in.readLong(), in.readLong(), in.readLong(), in.readLong(),
                in.readLong(), in.readLong(), in.readLong(), in.readLong());
    }

    private static void security(DataOutputStream out, SecurityContext value) throws IOException {
        text(out, value.requestId()); text(out, value.tenantId()); text(out, value.subject());
        text(out, value.principalType().name()); text(out, value.issuer());
    }

    private static SecurityContext security(DataInputStream in) throws IOException {
        return new SecurityContext(text(in), text(in), text(in), PrincipalType.valueOf(text(in)), text(in));
    }

    private static void instant(DataOutputStream out, Instant value) throws IOException {
        out.writeLong(value.getEpochSecond()); out.writeInt(value.getNano());
    }
    private static Instant instant(DataInputStream in) throws IOException { return Instant.ofEpochSecond(in.readLong(), in.readInt()); }
    private static void uuid(DataOutputStream out, UUID value) throws IOException { out.writeLong(value.getMostSignificantBits()); out.writeLong(value.getLeastSignificantBits()); }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static void nullableUuid(DataOutputStream out, UUID value) throws IOException { out.writeBoolean(value != null); if (value != null) uuid(out, value); }
    private static UUID nullableUuid(DataInputStream in) throws IOException { return in.readBoolean() ? uuid(in) : null; }

    private static void text(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT_BYTES) throw new IllegalArgumentException("agent authority text is too large");
        out.writeInt(bytes.length); out.write(bytes);
    }
    static String text(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_TEXT_BYTES) throw new IllegalArgumentException("invalid agent authority text length");
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
    private static void strings(DataOutputStream out, Set<String> values) throws IOException {
        out.writeInt(values.size()); for (String value : values.stream().sorted().toList()) text(out, value);
    }
    private static Set<String> strings(DataInputStream in) throws IOException {
        int count = count(in); var values = new LinkedHashSet<String>();
        for (int i = 0; i < count; i++) if (!values.add(text(in))) throw new IllegalArgumentException("duplicate scope");
        return Set.copyOf(values);
    }
    private static void uuids(DataOutputStream out, Set<UUID> values) throws IOException {
        out.writeInt(values.size()); for (UUID value : values.stream().sorted().toList()) uuid(out, value);
    }
    private static Set<UUID> uuids(DataInputStream in) throws IOException {
        int count = count(in); var values = new LinkedHashSet<UUID>();
        for (int i = 0; i < count; i++) if (!values.add(uuid(in))) throw new IllegalArgumentException("duplicate id");
        return Set.copyOf(values);
    }
    private static int count(DataInputStream in) throws IOException {
        int value = in.readInt(); if (value < 0 || value > MAX_ITEMS) throw new IllegalArgumentException("invalid item count"); return value;
    }
    private static final class CodecFailure extends RuntimeException { private CodecFailure(Throwable cause) { super(cause); } }
}
