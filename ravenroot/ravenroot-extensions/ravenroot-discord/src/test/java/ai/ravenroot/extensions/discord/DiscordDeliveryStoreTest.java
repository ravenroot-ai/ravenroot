package ai.ravenroot.extensions.discord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DiscordDeliveryStoreTest {
    @TempDir Path directory;
    @Test void bindingSurvivesRestartAndRejectsIdentityCollision() {
        DiscordConfiguration configuration = DiscordTestSupport.configuration(directory.resolve("deliveries.db"));
        String id = "523456789012345678"; String first = "a".repeat(64); String second = "b".repeat(64);
        assertEquals(DiscordDeliveryStore.Decision.FIRST_SEEN,
                new SqliteDiscordDeliveryStore(configuration.store(), DiscordTestSupport.fixedClock())
                        .bind(DiscordTestSupport.TENANT, DiscordTestSupport.PROFILE, DiscordTestSupport.APPLICATION, id, first));
        var restarted = new SqliteDiscordDeliveryStore(configuration.store(), DiscordTestSupport.fixedClock());
        assertEquals(DiscordDeliveryStore.Decision.REPLAY,
                restarted.bind(DiscordTestSupport.TENANT, DiscordTestSupport.PROFILE, DiscordTestSupport.APPLICATION, id, first));
        DiscordException collision = assertThrows(DiscordException.class,
                () -> restarted.bind(DiscordTestSupport.TENANT, DiscordTestSupport.PROFILE,
                        DiscordTestSupport.APPLICATION, id, second));
        assertEquals(DiscordException.Code.FORBIDDEN, collision.code());
        assertFalse(collision.getMessage().contains(first));
    }
}
