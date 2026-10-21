package ai.ravenroot.server.persistence;

import ai.ravenroot.api.correlation.BusinessHistoryPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BusinessHistoryConfigurationTest {
    @Test void normalCompositionCarriesPropertyPrecedenceAndPublishedLimitsIntoSqlite(@TempDir Path directory) {
        var properties = new Properties();
        properties.setProperty("ravenroot.history.max-page-size", "1000");
        properties.setProperty("ravenroot.history.response-max-bytes", "1048576");
        properties.setProperty("ravenroot.history.read-timeout-ms", "250");
        var parsed = assertInstanceOf(ExecutionStoreConfiguration.SingleHost.class,
                ExecutionStoreConfiguration.fromSystem(properties, Map.of("RAVENROOT_EXECUTION_STORE_DIR", directory.toString(),
                        "RAVENROOT_HISTORY_MAX_PAGE_SIZE", "20", "RAVENROOT_HISTORY_MAX_ID_CHARACTERS", "128")));
        var policy = parsed.storeConfig().businessHistories();
        assertEquals(1000, policy.maxPageSize()); assertEquals(1048576, policy.response().maxEncodedBytes());
        assertEquals(Duration.ofMillis(250), policy.readTimeout()); assertEquals(128, policy.selection().maxIdCharacters());
        try (var opened = ExecutionStoreBootstrap.openOwned(parsed, Clock.systemUTC())) {
            assertEquals(policy, opened.store().businessHistoryPolicy());
        }
    }
    @Test void sharedConfigurationUsesTheSameHistoryPolicyWithoutOpeningADatabase() {
        var parsed = assertInstanceOf(ExecutionStoreConfiguration.Shared.class, ExecutionStoreConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_EXECUTION_STORE", "postgresql", "RAVENROOT_EXECUTION_STORE_URL", "jdbc:postgresql://database.example/runtime",
                "RAVENROOT_HISTORY_MAX_PAGE_SIZE", "75", "RAVENROOT_HISTORY_RESPONSE_MAX_VALUES", "40000")));
        assertEquals(75, parsed.storeConfig().businessHistories().maxPageSize());
        assertEquals(40000, parsed.storeConfig().businessHistories().response().maxValueCount());
    }
    @Test void resourceCeilingsAndContradictoryProjectionBudgetsFailBeforeComposition() {
        for (var invalid : List.of(Map.of("RAVENROOT_HISTORY_MAX_PAGE_SIZE", "1001"), Map.of("RAVENROOT_HISTORY_MAX_PAGE_SIZE", "0"),
                Map.of("RAVENROOT_HISTORY_READ_TIMEOUT_MS", "60001"), Map.of("RAVENROOT_HISTORY_RESPONSE_MAX_BYTES", "16777217"),
                Map.of("RAVENROOT_HISTORY_MAX_PAGE_SIZE", "1000", "RAVENROOT_HISTORY_RESPONSE_MAX_VALUES", "1000")))
            assertThrows(IllegalArgumentException.class, () -> BusinessHistoryConfiguration.fromSources(Map.of(), invalid));
        assertEquals(BusinessHistoryPolicy.DEFAULTS, BusinessHistoryConfiguration.fromSources(Map.of(), Map.of()));
    }
}
