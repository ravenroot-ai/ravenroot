package ai.ravenroot.extensions.jdbc;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentJdbcProfileResolverTest {
    @Test void resolvesExactTenantScopedOperatorProfile() {
        String key = EnvironmentJdbcProfileResolver.variable("tenant-a", "main");
        JdbcProfile profile = new EnvironmentJdbcProfileResolver(Map.of(key, encoded(validJson())))
                .resolve("tenant-a", "main").orElseThrow();
        assertEquals("postgresql-42.7.7", profile.driverId());
        assertEquals("jdbc:postgresql://db.example:5432/app", profile.url());
        assertEquals("accounting", profile.schema());
        assertEquals("SELECT id FROM users WHERE id=?", profile.statement("find", JdbcStatementProfile.Kind.QUERY).sql().jdbcSql());
        assertTrue(new EnvironmentJdbcProfileResolver(Map.of(key, encoded(validJson())))
                .resolve("tenant-b", "main").isEmpty());
    }

    @Test void rejectsCredentialBearingUrlsUnknownFieldsAndNonCanonicalBase64() {
        String key = EnvironmentJdbcProfileResolver.variable("tenant-a", "main");
        assertTrue(new EnvironmentJdbcProfileResolver(Map.of(key, encoded(validJson().replace(
                "jdbc:postgresql://db.example:5432/app", "jdbc:postgresql://user@db.example:5432/app"))))
                .resolve("tenant-a", "main").isEmpty());
        assertTrue(new EnvironmentJdbcProfileResolver(Map.of(key, encoded(validJson().replace(
                "\"driverId\":", "\"extra\":true,\"driverId\":"))))
                .resolve("tenant-a", "main").isEmpty());
        assertTrue(new EnvironmentJdbcProfileResolver(Map.of(key, "%%%"))
                .resolve("tenant-a", "main").isEmpty());
    }

    @Test void sameVendorProfilesKeepDatabaseSchemaUsernameAndCredentialDistinct() {
        String ordersKey = EnvironmentJdbcProfileResolver.variable("tenant-a", "orders");
        String archiveKey = EnvironmentJdbcProfileResolver.variable("tenant-a", "archive");
        String archiveJson = validJson()
                .replace("db.example:5432/app", "archive.example:5432/history")
                .replace("\"username\":\"app\"", "\"username\":\"archive_app\"")
                .replace("db-password", "archive-password")
                .replace("accounting", "archive");
        var resolver = new EnvironmentJdbcProfileResolver(Map.of(
                ordersKey, encoded(validJson()), archiveKey, encoded(archiveJson)));

        JdbcProfile orders = resolver.resolve("tenant-a", "orders").orElseThrow();
        JdbcProfile archive = resolver.resolve("tenant-a", "archive").orElseThrow();
        assertEquals("jdbc:postgresql://db.example:5432/app", orders.url());
        assertEquals("accounting", orders.schema());
        assertEquals("app", orders.username());
        assertEquals("db-password", orders.credentialRef());
        assertEquals("jdbc:postgresql://archive.example:5432/history", archive.url());
        assertEquals("archive", archive.schema());
        assertEquals("archive_app", archive.username());
        assertEquals("archive-password", archive.credentialRef());
    }

    private static String encoded(String json) {
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String validJson() {
        return """
                {"driverId":"postgresql-42.7.7","driverClass":"org.example.Driver","driverSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "url":"jdbc:postgresql://db.example:5432/app","username":"app","credentialRef":"db-password","schema":"accounting","isolation":"READ_COMMITTED",
                "deadlineMs":1000,"maxConcurrency":2,"maxParameters":16,"maxParameterBytes":4096,"maxRows":10,"maxColumns":8,
                "maxCellBytes":1024,"maxTotalBytes":16384,"maxGeneratedKeyRows":4,
                "statements":{"find":{"kind":"QUERY","sql":"SELECT id FROM users WHERE id=:id","generatedKeys":[]}}}
                """;
    }
}
