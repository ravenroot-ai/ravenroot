package ai.ravenroot.server.embed;

import ai.ravenroot.api.application.DurableExecutionEvent;
import ai.ravenroot.api.application.DurableProcessEventPage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmbedBrowserHttpHandlerReplayTest {
    @Test
    void rejectsFullyCompactedReplayAndInterPageCompactionGap() {
        assertEquals(-1, EmbedBrowserHttpHandler.validateDurableReplayPage(0,
                new DurableProcessEventPage(List.of(), 4, 4), 512));
        assertEquals(-1, EmbedBrowserHttpHandler.validateDurableReplayPage(1,
                new DurableProcessEventPage(List.of(), 4, 4), 512));
        assertEquals(3, EmbedBrowserHttpHandler.validateDurableReplayPage(3,
                new DurableProcessEventPage(List.of(), 4, 4), 512));
    }

    @Test
    void requiresEveryReturnedPageToBeContiguousAndBounded() {
        var first = event(1);
        assertEquals(1, EmbedBrowserHttpHandler.validateDurableReplayPage(0,
                new DurableProcessEventPage(List.of(first), 1, 2), 512));
        assertEquals(-1, EmbedBrowserHttpHandler.validateDurableReplayPage(1,
                new DurableProcessEventPage(List.of(event(3)), 2, 4), 512));
    }

    private static DurableExecutionEvent event(long sequence) {
        return new DurableExecutionEvent(UUID.randomUUID(), sequence, sequence, "tenant-a", "STARTED",
                UUID.randomUUID(), UUID.randomUUID(), null, null, null, "request-a", "version-a",
                Instant.EPOCH, null);
    }
}
