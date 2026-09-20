package ai.ravenroot.server;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DeploymentObservationCursorStoreTest {
    @Test
    void cursorIsOpaqueBoundToAuthorityIncarnationAndVersionAndEvictsAtCapacity() {
        var store = new DeploymentObservationCursorStore(
                Clock.fixed(Instant.parse("2026-09-17T00:00:00Z"), ZoneOffset.UTC), 2, Duration.ofMinutes(1));
        var binding = new DeploymentObservationCursorStore.Binding("session-1", "deployment", "inc-1", "v1");
        String first = store.issue(binding, 7);
        assertEquals(7, store.resolve(first, binding).sequence());
        assertNull(store.resolve(first,
                new DeploymentObservationCursorStore.Binding("session-2", "deployment", "inc-1", "v1")));
        assertNull(store.resolve(first,
                new DeploymentObservationCursorStore.Binding("session-1", "deployment", "inc-2", "v1")));
        assertNull(store.resolve(first,
                new DeploymentObservationCursorStore.Binding("session-1", "deployment", "inc-1", "v2")));

        store.issue(binding, 8);
        store.issue(binding, 9);
        assertNull(store.resolve(first, binding), "bounded cursor state must fail closed after eviction");
    }
}
