package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.programming.ArtifactProvenanceVerifier;
import ai.ravenroot.api.embed.EmbedProjectionBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqliteConnectionPolicyTest {
    @TempDir Path directory;

    @Test
    void oneTypedPolicyControlsArtifactAndEmbedConnectionWaits() throws Exception {
        var policy = new SqliteConnectionPolicy(Duration.ofMillis(1234));
        try (var ignored = SqliteArtifactRegistry.openUnder(directory.resolve("artifacts"),
                ArtifactProvenanceVerifier.refusing(), policy);
             var embed = SqliteEmbedRegistrationStore.openUnder(directory.resolve("embed"),
                     Clock.systemUTC(), EmbedProjectionBudget.DEFAULTS, policy)) {}

        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            policy.apply(connection);
            assertEquals(1234, busyTimeout(connection));
        }
    }

    @Test
    void unsupportedSqliteTimeoutsFailBeforeOpeningAStore() {
        assertThrows(IllegalArgumentException.class,
                () -> new SqliteConnectionPolicy(Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> new SqliteConnectionPolicy(Duration.ofMillis((long) Integer.MAX_VALUE + 1)));
    }

    private static int busyTimeout(java.sql.Connection connection) throws Exception {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("PRAGMA busy_timeout")) {
            return rows.next() ? rows.getInt(1) : -1;
        }
    }
}
