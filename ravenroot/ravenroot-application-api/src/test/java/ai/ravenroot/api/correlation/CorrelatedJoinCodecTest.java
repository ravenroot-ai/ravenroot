package ai.ravenroot.api.correlation;

import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CorrelatedJoinCodecTest {
    @Test void boundedAnnouncementsCannotAccumulateAnUnreadableMetadataOnlyCheckpoint() {
        var defaults = BusinessHistoryPolicy.DEFAULTS;
        var input = new PayloadLimits(8 * 1024 * 1024, 32, 1000, 10000, 32768, 256);
        var selection = new CorrelationLimits(4096, 1024, 32, 1000, input);
        var policy = new BusinessHistoryPolicy(defaults.maxHistoriesPerScope(), defaults.maxOpenJoinsPerHistory(),
                defaults.maxRetainedJoinsPerHistory(), 10000, defaults.maxJoinPayloadBytes(), defaults.maxPageSize(),
                defaults.historyRetention(), defaults.joinDeadline(), selection);
        var now = Instant.parse("2026-01-01T00:00:00Z");
        var cause = new JoinCause(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new SecurityContext("request", "tenant", "actor", PrincipalType.WORKLOAD, "test"));
        var key = new CorrelatedJoinKey(new BusinessScope("tenant", "deployment", "version"), "operation", "join", "round");
        var record = CorrelatedJoinRecord.open(key, Set.of(), null, false, cause, now, now.plusSeconds(60));
        var selector = new CorrelationSelector("/expected", selection);
        var prefix = "\u20ac".repeat(4090);
        for (int batch = 0; batch < 10; batch++) {
            var ids = new ArrayList<String>();
            for (int item = 0; item < 550; item++) ids.add(prefix + "-" + (batch * 550 + item));
            // Every actual selector input fits its configured bound; the retained union grows.
            var announced = Set.copyOf(selector.selectManifest(PayloadValue.fromJava(Map.of("expected", ids), input)));
            record = record.contribute(null, announced, null, false, now, policy);
            if (batch == 0) assertEquals(record, CorrelatedJoinCodec.decode(CorrelatedJoinCodec.encode(record)));
        }
        assertEquals(5500, record.expectedIds().size());
        assertTrue(record.contributions().isEmpty());
        var oversized = record;
        var failure = assertThrows(IllegalArgumentException.class, () -> CorrelatedJoinCodec.encode(oversized));
        assertEquals("join encoding exceeds limit", failure.getMessage());
    }
}
