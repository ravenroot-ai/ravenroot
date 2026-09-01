package ai.ravenroot.extensions.jdbc;

import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.security.EnvironmentKeyCodec;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Reads one strict Base64-encoded JSON operator profile from the environment. */
public final class EnvironmentJdbcProfileResolver implements JdbcProfileResolver {
    private static final PayloadLimits PROFILE_LIMITS = new PayloadLimits(256 * 1024, 8, 512, 4_096, 65_536, 256);
    private static final Set<String> PROFILE_KEYS = Set.of("driverId", "driverClass", "driverSha256", "url",
            "username", "credentialRef", "isolation", "deadlineMs", "maxConcurrency", "maxParameters",
            "maxParameterBytes", "maxRows", "maxColumns", "maxCellBytes", "maxTotalBytes",
            "maxGeneratedKeyRows", "statements");
    private final Map<String, String> environment;

    public EnvironmentJdbcProfileResolver() { this(System.getenv()); }
    EnvironmentJdbcProfileResolver(Map<String, String> environment) { this.environment = Map.copyOf(environment); }

    @Override public Optional<JdbcProfile> resolve(String tenant, String profile) {
        if (!identifier(tenant) || !identifier(profile)) return Optional.empty();
        try {
            String encoded = environment.get(variable(tenant, profile));
            if (encoded == null || encoded.length() > 350_000) return Optional.empty();
            byte[] json = Base64.getDecoder().decode(encoded);
            PayloadValue parsed = PayloadJson.read(json, PROFILE_LIMITS);
            if (!(parsed instanceof PayloadValue.MapValue root)) return Optional.empty();
            Map<String, PayloadValue> value = root.entries();
            exactProfileKeys(value);
            Map<String, JdbcStatementProfile> statements = statements(map(value, "statements"));
            return Optional.of(new JdbcProfile(tenant, profile, text(value, "driverId"), text(value, "driverClass"), text(value, "driverSha256"),
                    text(value, "url"), text(value, "username"), text(value, "credentialRef"), optionalText(value, "schema"),
                    isolation(text(value, "isolation")), integer(value, "deadlineMs"), integer(value, "maxConcurrency"),
                    integer(value, "maxParameters"), integer(value, "maxParameterBytes"), integer(value, "maxRows"),
                    integer(value, "maxColumns"), integer(value, "maxCellBytes"), integer(value, "maxTotalBytes"),
                    integer(value, "maxGeneratedKeyRows"), statements));
        } catch (RuntimeException invalid) { return Optional.empty(); }
    }

    static String variable(String tenant, String profile) {
        return "RAVENROOT_JDBC_PROFILE_" + EnvironmentKeyCodec.hex(tenant) + "_" + EnvironmentKeyCodec.hex(profile);
    }

    private static Map<String, JdbcStatementProfile> statements(Map<String, PayloadValue> source) {
        var result = new LinkedHashMap<String, JdbcStatementProfile>();
        source.forEach((id, raw) -> {
            if (!(raw instanceof PayloadValue.MapValue object)) throw new IllegalArgumentException();
            exactKeys(object.entries(), Set.of("kind", "sql", "generatedKeys"));
            JdbcStatementProfile.Kind kind = JdbcStatementProfile.Kind.valueOf(text(object.entries(), "kind"));
            Set<String> keys = textSet(object.entries().get("generatedKeys"));
            result.put(id, new JdbcStatementProfile(id, kind, NamedSql.parse(text(object.entries(), "sql")), keys));
        });
        return result;
    }

    private static Set<String> textSet(PayloadValue raw) {
        if (!(raw instanceof PayloadValue.ListValue list)) throw new IllegalArgumentException();
        var result = new LinkedHashSet<String>();
        for (PayloadValue value : list.values()) {
            if (!(value instanceof PayloadValue.TextValue text) || !result.add(text.value())) throw new IllegalArgumentException();
        }
        return result;
    }
    private static Map<String, PayloadValue> map(Map<String, PayloadValue> source, String key) {
        if (!(source.get(key) instanceof PayloadValue.MapValue map)) throw new IllegalArgumentException();
        return map.entries();
    }
    private static String text(Map<String, PayloadValue> source, String key) {
        if (!(source.get(key) instanceof PayloadValue.TextValue text)) throw new IllegalArgumentException();
        return text.value();
    }
    private static String optionalText(Map<String, PayloadValue> source, String key) {
        if (!source.containsKey(key)) return null;
        return text(source, key);
    }
    private static int integer(Map<String, PayloadValue> source, String key) {
        if (!(source.get(key) instanceof PayloadValue.IntegerValue integer)) throw new IllegalArgumentException();
        return Math.toIntExact(integer.value());
    }
    private static int isolation(String name) {
        return switch (name) {
            case "READ_COMMITTED" -> Connection.TRANSACTION_READ_COMMITTED;
            case "REPEATABLE_READ" -> Connection.TRANSACTION_REPEATABLE_READ;
            case "SERIALIZABLE" -> Connection.TRANSACTION_SERIALIZABLE;
            default -> throw new IllegalArgumentException();
        };
    }
    private static void exactKeys(Map<String, ?> source, Set<String> expected) {
        if (!source.keySet().equals(expected)) throw new IllegalArgumentException();
    }
    private static void exactProfileKeys(Map<String, ?> source) {
        if (source.keySet().equals(PROFILE_KEYS)) return;
        var withSchema = new java.util.HashSet<>(PROFILE_KEYS);
        withSchema.add("schema");
        if (!source.keySet().equals(withSchema)) throw new IllegalArgumentException();
    }
    private static boolean identifier(String value) { return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"); }
}
