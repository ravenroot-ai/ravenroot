package ai.ravenroot.extensions.jdbc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Incremental, canonical-result materialization with no unbounded JDBC getter. */
final class JdbcResults {
    private static final int MAX_NUMERIC_TEXT_BYTES = 128;

    private JdbcResults() { }

    static Object query(ResultSet result, JdbcProfile profile) throws SQLException {
        ResultSetMetaData metadata = result.getMetaData();
        int count = columnCount(metadata, profile);
        int[] types = new int[count];
        List<String> columns = new ArrayList<>(count);
        JsonBudget budget = new JsonBudget(profile.maxTotalBytes());
        budget.ascii("{\"columns\":[");
        for (int index = 1; index <= count; index++) {
            if (index > 1) budget.ascii(",");
            String label = label(metadata, index);
            types[index - 1] = metadata.getColumnType(index);
            budget.string(label);
            columns.add(label);
        }
        // One digit is reserved for rowCount=0 and extended before each tenth row is read.
        budget.ascii("],\"contract\":\"jdbc.query.result.v1\",\"rowCount\":0,\"rows\":[");
        int rowDigits = 1;
        List<List<Object>> rows = new ArrayList<>();
        while (result.next()) {
            if (rows.size() >= profile.maxRows()) throw limit();
            int rowNumber = rows.size() + 1;
            int requiredDigits = decimalDigits(rowNumber);
            if (requiredDigits > rowDigits) {
                budget.charge(requiredDigits - rowDigits);
                rowDigits = requiredDigits;
            }
            if (rowNumber > 1) budget.ascii(",");
            budget.ascii("[");
            List<Object> row = new ArrayList<>(count);
            for (int index = 1; index <= count; index++) {
                if (index > 1) budget.ascii(",");
                // Reserve the remaining separators, row close and result close before a
                // streaming cell is allowed to consume the total-result budget.
                int enclosingSuffix = (count - index) + 3;
                row.add(cell(result, index, types[index - 1], profile.maxCellBytes(), budget,
                        enclosingSuffix));
            }
            budget.ascii("]");
            rows.add(Collections.unmodifiableList(row));
        }
        budget.ascii("]}");
        return Map.of("contract", "jdbc.query.result.v1", "columns", List.copyOf(columns),
                "rows", List.copyOf(rows), "rowCount", (long) rows.size());
    }

    static Object insert(int affected, ResultSet keys, JdbcStatementProfile statement, JdbcProfile profile)
            throws SQLException {
        if (affected < 0) throw new JdbcFailure(JdbcFailure.Code.EXECUTION_FAILED);
        JsonBudget budget = new JsonBudget(profile.maxTotalBytes());
        budget.ascii("{\"affectedRows\":");
        budget.number((long) affected);
        budget.ascii(",\"contract\":\"jdbc.insert.result.v1\",\"generatedKeys\":[");
        List<Map<String, Object>> rows = new ArrayList<>();
        if (keys != null && !statement.generatedKeys().isEmpty()) {
            ResultSetMetaData metadata = keys.getMetaData();
            int count = columnCount(metadata, profile);
            Map<String, Column> indexes = new LinkedHashMap<>();
            for (int index = 1; index <= count; index++) {
                indexes.putIfAbsent(label(metadata, index), new Column(index, metadata.getColumnType(index)));
            }
            if (!indexes.keySet().containsAll(statement.generatedKeys())) throw limit();
            while (keys.next()) {
                if (rows.size() >= profile.maxGeneratedKeyRows()) throw limit();
                if (!rows.isEmpty()) budget.ascii(",");
                budget.ascii("{");
                var row = new LinkedHashMap<String, Object>();
                int emitted = 0;
                List<String> generatedKeys = List.copyOf(statement.generatedKeys());
                for (int keyIndex = 0; keyIndex < generatedKeys.size(); keyIndex++) {
                    String key = generatedKeys.get(keyIndex);
                    if (emitted++ > 0) budget.ascii(",");
                    budget.string(key);
                    budget.ascii(":");
                    Column column = indexes.get(key);
                    int enclosingSuffix = generatedKeySuffix(generatedKeys, keyIndex + 1);
                    row.put(key, cell(keys, column.index(), column.type(), profile.maxCellBytes(), budget,
                            enclosingSuffix));
                }
                budget.ascii("}");
                rows.add(Collections.unmodifiableMap(row));
            }
        }
        budget.ascii("]}");
        return Map.of("contract", "jdbc.insert.result.v1", "affectedRows", (long) affected,
                "generatedKeys", List.copyOf(rows));
    }

