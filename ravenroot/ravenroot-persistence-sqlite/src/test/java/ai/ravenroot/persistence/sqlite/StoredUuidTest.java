package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exhaustive contract for UUID text crossing the SQLite read boundary. */
class StoredUuidTest {

    private static final ExecutionKey KEY = new ExecutionKey("acme", UUID.randomUUID());
    private static final String HOSTILE = "not-a-uuid secret=do-not-log";
    private static final Pattern DIRECT_PARSER = Pattern.compile(
            "(?s)(?:java\\.util\\.)?UUID\\s*(?:\\.|::)\\s*fromString|"
                    + "import\\s+static\\s+java\\.util\\.UUID\\.(?:fromString|\\*)");

    @ParameterizedTest(name = "{0}")
    @MethodSource("uuidReadPaths")
    void everyPersistedUuidReadPathMapsMalformedTextToSanitizedCorruption(
            String path, String table, String column, boolean nullable) {
        ExecutionStoreException thrown = assertThrows(ExecutionStoreException.class,
                () -> decode(nullable, HOSTILE, table, column));

        ExecutionStoreFailure.Corrupted corrupted = assertInstanceOf(
                ExecutionStoreFailure.Corrupted.class, thrown.failure(), path);
        assertEquals(KEY, corrupted.key());
        assertEquals("stored " + table + "." + column + " is not a canonical UUID",
                corrupted.reason());
        assertFalse(thrown.getMessage().contains(HOSTILE));
        assertNull(thrown.getCause(), "the UUID parser and its input must not survive as a cause");
        assertFalse(causeChain(thrown).stream().anyMatch(IllegalArgumentException.class::isInstance));
    }

    @Test
    void missingRequiredValuesCorruptWhileNullableColumnsAcceptSqlNull() {
        ExecutionStoreException missing = assertThrows(ExecutionStoreException.class,
                () -> StoredUuid.required((String) null, "attempt", "attempt_id", KEY));
        var corrupted = assertInstanceOf(ExecutionStoreFailure.Corrupted.class, missing.failure());
        assertEquals("stored attempt.attempt_id is missing", corrupted.reason());
        assertNull(StoredUuid.optional((String) null, "event_journal", "attempt_id", KEY));
    }

    @Test
    void canonicalTextIsAcceptedButJdkShortAliasesAreRejected() {
        UUID value = UUID.fromString("00000001-0001-0001-0001-000000000001");
        assertEquals(value, StoredUuid.required(value.toString().toUpperCase(Locale.ROOT),
                "attempt", "attempt_id", KEY));

        ExecutionStoreException alias = assertThrows(ExecutionStoreException.class,
                () -> StoredUuid.required("1-1-1-1-1", "attempt", "attempt_id", KEY));
        assertEquals("stored attempt.attempt_id is not a canonical UUID",
                assertInstanceOf(ExecutionStoreFailure.Corrupted.class, alias.failure()).reason());
    }

    @Test
    void matchingAndRelationshipChecksRejectCanonicalButContextuallyWrongIds() {
        UUID expected = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        ExecutionStoreException mismatched = assertThrows(ExecutionStoreException.class,
                () -> StoredUuid.requiredMatching(other.toString(), "tool_approval", "approval_id",
                        KEY, expected));
        assertEquals("stored tool_approval.approval_id does not match the requested identity",
                assertInstanceOf(ExecutionStoreFailure.Corrupted.class, mismatched.failure()).reason());

        ExecutionStoreException unknown = assertThrows(ExecutionStoreException.class,
                () -> StoredUuid.requireKnown(Set.of(expected), other, "attempt", "invocation_id", KEY));
        assertEquals("stored attempt.invocation_id does not reference stored state in this process",
                assertInstanceOf(ExecutionStoreFailure.Corrupted.class, unknown.failure()).reason());
    }

