package ai.ravenroot.api.node.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExternalIoLimitsTest {
    @Test
    void intersectionCanOnlyNarrowEveryResourceDimension() {
        ExternalIoLimits caller = new ExternalIoLimits(100, 200, 300, 150, 20,
                Duration.ofSeconds(10), Duration.ofSeconds(2), Set.of("application/json"),
                Set.of("identity", "gzip"));
        ExternalIoLimits operator = new ExternalIoLimits(80, 250, 100, 120, 10,
                Duration.ofSeconds(5), Duration.ofSeconds(1), Set.of("application/json", "text/plain"),
                Set.of("identity"));

        ExternalIoLimits effective = caller.intersect(operator);

        assertEquals(80, effective.maximumRequestBytes());
        assertEquals(200, effective.maximumEncodedResponseBytes());
        assertEquals(100, effective.maximumDecodedResponseBytes());
        assertEquals(120, effective.maximumOutputBytes());
        assertEquals(10, effective.maximumDecompressionRatio());
        assertEquals(Duration.ofSeconds(5), effective.maximumDuration());
        assertEquals(Duration.ofSeconds(1), effective.cancellationBound());
        assertEquals(Set.of("application/json"), effective.acceptedMediaTypes());
        assertEquals(Set.of("identity"), effective.acceptedContentEncodings());
    }

    @Test
    void disjointProtocolAuthorityFailsClosedInsteadOfBecomingAcceptAny() {
        ExternalIoLimits json = ExternalIoLimits.http(10, 10, Duration.ofSeconds(1),
                Set.of("application/json"));
        ExternalIoLimits text = ExternalIoLimits.http(10, 10, Duration.ofSeconds(1),
                Set.of("text/plain"));
        assertThrows(IllegalArgumentException.class, () -> json.intersect(text));

        ExternalIoLimits gzip = new ExternalIoLimits(10, 10, 10, 10, 2,
                Duration.ofSeconds(1), Duration.ofSeconds(1), Set.of(), Set.of("gzip"));
        assertThrows(IllegalArgumentException.class, () -> json.intersect(gzip));
    }

    @Test
    void invalidOrUnlimitedDimensionsAndOversizedProjectionAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ExternalIoLimits(0, 1, 1, 1, 1,
                Duration.ofSeconds(1), Duration.ofSeconds(1), Set.of(), Set.of("identity")));
        assertThrows(IllegalArgumentException.class, () -> ExternalIoLimits.MANAGED_HTTP_DEFAULTS
                .requireOutputBytes(ExternalIoLimits.MANAGED_HTTP_DEFAULTS.maximumOutputBytes() + 1));
    }

    @Test
    void responseRepresentationPolicyIsFiniteAndAlwaysValidatesSuccess() {
        var selected = new OutboundHttpRepresentationPolicy(false, Set.of(401, 429));
        assertTrue(selected.validates(200));
        assertTrue(selected.validates(429));
        assertFalse(selected.validates(500));
        assertTrue(OutboundHttpRepresentationPolicy.ALL_STATUSES.validates(500));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboundHttpRepresentationPolicy(false, Set.of(99)));
    }

    @Test
    void managedResponseCarriesEffectiveOutputAuthorityWithoutBreakingLegacyDoubles() {
        assertEquals(Long.MAX_VALUE,
                new OutboundHttpResponse(200, Map.of(), new byte[0]).effectiveMaximumOutputBytes());
        assertEquals(73,
                new OutboundHttpResponse(200, Map.of(), new byte[0], 73)
                        .effectiveMaximumOutputBytes());
        assertThrows(IllegalArgumentException.class,
                () -> new OutboundHttpResponse(200, Map.of(), new byte[0], 0));
    }
}
