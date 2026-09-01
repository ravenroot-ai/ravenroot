package ai.ravenroot.extensions.jdbc;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcParametersTest {
    @Test void bindsExactTypesInDeclaredOrderWithoutSqlInterpolation() throws Exception {
        JdbcStatementProfile statement = JdbcTestSupport.query(
                "SELECT * FROM t WHERE a=:text OR b=:text AND n=:number AND ok=:flag AND bin=:binary");
        JdbcProfile profile = JdbcTestSupport.profile(statement);
        JdbcParameters parameters = JdbcParameters.parse(JdbcTestSupport.parameters(Map.of(
                "text", "x' OR 1=1 --", "number", 7L, "flag", true,
                "binary", Map.of("binary", Base64.getEncoder().encodeToString(new byte[]{1, 2})))), profile, statement);
        List<Object[]> calls = new ArrayList<>();
        List<String> methods = new ArrayList<>();
        PreparedStatement prepared = (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                    if (method.getName().startsWith("set")) { methods.add(method.getName()); calls.add(args.clone()); }
                    return FakeJdbc.defaultValue(method.getReturnType());
                });
        parameters.bind(prepared, statement);
        assertEquals(List.of("setString", "setString", "setLong", "setBoolean", "setBytes"),
                methods);
        assertEquals("x' OR 1=1 --", calls.get(0)[1]);
        assertEquals("x' OR 1=1 --", calls.get(1)[1]);
        assertArrayEquals(new byte[]{1, 2}, (byte[]) calls.get(4)[1]);
        assertEquals("SELECT * FROM t WHERE a=? OR b=? AND n=? AND ok=? AND bin=?", statement.sql().jdbcSql());
    }

    @Test void rejectsMissingExtraCollectionAndNonCanonicalBase64Parameters() {
        JdbcStatementProfile statement = JdbcTestSupport.query("SELECT * FROM t WHERE id=:id");
        JdbcProfile profile = JdbcTestSupport.profile(statement);
        assertThrows(JdbcFailure.class, () -> JdbcParameters.parse(JdbcTestSupport.parameters(Map.of()), profile, statement));
        assertThrows(JdbcFailure.class, () -> JdbcParameters.parse(JdbcTestSupport.parameters(Map.of("id", 1L, "extra", 2L)), profile, statement));
        assertThrows(JdbcFailure.class, () -> JdbcParameters.parse(JdbcTestSupport.parameters(Map.of("id", List.of(1))), profile, statement));
        assertThrows(JdbcFailure.class, () -> JdbcParameters.parse(JdbcTestSupport.parameters(Map.of("id", Map.of("binary", "AA==junk"))), profile, statement));
    }

    @Test void acceptsCanonicalNullAndUsesTypedSetNull() throws Exception {
        JdbcStatementProfile statement = JdbcTestSupport.query("SELECT * FROM t WHERE id=:id");
        JdbcProfile profile = JdbcTestSupport.profile(statement);
        Map<String, Object> values = new java.util.LinkedHashMap<>(); values.put("id", null);
        JdbcParameters parameters = JdbcParameters.parse(JdbcTestSupport.parameters(values), profile, statement);
        List<String> calls = new ArrayList<>();
        PreparedStatement prepared = (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                    if (method.getName().startsWith("set")) calls.add(method.getName());
                    return FakeJdbc.defaultValue(method.getReturnType());
                });
        parameters.bind(prepared, statement);
        assertEquals(List.of("setNull"), calls);
    }
}