    @Test
    void productionReadersCannotBypassTheSingleDecoder() throws IOException {
        Path sources = Path.of(System.getProperty("basedir"), "src", "main", "java");

        List<Path> bypasses;
        try (Stream<Path> files = Files.walk(sources)) {
            bypasses = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("StoredUuid.java"))
                    .filter(path -> {
                        try {
                            return DIRECT_PARSER.matcher(Files.readString(path)).find();
                        } catch (IOException failure) {
                            throw new java.io.UncheckedIOException(failure);
                        }
                    })
                    .toList();
        }
        assertTrue(bypasses.isEmpty(),
                "all persisted UUID parsing must stay behind StoredUuid; direct/qualified/static-import/"
                        + "method-reference bypasses: " + bypasses);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UUID.fromString(value)",
            "java.util.UUID.fromString(value)",
            "UUID::fromString",
            "import static java.util.UUID.fromString;",
            "import static java.util.UUID.*;"
    })
    void sourceGuardDetectsEveryJavaParserBypassShape(String mutation) {
        assertTrue(DIRECT_PARSER.matcher(mutation).find(), mutation);
    }

    private static UUID decode(boolean nullable, String value, String table, String column) {
        return nullable
                ? StoredUuid.optional(value, table, column, KEY)
                : StoredUuid.required(value, table, column, KEY);
    }

    private static List<Throwable> causeChain(Throwable thrown) {
        var causes = new java.util.ArrayList<Throwable>();
        Throwable current = thrown;
        while (current != null && !causes.contains(current)) {
            causes.add(current);
            current = current.getCause();
        }
        return List.copyOf(causes);
    }

    private static Stream<Arguments> uuidReadPaths() {
        return Stream.of(
                path("aggregate traversal", "traversal", "traversal_id"),
                path("aggregate invocation traversal", "invocation", "traversal_id"),
                path("aggregate invocation identity", "invocation", "invocation_id"),
                path("aggregate parent child", "invocation_parent", "invocation_id"),
                path("aggregate parent identity", "invocation_parent", "parent_invocation_id"),
                path("aggregate attempt owner", "attempt", "invocation_id"),
                path("aggregate attempt identity", "attempt", "attempt_id"),
                path("lease listing", "lease", "process_instance_id"),
                path("traversal inventory", "traversal", "traversal_id"),
                path("process inventory", "process_instance", "process_instance_id"),
                path("tenant instance keys", "process_instance", "process_instance_id"),
                path("claim invocation traversal", "invocation", "traversal_id"),
                path("claim joined traversal", "traversal", "traversal_id"),
                path("claim attempt owner", "attempt", "invocation_id"),
                path("claim attempt identity", "attempt", "attempt_id"),
                path("timer identity", "timer", "timer_id"),
                optionalPath("timer traversal", "timer", "traversal_id"),
                optionalPath("timer invocation", "timer", "invocation_id"),
                path("handler process", "execution_handler", "process_instance_id"),
                path("handler identity", "execution_handler", "handler_id"),
                path("handler traversal", "execution_handler", "traversal_id"),
                path("handler invocation", "execution_handler", "invocation_id"),
                optionalPath("handler resume traversal", "execution_handler", "resume_traversal_id"),
                path("authority budget process", "agent_authority_budget", "process_instance_id"),
                path("approval process", "tool_approval", "process_instance_id"),
                path("approval identity", "tool_approval", "approval_id"),
                path("approval traversal", "tool_approval", "traversal_id"),
                path("approval invocation", "tool_approval", "invocation_id"),
                path("approval attempt", "tool_approval", "attempt_id"),
                path("approval call", "tool_approval", "call_id"),
                path("pause process", "execution_pause", "process_instance_id"),
                path("pause identity", "execution_pause", "pause_id"),
                path("pause traversal", "execution_pause", "traversal_id"),
                path("pause invocation", "execution_pause", "after_invocation_id"),
                path("human task process", "human_task", "process_instance_id"),
                path("human task identity", "human_task", "task_id"),
                path("human task traversal", "human_task", "traversal_id"),
                path("human task invocation", "human_task", "invocation_id"),
                path("human task attempt", "human_task", "attempt_id"),
                path("journal process", "event_journal", "process_instance_id"),
                path("journal event", "event_journal", "event_id"),
                path("journal traversal", "event_journal", "traversal_id"),
                optionalPath("journal invocation", "event_journal", "invocation_id"),
                optionalPath("journal attempt", "event_journal", "attempt_id"),
                optionalPath("journal causation", "event_journal", "causation_id"));
    }

    private static Arguments path(String name, String table, String column) {
        return Arguments.of(name, table, column, false);
    }

    private static Arguments optionalPath(String name, String table, String column) {
        return Arguments.of(name, table, column, true);
    }
}
