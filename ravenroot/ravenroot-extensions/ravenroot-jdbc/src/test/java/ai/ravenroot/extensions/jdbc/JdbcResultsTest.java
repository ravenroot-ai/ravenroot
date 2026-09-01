package ai.ravenroot.extensions.jdbc;

import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcResultsTest {
    private static final PayloadLimits TEST_LIMITS = new PayloadLimits(1_048_576, 16, 1_000, 10_000,
            1_048_576, 256);

    @Test
    void queryAcceptsTheExactCanonicalByteCeilingAndRefusesMaxPlusOne() throws Exception {
        List<List<Object>> rows = List.of(List.of("a".repeat(700)), List.of("b".repeat(700)));
        JdbcProfile generous = profile(JdbcTestSupport.query("SELECT text FROM values"), 800, 16_384);
        Object expected = JdbcResults.query(FakeJdbc.result(List.of("text"), rows), generous);
        int exact = encodedBytes(expected);

        assertEquals(expected, JdbcResults.query(FakeJdbc.result(List.of("text"), rows),
                profile(JdbcTestSupport.query("SELECT text FROM values"), 800, exact)));
        JdbcFailure refused = assertThrows(JdbcFailure.class,
                () -> JdbcResults.query(FakeJdbc.result(List.of("text"), rows),
                        profile(JdbcTestSupport.query("SELECT text FROM values"), 800, exact - 1)));
        assertEquals(JdbcFailure.Code.RESULT_LIMIT_EXCEEDED, refused.code());
    }

    @Test
    void cumulativeOverflowStopsTheLaterCellStreamBeforeItIsMaterialized() {
        CountingReader first = new CountingReader("a".repeat(900));
        CountingReader second = new CountingReader("b".repeat(900));
        ResultSet result = characterResult(List.of("first", "second"), List.of(first, second));

        JdbcFailure refused = assertThrows(JdbcFailure.class,
                () -> JdbcResults.query(result,
                        profile(JdbcTestSupport.query("SELECT first,second FROM values"), 900, 1_100)));

        assertEquals(JdbcFailure.Code.RESULT_LIMIT_EXCEEDED, refused.code());
        assertEquals(900, first.reads.get());
        assertTrue(second.reads.get() < 900,
                "the total budget must refuse while the later JDBC stream is still unread");
    }

    @Test
    void generatedKeysUseTheSameExactIncrementalBudget() throws Exception {
        JdbcStatementProfile statement = new JdbcStatementProfile("add", JdbcStatementProfile.Kind.INSERT,
                NamedSql.parse("INSERT INTO values(name) VALUES (:name)"), Set.of("id", "token"));
        List<List<Object>> rows = List.of(List.of(41L, "t".repeat(900)));
        JdbcProfile generous = profile(statement, 900, 16_384);
        Object expected = JdbcResults.insert(1, FakeJdbc.result(List.of("id", "token"), rows), statement, generous);
        int exact = encodedBytes(expected);

        assertEquals(expected, JdbcResults.insert(1, FakeJdbc.result(List.of("id", "token"), rows), statement,
                profile(statement, 900, exact)));
        assertThrows(JdbcFailure.class,
                () -> JdbcResults.insert(1, FakeJdbc.result(List.of("id", "token"), rows), statement,
                        profile(statement, 900, exact - 1)));
    }

    @Test
    void queryBinaryReadsAtMostTheSmallCellAllowancePlusOneBelowEightKiB() throws Exception {
        JdbcStatementProfile statement = JdbcTestSupport.query("SELECT payload FROM values");
        CountingInputStream exact = new CountingInputStream(1_024);
        JdbcResults.query(binaryResult("payload", exact), profile(statement, 1_024, 16_384));
        assertEquals(1_024, exact.consumed.get());
        assertTrue(exact.largestRequest.get() <= 1_025);

        CountingInputStream overflow = new CountingInputStream(1_025);
        JdbcFailure refused = assertThrows(JdbcFailure.class,
                () -> JdbcResults.query(binaryResult("payload", overflow),
                        profile(statement, 1_024, 16_384)));
        assertEquals(JdbcFailure.Code.RESULT_LIMIT_EXCEEDED, refused.code());
        assertEquals(1_025, overflow.consumed.get(), "only max+1 raw bytes may cross the JDBC stream boundary");
        assertTrue(overflow.largestRequest.get() <= 1_025, "the reader must never receive the old 8192 request");

        CountingInputStream totalOverflow = new CountingInputStream(1_024);
        assertThrows(JdbcFailure.class, () -> JdbcResults.query(binaryResult("payload", totalOverflow),
                profile(statement, 1_024, 1_024)));
        assertTrue(totalOverflow.consumed.get() < 1_024,
                "Base64 and canonical JSON allowance must stop the stream before the raw cell ceiling");
        assertTrue(totalOverflow.largestRequest.get() <= totalOverflow.consumed.get());
    }

    @Test
    void generatedKeyBinaryUsesTheSameBoundedReadContract() throws Exception {
        JdbcStatementProfile statement = new JdbcStatementProfile("add", JdbcStatementProfile.Kind.INSERT,
                NamedSql.parse("INSERT INTO values(name) VALUES (:name)"), Set.of("token"));
        CountingInputStream exact = new CountingInputStream(511);
        JdbcResults.insert(1, binaryResult("token", exact), statement, profile(statement, 511, 16_384));
        assertEquals(511, exact.consumed.get());

        CountingInputStream overflow = new CountingInputStream(512);
        JdbcFailure refused = assertThrows(JdbcFailure.class,
                () -> JdbcResults.insert(1, binaryResult("token", overflow), statement,
                        profile(statement, 511, 16_384)));
        assertEquals(JdbcFailure.Code.RESULT_LIMIT_EXCEEDED, refused.code());
        assertEquals(512, overflow.consumed.get());
        assertTrue(overflow.largestRequest.get() <= 512);
    }

    @Test
    void queryBinaryReservesEveryEnclosingSuffixAtExactAndExactMinusOneAcrossBase64Quanta()
            throws Exception {
        JdbcStatementProfile statement = JdbcTestSupport.query("SELECT payload FROM values");
        for (int rawLength = 509; rawLength <= 514; rawLength++) {
            int length = rawLength;
            Object expected = JdbcResults.query(binaryResult("payload", new CountingInputStream(length)),
                    profile(statement, length, 16_384));
            int exact = encodedBytes(expected);

            CountingInputStream accepted = new CountingInputStream(rawLength);
            assertEquals(expected, JdbcResults.query(binaryResult("payload", accepted),
                    profile(statement, rawLength, exact)), "rawLength=" + rawLength);
            assertEquals(rawLength, accepted.consumed.get());

            CountingInputStream refused = new CountingInputStream(rawLength);
            assertThrows(JdbcFailure.class, () -> JdbcResults.query(binaryResult("payload", refused),
                    profile(statement, length, exact - 1)), "rawLength=" + rawLength);
            assertEquals(0, refused.endOfStreamReads.get(),
                    "refusal must happen before consuming the JDBC stream through EOF; rawLength=" + rawLength);
            assertTrue(refused.largestRequest.get() <= Math.max(1, refused.consumed.get()));
        }
    }

    @Test
    void generatedKeyBinaryReservesEveryEnclosingSuffixAtExactAndExactMinusOneAcrossBase64Quanta()
            throws Exception {
        JdbcStatementProfile statement = new JdbcStatementProfile("add", JdbcStatementProfile.Kind.INSERT,
                NamedSql.parse("INSERT INTO values(name) VALUES (:name)"), Set.of("token"));
        for (int rawLength = 509; rawLength <= 514; rawLength++) {
            int length = rawLength;
            Object expected = JdbcResults.insert(1, binaryResult("token", new CountingInputStream(length)),
                    statement, profile(statement, length, 16_384));
            int exact = encodedBytes(expected);

            CountingInputStream accepted = new CountingInputStream(rawLength);
            assertEquals(expected, JdbcResults.insert(1, binaryResult("token", accepted), statement,
                    profile(statement, rawLength, exact)), "rawLength=" + rawLength);

            CountingInputStream refused = new CountingInputStream(rawLength);
            assertThrows(JdbcFailure.class, () -> JdbcResults.insert(1, binaryResult("token", refused),
                    statement, profile(statement, length, exact - 1)), "rawLength=" + rawLength);
            assertEquals(0, refused.endOfStreamReads.get(),
                    "refusal must happen before consuming the JDBC stream through EOF; rawLength=" + rawLength);
            assertTrue(refused.largestRequest.get() <= Math.max(1, refused.consumed.get()));
        }
    }

    private static JdbcProfile profile(JdbcStatementProfile statement, int maxCellBytes, int maxTotalBytes) {
        JdbcProfile base = JdbcTestSupport.profile(statement);
        return new JdbcProfile(base.tenant(), base.name(), base.driverId(), base.driverClass(),
                base.driverSha256(), base.url(), base.username(), base.credentialRef(), base.isolation(),
                base.deadlineMs(), base.maxConcurrency(), base.maxParameters(), base.maxParameterBytes(),
                base.maxRows(), base.maxColumns(), maxCellBytes, maxTotalBytes, base.maxGeneratedKeyRows(),
                base.statements());
    }

    private static int encodedBytes(Object value) {
        PayloadValue payload = PayloadValue.fromJava(value, TEST_LIMITS);
        return PayloadJson.write(payload).getBytes(StandardCharsets.UTF_8).length;
    }

    private static ResultSet characterResult(List<String> columns, List<? extends Reader> readers) {
        ResultSetMetaData metadata = (ResultSetMetaData) Proxy.newProxyInstance(
                JdbcResultsTest.class.getClassLoader(), new Class<?>[]{ResultSetMetaData.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getColumnCount" -> columns.size();
                    case "getColumnLabel" -> columns.get((int) args[0] - 1);
                    case "getColumnType" -> Types.VARCHAR;
                    default -> FakeJdbc.defaultValue(method.getReturnType());
                });
        AtomicInteger cursor = new AtomicInteger();
        return (ResultSet) Proxy.newProxyInstance(JdbcResultsTest.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getMetaData" -> metadata;
                    case "next" -> cursor.getAndIncrement() == 0;
                    case "getCharacterStream" -> readers.get((int) args[0] - 1);
                    case "wasNull" -> false;
                    case "close" -> null;
                    default -> FakeJdbc.defaultValue(method.getReturnType());
                });
    }

    private static ResultSet binaryResult(String column, InputStream input) {
        ResultSetMetaData metadata = (ResultSetMetaData) Proxy.newProxyInstance(
                JdbcResultsTest.class.getClassLoader(), new Class<?>[]{ResultSetMetaData.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getColumnCount" -> 1;
                    case "getColumnLabel" -> column;
                    case "getColumnType" -> Types.VARBINARY;
                    default -> FakeJdbc.defaultValue(method.getReturnType());
                });
        AtomicInteger cursor = new AtomicInteger();
        return (ResultSet) Proxy.newProxyInstance(JdbcResultsTest.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getMetaData" -> metadata;
                    case "next" -> cursor.getAndIncrement() == 0;
                    case "getBinaryStream" -> input;
                    case "wasNull" -> false;
                    case "close" -> null;
                    default -> FakeJdbc.defaultValue(method.getReturnType());
                });
    }

    private static final class CountingReader extends StringReader {
        private final AtomicInteger reads = new AtomicInteger();

        CountingReader(String value) {
            super(value);
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) reads.incrementAndGet();
            return value;
        }

        @Override
        public int read(char[] target, int offset, int length) throws IOException {
            int read = super.read(target, offset, length);
            if (read > 0) reads.addAndGet(read);
            return read;
        }
    }

    private static final class CountingInputStream extends ByteArrayInputStream {
        private final AtomicInteger consumed = new AtomicInteger();
        private final AtomicInteger largestRequest = new AtomicInteger();
        private final AtomicInteger endOfStreamReads = new AtomicInteger();

        CountingInputStream(int size) {
            super(new byte[size]);
        }

        @Override
        public synchronized int read(byte[] target, int offset, int length) {
            largestRequest.accumulateAndGet(length, Math::max);
            int read = super.read(target, offset, length);
            if (read > 0) consumed.addAndGet(read);
            if (read < 0) endOfStreamReads.incrementAndGet();
            return read;
        }
    }
}
