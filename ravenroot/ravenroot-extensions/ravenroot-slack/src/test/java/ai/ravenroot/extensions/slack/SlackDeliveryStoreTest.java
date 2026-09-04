package ai.ravenroot.extensions.slack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SlackDeliveryStoreTest {
    @TempDir Path directory;
    @Test void bindingSurvivesRestartAndRejectsCollisionWithoutContent() {
        SlackConfiguration configuration = SlackTestSupport.configuration(directory.resolve("deliveries.db"));
        String first = "a".repeat(64); String second = "b".repeat(64);
        assertEquals(SlackDeliveryStore.Decision.FIRST_SEEN,
                new SqliteSlackDeliveryStore(configuration.store(), SlackTestSupport.fixedClock())
                        .bind(SlackTestSupport.TENANT, SlackTestSupport.PROFILE, "event", "Ev01234567", first));
        var restarted = new SqliteSlackDeliveryStore(configuration.store(), SlackTestSupport.fixedClock());
        assertEquals(SlackDeliveryStore.Decision.REPLAY,
                restarted.bind(SlackTestSupport.TENANT, SlackTestSupport.PROFILE, "event", "Ev01234567", first));
        SlackException collision = assertThrows(SlackException.class, () -> restarted.bind(
                SlackTestSupport.TENANT, SlackTestSupport.PROFILE, "event", "Ev01234567", second));
        assertEquals(SlackException.Code.FORBIDDEN, collision.code());
        assertFalse(collision.getMessage().contains(first));
    }
}
