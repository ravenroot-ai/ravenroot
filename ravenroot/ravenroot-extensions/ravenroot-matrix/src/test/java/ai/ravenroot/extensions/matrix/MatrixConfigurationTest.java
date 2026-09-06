package ai.ravenroot.extensions.matrix;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MatrixConfigurationTest {
    @TempDir Path directory;

    @Test void strictEnvironmentProfileConstrainsHomeserverIdentityRoomsAndInitialMode() {
        Map<String, Object> root = Map.of("store", Map.of("path", directory.resolve("sync.db").toString(),
                        "maxDeliveries", 100L, "retentionHours", 24L, "maxSources", 10L),
                "profiles", Map.of(MatrixTestSupport.PROFILE, Map.ofEntries(
                        Map.entry("tenantId", MatrixTestSupport.TENANT),
                        Map.entry("homeserverOrigin", MatrixTestSupport.ORIGIN.toString()),
                        Map.entry("userId", MatrixTestSupport.USER), Map.entry("rooms", List.of(MatrixTestSupport.ROOM)),
                        Map.entry("eventTypes", List.of("m.room.message")),
                        Map.entry("credentialBindingId", "matrix-bearer"),
                        Map.entry("credentialReference", "matrix-access-token"),
                        Map.entry("initialSyncMode", "deliver-bounded"), Map.entry("initialSince", "seed"),
                        Map.entry("limits", Map.ofEntries(Map.entry("requestTimeoutMs", 5000L),
                                Map.entry("maxRequestBytes", 1048576L), Map.entry("maxResponseBytes", 1048576L),
                                Map.entry("maxTextChars", 4000L), Map.entry("maxConcurrency", 2L),
                                Map.entry("maxPerSecond", 20L), Map.entry("pollTimeoutMs", 1000L),
                                Map.entry("retryBackoffMs", 100L), Map.entry("maxEventsPerSync", 10L))))));
        String encoded = Base64.getEncoder().encodeToString(MatrixValues.jsonBytes(root));
        MatrixConfiguration configuration = MatrixConfiguration.fromEnvironment(
                Map.of(MatrixConfiguration.ENVIRONMENT, encoded));
        MatrixProfile profile = configuration.profile(MatrixTestSupport.TENANT, MatrixTestSupport.PROFILE).orElseThrow();
        assertEquals(MatrixTestSupport.ORIGIN, profile.homeserverOrigin());
        assertEquals(MatrixProfile.InitialSyncMode.DELIVER_BOUNDED, profile.initialSyncMode());
        assertEquals("seed", profile.initialSince());
    }

    @Test void rejectsUnknownFieldsInsecureOriginsAndPollDeadlineInversion() {
        String invalid = Base64.getEncoder().encodeToString(
                "{\"store\":{},\"profiles\":{},\"extra\":true}".getBytes(StandardCharsets.UTF_8));
        assertThrows(MatrixException.class, () -> MatrixConfiguration.fromEnvironment(
                Map.of(MatrixConfiguration.ENVIRONMENT, invalid)));
        MatrixProfile base = MatrixTestSupport.configuration(directory.resolve("base.db")).profiles().values().iterator().next();
        assertThrows(MatrixException.class, () -> new MatrixProfile(base.tenantId(), base.name(),
                java.net.URI.create("http://matrix.example.org/"), base.userId(), base.roomIds(), base.eventTypes(),
                base.credentialBindingId(), base.credentialReference(), 1_000, base.maxRequestBytes(),
                base.maxResponseBytes(), base.maxTextChars(), base.maxConcurrency(), base.maxPerSecond(),
                1_000, base.retryBackoffMs(), base.maxEventsPerSync(), base.initialSyncMode(), base.initialSince()));
    }
}