    private static int columnCount(ResultSetMetaData metadata, JdbcProfile profile) throws SQLException {
        int count = metadata.getColumnCount();
        if (count < 1 || count > profile.maxColumns()) throw limit();
        return count;
    }

    private static String label(ResultSetMetaData metadata, int index) throws SQLException {
        String label = metadata.getColumnLabel(index);
        if (label == null || label.isBlank() || label.length() > 256) throw limit();
        return label;
    }

    private static Object cell(ResultSet result, int index, int type, int maximum, JsonBudget budget,
                               int enclosingSuffix)
            throws SQLException {
        return switch (type) {
            case Types.NULL -> nullValue(budget);
            case Types.BOOLEAN, Types.BIT -> {
                boolean value = result.getBoolean(index);
                yield result.wasNull() ? nullValue(budget) : booleanValue(value, budget);
            }
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> {
                long value = result.getLong(index);
                yield result.wasNull() ? nullValue(budget) : longValue(value, budget);
            }
            case Types.REAL, Types.FLOAT, Types.DOUBLE -> {
                double value = result.getDouble(index);
                if (result.wasNull()) yield nullValue(budget);
                if (!Double.isFinite(value)) throw limit();
                budget.number(value);
                yield value;
            }
            case Types.NUMERIC, Types.DECIMAL -> decimal(result, index, maximum, budget);
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR,
                 Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB,
                 Types.DATE, Types.TIME, Types.TIME_WITH_TIMEZONE,
                 Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE ->
                    text(result, index, maximum, budget, enclosingSuffix);
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB ->
                    binary(result, index, maximum, budget, enclosingSuffix);
            default -> throw limit();
        };
    }

    private static Object decimal(ResultSet result, int index, int maximum, JsonBudget budget) throws SQLException {
        String text = readText(result, index, Math.min(maximum, MAX_NUMERIC_TEXT_BYTES), null);
        if (text == null) return nullValue(budget);
        try {
            double value = new BigDecimal(text).doubleValue();
            if (!Double.isFinite(value)) throw limit();
            budget.number(value);
            return value;
        } catch (NumberFormatException invalid) {
            throw limit();
        }
    }

    private static Object text(ResultSet result, int index, int maximum, JsonBudget budget,
                               int enclosingSuffix) throws SQLException {
        String value = readText(result, index, maximum, budget, enclosingSuffix);
        return value == null ? nullValue(budget) : value;
    }

    private static String readText(ResultSet result, int index, int maximum, JsonBudget budget) throws SQLException {
        return readText(result, index, maximum, budget, 0);
    }

    private static String readText(ResultSet result, int index, int maximum, JsonBudget budget,
                                   int enclosingSuffix) throws SQLException {
        Reader raw = result.getCharacterStream(index);
        if (raw == null) {
            if (result.wasNull()) return null;
            throw limit();
        }
        try (Reader reader = raw) {
            return (budget == null ? new JsonBudget(Integer.MAX_VALUE) : budget).readString(reader, maximum,
                    budget != null, enclosingSuffix);
        } catch (IOException failure) {
            throw limit();
        }
    }

    private static Object binary(ResultSet result, int index, int maximum, JsonBudget budget,
                                 int enclosingSuffix) throws SQLException {
        InputStream raw = result.getBinaryStream(index);
        if (raw == null) {
            if (result.wasNull()) return nullValue(budget);
            throw limit();
        }
        budget.ascii("{\"base64\":\"");
        try (InputStream input = raw; var output = new ByteArrayOutputStream(Math.min(maximum, 8_192))) {
            byte[] buffer = new byte[Math.min(8_192, maximum + 1)];
            int size = 0;
            for (;;) {
                int allowed = Math.min(maximum - size, budget.base64RawAllowance(size, enclosingSuffix));
                int requested = allowed >= buffer.length ? buffer.length : allowed + 1;
                int read = input.read(buffer, 0, requested);
                if (read < 0) break;
                if (read == 0) continue;
                if (read > allowed) throw limit();
                int next = Math.addExact(size, read);
                budget.charge(base64Length(next) - base64Length(size));
                output.write(buffer, 0, read);
                size = next;
            }
            budget.ascii("\"}");
            return Map.of("base64", Base64.getEncoder().encodeToString(output.toByteArray()));
        } catch (IOException | ArithmeticException failure) {
            throw limit();
        }
    }

