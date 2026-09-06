package ai.ravenroot.api.persistence;

import ai.ravenroot.api.payload.PayloadKind;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HumanTaskPolicyTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final HumanTaskPolicy POLICY = new HumanTaskPolicy(
            100, 200, 10, 20, 30, 40,
            10, 20, 10, 2, 10,
            300, 5, 10,
            5, 6, 7, 8, 9, 2);

    @Test
    void acceptsEveryPolicyBoundaryIncludingTheFixedVersionProtocolBoundary() {
        HumanTaskRegistration registration = registration(
                new HumanTaskMetadata("0123456789", "01234567890123456789"),
                new HumanTaskResponseSchema("application/json", "abcdefghij", "v".repeat(128),
                        PayloadKind.MAP, 200),
                new HandlerAuthorization(Set.of("a", "b"), Set.of("c", "d")),
                Optional.of(NOW.plusSeconds(20)), NOW.plusSeconds(40),
                POLICY.executionLimits(200));

        assertDoesNotThrow(() -> POLICY.requireNewRegistration(registration, NOW));
    }

    @Test
    void refusesEveryNewRegistrationDimensionOutsideTheActivePolicy() {
        HumanTaskRegistration valid = registration(
                new HumanTaskMetadata("Review", "Check"),
                new HumanTaskResponseSchema("application/json", "response", "1",
                        PayloadKind.MAP, 100),
                new HandlerAuthorization(Set.of("role"), Set.of("scope")),
                Optional.of(NOW.plusSeconds(10)), NOW.plusSeconds(30),
                POLICY.executionLimits(100));
        var invalid = new ArrayList<HumanTaskRegistration>();
        invalid.add(copy(valid, new HumanTaskMetadata("x".repeat(11), "Check"),
                valid.responseSchema(), valid.responderRequirements(), valid.escalateAt(),
                valid.expiresAt(), valid.executionLimits()));
        invalid.add(copy(valid, new HumanTaskMetadata("Review", "x".repeat(21)),
                valid.responseSchema(), valid.responderRequirements(), valid.escalateAt(),
                valid.expiresAt(), valid.executionLimits()));
        invalid.add(copy(valid, valid.metadata(),
                new HumanTaskResponseSchema("application/json", "response", "1",
                        PayloadKind.MAP, 201), valid.responderRequirements(), valid.escalateAt(),
                valid.expiresAt(), limits(201, 5, 6, 7, 8, 9, 300, 2)));
        invalid.add(copy(valid, valid.metadata(), schema("abcdefghijk", "1"),
                valid.responderRequirements(), valid.escalateAt(), valid.expiresAt(),
                valid.executionLimits()));
        invalid.add(copy(valid, valid.metadata(), schema("bad schema", "1"),
                valid.responderRequirements(), valid.escalateAt(), valid.expiresAt(),
                valid.executionLimits()));
        invalid.add(copy(valid, valid.metadata(), schema("response", "bad version"),
                valid.responderRequirements(), valid.escalateAt(), valid.expiresAt(),
                valid.executionLimits()));
        invalid.add(copy(valid, valid.metadata(), schema("response", "v".repeat(129)),
                valid.responderRequirements(), valid.escalateAt(), valid.expiresAt(),
                valid.executionLimits()));
        invalid.add(copy(valid, valid.metadata(), valid.responseSchema(),
                new HandlerAuthorization(Set.of("a", "b", "c"), Set.of("scope")),
                valid.escalateAt(), valid.expiresAt(), valid.executionLimits()));
        invalid.add(copy(valid, valid.metadata(), valid.responseSchema(),
                new HandlerAuthorization(Set.of("role"), Set.of("a", "b", "c")),
                valid.escalateAt(), valid.expiresAt(), valid.executionLimits()));
        invalid.add(copy(valid, valid.metadata(), valid.responseSchema(),
                new HandlerAuthorization(Set.of("x".repeat(11)), Set.of("scope")),
                valid.escalateAt(), valid.expiresAt(), valid.executionLimits()));
        invalid.add(copy(valid, valid.metadata(), valid.responseSchema(),
                new HandlerAuthorization(Set.of("role"), Set.of("x".repeat(11))),
                valid.escalateAt(), valid.expiresAt(), valid.executionLimits()));
        invalid.add(copy(valid, valid.metadata(), valid.responseSchema(),
                valid.responderRequirements(), valid.escalateAt(), NOW.plusSeconds(41),
                valid.executionLimits()));
        invalid.add(copy(valid, valid.metadata(), valid.responseSchema(),
                valid.responderRequirements(), Optional.of(NOW.plusSeconds(21)),
                valid.expiresAt(), valid.executionLimits()));
        invalid.add(copy(valid, valid.metadata(), valid.responseSchema(),
                valid.responderRequirements(), Optional.empty(), NOW, valid.executionLimits()));
        invalid.add(copy(valid, valid.metadata(), valid.responseSchema(),
                valid.responderRequirements(), valid.escalateAt(), valid.expiresAt(),
                limits(100, 6, 6, 7, 8, 9, 300, 2)));
        invalid.add(copy(valid, valid.metadata(), valid.responseSchema(),
                valid.responderRequirements(), valid.escalateAt(), valid.expiresAt(),
                limits(100, 5, 7, 7, 8, 9, 300, 2)));
        invalid.add(copy(valid, valid.metadata(), valid.responseSchema(),
                valid.responderRequirements(), valid.escalateAt(), valid.expiresAt(),
                limits(100, 5, 6, 8, 8, 9, 300, 2)));
        invalid.add(copy(valid, valid.metadata(), valid.responseSchema(),
                valid.responderRequirements(), valid.escalateAt(), valid.expiresAt(),
                limits(100, 5, 6, 7, 9, 9, 300, 2)));
        invalid.add(copy(valid, valid.metadata(), valid.responseSchema(),
                valid.responderRequirements(), valid.escalateAt(), valid.expiresAt(),
                limits(100, 5, 6, 7, 8, 10, 300, 2)));
        invalid.add(copy(valid, valid.metadata(), valid.responseSchema(),
                valid.responderRequirements(), valid.escalateAt(), valid.expiresAt(),
                limits(100, 5, 6, 7, 8, 9, 301, 2)));
        invalid.add(copy(valid, valid.metadata(), valid.responseSchema(),
                valid.responderRequirements(), valid.escalateAt(), valid.expiresAt(),
                limits(100, 5, 6, 7, 8, 9, 300, 3)));

        for (HumanTaskRegistration registration : invalid) {
            assertThrows(IllegalArgumentException.class,
                    () -> POLICY.requireNewRegistration(registration, NOW));
        }
    }

    private static HumanTaskResponseSchema schema(String schema, String version) {
        return new HumanTaskResponseSchema("application/json", schema, version, PayloadKind.MAP, 100);
    }

    private static HumanTaskExecutionLimits limits(int bytes, int depth, int collection, int values,
                                                    int text, int key, int body, int attempts) {
        return new HumanTaskExecutionLimits(
                new PayloadLimits(bytes, depth, collection, values, text, key), body, attempts);
    }

    private static HumanTaskRegistration registration(HumanTaskMetadata metadata,
                                                       HumanTaskResponseSchema schema,
                                                       HandlerAuthorization authorization,
                                                       Optional<Instant> escalation,
                                                       Instant expiry,
                                                       HumanTaskExecutionLimits limits) {
        byte[] continuation = new byte[0];
        return new HumanTaskRegistration(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "review", "correlation", "deduplication", metadata, schema,
                authorization,
                new SecurityContext("request", "tenant", "requester", PrincipalType.USER, "issuer"),
                new GraphVersionPin("graph-v1"), escalation, expiry,
                new HumanTaskReentryMapping("resolved", "denied", "expired", "cancelled"), limits,
                1, continuation, ToolApprovalRegistration.digest(continuation));
    }

    private static HumanTaskRegistration copy(HumanTaskRegistration source,
                                               HumanTaskMetadata metadata,
                                               HumanTaskResponseSchema schema,
                                               HandlerAuthorization authorization,
                                               Optional<Instant> escalation,
                                               Instant expiry,
                                               HumanTaskExecutionLimits limits) {
        return new HumanTaskRegistration(source.taskId(), source.traversalId(), source.invocationId(),
                source.attemptId(), source.nodeId(), source.correlationKey(), source.deduplicationKey(),
                metadata, schema, authorization, source.requester(), source.graphVersionPin(), escalation,
                expiry, source.reentryMapping(), limits, source.continuationVersion(),
                source.continuation(), source.continuationDigest());
    }
}
