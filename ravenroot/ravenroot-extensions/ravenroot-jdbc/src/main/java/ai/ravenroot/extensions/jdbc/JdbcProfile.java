package ai.ravenroot.extensions.jdbc;

import java.sql.Connection;
import java.util.Map;

record JdbcProfile(String tenant, String name, String driverId, String driverClass, String driverSha256,
                   String url, String username, String credentialRef, String schema, int isolation,
                   int deadlineMs, int maxConcurrency, int maxParameters, int maxParameterBytes,
                   int maxRows, int maxColumns, int maxCellBytes, int maxTotalBytes,
                   int maxGeneratedKeyRows, Map<String, JdbcStatementProfile> statements) {
    JdbcProfile {
        statements = Map.copyOf(statements == null ? Map.of() : statements);
        if (!identifier(tenant) || !identifier(name) || !JdbcDriverArtifactName.validDriverId(driverId)
                || driverClass == null || !driverClass.matches("[A-Za-z_$][A-Za-z0-9_$.]{0,254}")
                || driverSha256 == null || !driverSha256.matches("[0-9a-f]{64}")
                || !safeUrl(url) || username == null || username.length() > 256
                || credentialRef == null || credentialRef.isBlank() || credentialRef.length() > 256
                || !safeSchema(schema)
                || !supportedIsolation(isolation) || deadlineMs < 100 || deadlineMs > 30_000
                || maxConcurrency < 1 || maxConcurrency > 16 || maxParameters < 1 || maxParameters > 256
                || maxParameterBytes < 1 || maxParameterBytes > 1_048_576
                || maxRows < 1 || maxRows > 10_000 || maxColumns < 1 || maxColumns > 256
                || maxCellBytes < 1 || maxCellBytes > 1_048_576
                || maxTotalBytes < maxCellBytes || maxTotalBytes > 16_777_216
                || maxGeneratedKeyRows < 1 || maxGeneratedKeyRows > 1_000
                || statements.isEmpty() || statements.size() > 128
                || statements.entrySet().stream().anyMatch(e -> !e.getKey().equals(e.getValue().id()))) {
            throw new JdbcFailure(JdbcFailure.Code.PROFILE_UNAVAILABLE);
        }
    }

    JdbcProfile(String tenant, String name, String driverId, String driverClass, String driverSha256,
                String url, String username, String credentialRef, int isolation,
                int deadlineMs, int maxConcurrency, int maxParameters, int maxParameterBytes,
                int maxRows, int maxColumns, int maxCellBytes, int maxTotalBytes,
                int maxGeneratedKeyRows, Map<String, JdbcStatementProfile> statements) {
        this(tenant, name, driverId, driverClass, driverSha256, url, username, credentialRef, null,
                isolation, deadlineMs, maxConcurrency, maxParameters, maxParameterBytes, maxRows,
                maxColumns, maxCellBytes, maxTotalBytes, maxGeneratedKeyRows, statements);
    }

    JdbcStatementProfile statement(String id, JdbcStatementProfile.Kind expected) {
        JdbcStatementProfile statement = statements.get(id);
        if (statement == null || statement.kind() != expected) throw new JdbcFailure(JdbcFailure.Code.STATEMENT_UNAVAILABLE);
        return statement;
    }

    private static boolean identifier(String value) { return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"); }
    private static boolean supportedIsolation(int value) {
        return value == Connection.TRANSACTION_READ_COMMITTED || value == Connection.TRANSACTION_REPEATABLE_READ
                || value == Connection.TRANSACTION_SERIALIZABLE;
    }
    private static boolean safeSchema(String value) {
        return value == null || (!value.isBlank() && value.length() <= 256
                && value.chars().noneMatch(character -> character < 0x20 || character == 0x7f));
    }
    private static boolean safeUrl(String value) {
        if (value == null || value.length() > 2_048 || !value.startsWith("jdbc:")
                || value.chars().anyMatch(character -> character <= 0x20 || character == 0x7f)
                || value.indexOf(';') >= 0 || value.indexOf('?') >= 0 || value.indexOf('#') >= 0) return false;
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        int separator = value.indexOf(':', 5);
        if (separator < 6 || !value.substring(5, separator).matches("[a-z][a-z0-9-]{0,31}")) return false;
        if (lower.contains("password") || lower.contains("passwd") || lower.contains("user=")
                || lower.contains("username=") || value.indexOf('@') >= 0) return false;
        String location = value.substring(separator + 1);
        if (!location.startsWith("//")) return !location.isBlank();
        try {
            java.net.URI endpoint = java.net.URI.create("jdbc-" + value.substring(5, separator) + ":" + location);
            return endpoint.getHost() != null && !endpoint.getHost().isBlank() && endpoint.getUserInfo() == null
                    && endpoint.getQuery() == null && endpoint.getFragment() == null
                    && endpoint.getPath() != null && endpoint.getPath().length() > 1
                    && endpoint.getPort() >= -1 && endpoint.getPort() <= 65_535;
        } catch (IllegalArgumentException invalid) { return false; }
    }
}