    private static int base64Length(int bytes) {
        return bytes == 0 ? 0 : ((bytes + 2) / 3) * 4;
    }

    private static int generatedKeySuffix(List<String> keys, int nextIndex) {
        int suffix = 3; // generated-key object, generatedKeys array and result object
        for (int index = nextIndex; index < keys.size(); index++) {
            suffix = Math.addExact(suffix, 2); // comma and colon
            suffix = Math.addExact(suffix, JsonBudget.quotedBytes(keys.get(index)));
        }
        return suffix;
    }

    private static Object nullValue(JsonBudget budget) {
        budget.ascii("null");
        return null;
    }

    private static Object booleanValue(boolean value, JsonBudget budget) {
        budget.ascii(value ? "true" : "false");
        return value;
    }

    private static Object longValue(long value, JsonBudget budget) {
        budget.number(value);
        return value;
    }

    private static int decimalDigits(int value) {
        return Integer.toString(value).length();
    }

    private record Column(int index, int type) { }

    /** Counts the exact canonical JSON bytes before each corresponding result allocation/copy. */
    private static final class JsonBudget {
        private final long maximum;
        private long used;

        JsonBudget(long maximum) {
            this.maximum = maximum;
        }

        void ascii(String text) {
            charge(text.length());
        }

        void number(long value) {
            ascii(Long.toString(value));
        }

        void number(double value) {
            ascii(Double.toString(value));
        }

        int base64RawAllowance(int currentRawBytes, int enclosingSuffix) {
            long suffixReserved = Math.addExact(2L, enclosingSuffix);
            long encodedCeiling = base64Length(currentRawBytes)
                    + Math.max(0L, maximum - used - suffixReserved);
            long rawCeiling = (encodedCeiling / 4L) * 3L;
            return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, rawCeiling - currentRawBytes));
        }

        void string(String value) {
            charge(quotedBytes(value));
        }

        static int quotedBytes(String value) {
            int bytes = 2;
            for (int index = 0; index < value.length();) {
                int point = value.codePointAt(index);
                int chars = Character.charCount(point);
                if (chars == 1 && Character.isSurrogate(value.charAt(index))) throw limit();
                bytes = Math.addExact(bytes, jsonBytes(point));
                index += chars;
            }
            return bytes;
        }

        String readString(Reader reader, int maxRawBytes, boolean chargeJson, int enclosingSuffix)
                throws IOException {
            if (chargeJson) chargeReserved(1, Math.addExact(1L, enclosingSuffix));
            var result = new StringBuilder(Math.min(maxRawBytes, 8_192));
            int rawBytes = 0;
            int pendingHigh = -1;
            for (int next; (next = reader.read()) >= 0;) {
                char character = (char) next;
                if (pendingHigh >= 0) {
                    if (!Character.isLowSurrogate(character)) throw limit();
                    rawBytes += 4;
                    if (rawBytes > maxRawBytes) throw limit();
                    if (chargeJson) chargeReserved(4, Math.addExact(1L, enclosingSuffix));
                    result.append((char) pendingHigh).append(character);
                    pendingHigh = -1;
                } else if (Character.isHighSurrogate(character)) {
                    pendingHigh = character;
                } else {
                    if (Character.isLowSurrogate(character)) throw limit();
                    rawBytes += utf8Bytes(character);
                    if (rawBytes > maxRawBytes) throw limit();
                    if (chargeJson) chargeReserved(jsonBytes(character), Math.addExact(1L, enclosingSuffix));
                    result.append(character);
                }
            }
            if (pendingHigh >= 0) throw limit();
            if (chargeJson) charge(1);
            return result.toString();
        }

        void chargeReserved(long bytes, long reserved) {
            if (reserved < 0 || bytes < 0 || used > maximum - reserved
                    || used + reserved > maximum - bytes) throw limit();
            used += bytes;
        }

        void charge(long bytes) {
            if (bytes < 0 || used > maximum - bytes) throw limit();
            used += bytes;
        }

        private static int utf8Bytes(int point) {
            return point <= 0x7f ? 1 : point <= 0x7ff ? 2 : point <= 0xffff ? 3 : 4;
        }

        private static int jsonBytes(int point) {
            return switch (point) {
                case '"', '\\', '\n', '\r', '\t', '\b', '\f' -> 2;
                default -> point < 0x20 ? 6 : utf8Bytes(point);
            };
        }
    }

    private static JdbcFailure limit() {
        return new JdbcFailure(JdbcFailure.Code.RESULT_LIMIT_EXCEEDED);
    }
}
