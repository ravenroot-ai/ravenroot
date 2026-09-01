package ai.ravenroot.extensions.jdbc;

import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

final class JdbcParameters {
    private final Map<String, Object> values;

    private JdbcParameters(Map<String, Object> values) {
        this.values = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    static JdbcParameters parse(Object raw, JdbcProfile profile, JdbcStatementProfile statement) {
        try {
            var limits = new PayloadLimits(profile.maxParameterBytes(), 5, profile.maxParameters() + 2,
                    profile.maxParameters() * 3 + 4, profile.maxParameterBytes(), 64);
            PayloadValue value = PayloadValue.fromJava(raw, limits); limits.enforce(value);
            if (!(value instanceof PayloadValue.MapValue root)
                    || !root.entries().keySet().equals(java.util.Set.of("contract", "parameters"))
                    || !(root.entries().get("contract") instanceof PayloadValue.TextValue contract)
                    || !"jdbc.parameters.v1".equals(contract.value())
                    || !(root.entries().get("parameters") instanceof PayloadValue.MapValue parameters)
                    || parameters.entries().size() > profile.maxParameters()
                    || !parameters.entries().keySet().equals(statement.sql().parameterNames())) throw rejected();
            var converted = new LinkedHashMap<String, Object>();
            parameters.entries().forEach((name, parameter) -> converted.put(name, convert(parameter, profile.maxParameterBytes())));
            return new JdbcParameters(converted);
        } catch (JdbcFailure failure) { throw failure; }
        catch (RuntimeException failure) { throw rejected(); }
    }

    void bind(PreparedStatement statement, JdbcStatementProfile profile) throws SQLException {
        int index = 1;
        for (String name : profile.orderedParameters()) bind(statement, index++, values.get(name));
    }

    private static Object convert(PayloadValue value, int maxBytes) {
        return switch (value) {
            case PayloadValue.NullValue ignored -> null;
            case PayloadValue.BooleanValue flag -> flag.value();
            case PayloadValue.IntegerValue number -> number.value();
            case PayloadValue.DecimalValue number -> number.value();
            case PayloadValue.TextValue text -> text.value();
            case PayloadValue.MapValue map -> {
                if (!map.entries().keySet().equals(java.util.Set.of("binary"))
                        || !(map.entries().get("binary") instanceof PayloadValue.TextValue encoded)) throw rejected();
                byte[] binary;
                try { binary = Base64.getDecoder().decode(encoded.value()); }
                catch (IllegalArgumentException invalid) { throw rejected(); }
                if (binary.length > maxBytes || !Base64.getEncoder().encodeToString(binary).equals(encoded.value())) throw rejected();
                yield binary;
            }
            case PayloadValue.ListValue ignored -> throw rejected();
        };
    }

    private static void bind(PreparedStatement statement, int index, Object value) throws SQLException {
        if (value == null) statement.setNull(index, Types.NULL);
        else if (value instanceof Boolean flag) statement.setBoolean(index, flag);
        else if (value instanceof Long number) statement.setLong(index, number);
        else if (value instanceof Double number) statement.setDouble(index, number);
        else if (value instanceof String text) statement.setString(index, text);
        else if (value instanceof byte[] binary) statement.setBytes(index, binary);
        else throw rejected();
    }
    private static JdbcFailure rejected() { return new JdbcFailure(JdbcFailure.Code.INPUT_REJECTED); }
}
