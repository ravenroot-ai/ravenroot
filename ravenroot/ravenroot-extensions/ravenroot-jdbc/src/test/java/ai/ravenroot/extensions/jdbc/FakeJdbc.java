package ai.ravenroot.extensions.jdbc;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class FakeJdbc implements Driver {
    final State state;
    FakeJdbc(State state) { this.state = state; }

    @Override public Connection connect(String url, Properties info) throws SQLException {
        state.url = url; state.user = info.getProperty("user"); state.password = info.getProperty("password");
        state.connectEntered.countDown();
        boolean waiting = true;
        while (waiting) try { state.connectRelease.await(); waiting = false; }
        catch (InterruptedException interrupted) {
            if (!state.ignoreConnectInterrupt) { Thread.currentThread().interrupt(); throw new SQLException("secret connect interrupt", interrupted); }
        }
        return connection(state);
    }
    @Override public boolean acceptsURL(String url) { return true; }
    @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
    @Override public int getMajorVersion() { return 1; }
    @Override public int getMinorVersion() { return 0; }
    @Override public boolean jdbcCompliant() { return true; }
    @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }

    static Connection connection(State state) {
        return (Connection) Proxy.newProxyInstance(FakeJdbc.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "prepareStatement" -> { state.sql = (String) args[0]; yield prepared(state); }
                    case "setAutoCommit" -> { state.autoCommit = (boolean) args[0]; yield null; }
                    case "setReadOnly" -> { state.readOnly = (boolean) args[0]; yield null; }
                    case "setTransactionIsolation" -> { state.isolation = (int) args[0]; yield null; }
                    case "setSchema" -> {
                        if (state.schemaUnsupported) throw new java.sql.SQLFeatureNotSupportedException("secret vendor detail");
                        state.schema = (String) args[0];
                        yield null;
                    }
                    case "setNetworkTimeout" -> null;
                    case "commit" -> {
                        state.commits.incrementAndGet(); state.commitEntered.countDown();
                        try { state.commitRelease.await(); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new SQLException("secret commit interrupt", interrupted); }
                        if (state.commitFailure) throw new SQLException("secret vendor commit detail"); yield null;
                    }
                    case "rollback" -> { state.rollbacks.incrementAndGet(); yield null; }
                    case "abort" -> {
                        state.aborts.incrementAndGet(); state.abortEntered.countDown();
                        state.release.countDown(); state.commitRelease.countDown(); yield null;
                    }
                    case "close" -> {
                        state.closes.incrementAndGet(); state.closeEntered.countDown();
                        state.release.countDown(); state.commitRelease.countDown();
                        if (state.closeFailure) throw new SQLException("secret close after commit"); yield null;
                    }
                    case "isClosed" -> false;
                    default -> defaultValue(method.getReturnType());
                });
    }

    static PreparedStatement prepared(State state) {
        return (PreparedStatement) Proxy.newProxyInstance(FakeJdbc.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer index) {
                        state.bindings.put(index, args[1]); return null;
                    }
                    return switch (name) {
                        case "executeQuery" -> { await(state); yield result(state.columns, state.rows); }
                        case "executeUpdate" -> { await(state); yield state.affected; }
                        case "getGeneratedKeys" -> result(state.keyColumns, state.keyRows, state);
                        case "cancel" -> {
                            state.cancels.incrementAndGet(); state.cancelEntered.countDown();
                            boolean waiting = true;
                            while (waiting) try { state.cancelRelease.await(); waiting = false; }
                            catch (InterruptedException ignored) { }
                            state.release.countDown(); yield null;
                        }
                        case "close", "setQueryTimeout", "setMaxRows", "setFetchSize" -> null;
                        default -> defaultValue(method.getReturnType());
                    };
                });
    }

    private static void await(State state) throws SQLException {
        state.entered.countDown();
        try { state.release.await(); } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new SQLException("interrupted secret", interrupted);
        }
        if (state.executeFailure) throw new SQLException("secret SQL and vendor state", "42000", 999);
    }

    static ResultSet result(List<String> columns, List<List<Object>> rows) {
        return result(columns, rows, null);
    }

    private static ResultSet result(List<String> columns, List<List<Object>> rows, State state) {
        AtomicInteger cursor = new AtomicInteger(-1);
        Object[] last = {null};
        ResultSetMetaData metadata = (ResultSetMetaData) Proxy.newProxyInstance(FakeJdbc.class.getClassLoader(),
                new Class<?>[]{ResultSetMetaData.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getColumnCount" -> columns.size();
                    case "getColumnLabel", "getColumnName" -> columns.get((int) args[0] - 1);
                    case "getColumnType" -> columnType(rows, (int) args[0] - 1);
                    default -> defaultValue(method.getReturnType());
                });
        return (ResultSet) Proxy.newProxyInstance(FakeJdbc.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMetaData" -> metadata;
                    case "next" -> cursor.incrementAndGet() < rows.size();
                    case "getObject" -> last[0] = value(rows, cursor, args);
                    case "getLong" -> { last[0] = value(rows, cursor, args); yield last[0] == null ? 0L : ((Number) last[0]).longValue(); }
                    case "getDouble" -> { last[0] = value(rows, cursor, args); yield last[0] == null ? 0D : ((Number) last[0]).doubleValue(); }
                    case "getBoolean" -> { last[0] = value(rows, cursor, args); yield last[0] != null && (Boolean) last[0]; }
                    case "getCharacterStream" -> {
                        last[0] = value(rows, cursor, args);
                        yield last[0] == null ? null : new StringReader(String.valueOf(last[0]));
                    }
                    case "getBinaryStream" -> {
                        last[0] = value(rows, cursor, args);
                        yield last[0] == null ? null : new ByteArrayInputStream((byte[]) last[0]);
                    }
                    case "wasNull" -> last[0] == null;
                    case "close" -> {
                        if (state != null) {
                            state.keyCloseEntered.countDown();
                            boolean waiting = true;
                            while (waiting) try { state.keyCloseRelease.await(); waiting = false; }
                            catch (InterruptedException ignored) { }
                        }
                        yield null;
                    }
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object value(List<List<Object>> rows, AtomicInteger cursor, Object[] args) {
        return rows.get(cursor.get()).get((int) args[0] - 1);
    }

    private static int columnType(List<List<Object>> rows, int column) {
        for (List<Object> row : rows) {
            Object value = row.get(column);
            if (value == null) continue;
            if (value instanceof Boolean) return Types.BOOLEAN;
            if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
                return Types.BIGINT;
            if (value instanceof Float || value instanceof Double) return Types.DOUBLE;
            if (value instanceof java.math.BigDecimal) return Types.DECIMAL;
            if (value instanceof String) return Types.VARCHAR;
            if (value instanceof byte[]) return Types.VARBINARY;
            return Types.OTHER;
        }
        return Types.NULL;
    }

    static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }

    static final class State {
        String url, user, password, schema, sql;
        boolean autoCommit = true, readOnly, schemaUnsupported, commitFailure, executeFailure, closeFailure;
        int isolation, affected = 1;
        final Map<Integer, Object> bindings = new LinkedHashMap<>();
        List<String> columns = List.of("id", "name");
        List<List<Object>> rows = new ArrayList<>(List.of(List.of(1L, "Ada")));
        List<String> keyColumns = List.of("id", "internal_secret");
        List<List<Object>> keyRows = List.of(List.of(41L, "hidden"));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(0);
        CountDownLatch connectEntered = new CountDownLatch(0);
        CountDownLatch connectRelease = new CountDownLatch(0);
        CountDownLatch commitEntered = new CountDownLatch(0);
        CountDownLatch commitRelease = new CountDownLatch(0);
        CountDownLatch cancelEntered = new CountDownLatch(0);
        CountDownLatch cancelRelease = new CountDownLatch(0);
        CountDownLatch abortEntered = new CountDownLatch(0);
        CountDownLatch closeEntered = new CountDownLatch(0);
        CountDownLatch keyCloseEntered = new CountDownLatch(0);
        CountDownLatch keyCloseRelease = new CountDownLatch(0);
        final AtomicInteger commits = new AtomicInteger(), rollbacks = new AtomicInteger(), closes = new AtomicInteger();
        final AtomicInteger aborts = new AtomicInteger(), cancels = new AtomicInteger();
        void block() { entered = new CountDownLatch(1); release = new CountDownLatch(1); }
        boolean ignoreConnectInterrupt;
        void blockConnectIgnoringInterrupt() {
            ignoreConnectInterrupt = true; connectEntered = new CountDownLatch(1); connectRelease = new CountDownLatch(1);
        }
        void blockCommit() { commitEntered = new CountDownLatch(1); commitRelease = new CountDownLatch(1); }
        void blockCancel() { cancelEntered = new CountDownLatch(1); cancelRelease = new CountDownLatch(1); }
        void observeCancellationCleanup() {
            cancelEntered = new CountDownLatch(1);
            abortEntered = new CountDownLatch(1);
            closeEntered = new CountDownLatch(1);
        }
        void blockGeneratedKeyClose() {
            keyCloseEntered = new CountDownLatch(1); keyCloseRelease = new CountDownLatch(1);
        }
        boolean entered(long timeout, TimeUnit unit) throws InterruptedException { return entered.await(timeout, unit); }
    }
}
